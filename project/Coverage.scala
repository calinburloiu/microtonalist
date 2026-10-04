/*
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import sbt.*
import sbt.Keys.*
import scoverage.ScoverageKeys.*

/**
 * `coverageAll`, `coverageModules <module> [<module> ...]`, `coverageCheck`, `coverageClean`, and `coverageThresholds`
 * sbt commands — run the coverage workflow, clean up the reports directory, and inspect per-module thresholds.
 *
 * `coverageAll` runs `clean; coverage`, measures every module that root aggregates, then runs `coverageAggregate`. It
 * measures the modules in a single `coverage` session: it compiles them in parallel, then runs each module's `test`
 * right before its own `coverageReport`, dependencies first (see `measure`). So each module's report counts only its
 * own tests, not those of the modules that depend on it and exercise the same code, while the aggregate report counts
 * every test.
 *
 * `coverageModules <module> [<module> ...]` runs the same workflow for the named modules only, without the aggregate.
 * At least one module must be supplied.
 *
 * `docs/development/coverage.md` says how to check by hand that a report counts its module's own tests only.
 *
 * `coverageClean` deletes the `coverage-reports/` directory at the repo root. The reports directory is configured via
 * `coverageDataDir` in `build.sbt` to live outside `target/` so it survives `sbt clean`. Use `coverageClean` when you
 * want to discard the persisted reports themselves.
 *
 * `coverageCheck` is intended for CI: it runs the same workflow as `coverageAll` but disables HTML and Cobertura report
 * output for speed, keeping only the XML reports. Per-module thresholds are enforced by `coverageReport` (each
 * subproject with `coverageFailOnMinimum`) on the coverage of the module's own tests, and the aggregate threshold by
 * `coverageAggregate` against the root project's settings. The HTML/Cobertura toggles are restored at the end so a
 * local invocation does not leave the session with reduced output enabled.
 *
 * `coverageThresholds` prints a table of the minimum statement and branch coverage percentages configured for each
 * module aggregated by root (excluding modules with coverage disabled, e.g. `common-test-utils`). Useful for agents and
 * developers who need to check thresholds without reading `build.sbt`.
 */
object Coverage {

  val commands: Seq[Command] =
    Seq(coverageAll, coverageModules, coverageCheck, coverageClean, coverageThresholds)

  private def coverageAll: Command = Command.command("coverageAll") { state =>
    "clean" ::
      "coverage" ::
      measure(aggregatedModules(state), state) :::
      "coverageAggregate" ::
      state
  }

  private def coverageModules: Command = Command.args("coverageModules", "<module> [<module> ...]") {
    (state, args) =>
      if (args.isEmpty) {
        state.globalLogging.full.error("Usage: coverageModules <module> [<module> ...]")
        state.fail
      } else {
        "clean" ::
          "coverage" ::
          measure(args, state) :::
          state
      }
  }

  private def coverageCheck: Command = Command.command("coverageCheck") { state =>
    "clean" ::
      "set Global / coverageOutputHTML := false" ::
      "set Global / coverageOutputCobertura := false" ::
      "coverage" ::
      measure(aggregatedModules(state), state) :::
      "coverageAggregate" ::
      "set Global / coverageOutputHTML := true" ::
      "set Global / coverageOutputCobertura := true" ::
      state
  }

  /**
   * The commands that measure the coverage of each given module with its own tests only: they compile the modules in
   * parallel, then run each module's `test` right before its `coverageReport`, every module after the given modules it
   * depends on.
   *
   * Instrumented code records its hits in the data directory of the module that owns it, whichever module's tests run
   * it, so a module's report counts every test that ran before it. A module's tests can only reach its own code and its
   * dependencies' code, so with dependencies first each report is written before any other given module's test that
   * could reach the module's code. Running the tests one module at a time loses sbt's parallelism, but they take little
   * time next to the compilation, which stays parallel.
   *
   * `coverageAggregate` still counts every test, since it reads the hits that the data directories hold once all the
   * tests have run, not the reports.
   *
   * @param modules the sbt project IDs of the modules to measure; one that isn't in the build is kept, for sbt to
   *   reject
   * @param state the sbt state, which holds the build's inter-project dependencies
   * @return the commands to run, in order
   */
  private def measure(modules: Seq[String], state: State): List[String] = {
    val transitiveDependencyCounts: Map[String, Int] = Project.extract(state).get(buildDependencies)
      .classpathTransitive.map { case (ref, dependencies) => ref.project -> dependencies.size }
    // A module has more transitive dependencies than each of its dependencies: all of theirs, plus the dependency
    // itself. So sorting by their count puts every module after its dependencies. The ID makes the order of the others
    // deterministic.
    val orderedModules = modules.sortBy(module => (transitiveDependencyCounts.getOrElse(module, 0), module)).toList

    orderedModules.map(module => s"$module/Test/compile").mkString("all ", " ", "") ::
      orderedModules.flatMap(module => List(s"$module/test", s"$module/coverageReport"))
  }

  /** The IDs of the modules that the build's root project aggregates, directly or transitively. */
  private def aggregatedModules(state: State): Seq[String] = {
    val extracted = Project.extract(state)
    extracted.get(buildDependencies).aggregateTransitive(extracted.get(LocalRootProject / thisProjectRef))
      .map(_.project)
  }

  private def coverageClean: Command = Command.command("coverageClean") { state =>
    val baseDir = Project.extract(state).get(LocalRootProject / baseDirectory)
    val reportsDir = baseDir / "coverage-reports"
    val log = state.globalLogging.full
    if (reportsDir.exists()) {
      IO.delete(reportsDir)
      log.info(s"Deleted $reportsDir")
    } else {
      log.info(s"$reportsDir does not exist; nothing to delete")
    }
    state
  }

  private def coverageThresholds: Command = Command.command("coverageThresholds") { state =>
    val extracted = Project.extract(state)
    val log = state.globalLogging.full
    val structure = extracted.structure

    // Collect the project IDs that root aggregates, plus root itself.
    val rootResolved = structure.units.values.flatMap(_.defined.values).find(_.id == "root")
    val includedIds: Set[String] = rootResolved match {
      case Some(root) => root.aggregate.map(_.project).toSet + "root"
      case None => structure.allProjectRefs.map(_.project).toSet
    }

    case class Row(id: String, stmt: Double, branch: Double)

    val rows = structure.allProjectRefs.flatMap { ref =>
      if (!includedIds.contains(ref.project)) None
      else {
        // A module participates iff it enforces a coverage minimum (the `coverageSettings` helper in
        // `build.sbt` sets `coverageFailOnMinimum := true`). Test-utility modules such as
        // `common-test-utils` never call it and are excluded. `coverageEnabled` can't be used here: it
        // defaults to false for every module outside an active `coverage` session.
        val enforcesMinimum = extracted.getOpt(ref / coverageFailOnMinimum).contains(true)
        if (!enforcesMinimum) None
        else Some(Row(
          ref.project,
          extracted.get(ref / coverageMinimumStmtTotal),
          extracted.get(ref / coverageMinimumBranchTotal),
        ))
      }
    }.sortBy(_.id)

    val colWidth = ("Module".length +: rows.map(_.id.length)).max
    log.info(f"${"Module".padTo(colWidth, ' ')}  ${"stmt%"}%6s  ${"branch%"}%7s")
    log.info("-" * (colWidth + 17))
    rows.foreach { row =>
      log.info(f"${row.id.padTo(colWidth, ' ')}  ${row.stmt}%6.1f  ${row.branch}%7.1f")
    }

    state
  }
}

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
import scoverage.ScoverageKeys.coverageEnabled

/**
 * `coverageAll`, `coverageModules <module> [<module> ...]`, `coverageCheck`, and `coverageClean` sbt commands — run
 * the coverage workflow with a two-pass build (and clean up the reports directory).
 *
 * Single-invocation `clean; coverage; test` reliably fails on this multi-project
 * Scala 3.6.3 + sbt-scoverage 2.x setup with TASTy/companion-class errors (see
 * https://github.com/scoverage/sbt-scoverage/issues/517 and
 * https://github.com/scoverage/sbt-scoverage/issues/511). The first pass compiles
 * the project without instrumentation so the on-disk TASTy is valid before
 * `coverageEnabled := true` triggers an instrumented recompile in the second pass.
 *
 * For `coverageAll`, the first pass compiles all modules in parallel; then
 * compile-task parallelism is serialized for the instrumented second pass as
 * belt-and-braces protection against https://github.com/sbt/sbt/issues/1673 /
 * sbt-scoverage#108.
 *
 * `coverageModules <module> [<module> ...]` runs the same two-pass workflow but only the named modules' tests are
 * run, giving accurate per-module coverage that is not inflated by tests from other modules exercising the same
 * code. At least one module must be supplied. All listed modules' tests run inside a single `coverage` session,
 * then each produces its own `coverageReport`.
 *
 * `coverageClean` deletes the `coverage-reports/` directory at the repo root. The reports directory is
 * configured via `coverageDataDir` in `build.sbt` to live outside `target/` so it survives `sbt clean`
 * (which is recommended after a coverage run to remove instrumented `.class`/`.tasty` files). Use
 * `coverageClean` when you want to discard the persisted reports themselves.
 *
 * `coverageCheck` is intended for CI: it runs the same two-pass workflow as `coverageAll`
 * but disables HTML and Cobertura report output for speed. XML output is kept on because
 * `coverageAggregate` reads each subproject's XML to combine their coverage data. Per-module
 * thresholds are enforced by `coverageReport` (each subproject with `coverageFailOnMinimum`)
 * and the aggregate threshold by `coverageAggregate` against the root project's settings.
 *
 * All three commands snapshot the settings they modify — `Global / concurrentRestrictions`
 * and per-project `coverageEnabled` — on entry, and `coverageAllRestore` re-applies the
 * snapshot at the end. `coverageCheck` additionally toggles `coverageOutputHTML` /
 * `coverageOutputCobertura` for the duration of the run; those are not snapshotted because
 * CI invocations are one-shot, but a local user can `reload` to drop them.
 */
object Coverage {

  val commands: Seq[Command] =
    Seq(coverageAll, coverageModules, coverageCheck, coverageClean, coverageAllRestore)

  private val savedRestrictions = AttributeKey[Seq[Tags.Rule]](
    "coverageAllSavedRestrictions",
    "Snapshot of Global / concurrentRestrictions captured on coverageAll entry.",
  )

  private val savedCoverageEnabled = AttributeKey[Map[ProjectRef, Boolean]](
    "coverageAllSavedCoverageEnabled",
    "Snapshot of per-project coverageEnabled values captured on coverageAll entry.",
  )

  private def coverageAll: Command = Command.command("coverageAll") { state =>
    val saved = snapshotSettings(state)
    "clean" ::
      "compile" ::
      "set Global / concurrentRestrictions += Tags.limit(Tags.Compile, 1)" ::
      "coverage" ::
      "test" ::
      "coverageReport" ::
      "coverageAggregate" ::
      "coverageAllRestore" ::
      saved
  }

  private def coverageModules: Command = Command.args("coverageModules", "<module> [<module> ...]") {
    (state, args) =>
      if (args.isEmpty) {
        state.globalLogging.full.error("Usage: coverageModules <module> [<module> ...]")
        state.fail
      } else {
        val saved = snapshotSettings(state)
        val testTasks = args.map(m => s"$m/test").toList
        val reportTasks = args.map(m => s"$m/coverageReport").toList
        ("clean" ::
          "compile" ::
          "set Global / concurrentRestrictions += Tags.limit(Tags.Compile, 1)" ::
          "coverage" ::
          testTasks :::
          reportTasks :::
          "coverageAllRestore" ::
          Nil) ::: saved
      }
  }

  private def coverageCheck: Command = Command.command("coverageCheck") { state =>
    val saved = snapshotSettings(state)
    "clean" ::
      "compile" ::
      "set Global / concurrentRestrictions += Tags.limit(Tags.Compile, 1)" ::
      "set Global / coverageOutputHTML := false" ::
      "set Global / coverageOutputCobertura := false" ::
      "coverage" ::
      "test" ::
      "coverageReport" ::
      "coverageAggregate" ::
      "coverageAllRestore" ::
      saved
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

  private def snapshotSettings(state: State): State = {
    val extracted = Project.extract(state)
    val data = extracted.structure.data
    val originalRestrictions = (Global / concurrentRestrictions).get(data).getOrElse(Seq.empty)
    val originalCoverageEnabled = extracted.structure.allProjectRefs.flatMap { ref =>
      (ref / coverageEnabled).get(data).map(ref -> _)
    }.toMap
    state
      .put(savedRestrictions, originalRestrictions)
      .put(savedCoverageEnabled, originalCoverageEnabled)
  }

  private def coverageAllRestore: Command = Command.command("coverageAllRestore") { state =>
    val extracted = Project.extract(state)
    val restrictionsSetting: Seq[Setting[?]] =
      state.attributes.get(savedRestrictions)
        .map(original => Seq(Global / concurrentRestrictions := original))
        .getOrElse(Seq.empty)
    val coverageEnabledSettings: Seq[Setting[?]] =
      state.attributes.get(savedCoverageEnabled)
        .map(_.toSeq.map { case (ref, value) => ref / coverageEnabled := value })
        .getOrElse(Seq.empty)
    val cleanState = state
      .remove(savedRestrictions)
      .remove(savedCoverageEnabled)
    val toApply = restrictionsSetting ++ coverageEnabledSettings
    if (toApply.isEmpty) cleanState
    else extracted.appendWithSession(toApply, cleanState)
  }
}

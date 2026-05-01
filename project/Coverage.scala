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

/**
 * `coverageAll`, `coverageModules <module> [<module> ...]`, `coverageCheck`, and `coverageClean` sbt commands — run
 * the coverage workflow and clean up the reports directory.
 *
 * `coverageAll` runs `clean; coverage; test; coverageReport; coverageAggregate` across all modules, then cleans the
 * active `target/` tree to remove instrumented `.class`/`.tasty` files left by scoverage. The `coverage-reports/`
 * directory is configured via `coverageDataDir` to live outside `target/`, so it is not affected by the trailing
 * `clean`.
 *
 * `coverageModules <module> [<module> ...]` runs the same workflow but only the named modules' tests are run, giving
 * accurate per-module coverage that is not inflated by tests from other modules exercising the same code. At least one
 * module must be supplied. All listed modules' tests run inside a single `coverage` session, then each produces its
 * own `coverageReport`. Note that `coverageEnabled` is set globally by the `coverage` task, so even though only the
 * listed `<module>/test` tasks run, the modules' upstream dependencies are recompiled with instrumentation too —
 * hence the single trailing `clean` is full, not module-scoped, and covers all listed modules at once.
 *
 * `coverageClean` deletes the `coverage-reports/` directory at the repo root. Use it when you want to discard the
 * persisted reports explicitly.
 *
 * `coverageCheck` is intended for CI: it runs the same workflow as `coverageAll` but disables HTML and
 * Cobertura report output for speed. XML output is kept on because `coverageAggregate` reads each subproject's
 * XML to combine their coverage data. Per-module thresholds are enforced by `coverageReport` (each subproject
 * with `coverageFailOnMinimum`) and the aggregate threshold by `coverageAggregate` against the root project's
 * settings. The HTML/Cobertura toggles are restored at the end so a local invocation does not leave the session
 * with reduced output enabled.
 */
object Coverage {

  val commands: Seq[Command] =
    Seq(coverageAll, coverageModules, coverageCheck, coverageClean)

  private def coverageAll: Command = Command.command("coverageAll") { state =>
    "clean" ::
      "coverage" ::
      "test" ::
      "coverageReport" ::
      "coverageAggregate" ::
      "clean" ::
      state
  }

  private def coverageModules: Command = Command.args("coverageModules", "<module> [<module> ...]") {
    (state, args) =>
      if (args.isEmpty) {
        state.globalLogging.full.error("Usage: coverageModules <module> [<module> ...]")
        state.fail
      } else {
        val testTasks = args.map(m => s"$m/test").toList
        val reportTasks = args.map(m => s"$m/coverageReport").toList
        ("clean" :: "coverage" :: testTasks ::: reportTasks ::: "clean" :: Nil) ::: state
      }
  }

  private def coverageCheck: Command = Command.command("coverageCheck") { state =>
    "clean" ::
      "set Global / coverageOutputHTML := false" ::
      "set Global / coverageOutputCobertura := false" ::
      "coverage" ::
      "test" ::
      "coverageReport" ::
      "coverageAggregate" ::
      "set Global / coverageOutputHTML := true" ::
      "set Global / coverageOutputCobertura := true" ::
      "clean" ::
      state
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
}

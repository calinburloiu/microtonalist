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
 * `coverageAll`, `coverageModule <module>`, `coverageCheck`, and `coverageClean` sbt commands — run the coverage
 * workflow and clean up the reports directory.
 *
 * `coverageAll` runs `clean; coverage; test; coverageReport; coverageAggregate` across all modules, then cleans the
 * active `target/` tree to remove instrumented `.class`/`.tasty` files left by scoverage. The `coverage-reports/`
 * directory is configured via `coverageDataDir` to live outside `target/`, so it is not affected by the trailing
 * `clean`.
 *
 * `coverageModule <module>` runs the same workflow but only the named module's tests are run, giving accurate
 * per-module coverage that is not inflated by tests from other modules exercising the same code. Note that
 * `coverageEnabled` is set globally by the `coverage` task, so even though only `<module>/test` runs, the module's
 * upstream dependencies are recompiled with instrumentation too — hence the trailing `clean` is full, not module-scoped.
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
    Seq(coverageAll, coverageModule, coverageCheck, coverageClean)

  private def coverageAll: Command = Command.command("coverageAll") { state =>
    "clean" ::
      "coverage" ::
      "test" ::
      "coverageReport" ::
      "coverageAggregate" ::
      "clean" ::
      state
  }

  private def coverageModule: Command = Command.args("coverageModule", "<module>") { (state, args) =>
    args match {
      case Seq(module) =>
        "clean" ::
          "coverage" ::
          s"$module/test" ::
          s"$module/coverageReport" ::
          "clean" ::
          state
      case _ =>
        state.globalLogging.full.error("Usage: coverageModule <module>")
        state.fail
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

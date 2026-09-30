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
import xsbti.{Problem, Severity}

import scala.collection.JavaConverters.*

/**
 * The compiler warnings policy, and the `warningsCheck` task that the `lint` command alias runs.
 *
 * Every compiler warning is a compile error, except deprecations and the [[lenientWarnings]]: an unused explicit
 * parameter, the one warning a TDD red-phase stub needs, and an unused import, which `sbtn fix` removes (scalafix only
 * runs on code that compiles). `warningsCheck` fails on a lenient warning, so `lint` lets only deprecations through.
 *
 * `warningsCheck` doesn't recompile anything to find the warnings: the compiler bridge stores each source file's
 * warnings in Zinc's incremental analysis, which keeps them until that file is recompiled. So the check runs on the
 * `sbtn` server as it is, unlike a stricter `-Wconf`, which would recompile everything twice, to check and to go back.
 *
 * See docs/development/linting.md#warnings-policy.
 */
object Warnings {

  /** Message regexes of the warnings that compile allows and `warningsCheck` doesn't. */
  val lenientWarnings: Seq[String] = Seq("unused explicit parameter", "unused import")

  /**
   * The `-Wconf` compiler option of the policy. Within one `-Wconf` option the rightmost matching rule wins (not the
   * leftmost, as scalac's help says), so `any:e` comes first. Re-test any change on a scratch file.
   */
  val policy: String = {
    val rules = Seq("any:e", "cat=deprecation:w") ++ lenientWarnings.map(msg => s"msg=$msg:w")
    rules.mkString("-Wconf:", ",", "")
  }

  /** Fails on a warning in [[lenientWarnings]] left in the main or test code. The `lint` command alias runs it. */
  val warningsCheck: TaskKey[Unit] =
    taskKey[Unit]("Compiles the main and test code, and fails on an unused import or explicit parameter left in it.")

  /** The settings that define `warningsCheck` in a project. */
  val settings: Seq[Setting[_]] = Seq(
    warningsCheck := {
      val log = streams.value.log
      val analyses = Seq((Compile / compile).value, (Test / compile).value)
      val warnings = for {
        analysis <- analyses
        sourceInfo <- analysis.readSourceInfos().getAllSourceInfos.values().asScala.toSeq
        problem <- sourceInfo.getReportedProblems.toSeq ++ sourceInfo.getUnreportedProblems.toSeq
        if isLenientWarning(problem)
      } yield describe(problem)

      warnings.sorted.foreach(warning => log.error(warning))
      if (warnings.nonEmpty) {
        throw new MessageOnlyException(
          s"${warnings.size} warning(s) left in ${thisProject.value.id}. Fix them: `lint` only allows deprecations. " +
            "See docs/development/linting.md#warnings-policy."
        )
      }
    },
  )

  private def isLenientWarning(problem: Problem): Boolean =
    problem.severity == Severity.Warn && lenientWarnings.exists(_.r.findFirstIn(problem.message).isDefined)

  /** Formats a warning as `<path>:<line>:<column>: <message>`, with the path relative to the build's root directory. */
  private def describe(problem: Problem): String = {
    val position = problem.position
    // Zinc names a source file by a virtual path under `${BASE}`, the build's root directory.
    val path = position.sourcePath.orElse("<unknown file>").stripPrefix("${BASE}/")
    val line = position.line.map[String](_.toString).orElse("?")
    val column = position.pointer.map[String](pointer => (pointer + 1).toString).orElse("?")
    s"$path:$line:$column: ${problem.message}"
  }
}

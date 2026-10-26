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

package org.calinburloiu.music.microtonalist.perf.results

import org.calinburloiu.music.microtonalist.perf.env.{JvmInfo, MachineInfo}
import org.calinburloiu.music.microtonalist.perf.run.CaseOutcome
import play.api.libs.json.JsObject

import java.time.Instant

/**
 * The build of the app a run measured.
 *
 * @param commit The full hash of the commit it was built from.
 * @param dirty Whether tracked files differed from the commit; `None` when the JAR doesn't tell.
 * @param jarSha256 The digest of the JAR.
 */
case class AppBuild(version: String, commit: String, dirty: Option[Boolean], scalaVersion: String, jarSha256: String)

/**
 * The commit of the suite that measured, and whether tracked files differed from it, since the suite may move the
 * numbers.
 */
case class SuiteBuild(commit: Option[String], dirty: Option[Boolean])

/** How each case of a run was measured. */
case class RunConfigSummary(launches: Int, warmupIterations: Int, timedIterations: Int, paceMillis: Long)

/**
 * The result of a case.
 *
 * @param tracks Resource path of the case's tracks template.
 * @param composition Resource path of the case's composition.
 */
case class CaseResult(name: String, tracks: String, composition: String, outcome: CaseOutcome)

/**
 * Everything a run records: when, what and where it measured, and the results of the floor and of each case.
 *
 * @param floor The suite's own latency, measured over the loopback of the app's input device with no app running.
 * @param extra Room for later additions.
 */
case class RunResult(startTime: Instant,
                     endTime: Instant,
                     app: AppBuild,
                     suite: SuiteBuild,
                     jvm: JvmInfo,
                     machine: MachineInfo,
                     config: RunConfigSummary,
                     floor: CaseOutcome,
                     cases: Seq[CaseResult],
                     extra: JsObject = JsObject.empty)

object RunResult {
  /** The version of the run files' schema; it increases on a change that breaks their readers. */
  val SchemaVersion: Int = 1
}

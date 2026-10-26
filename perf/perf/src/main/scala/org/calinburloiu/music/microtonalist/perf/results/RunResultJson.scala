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

import org.calinburloiu.music.microtonalist.perf.PerfDevices
import org.calinburloiu.music.microtonalist.perf.env.{JvmInfo, MachineInfo, OsInfo}
import org.calinburloiu.music.microtonalist.perf.run.CaseOutcome
import org.calinburloiu.music.microtonalist.perf.stats.*
import org.calinburloiu.music.scmidi.MidiDeviceId
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import java.time.Instant

/**
 * The JSON format of the run files, which the report reads: durations in nanoseconds, a metric's statistics flattened
 * into it, a histogram's bins as `[lower bound, count]` pairs, and unknown values as `null`.
 */
object RunResultJson {

  /** The run as pretty-printed JSON, ending in a newline. */
  def write(run: RunResult): String = Json.prettyPrint(Json.toJson(run)) + "\n"

  /** Reads a run file. */
  def read(json: String): RunResult = Json.parse(json).as[RunResult]

  private val withNulls = Json.configured(using JsonConfiguration(optionHandlers = OptionHandlers.WritesNull))

  private given OFormat[MidiDeviceId] = Json.format[MidiDeviceId]
  private given OFormat[PerfDevices] = Json.format[PerfDevices]
  private given OFormat[OsInfo] = Json.format[OsInfo]
  private given OFormat[MachineInfo] = withNulls.format[MachineInfo]
  private given OFormat[JvmInfo] = Json.format[JvmInfo]
  private given OFormat[AppBuild] = withNulls.format[AppBuild]
  private given OFormat[SuiteBuild] = withNulls.format[SuiteBuild]
  private given OFormat[RunConfigSummary] = Json.format[RunConfigSummary]
  private given OFormat[MetricStatistics] = Json.format[MetricStatistics]
  private given OFormat[LaunchStatistics] = Json.format[LaunchStatistics]

  private given Format[HistogramBin] = Format(
    Reads(_.validate[(Long, Int)].map(HistogramBin.apply)),
    Writes(bin => Json.arr(bin.lowerBound, bin.count))
  )
  private given OFormat[Histogram] = Json.format[Histogram]

  private given OFormat[MetricResult] = (
    (__ \ "name").format[String] and
      (__ \ "from").format[String] and
      (__ \ "to").format[String] and
      __.format[MetricStatistics] and
      (__ \ "histogram").format[Histogram] and
      (__ \ "launches").format[Seq[LaunchStatistics]]
  )(MetricResult.apply, Tuple.fromProductTyped)

  private given OFormat[CaseOutcome] = OFormat(
    Reads { json =>
      (json \ "outcome").validate[String].flatMap {
        case "passed" => (json \ "metrics").validate[Seq[MetricResult]].map(CaseOutcome.Passed(_))
        case "failed" => (json \ "failure").validate[String].map(CaseOutcome.Failed(_))
        case other => JsError(s"Unknown outcome $other")
      }
    },
    OWrites[CaseOutcome] {
      case CaseOutcome.Passed(metrics) => Json.obj("outcome" -> "passed", "metrics" -> metrics)
      case CaseOutcome.Failed(reason) => Json.obj("outcome" -> "failed", "failure" -> reason)
    }
  )

  private given OFormat[CaseResult] = (
    (__ \ "name").format[String] and
      (__ \ "tracks").format[String] and
      (__ \ "composition").format[String] and
      __.format[CaseOutcome]
  )(CaseResult.apply, Tuple.fromProductTyped)

  private val runFieldsFormat: OFormat[RunResult] = (
    (__ \ "startTime").format[Instant] and
      (__ \ "endTime").format[Instant] and
      (__ \ "app").format[AppBuild] and
      (__ \ "suite").format[SuiteBuild] and
      (__ \ "jvm").format[JvmInfo] and
      (__ \ "machine").format[MachineInfo] and
      (__ \ "config").format[RunConfigSummary] and
      (__ \ "floor").format[CaseOutcome] and
      (__ \ "cases").format[Seq[CaseResult]] and
      (__ \ "extra").format[JsObject]
  )(RunResult.apply, Tuple.fromProductTyped)

  private given OFormat[RunResult] = OFormat(
    runFieldsFormat,
    OWrites[RunResult](run => Json.obj("schemaVersion" -> RunResult.SchemaVersion) ++ runFieldsFormat.writes(run))
  )
}

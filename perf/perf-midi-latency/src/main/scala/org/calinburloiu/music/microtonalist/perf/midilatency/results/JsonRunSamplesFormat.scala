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

package org.calinburloiu.music.microtonalist.perf.midilatency.results

import org.calinburloiu.music.microtonalist.perf.midilatency.run.CaseOutcome
import play.api.libs.json.{JsObject, Json}

/**
 * The JSON format of a run's raw samples, which the report plots per sample: each metric's samples of each launch, in
 * nanoseconds, in the order they were taken, laid out like the run file.
 */
object JsonRunSamplesFormat {

  /** The version of the samples files' schema; it increases on a change that breaks their readers. */
  val SchemaVersion: Int = 1

  /** The raw samples of the run as compact JSON, ending in a newline; a failed case has no metrics. */
  def write(run: RunResult): String = Json.stringify(Json.obj(
    "schemaVersion" -> SchemaVersion,
    "floor" -> Json.obj("metrics" -> metricsOf(run.floor)),
    "cases" -> run.cases.map(result => Json.obj("name" -> result.name, "metrics" -> metricsOf(result.outcome)))
  )) + "\n"

  private def metricsOf(outcome: CaseOutcome): Seq[JsObject] = outcome match {
    case CaseOutcome.Passed(metrics) =>
      metrics.map(metric => Json.obj("name" -> metric.name, "launches" -> metric.samples))
    case CaseOutcome.Failed(_) => Seq.empty
  }
}

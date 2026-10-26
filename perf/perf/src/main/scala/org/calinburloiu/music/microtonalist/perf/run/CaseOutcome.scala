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

package org.calinburloiu.music.microtonalist.perf.run

import org.calinburloiu.music.microtonalist.perf.stats.MetricResult

/** The outcome of measuring a case. */
enum CaseOutcome {
  /** Every iteration got its expected output; holds the statistics of each metric. */
  case Passed(metrics: Seq[MetricResult])

  /** An iteration failed, or the app wasn't ready; the reason says where and why. */
  case Failed(reason: String)
}

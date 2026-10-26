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

package org.calinburloiu.music.microtonalist.perf.stats

import org.calinburloiu.music.microtonalist.perf.Metric
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MetricResultTest extends AnyWordSpec with Matchers {

  "MetricResult" should {
    "compute the statistics of all launches and of each" in {
      // Given
      val metric = Metric("pedal→tuning", from = "pedal", to = "tuning")
      val samples = (1L to 30L).toArray

      // When
      val result = MetricResult.of(metric, samples, launchSizes = Seq(10, 20))

      // Then
      result.name shouldEqual "pedal→tuning"
      result.from shouldEqual "pedal"
      result.to shouldEqual "tuning"
      result.statistics.count shouldEqual 30
      result.launches.head shouldEqual LaunchStatistics(count = 10, median = 5, p99 = 10, max = 10)
      result.launches(1).median shouldEqual 20
      result.histogram.bins.map(_.count).sum shouldEqual 30
    }
  }
}

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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class SampleStatisticsTest extends AnyWordSpec with Matchers {

  private val oneToHundred: Array[Long] = (1L to 100L).toArray

  "SampleStatistics" should {
    "compute the statistics of 1 to 100" in {
      // Given
      val samples = Random(42).shuffle(oneToHundred.toSeq).toArray

      // When
      val statistics = SampleStatistics.of(samples, 0, samples.length)

      // Then
      statistics.count shouldEqual 100
      statistics.min shouldEqual 1
      statistics.max shouldEqual 100
      statistics.median shouldEqual 50
      statistics.p90 shouldEqual 90
      statistics.p95 shouldEqual 95
      statistics.p99 shouldEqual 99
      statistics.mean shouldEqual 50.5
      statistics.stdDev shouldEqual 29.0115 +- 1e-3
    }

    "use only the given range" in {
      // When
      val statistics = SampleStatistics.of(Array(9L, 1L, 2L, 3L, 9L), 1, 4)

      // Then
      statistics.count shouldEqual 3
      statistics.min shouldEqual 1
      statistics.max shouldEqual 3
    }

    "leave the samples in place" in {
      // Given
      val samples = Array(5L, 3L, 9L, 1L)
      val copy = samples.clone()

      // When
      SampleStatistics.of(samples, 0, samples.length)

      // Then
      samples shouldEqual copy
    }

    "give a zero deviation for one sample" in {
      // When
      val statistics = SampleStatistics.of(Array(7L), 0, 1)

      // Then
      statistics.stdDev shouldEqual 0.0
      statistics.median shouldEqual 7
      statistics.p99 shouldEqual 7
    }

    "reject an empty range" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy SampleStatistics.of(Array(1L, 2L), 1, 1)
    }

    "compute a launch's statistics" in {
      // When
      val statistics = SampleStatistics.launch(oneToHundred, 0, oneToHundred.length)

      // Then
      statistics shouldEqual LaunchStatistics(count = 100, median = 50, p99 = 99, max = 100)
    }
  }
}

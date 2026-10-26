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

class LogHistogramTest extends AnyWordSpec with Matchers {

  private def histogramOf(samples: Long*): Histogram = LogHistogram.of(samples.toArray, 0, samples.size)

  /** The bin of `histogram` that holds `value`: the last one whose lower bound doesn't exceed it. */
  private def binOf(histogram: Histogram, value: Long): HistogramBin =
    histogram.bins.filter(_.lowerBound <= value).last

  "LogHistogram" should {
    "put each sample in the bin that bounds it" in {
      // Given
      val samples = (1L to 200000L by 997L).toArray

      // When
      val histogram = LogHistogram.of(samples, 0, samples.length)

      // Then
      histogram.growth shouldEqual LogHistogram.DefaultGrowth
      histogram.bins.map(_.count).sum shouldEqual samples.length
      for (sample <- samples) {
        val bin = binOf(histogram, sample)
        // A bin's bounds are floored to whole nanoseconds, so its upper bound is below (lower bound + 1) · growth
        sample.toDouble should be < (bin.lowerBound + 1) * histogram.growth
      }
    }

    "keep only non-empty bins, ascending" in {
      // When
      val histogram = histogramOf(5000, 10, 5000, 300)

      // Then
      histogram.bins.map(_.count) shouldEqual Seq(1, 1, 2)
      histogram.bins.map(_.lowerBound) shouldEqual histogram.bins.map(_.lowerBound).sorted
    }

    "separate values 1% apart" in {
      // When
      val histogram = histogramOf(1000, 1005, 1011)

      // Then
      histogram.bins should have size 2
      binOf(histogram, 1000) shouldEqual binOf(histogram, 1005)
      binOf(histogram, 1011) should not equal binOf(histogram, 1000)
    }

    "count a zero sample in the lowest bin" in {
      // When
      val histogram = histogramOf(0)

      // Then
      histogram.bins shouldEqual Seq(HistogramBin(lowerBound = 1, count = 1))
    }
  }
}

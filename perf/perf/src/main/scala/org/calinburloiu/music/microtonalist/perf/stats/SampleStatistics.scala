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

import java.util

/** Computes the statistics of raw duration samples. */
object SampleStatistics {

  /**
   * Computes the statistics of `samples(from until until)`, without changing the array.
   *
   * @throws IllegalArgumentException if the range is empty.
   */
  def of(samples: Array[Long], from: Int, until: Int): MetricStatistics = {
    val sorted = sortedCopy(samples, from, until)
    val count = sorted.length
    val mean = sorted.map(_.toDouble).sum / count
    val squaredDeviations = sorted.map { sample =>
      val deviation = sample - mean
      deviation * deviation
    }.sum
    val stdDev = if (count > 1) Math.sqrt(squaredDeviations / (count - 1)) else 0.0

    MetricStatistics(
      count = count,
      min = sorted.head,
      mean = mean,
      stdDev = stdDev,
      median = nearestRank(sorted, 50),
      p90 = nearestRank(sorted, 90),
      p95 = nearestRank(sorted, 95),
      p99 = nearestRank(sorted, 99),
      max = sorted.last
    )
  }

  /**
   * Computes the statistics of one launch from `samples(from until until)`, without changing the array.
   *
   * @throws IllegalArgumentException if the range is empty.
   */
  def launch(samples: Array[Long], from: Int, until: Int): LaunchStatistics = {
    val sorted = sortedCopy(samples, from, until)
    LaunchStatistics(count = sorted.length, median = nearestRank(sorted, 50), p99 = nearestRank(sorted, 99),
      max = sorted.last)
  }

  private def sortedCopy(samples: Array[Long], from: Int, until: Int): Array[Long] = {
    require(from < until, s"No samples in the range [$from, $until)")
    val sorted = util.Arrays.copyOfRange(samples, from, until)
    util.Arrays.sort(sorted)
    sorted
  }

  /** The smallest sample that at least `percentile`% of the `sorted` samples don't exceed. */
  private def nearestRank(sorted: Array[Long], percentile: Int): Long = {
    val rank = Math.ceil(percentile / 100.0 * sorted.length).toInt
    sorted(Math.max(rank, 1) - 1)
  }
}

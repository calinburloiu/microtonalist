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

/**
 * The statistics of a metric over every launch of its case, and of each launch.
 *
 * @param from The mark of the metric's input series.
 * @param to The mark of the metric's output series.
 */
case class MetricResult(name: String,
                        from: String,
                        to: String,
                        statistics: MetricStatistics,
                        histogram: Histogram,
                        launches: Seq[LaunchStatistics])

object MetricResult {

  /**
   * Computes the result of `metric` from its raw `samples`, the launches' one after another.
   *
   * @param launchSizes How many samples each launch contributed, in order.
   */
  def of(metric: Metric, samples: Array[Long], launchSizes: Seq[Int]): MetricResult = {
    val launchStarts = launchSizes.scanLeft(0)(_ + _)
    val count = launchStarts.last
    val launches = launchStarts.zip(launchStarts.tail).map { case (from, until) =>
      SampleStatistics.launch(samples, from, until)
    }

    MetricResult(
      name = metric.name,
      from = metric.from,
      to = metric.to,
      statistics = SampleStatistics.of(samples, 0, count),
      histogram = LogHistogram.of(samples, 0, count),
      launches = launches
    )
  }
}

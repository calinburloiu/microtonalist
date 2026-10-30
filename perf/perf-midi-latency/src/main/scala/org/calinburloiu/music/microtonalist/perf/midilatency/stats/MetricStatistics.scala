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

package org.calinburloiu.music.microtonalist.perf.midilatency.stats

/**
 * The statistics of a metric's samples, all durations in nanoseconds.
 *
 * Percentiles are nearest-rank: the smallest sample that at least that percentage of the samples doesn't exceed.
 *
 * @param count Number of samples.
 * @param stdDev Sample standard deviation (with `count − 1`), `0` for one sample.
 */
case class MetricStatistics(count: Int,
                            min: Long,
                            mean: Double,
                            stdDev: Double,
                            median: Long,
                            p90: Long,
                            p95: Long,
                            p99: Long,
                            max: Long)

/**
 * The statistics of the samples of one launch of the app, which tell whether the launches of a case agree.
 *
 * Durations are in nanoseconds; percentiles are nearest-rank, as in [[MetricStatistics]].
 */
case class LaunchStatistics(count: Int, median: Long, p99: Long, max: Long)

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
 * A bin of a [[Histogram]].
 *
 * @param lowerBound The smallest duration the bin holds, in nanoseconds.
 * @param count Number of samples in the bin.
 */
case class HistogramBin(lowerBound: Long, count: Int)

/**
 * The distribution of a metric's samples over log-spaced bins.
 *
 * @param growth Ratio between the bounds of consecutive bins: a bin holds the durations from its lower bound up to
 *   about its lower bound times `growth`.
 * @param bins The non-empty bins, ascending.
 */
case class Histogram(growth: Double, bins: Seq[HistogramBin])

/** Builds [[Histogram]]s whose bins grow geometrically. */
object LogHistogram {

  /** Each bin is 1% wider than the one before it. */
  val DefaultGrowth: Double = 1.01

  /**
   * Builds the histogram of `samples(from until until)`. Bin `i` holds the durations from `floor(growth^i)` up to, but
   * excluding, `floor(growth^(i+1))`; a sample below 1 ns counts in the bin of 1 ns.
   */
  def of(samples: Array[Long], from: Int, until: Int, growth: Double = DefaultGrowth): Histogram = {
    val counts = scala.collection.mutable.TreeMap[Int, Int]()
    for (i <- from until until) {
      val index = binIndex(Math.max(samples(i), 1L), growth)
      counts(index) = counts.getOrElse(index, 0) + 1
    }

    Histogram(growth, counts.toSeq.map { case (index, count) => HistogramBin(lowerBound(index, growth), count) })
  }

  private def lowerBound(index: Int, growth: Double): Long = Math.floor(Math.pow(growth, index)).toLong

  /** The index of the bin that holds `value`, which must be at least 1. */
  private def binIndex(value: Long, growth: Double): Int = {
    var index = Math.floor(Math.log(value.toDouble) / Math.log(growth)).toInt
    // Rounding, and the narrow low bins whose floored bounds hold no whole value, may put the first guess a few bins off
    while (lowerBound(index, growth) > value) {
      index -= 1
    }
    while (lowerBound(index + 1, growth) <= value) {
      index += 1
    }
    index
  }
}

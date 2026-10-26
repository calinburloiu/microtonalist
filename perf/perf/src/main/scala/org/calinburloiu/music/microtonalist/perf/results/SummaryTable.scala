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

import org.calinburloiu.music.microtonalist.perf.run.CaseOutcome

import java.util.Locale

/** The table a run prints at its end: the median, p99 and maximum of each metric, in microseconds. */
object SummaryTable {

  private val Header = Seq("Case", "Metric", "Median (µs)", "p99 (µs)", "Max (µs)")

  /** Renders the table, the floor first; a failed case shows its reason instead. */
  def render(run: RunResult): String = {
    val outcomes = ("floor" -> run.floor) +: run.cases.map(result => result.name -> result.outcome)
    val rows = outcomes.flatMap {
      case (caseName, CaseOutcome.Passed(metrics)) => metrics.map { metric =>
          val statistics = metric.statistics
          Seq(caseName, metric.name, micros(statistics.median), micros(statistics.p99), micros(statistics.max))
        }
      case (caseName, CaseOutcome.Failed(reason)) => Seq(Seq(caseName, s"FAILED: $reason"))
    }

    // The columns are as wide as their widest cell, but a failure's reason doesn't widen its column
    val widths = Header.indices.map { column =>
      (Header +: rows.filter(_.size == Header.size)).map(_(column).length).max
    }
    (Header +: rows).map { row =>
      val isFailure = row.size < Header.size
      row.zipWithIndex.map { case (cell, column) =>
        if (column == 0 || (column == 1 && !isFailure)) cell.padTo(widths(column), ' ')
        else if (isFailure) cell
        else " " * (widths(column) - cell.length) + cell
      }.mkString("  ")
    }.mkString("", "\n", "\n")
  }

  private def micros(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / 1000.0)
}

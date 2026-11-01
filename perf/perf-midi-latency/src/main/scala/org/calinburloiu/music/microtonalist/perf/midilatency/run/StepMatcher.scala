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

package org.calinburloiu.music.microtonalist.perf.midilatency.run

import org.calinburloiu.music.microtonalist.perf.midilatency.OutputSeries
import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * Matches the messages received during a step to the step's output series.
 *
 * Within a series the messages must match in order. A message goes to the first series, in declaration order, whose
 * next expected message it matches, since no order is imposed among the series. One that matches none, or that arrives
 * once every series is complete, is a mismatch.
 *
 * It is not thread-safe: the thread that runs the step feeds it.
 */
final class StepMatcher(series: Seq[OutputSeries]) {

  import StepMatcher.Status

  private val expected = series.map(_.expected.toIndexedSeq).toIndexedSeq
  // The index of the next expected message of each series, and the receive time of its last message
  private val cursors: Array[Int] = new Array(series.size)
  private val times: Array[Long] = new Array(series.size)
  private val received = scala.collection.mutable.ArrayBuffer[MidiMsg]()
  private var completeCount: Int = 0

  /** Feeds a received message and tells the status of the step's output. */
  def accept(message: MidiMsg, receivedAt: Long): Status = {
    received += message
    if (isComplete) {
      Status.Mismatch(s"Received the extra message $message after the expected output")
    } else {
      val seriesIndex = expected.indices.indexWhere { i =>
        cursors(i) < expected(i).size && expected(i)(cursors(i)).matches(message)
      }
      if (seriesIndex < 0) {
        Status.Mismatch(s"Received the unexpected message $message; expected next: $describeNext; $describeReceived")
      } else {
        cursors(seriesIndex) += 1
        if (cursors(seriesIndex) == expected(seriesIndex).size) {
          times(seriesIndex) = receivedAt
          completeCount += 1
        }
        if (isComplete) Status.Complete else Status.Pending
      }
    }
  }

  /** Whether every series is complete. */
  def isComplete: Boolean = completeCount == series.size

  /** For each series, the receive time of its last message, once it is complete. */
  def completionTimes: IndexedSeq[Long] = times.toIndexedSeq

  /** Describes what each incomplete series still expects, and what was received, for a failure. */
  def describeMissing: String = {
    val missing = for (i <- expected.indices if cursors(i) < expected(i).size)
      yield s"${label(i)} still expected: ${expected(i).drop(cursors(i)).map(_.description).mkString(", ")}"
    s"${missing.mkString("; ")}; $describeReceived"
  }

  /** Forgets everything received, for the next iteration. */
  def reset(): Unit = {
    java.util.Arrays.fill(cursors, 0)
    java.util.Arrays.fill(times, 0L)
    received.clear()
    completeCount = 0
  }

  private def describeNext: String =
    (for (i <- expected.indices if cursors(i) < expected(i).size)
      yield s"${label(i)}: ${expected(i)(cursors(i)).description}").mkString("; ")

  private def describeReceived: String = s"received: ${received.mkString(", ")}"

  private def label(seriesIndex: Int): String =
    series(seriesIndex).mark.map(mark => s"series '$mark'").getOrElse(s"series #${seriesIndex + 1}")
}

object StepMatcher {

  /** The status of a step's output. */
  enum Status {
    /** Some messages are still expected. */
    case Pending

    /** Every series is complete. */
    case Complete

    /** A message didn't match what was expected. */
    case Mismatch(reason: String)
  }
}

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

package org.calinburloiu.music.microtonalist.perf.midi

import org.calinburloiu.music.scmidi.MidiReceiver
import org.calinburloiu.music.scmidi.message.MidiMsg

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * A message the suite received.
 *
 * @param nanos The `System.nanoTime` when it arrived.
 */
case class Received(message: MidiMsg, nanos: Long)

/**
 * The receiver the suite attaches to the app's output: it timestamps each message as it arrives and stores it for the
 * thread that runs the iterations, doing nothing else, so that it adds as little as possible to what it measures.
 *
 * It is thread-safe: MIDI threads send to it while one thread polls it.
 *
 * @param capacity How many messages it holds; one more sets [[hasOverflowed]].
 */
final class ReceiveBuffer(capacity: Int = ReceiveBuffer.DefaultCapacity) extends MidiReceiver {

  private val queue = ArrayBlockingQueue[Received](capacity)
  @volatile private var overflowed: Boolean = false

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    // The receive time is taken first, before anything else the callback does
    val nanos = System.nanoTime()
    if (!queue.offer(Received(message, nanos))) {
      overflowed = true
    }
  }

  /** Takes the oldest message, waiting for one until `deadlineNanos` (a `System.nanoTime`) at most. */
  def pollUntil(deadlineNanos: Long): Option[Received] =
    Option(queue.poll(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS))

  /** Takes every message, waiting until none arrives for `quiet`. */
  def drainUntilQuiet(quiet: FiniteDuration): Seq[Received] = {
    val drained = mutable.ArrayBuffer[Received]()
    var next = queue.poll(quiet.toNanos, TimeUnit.NANOSECONDS)
    while (next != null) {
      drained += next
      next = queue.poll(quiet.toNanos, TimeUnit.NANOSECONDS)
    }
    drained.toSeq
  }

  /** Forgets the messages it holds and an overflow. */
  def clear(): Unit = {
    queue.clear()
    overflowed = false
  }

  /** Whether a message arrived while it was full, and was lost, since it was created or cleared. */
  def hasOverflowed: Boolean = overflowed
}

object ReceiveBuffer {
  /** Room for far more messages than a step of a case brings. */
  val DefaultCapacity: Int = 4096
}

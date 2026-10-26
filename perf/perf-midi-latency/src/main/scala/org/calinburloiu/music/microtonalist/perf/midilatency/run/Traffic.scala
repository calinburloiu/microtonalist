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

import org.calinburloiu.music.microtonalist.perf.midilatency.{Expected, Scenario}
import org.calinburloiu.music.scmidi.message.{MidiMsg, SysExMidiMsg}

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

/**
 * The MIDI traffic of an iteration, which paces the warm-up so that no device carries more messages a second than it
 * takes. A message counts as one, and so does an expected pattern; a SysEx counts as one per 6 data bytes, as many as a
 * 64-bit Universal MIDI Packet carries.
 */
object Traffic {

  private val SysExBytesPerPacket: Int = 6

  /** How many messages `message` counts as. */
  def weight(message: MidiMsg): Int = message match {
    // The data bytes leave out the leading 0xF0 and the trailing 0xF7
    case SysExMidiMsg(data) => Math.max(1, Math.ceilDiv(data.size - 2, SysExBytesPerPacket))
    case _ => 1
  }

  /**
   * How many messages an iteration of `scenario` sends to the app's input device or expects from its output device,
   * whichever is more.
   */
  def perIteration(scenario: Scenario): Int = {
    val sent = scenario.steps.flatMap(_.inputs).flatMap(_.messages).map(weight).sum
    val expected = scenario.steps.flatMap(_.outputs).flatMap(_.expected).map {
      case Expected.Exactly(message) => weight(message)
      case _: Expected.Matching => 1
    }.sum
    Math.max(sent, expected)
  }

  /**
   * The period on which iterations of `scenario` keep each device under `maxMessageRate` messages a second; zero, to
   * run them back to back, for a rate of zero, which sets no limit.
   */
  def warmupPace(scenario: Scenario, maxMessageRate: Int): FiniteDuration =
    if (maxMessageRate == 0) {
      Duration.Zero
    } else {
      Math.ceilDiv(perIteration(scenario) * 1_000_000_000L, maxMessageRate.toLong).nanos
    }
}

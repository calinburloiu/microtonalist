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

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.{NoteOffMidiMsg, NoteOnMidiMsg}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ReceiveBufferTest extends AnyWordSpec with Matchers {

  /** How long a test waits for something that must not arrive. */
  private val ShortWait: FiniteDuration = 50.millis

  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))

  "ReceiveBuffer" should {
    "timestamp a message when it arrives" in {
      // Given
      val buffer = ReceiveBuffer()
      val before = System.nanoTime()

      // When
      buffer.send(noteOn)
      val after = System.nanoTime()

      // Then
      val received = buffer.pollUntil(System.nanoTime() + ShortWait.toNanos)
      received.map(_.message) shouldEqual Some(noteOn)
      received.get.nanos should (be >= before and be <= after)
    }

    "return nothing when nothing arrives by the deadline" in {
      // Given
      val buffer = ReceiveBuffer()

      // When / Then
      buffer.pollUntil(System.nanoTime() + ShortWait.toNanos) shouldBe empty
    }

    "flag an overflow" in {
      // Given
      val buffer = ReceiveBuffer(capacity = 1)

      // When
      buffer.send(noteOn)
      buffer.send(noteOff)

      // Then
      buffer.hasOverflowed shouldBe true
    }

    "drain everything until quiet" in {
      // Given
      val buffer = ReceiveBuffer()
      buffer.send(noteOn)
      buffer.send(noteOff)

      // When
      val drained = buffer.drainUntilQuiet(ShortWait)

      // Then
      drained.map(_.message) shouldEqual Seq(noteOn, noteOff)
      buffer.pollUntil(System.nanoTime()) shouldBe empty
    }

    "forget everything on clear" in {
      // Given
      val buffer = ReceiveBuffer(capacity = 1)
      buffer.send(noteOn)
      buffer.send(noteOff)

      // When
      buffer.clear()

      // Then
      buffer.pollUntil(System.nanoTime()) shouldBe empty
      buffer.hasOverflowed shouldBe false
    }
  }
}

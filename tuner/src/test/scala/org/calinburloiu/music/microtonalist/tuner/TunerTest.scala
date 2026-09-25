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

package org.calinburloiu.music.microtonalist.tuner

import ch.qos.logback.classic.Level
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.*
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, PitchBendMidiMsg}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TunerTest extends AnyWordSpec with Matchers {

  private val resetMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)
  private val standardTuningMessage: MidiMsg = PitchBendMidiMsg(0, 0)
  private val justCMajMessage: MidiMsg = PitchBendMidiMsg(0, 100)
  private val justCRastMessage: MidiMsg = PitchBendMidiMsg(0, 200)
  private val justDUssakMessage: MidiMsg = PitchBendMidiMsg(0, 300)

  private abstract class Fixture {
    val tuner: FakeTuner = FakeTuner(
      resetMessages = Seq(resetMessage),
      tuningMessages = Map(
        Tuning.Standard -> Seq(standardTuningMessage),
        TestTunings.justCMaj -> Seq(justCMajMessage),
        TestTunings.justCRast -> Seq(justCRastMessage),
        TestTunings.justDUssak -> Seq(justDUssakMessage)
      ),
      untunableTunings = Set(TestTunings.justDUssak)
    )
  }

  "a Tuner" should {
    "be in the Standard Tuning before being tuned" in new Fixture {
      // Then
      tuner.tuning shouldEqual Tuning.Standard
    }

    "return the messages that apply a tuning when tuned to it" in new Fixture {
      // When
      private val output = tuner.tune(TestTunings.justCMaj)

      // Then
      output shouldEqual Seq(justCMajMessage)
    }

    "tell onTune that the output instrument was in its current tuning when tuned" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      tuner.tune(TestTunings.justCRast)

      // Then
      tuner.previousTunings shouldEqual Seq(Some(Tuning.Standard), Some(TestTunings.justCMaj))
    }

    "keep the tuning it was last tuned to" in new Fixture {
      // When
      tuner.tune(TestTunings.justCMaj)
      tuner.tune(TestTunings.justCRast)

      // Then
      tuner.tuning shouldEqual TestTunings.justCRast
    }

    "return no messages when tuned to a tuning it cannot tune" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      private val output = tuner.tune(TestTunings.justDUssak)

      // Then
      output shouldBe empty
      tuner.appliedTunings shouldEqual Seq(TestTunings.justCMaj)
    }

    "keep its current tuning when tuned to a tuning it cannot tune" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      tuner.tune(TestTunings.justDUssak)

      // Then
      tuner.tuning shouldEqual TestTunings.justCMaj
    }

    "warn when tuned to a tuning it cannot tune" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      private val (_, events) = LogCapture.capturing(classOf[FakeTuner].getName) {
        tuner.tune(TestTunings.justDUssak)
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual Seq(
        s"""The "fake" tuner cannot tune exactly to ${TestTunings.justDUssak}, so it keeps the current tuning """ +
          s"${TestTunings.justCMaj}."
      )
    }

    "restate its current tuning after its reset messages when reset" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      private val output = tuner.reset()

      // Then
      output shouldEqual Seq(resetMessage, justCMajMessage)
      tuner.appliedTunings shouldEqual Seq(TestTunings.justCMaj, TestTunings.justCMaj)
    }

    "tell onTune that the tuning of the output instrument is unknown when reset" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      tuner.reset()

      // Then
      tuner.previousTunings.last shouldBe None
    }

    "keep its current tuning when reset" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)

      // When
      tuner.reset()

      // Then
      tuner.tuning shouldEqual TestTunings.justCMaj
    }

    "restate the tuning it kept when reset after being tuned to a tuning it cannot tune" in new Fixture {
      // Given
      tuner.tune(TestTunings.justCMaj)
      tuner.tune(TestTunings.justDUssak)

      // When
      private val output = tuner.reset()

      // Then
      output shouldEqual Seq(resetMessage, justCMajMessage)
    }

    "restate the Standard Tuning when reset before being tuned" in new Fixture {
      // When
      private val output = tuner.reset()

      // Then
      output shouldEqual Seq(resetMessage, standardTuningMessage)
    }
  }
}

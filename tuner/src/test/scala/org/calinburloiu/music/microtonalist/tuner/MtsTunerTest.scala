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

import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOnMidiMsg, SysExMidiMsg}
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq

class MtsTunerTest extends AnyWordSpec with Matchers with MockFactory {

  abstract class Fixture(thru: Boolean = MtsTuner.DefaultThru, canEncode: Boolean = true) {
    val mtsMessageGenerator: MtsMessageGenerator = stub[MtsMessageGenerator]("MtsMessageGenerator")
    val sysExMessage: SysExMidiMsg = SysExMidiMsg(
      ArraySeq.unsafeWrapArray(Array(0xF0, 0x7E, 0x7F, 0x08, 0xF7).map(_.toByte)))
    val tuner: MtsTuner = new MtsTuner(mtsMessageGenerator, thru) {
      override val typeName: String = "test"
    }

    mtsMessageGenerator.canEncode.when(*).returns(canEncode)
    mtsMessageGenerator.generate.when(*).returns(sysExMessage)
  }

  /** Beyond the ±100 cents range of a tuning value in the 2-byte form, on every pitch class. */
  private val tuningBeyondASemitone = Tuning.fromOffsets("beyond a semitone", Seq.fill(12)(150.0))

  "MtsTuner#canTune" should {
    "accept a tuning that its MTS message generator can encode" in new Fixture {
      // When / Then
      tuner.canTune(TestTunings.justCMaj) shouldBe true
    }

    "reject a tuning that its MTS message generator cannot encode" in new Fixture(canEncode = false) {
      // When / Then
      tuner.canTune(TestTunings.justCMaj) shouldBe false
    }
  }

  "MtsTuner#tune" should {
    "return the generated SysEx MTS message" in new Fixture {
      // When
      val result: Seq[MidiMsg] = tuner.tune(TestTunings.justCMaj)
      // Then
      mtsMessageGenerator.generate.verify(TestTunings.justCMaj).once()
      result shouldEqual Seq(sysExMessage)
    }

    "return the generated SysEx MTS message for a tuning beyond the range of a tuning value in it" in {
      // Given
      val tuner = MtsOctave2ByteNonRealTimeTuner()

      // When
      val result: Seq[MidiMsg] = tuner.tune(tuningBeyondASemitone)

      // Then
      result shouldEqual Seq(MtsMessageGenerator.Octave2ByteNonRealTime.generate(tuningBeyondASemitone))
    }
  }

  "MtsTuner#reset" should {
    "re-send the tuning last set" in {
      // Given
      val tuner = MtsOctave1ByteNonRealTimeTuner()
      tuner.tune(TestTunings.justCMaj)
      // When
      val result: Seq[MidiMsg] = tuner.reset()
      // Then
      result shouldEqual Seq(MtsMessageGenerator.Octave1ByteNonRealTime.generate(TestTunings.justCMaj))
    }

    "send the Standard Tuning when no tuning was set" in {
      // Given
      val tuner = MtsOctave1ByteNonRealTimeTuner()
      // When
      val result: Seq[MidiMsg] = tuner.reset()
      // Then
      result shouldEqual Seq(MtsMessageGenerator.Octave1ByteNonRealTime.generate(Tuning.Standard))
    }
  }

  "MtsTuner#process" should {
    "return the received MIDI message if thru is true" in new Fixture(thru = true) {
      // Given
      val message: MidiMsg = NoteOnMidiMsg(0, MidiNote.A4)
      // Then
      tuner.process(message) shouldEqual Seq(message)
    }

    "return nothing if thru is false" in new Fixture(thru = false) {
      // Given
      val message: MidiMsg = NoteOnMidiMsg(0, MidiNote.A4)
      // Then
      tuner.process(message) shouldBe empty
    }
  }

  "MtsOctave1ByteNonRealTimeTuner" should {
    "use MtsMessageGenerator.Octave1ByteNonRealTime" in {
      val tuner = MtsOctave1ByteNonRealTimeTuner(thru = true)
      tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave1ByteNonRealTime
      tuner.thru shouldBe true
    }
  }

  "MtsOctave2ByteNonRealTimeTuner" should {
    "use MtsMessageGenerator.Octave2ByteNonRealTime" in {
      val tuner = MtsOctave2ByteNonRealTimeTuner(thru = true)
      tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave2ByteNonRealTime
      tuner.thru shouldBe true
    }
  }

  "MtsOctave1ByteRealTimeTuner" should {
    "use MtsMessageGenerator.Octave1ByteRealTime" in {
      val tuner = MtsOctave1ByteRealTimeTuner(thru = true)
      tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave1ByteRealTime
      tuner.thru shouldBe true
    }
  }

  "MtsOctave2ByteRealTimeTuner" should {
    "use MtsMessageGenerator.Octave2ByteRealTime" in {
      val tuner = MtsOctave2ByteRealTimeTuner(thru = true)
      tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave2ByteRealTime
      tuner.thru shouldBe true
    }
  }
}

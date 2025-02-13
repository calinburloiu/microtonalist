/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.tuner.TunerTestUtils.majTuning
import org.calinburloiu.music.scmidi.{MidiNote, ScNoteOnMidiMessage}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, SysexMessage}

class MtsTunerTest extends AnyFlatSpec with Matchers with MockFactory {

  abstract class Fixture(thru: Boolean = MtsTuner.DefaultThru) {
    val mtsMessageGenerator: MtsMessageGenerator = stub[MtsMessageGenerator]("MtsMessageGenerator")
    val sysexMessage: SysexMessage = stub[SysexMessage]("SysexMessage")
    val tuner: MtsTuner = new MtsTuner(mtsMessageGenerator, thru) {
      override val typeName: String = "test"
    }

    (mtsMessageGenerator.generate _).when(*).returns(sysexMessage)
  }

  "MtsTuner#tune" should "return the generated SysEx MTS message" in new Fixture {
    // When
    val result: Seq[MidiMessage] = tuner.tune(majTuning)
    // Then
    (mtsMessageGenerator.generate _).verify(majTuning).once()
    result shouldEqual Seq(sysexMessage)
  }

  "MtsTuner#process" should "return the received MIDI message if thru is true" in new Fixture(thru = true) {
    // Given
    val message: MidiMessage = ScNoteOnMidiMessage(0, MidiNote.A4).javaMidiMessage
    // Then
    tuner.process(message) shouldEqual Seq(message)
  }

  it should "return the received MIDI message if thru is false" in new Fixture(thru = false) {
    // Given
    val message: MidiMessage = ScNoteOnMidiMessage(0, MidiNote.A4).javaMidiMessage
    // Then
    tuner.process(message) shouldBe empty
  }

  "MtsOctave1ByteNonRealTimeTuner" should "use MtsMessageGenerator.Octave1ByteNonRealTime" in {
    val tuner = MtsOctave1ByteNonRealTimeTuner(thru = true)
    tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave1ByteNonRealTime
    tuner.thru shouldBe true
  }

  "MtsOctave2ByteNonRealTimeTuner" should "use MtsMessageGenerator.Octave2ByteNonRealTime" in {
    val tuner = MtsOctave2ByteNonRealTimeTuner(thru = true)
    tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave2ByteNonRealTime
    tuner.thru shouldBe true
  }

  "MtsOctave1ByteRealTimeTuner" should "use MtsMessageGenerator.Octave1ByteRealTime" in {
    val tuner = MtsOctave1ByteRealTimeTuner(thru = true)
    tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave1ByteRealTime
    tuner.thru shouldBe true
  }

  "MtsOctave2ByteRealTimeTuner" should "use MtsMessageGenerator.Octave2ByteRealTime" in {
    val tuner = MtsOctave2ByteRealTimeTuner(thru = true)
    tuner.mtsMessageGenerator shouldEqual MtsMessageGenerator.Octave2ByteRealTime
    tuner.thru shouldBe true
  }
}

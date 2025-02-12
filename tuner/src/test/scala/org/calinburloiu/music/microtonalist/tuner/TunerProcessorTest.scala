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

import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.microtonalist.tuner.TunerTestUtils.majTuning
import org.calinburloiu.music.scmidi.{ScCcMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver}

class TunerProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMessage = ScCcMidiMessage(0, 67, 0).javaMidiMessage

  val tuneMessage1: MidiMessage = ScPitchBendMidiMessage(0, 100).javaMidiMessage
  val tuneMessage2: MidiMessage = ScPitchBendMidiMessage(0, 0).javaMidiMessage

  val processMessage1: MidiMessage = ScNoteOnMidiMessage(0, 60, 64).javaMidiMessage
  val processMessage2: MidiMessage = ScPitchBendMidiMessage(0, 101).javaMidiMessage

  abstract class Fixture(shouldConnect: Boolean = true) {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.init()).when().returns(Seq(initMessage))
    (tuner.tune _).when(majTuning).returns(Seq(tuneMessage1))
    (tuner.tune _).when(OctaveTuning.Edo12).returns(Seq(tuneMessage2))
    (tuner.process _).when(processMessage1).returns(Seq(processMessage1, processMessage2))

    val receiver: Receiver = stub[Receiver]
    val processor: TunerProcessor = new TunerProcessor(tuner)

    if (shouldConnect) {
      processor.receiver = receiver
    }
  }

  "onConnect" should "send init message after connecting" in new Fixture(shouldConnect = false) {
    // When
    processor.receiver = receiver
    // Then
    (receiver.send _).verify(initMessage, -1).once()
  }

  it should "not send init message before connecting" in new Fixture(shouldConnect = false) {
    (receiver.send _).verify(*, *).never()
  }

  "tune" should "send the tune messages returned by the tuner" in new Fixture {
    // When
    processor.tune(majTuning)
    // Then
    (tuner.tune _).verify(majTuning).once()
    (receiver.send _).verify(tuneMessage1, -1).once()
  }

  "send" should "send the message processed by the tuner" in new Fixture {
    // Given
    val timeStamp: Long = 3L
    // When
    processor.send(processMessage1, timeStamp)
    // Then
    (tuner.process _).verify(processMessage1).once()
    (receiver.send _).verify(processMessage1, timeStamp).once()
    (receiver.send _).verify(processMessage2, timeStamp).once()
  }

  "onDisconnect" should "reset tuning to 12-EDO and the internal state of the tuner" in new Fixture {
    // When
    processor.close()
    // Then
    (tuner.tune _).verify(OctaveTuning.Edo12).once()
    (receiver.send _).verify(tuneMessage2, -1).once()

    (tuner.reset _).verify().once()
  }
}

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

import org.calinburloiu.music.scmidi.{MidiNote, ScCcMidiMessage, ScNoteOnMidiMessage}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver}

class TuningChangeProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val noteTriggerMidiMessage: MidiMessage = ScNoteOnMidiMessage(1, MidiNote.C4, 64).javaMidiMessage
  val ccTriggerMidiMessage: MidiMessage = ScCcMidiMessage(1, ScCcMidiMessage.SostenutoPedal, 32).javaMidiMessage
  val nonTriggerMidiMessage1: MidiMessage = ScCcMidiMessage(1, ScCcMidiMessage.Modulation, 96).javaMidiMessage
  val nonTriggerMidiMessage2: MidiMessage = ScNoteOnMidiMessage(1, MidiNote.B4, 16).javaMidiMessage

  abstract class Fixture(triggersThru: Boolean = false) {
    val tuningServiceStub: TuningService = stub[TuningService]("tuningService")

    val noteTuningChangerStub: TuningChanger = stub[TuningChanger]("noteTuningChanger")
    (noteTuningChangerStub.decide _).when(noteTriggerMidiMessage).returns(IndexTuningChange(2)).anyNumberOfTimes()
    (noteTuningChangerStub.decide _).when(*).returns(NoTuningChange).anyNumberOfTimes()

    val ccTuningChangerStub: TuningChanger = stub[TuningChanger]("ccTuningChanger")
    (ccTuningChangerStub.decide _).when(ccTriggerMidiMessage).returns(NextTuningChange).anyNumberOfTimes()
    (ccTuningChangerStub.decide _).when(*).returns(NoTuningChange).anyNumberOfTimes()

    val processor: TuningChangeProcessor = new TuningChangeProcessor(
      tuningServiceStub, Seq(noteTuningChangerStub, ccTuningChangerStub), triggersThru)

    val receiverStub: Receiver = stub[Receiver]
    processor.receiver = receiverStub
  }

  it should "inform the TuningService about the tuning decision taken" in new Fixture {
    // When
    processor.send(ccTriggerMidiMessage, 1)
    processor.send(noteTriggerMidiMessage, 2)
    processor.send(nonTriggerMidiMessage2, 3)
    processor.send(nonTriggerMidiMessage1, 4)

    // Then
    (tuningServiceStub.changeTuning _).verify(IndexTuningChange(2))
    (tuningServiceStub.changeTuning _).verify(NextTuningChange)
    (tuningServiceStub.changeTuning _).verify(NoTuningChange).repeated(2)
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.send(nonTriggerMidiMessage1, 1)
    // Then
    (receiverStub.send _).verify(nonTriggerMidiMessage1, 1)
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.send(nonTriggerMidiMessage1, 1)
    // Then
    (receiverStub.send _).verify(nonTriggerMidiMessage1, 1)
  }

  it should "forward MIDI messages that are tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.send(ccTriggerMidiMessage, 1)
    // Then
    (receiverStub.send _).verify(ccTriggerMidiMessage, 1)
  }

  it should "not forward MIDI messages that are tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.send(ccTriggerMidiMessage, 1)
    // Then
    (receiverStub.send _).verify(*, *).never()
  }
}

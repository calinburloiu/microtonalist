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

  val nonTriggerMidiMessage: MidiMessage = ScNoteOnMidiMessage(1, MidiNote.C4, 64).javaMidiMessage
  val triggerMidiMessage: MidiMessage = ScCcMidiMessage(1, ScCcMidiMessage.SostenutoPedal, 32).javaMidiMessage

  abstract class Fixture(triggersThru: Boolean) {
    val tuningServiceStub: TuningService = stub[TuningService]

    val tuningChangerStub: TuningChanger = stub[TuningChanger]
    (tuningChangerStub.decide _).when(triggerMidiMessage).returns(NextTuningChange)
    (tuningChangerStub.decide _).when(*).returns(NoTuningChange)

    val processor: TuningChangeProcessor = new TuningChangeProcessor(tuningServiceStub, tuningChangerStub, triggersThru)

    val receiverStub: Receiver = stub[Receiver]
    processor.receiver = receiverStub
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.send(nonTriggerMidiMessage, 1)
    // Then
    (tuningServiceStub.changeTuning _).verify(NoTuningChange)
    (receiverStub.send _).verify(nonTriggerMidiMessage, 1)
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.send(nonTriggerMidiMessage, 1)
    // Then
    (tuningServiceStub.changeTuning _).verify(NoTuningChange)
    (receiverStub.send _).verify(nonTriggerMidiMessage, 1)
  }

  it should "forward MIDI messages that are tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.send(triggerMidiMessage, 1)
    // Then
    (tuningServiceStub.changeTuning _).verify(NextTuningChange)
    (receiverStub.send _).verify(triggerMidiMessage, 1)
  }

  it should "not forward MIDI messages that are tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.send(triggerMidiMessage, 1)
    // Then
    (tuningServiceStub.changeTuning _).verify(NextTuningChange)
    (receiverStub.send _).verify(*, *).never()
  }
}

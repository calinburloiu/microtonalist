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

import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, NoteOnMidiMsg}
import org.calinburloiu.music.scmidi.{MidiNote, MidiReceiver}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningChangeProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val noteTriggerMessage: MidiMsg = NoteOnMidiMsg(1, MidiNote.C4, 64)
  val ccTriggerMessage: MidiMsg = CcMidiMsg(1, MidiCc.SostenutoPedal, 32)
  val nonTriggerMessage1: MidiMsg = CcMidiMsg(1, MidiCc.ModulationMsb, 96)
  val nonTriggerMessage2: MidiMsg = NoteOnMidiMsg(1, MidiNote.B4, 16)

  abstract class Fixture(triggersThru: Boolean = false) {
    val tuningServiceStub: TuningService = stub[TuningService]("tuningService")

    val noteTuningChangerStub: TuningChanger = stub[TuningChanger]("noteTuningChanger")
    noteTuningChangerStub.decide.when(noteTriggerMessage).returns(IndexTuningChange(2))
    noteTuningChangerStub.decide.when(noteTriggerMessage).returns(MayTriggerTuningChange)
    noteTuningChangerStub.decide.when(*).returns(NoTuningChange).anyNumberOfTimes()
    (() => noteTuningChangerStub.triggersThru).when().returns(triggersThru)

    val ccTuningChangerStub: TuningChanger = stub[TuningChanger]("ccTuningChanger")
    ccTuningChangerStub.decide.when(ccTriggerMessage).returns(NextTuningChange)
    ccTuningChangerStub.decide.when(ccTriggerMessage).returns(MayTriggerTuningChange)
    ccTuningChangerStub.decide.when(*).returns(NoTuningChange).anyNumberOfTimes()
    (() => ccTuningChangerStub.triggersThru).when().returns(triggersThru)

    val processor: TuningChangeProcessor = TuningChangeProcessor(Seq(noteTuningChangerStub, ccTuningChangerStub),
      tuningServiceStub)

    val receiverStub: MidiReceiver = stub[MidiReceiver]
    processor.transmitter.addReceiver(receiverStub)
  }

  it should "inform the TuningService about the tuning decision taken" in new Fixture {
    // When
    processor.process(ccTriggerMessage, 1)
    processor.process(noteTriggerMessage, 2)
    processor.process(nonTriggerMessage2, 3)
    processor.process(nonTriggerMessage1, 4)

    // Then
    tuningServiceStub.changeTuning.verify(IndexTuningChange(2))
    tuningServiceStub.changeTuning.verify(NextTuningChange)
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.receiver.send(nonTriggerMessage1, 1)
    // Then
    receiverStub.send.verify(nonTriggerMessage1, 1).once()
  }

  it should "forward MIDI messages that are not tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.receiver.send(nonTriggerMessage1, 1)
    // Then
    receiverStub.send.verify(nonTriggerMessage1, 1).once()
  }

  it should "forward MIDI messages that are tuning change triggers " +
    "when triggersThru is true" in new Fixture(triggersThru = true) {
    // When
    processor.receiver.send(ccTriggerMessage, 1)
    processor.receiver.send(ccTriggerMessage, 2)
    // Then
    receiverStub.send.verify(ccTriggerMessage, *).repeated(2)
  }

  it should "not forward MIDI messages that are tuning change triggers " +
    "when triggersThru is false" in new Fixture(triggersThru = false) {
    // When
    processor.receiver.send(ccTriggerMessage, 1)
    processor.receiver.send(ccTriggerMessage, 2)
    // Then
    receiverStub.send.verify(*, *).never()
  }
}

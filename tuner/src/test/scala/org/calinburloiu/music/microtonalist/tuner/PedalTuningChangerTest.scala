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
import org.calinburloiu.music.microtonalist.tuner.PedalTuningChanger.Cc
import org.calinburloiu.music.scmidi.{MidiNote, ScCcMidiMessage, ScNoteOnMidiMessage}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.ShortMessage

class PedalTuningChangerTest extends AnyFlatSpec with Matchers {

  "constructor" should "instantiate the class with default parameters" in {
    val defaultPedalTuningChanger: PedalTuningChanger = PedalTuningChanger()
    defaultPedalTuningChanger shouldBe a[TuningChanger]
    defaultPedalTuningChanger.previousTuningCcTrigger should contain(67)
    defaultPedalTuningChanger.nextTuningCcTrigger should contain(66)
    defaultPedalTuningChanger.threshold shouldBe 0
    defaultPedalTuningChanger.triggersThru shouldBe false
    defaultPedalTuningChanger.familyName shouldEqual TuningChanger.FamilyName
    defaultPedalTuningChanger.typeName shouldEqual "pedal"
  }

  val customPreviousTuningCcTrigger: Cc = 60
  val customNextTuningCcTrigger: Cc = 61
  val customIndex1TuningCcTrigger: Cc = 10
  val customIndex2TuningCcTrigger: Cc = 20
  val customThreshold = 16
  private val customTuningChangeTriggers: TuningChangeTriggers[Cc] = TuningChangeTriggers(
    previous = Some(customPreviousTuningCcTrigger),
    next = Some(customNextTuningCcTrigger),
    index = Map(1 -> customIndex1TuningCcTrigger, 2 -> customIndex2TuningCcTrigger)
  )
  val tuningChanger: PedalTuningChanger = PedalTuningChanger(customTuningChangeTriggers, customThreshold,
    triggersThru = false)

  val testCases: Seq[(TuningChange, Cc)] = Seq(
    (PreviousTuningChange, customPreviousTuningCcTrigger),
    (NextTuningChange, customNextTuningCcTrigger),
    (IndexTuningChange(1), customIndex1TuningCcTrigger),
    (IndexTuningChange(2), customIndex2TuningCcTrigger)
  )
  for ((tuningChange, cc) <- testCases) {
    def createCcMessage(value: Int): ShortMessage = ScCcMidiMessage(1, cc, value).javaMidiMessage

    "decide" should s"not trigger a $tuningChange if CC value is below or equal to the threshold" in {
      tuningChanger.decide(createCcMessage(0)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold - 1)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold)) shouldEqual MayTriggerTuningChange

    }

    it should s"trigger a single $tuningChange tuning change when CC value increases above the threshold" in {
      tuningChanger.decide(createCcMessage(customThreshold - 1)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold + 1)) shouldEqual tuningChange
      tuningChanger.decide(createCcMessage(customThreshold + 2)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(127)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold + 1)) shouldEqual MayTriggerTuningChange
    }

    it should s"trigger a double $tuningChange tuning change when CC value increases above the threshold twice" in {
      tuningChanger.decide(createCcMessage(customThreshold - 1)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold + 1)) shouldEqual tuningChange
      tuningChanger.decide(createCcMessage(customThreshold)) shouldEqual MayTriggerTuningChange
      tuningChanger.decide(createCcMessage(customThreshold + 2)) shouldEqual tuningChange
    }

    it should s"correctly trigger $tuningChange tuning changes when threshold is 0" in {
      val pedalTuningChangerWith0Threshold = PedalTuningChanger(customTuningChangeTriggers, 0, triggersThru = false)

      pedalTuningChangerWith0Threshold.decide(createCcMessage(0)) shouldEqual MayTriggerTuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(1)) shouldEqual tuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(0)) shouldEqual MayTriggerTuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(2)) shouldEqual tuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(127)) shouldEqual MayTriggerTuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(3)) shouldEqual MayTriggerTuningChange
      pedalTuningChangerWith0Threshold.decide(createCcMessage(0)) shouldEqual MayTriggerTuningChange
    }
  }

  "decide" should "return NoTuningChange for a Note On MIDI message" in {
    val noteOnMessage = ScNoteOnMidiMessage(1, MidiNote.C4, 64).javaMidiMessage
    tuningChanger.decide(noteOnMessage) shouldEqual NoTuningChange
  }

  it should "return NoTuningChange for a SysEx MIDI message" in {
    val sysExMessage = MtsMessageGenerator.Octave1ByteNonRealTime.generate(OctaveTuning.Edo12)
    tuningChanger.decide(sysExMessage) shouldEqual NoTuningChange
  }

  def createCcMessageForNext(value: Int): ShortMessage =
    ScCcMidiMessage(1, customNextTuningCcTrigger, value).javaMidiMessage

  "isPressed" should "tell if the pedal for a CC trigger is pressed" in {
    tuningChanger.decide(createCcMessageForNext(customThreshold - 1))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual false

    tuningChanger.decide(createCcMessageForNext(customThreshold + 1))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual true
    tuningChanger.decide(createCcMessageForNext(127))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual true

    tuningChanger.decide(createCcMessageForNext(customThreshold))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual false

    tuningChanger.decide(createCcMessageForNext(customThreshold + 2))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual true
  }

  "reset" should "reset the tuning change triggers" in {
    tuningChanger.decide(createCcMessageForNext(customThreshold - 1))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual false

    tuningChanger.decide(createCcMessageForNext(customThreshold + 1))
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual true
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual true

    tuningChanger.reset()
    tuningChanger.isPressed(customNextTuningCcTrigger) shouldEqual false
  }
}

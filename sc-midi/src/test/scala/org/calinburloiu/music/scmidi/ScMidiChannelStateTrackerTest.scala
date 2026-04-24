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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.MidiNote.{C4, E4, G4}
import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScMidiChannelStateTrackerTest extends AnyFlatSpec with Matchers {

  private val Channel = 3
  private val OtherChannel = 7

  private val NrpnA = (10, 20)
  private val NrpnB = (10, 21)

  private trait TrackerFixture {
    val tracker: ScMidiChannelStateTracker = ScMidiChannelStateTracker()
  }

  behavior of "ScMidiChannelStateTracker per note tracking"

  it should "have no active notes on any channel when empty" in new TrackerFixture {
    // When / Then
    for (channel <- 0 to 15) {
      tracker.activeNotes(channel) shouldBe empty
    }
  }

  it should "record a Note On as an active note with its velocity" in new TrackerFixture {
    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.isNoteActive(Channel, C4) shouldBe true
    tracker.velocityOption(Channel, C4) should equal(Some(100))
    tracker.velocity(Channel, C4) should equal(100)
  }

  it should "remove a note from the active set on Note Off" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // When
    tracker.send(NoteOffScMidiMessage(Channel, C4))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.isNoteActive(Channel, C4) shouldBe false
    tracker.velocityOption(Channel, C4) shouldBe None
    tracker.velocity(Channel, C4) should equal(0)
  }

  it should "treat a Note On with velocity 0 as a Note Off" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = NoteOnScMidiMessage.NoteOffVelocity))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.isNoteActive(Channel, C4) shouldBe false
  }

  it should "preserve insertion order of active notes" in new TrackerFixture {
    // When
    tracker.send(NoteOnScMidiMessage(Channel, G4, velocity = 80))
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 90))
    tracker.send(NoteOnScMidiMessage(Channel, E4, velocity = 70))

    // Then
    tracker.orderedActiveNotes(Channel) should contain theSameElementsInOrderAs Seq(G4, C4, E4)
  }

  it should "track active notes independently per channel" in new TrackerFixture {
    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 110))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.activeNotes(OtherChannel) should contain only E4
    tracker.velocityOption(Channel, E4) shouldBe None
    tracker.velocityOption(OtherChannel, C4) shouldBe None
  }

  it should "default Polyphonic Key Pressure to 0 for an active note that has not received one" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // When / Then
    tracker.polyPressureOption(Channel, C4) should equal(Some(0))
    tracker.polyPressure(Channel, C4) should equal(0)
  }

  it should "update Polyphonic Key Pressure for an active note" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))

    // When
    tracker.send(PolyPressureScMidiMessage(Channel, C4, value = 90))

    // Then
    tracker.polyPressureOption(Channel, C4) should equal(Some(90))
    tracker.polyPressure(Channel, C4) should equal(90)
  }

  it should "ignore Polyphonic Key Pressure for an inactive note" in new TrackerFixture {
    // When
    tracker.send(PolyPressureScMidiMessage(Channel, C4, value = 90))

    // Then
    tracker.polyPressureOption(Channel, C4) shouldBe None
    tracker.polyPressure(Channel, C4) should equal(0)
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "reset Polyphonic Key Pressure to its default when a note is re-triggered with Note On" in new
      TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(PolyPressureScMidiMessage(Channel, C4, value = 90))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 110))

    // Then
    tracker.polyPressureOption(Channel, C4) should equal(Some(0))
  }

  it should "overwrite the velocity of an active note when a Note On is re-sent" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 50))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 120))

    // Then
    tracker.velocityOption(Channel, C4) should equal(Some(120))
  }

  behavior of "ScMidiChannelStateTracker Control Change tracking"

  it should "return None for ccOption when the CC has not been set" in new TrackerFixture {
    // When / Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) shouldBe None
  }

  it should "record the value of a Control Change message" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, number = ScMidiCc.ModulationMsb, value = 42))

    // Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) should equal(Some(42))
    tracker.cc(Channel, ScMidiCc.ModulationMsb) should equal(42)
  }

  it should "track CC values independently per channel" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, number = ScMidiCc.VolumeMsb, value = 80))
    tracker.send(CcScMidiMessage(OtherChannel, number = ScMidiCc.VolumeMsb, value = 50))

    // Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb) should equal(80)
    tracker.cc(OtherChannel, ScMidiCc.VolumeMsb) should equal(50)
  }

  it should "fall back to the companion's DefaultCcValues for known CCs when nothing is recorded" in new
      TrackerFixture {
    // When / Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb) should equal(100)
    tracker.cc(Channel, ScMidiCc.PanMsb) should equal(64)
    tracker.cc(Channel, ScMidiCc.ExpressionMsb) should equal(127)
    tracker.cc(Channel, ScMidiCc.SustainPedal) should equal(0)
  }

  it should "throw NoSuchElementException for an unknown CC with no value, override, or default" in new TrackerFixture {
    // Given
    val unknownCc = 50

    // When / Then
    a[NoSuchElementException] should be thrownBy tracker.cc(Channel, unknownCc)
  }

  it should "use overrideDefaultValue when set and no value was recorded" in new TrackerFixture {
    // Given
    val unknownCc = 50

    // When / Then
    tracker.cc(Channel, unknownCc, overrideDefaultValue = Some(33)) should equal(33)
    // override also wins over the companion default
    tracker.cc(Channel, ScMidiCc.VolumeMsb, overrideDefaultValue = Some(7)) should equal(7)
  }

  it should "prefer the recorded value over override and defaults" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.VolumeMsb, value = 12))

    // When / Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb, overrideDefaultValue = Some(99)) should equal(12)
  }

  it should "honor a constructor-supplied ccDefault for an unknown CC" in {
    // Given
    val unknownCc = 50
    val tracker = ScMidiChannelStateTracker(ccDefaults = Map(unknownCc -> 21))

    // When / Then
    tracker.cc(Channel, unknownCc) should equal(21)
  }

  it should "let constructor-supplied ccDefaults override the companion's defaults" in {
    // Given
    val tracker = ScMidiChannelStateTracker(ccDefaults = Map(ScMidiCc.VolumeMsb -> 5))

    // When / Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb) should equal(5)
  }

  behavior of "ScMidiChannelStateTracker Channel Pressure / Pitch Bend / Program Change tracking"

  it should "default Channel Pressure / Pitch Bend / Program Change to 0" in new TrackerFixture {
    // When / Then
    tracker.channelPressure(Channel) should equal(0)
    tracker.pitchBend(Channel) should equal(0)
    tracker.programChange(Channel) should equal(0)
  }

  it should "record the latest Channel Pressure value" in new TrackerFixture {
    // When
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 95))

    // Then
    tracker.channelPressure(Channel) should equal(95)
  }

  it should "record the latest Pitch Bend value (signed)" in new TrackerFixture {
    // When
    tracker.send(PitchBendScMidiMessage(Channel, value = -2048))

    // Then
    tracker.pitchBend(Channel) should equal(-2048)
  }

  it should "record the latest Program Change value" in new TrackerFixture {
    // When
    tracker.send(ProgramChangeScMidiMessage(Channel, program = 42))

    // Then
    tracker.programChange(Channel) should equal(42)
  }

  it should "track Channel Pressure / Pitch Bend / Program Change independently per channel" in new TrackerFixture {
    // When
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(PitchBendScMidiMessage(OtherChannel, value = 1024))
    tracker.send(ProgramChangeScMidiMessage(Channel, program = 5))

    // Then
    tracker.channelPressure(Channel) should equal(80)
    tracker.channelPressure(OtherChannel) should equal(0)
    tracker.pitchBend(OtherChannel) should equal(1024)
    tracker.pitchBend(Channel) should equal(0)
    tracker.programChange(Channel) should equal(5)
    tracker.programChange(OtherChannel) should equal(0)
  }

  behavior of "ScMidiChannelStateTracker bankSelect"

  it should "default Bank Select to (0, 0) when nothing is recorded" in new TrackerFixture {
    // When / Then
    tracker.bankSelect(Channel) should equal((0, 0))
  }

  it should "reflect Bank Select MSB and LSB recorded via CC messages" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.BankSelectMsb, value = 3))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.BankSelectLsb, value = 7))

    // Then
    tracker.bankSelect(Channel) should equal((3, 7))
  }

  it should "honour constructor-supplied Bank Select defaults" in {
    // Given
    val tracker = ScMidiChannelStateTracker(
      ccDefaults = Map(ScMidiCc.BankSelectMsb -> 1, ScMidiCc.BankSelectLsb -> 2)
    )

    // When / Then
    tracker.bankSelect(Channel) should equal((1, 2))
  }

  behavior of "ScMidiChannelStateTracker RPN tracking"

  private def selectRpn(tracker: ScMidiChannelStateTracker, channel: Int, msb: Int, lsb: Int): Unit = {
    tracker.send(CcScMidiMessage(channel, ScMidiCc.RpnMsb, msb))
    tracker.send(CcScMidiMessage(channel, ScMidiCc.RpnLsb, lsb))
  }

  it should "return None for an RPN that has not been written" in new TrackerFixture {
    // When / Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
  }

  it should "throw NoSuchElementException for rpn() when no value, override, or default is available" in
    new TrackerFixture {
      // When / Then
      a[NoSuchElementException] should be thrownBy tracker.rpn(Channel, 5, 9)
    }

  it should "return the companion default for rpn() when no value has been recorded" in new TrackerFixture {
    // When / Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should equal((2, 0))
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal((64, 0))
  }

  it should "use overrideDefaultValue for rpn() when no value has been recorded" in new TrackerFixture {
    // When / Then
    tracker.rpn(Channel, 5, 9, overrideDefaultValue = Some((10, 0))) should equal((10, 0))
    // recorded value wins over override
    selectRpn(tracker, Channel, 5, 9)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 3))
    tracker.rpn(Channel, 5, 9, overrideDefaultValue = Some((10, 0))) should equal((3, 0))
  }

  it should "initially have no RPN/NRPN selected" in new TrackerFixture {
    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.NullMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.NullLsb
  }

  it should "select a RPN" in new TrackerFixture {
    // When
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.FineTuningMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.FineTuningLsb
  }

  it should "select a RPN when LSB is sent before MSB (reversed order)" in new TrackerFixture {
    // When — LSB first, then MSB
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnLsb, ScMidiRpn.FineTuningLsb))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnMsb, ScMidiRpn.FineTuningMsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
  }

  it should "record an RPN value via Data Entry MSB after RPN selection" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 24))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  it should "update the LSB component of an RPN value via Data Entry LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 50)))
  }

  it should "seed the unrecorded RPN value from the resolved default when only Data Entry LSB is sent" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

      // Then
      tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 50)))
    }

  it should "keep recorded RPN values when a different RPN is selected" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))

    // When
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 0)))
  }

  it should "ignore data changes when an initial RPN has only MSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.NullLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.NullLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual 0
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.NullLsb
  }

  it should "ignore data changes when an initial RPN has only LSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnOption(Channel, ScMidiRpn.NullMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.NullMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual 0
  }

  it should "ignore data changes when no RPN/NRPN is selected" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, 0, 0) shouldBe None
  }

  it should "ignore Data Entry after Null RPN is selected" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 8))
    selectRpn(tracker, Channel, ScMidiRpn.NullMsb, ScMidiRpn.NullLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((8, 0)))
    tracker.rpnOption(Channel, ScMidiRpn.NullMsb, ScMidiRpn.NullLsb) shouldBe None
  }

  it should "track RPN values independently per channel" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))
    selectRpn(tracker, OtherChannel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.DataEntryMsb, value = 24))

    // When / Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpnOption(OtherChannel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  it should "increment the recorded RPN value by 1 (combined 14-bit)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 51)))
  }

  it should "decrement the recorded RPN value by 1 (combined 14-bit)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 49)))
  }

  it should "carry across LSB and MSB when incrementing past 127 LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((1, 0)))
  }

  it should "carry across MSB and LSB when decrementing past 0 LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 1))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((0, 127)))
  }

  it should "clamp at 0 when decrementing below 0" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((0, 0)))
  }

  it should "clamp at 16383 when incrementing past the 14-bit maximum" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((127, 127)))
  }

  it should "ignore the data byte of Data Increment / Decrement (always ±1)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 100))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 5))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 52)))
  }

  it should "seed the value from the companion's DefaultRpnValues when nothing is recorded for Data Increment" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

      // Then — default is (2, 0); +1 → (2, 1)
      tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
        equal(Some((2, 1)))
    }

  it should "seed the value from the companion's DefaultRpnValues when nothing is recorded for Data Decrement" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

      // Then — default is (2, 0); -1 → (1, 127)
      tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
        equal(Some((1, 127)))
    }

  it should "prefer the constructor's rpnDefaults over the companion's" in {
    // Given
    val tracker = ScMidiChannelStateTracker(
      rpnDefaults = Map(
        (ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) -> (12, 0)
      )
    )
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 1)))
  }

  it should "ignore Data Increment when no RPN/NRPN is selected" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, 0, 0) shouldBe None
    tracker.nrpnOption(Channel, 0, 0) shouldBe None
  }

  it should "ignore Data Increment for an unknown RPN with no recorded value and no default" in new TrackerFixture {
    // Given
    val unknownRpnMsb = 5
    val unknownRpnLsb = 9
    selectRpn(tracker, Channel, unknownRpnMsb, unknownRpnLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, unknownRpnMsb, unknownRpnLsb) shouldBe None
  }

  it should "ignore Data Decrement for an unknown RPN with no recorded value and no default" in new TrackerFixture {
    // Given
    val unknownRpnMsb = 5
    val unknownRpnLsb = 9
    selectRpn(tracker, Channel, unknownRpnMsb, unknownRpnLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, unknownRpnMsb, unknownRpnLsb) shouldBe None
  }

  it should "still record Data Increment / Decrement values even when their effect is ignored" in new TrackerFixture {
    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 1))

    // Then
    tracker.ccOption(Channel, ScMidiCc.DataIncrement) should equal(Some(0))
    tracker.ccOption(Channel, ScMidiCc.DataDecrement) should equal(Some(1))
  }

  it should "let RPN selection take over a previous NRPN selection" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 5))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((5, 0)))
  }

  behavior of "ScMidiChannelStateTracker NRPN tracking"

  private def selectNrpn(tracker: ScMidiChannelStateTracker, channel: Int, msb: Int, lsb: Int): Unit = {
    tracker.send(CcScMidiMessage(channel, ScMidiCc.NrpnMsb, msb))
    tracker.send(CcScMidiMessage(channel, ScMidiCc.NrpnLsb, lsb))
  }

  it should "return None for an NRPN that has not been written" in new TrackerFixture {
    // When / Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "throw NoSuchElementException for nrpn() when no value, override, or default is available" in
    new TrackerFixture {
      // When / Then
      a[NoSuchElementException] should be thrownBy tracker.nrpn(Channel, NrpnA._1, NrpnA._2)
    }

  it should "use overrideDefaultValue for nrpn() when no value has been recorded" in new TrackerFixture {
    // When / Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2, overrideDefaultValue = Some((7, 0))) should equal((7, 0))
  }

  it should "select a NRPN" in new TrackerFixture {
    // When
    selectNrpn(tracker, Channel, 1, 2)

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(1, 2)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual 1
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual 2
  }

  it should "select a NRPN when LSB is sent before MSB (reversed order)" in new TrackerFixture {
    // When — LSB first, then MSB
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnLsb, NrpnA._2))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnMsb, NrpnA._1))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(NrpnA._1, NrpnA._2)
  }

  it should "record an NRPN value via Data Entry MSB after NRPN selection" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 17))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((17, 0)))
  }

  it should "let NRPN selection take over a previous RPN selection" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 5))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((5, 0)))
  }

  it should "keep recorded NRPN values when a different NRPN is selected" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 1))

    // When
    selectNrpn(tracker, Channel, NrpnB._1, NrpnB._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 2))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((1, 0)))
    tracker.nrpnOption(Channel, NrpnB._1, NrpnB._2) should equal(Some((2, 0)))
  }

  it should "ignore Data Entry after Null NRPN is selected" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 8))
    selectNrpn(tracker, Channel, ScMidiNrpn.NullMsb, ScMidiNrpn.NullLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((8, 0)))
    tracker.nrpnOption(Channel, ScMidiNrpn.NullMsb, ScMidiNrpn.NullLsb) shouldBe None
  }

  it should "ignore data changes when an initial NRPN has only MSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnMsb, 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.nrpnOption(Channel, 0, 0) shouldBe empty
    tracker.nrpnOption(Channel, 0, ScMidiNrpn.NullLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(0, ScMidiNrpn.NullLsb)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual 0
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual ScMidiNrpn.NullLsb
  }

  it should "ignore data changes when an initial NRPN has only LSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnLsb, 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.nrpnOption(Channel, 0, 0) shouldBe empty
    tracker.nrpnOption(Channel, ScMidiNrpn.NullMsb, 0) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(ScMidiNrpn.NullMsb, 0)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual ScMidiNrpn.NullMsb
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual 0
  }

  it should "increment a recorded NRPN value with constructor-supplied default" in {
    // Given
    val tracker = ScMidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 6)))
  }

  it should "decrement a recorded NRPN value with constructor-supplied default" in {
    // Given
    val tracker = ScMidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 4)))
  }

  it should "apply multiple Increments and a Decrement to an NRPN value" in {
    // Given
    val tracker = ScMidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then — (10, 5) + 1 + 1 - 1 = (10, 6)
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 6)))
  }

  it should "ignore Data Increment for NRPN with neither recorded value nor default" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "ignore Data Decrement for NRPN with neither recorded value nor default" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  behavior of "ScMidiChannelStateTracker close"

  it should "report isClosed as false initially" in new TrackerFixture {
    // When / Then
    tracker.isClosed shouldBe false
  }

  it should "report isClosed as true after close" in new TrackerFixture {
    // When
    tracker.close()

    // Then
    tracker.isClosed shouldBe true
  }

  it should "make send a no-op after close" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.close()

    // When
    tracker.send(NoteOnScMidiMessage(Channel, E4, velocity = 80))
    tracker.send(NoteOffScMidiMessage(Channel, C4))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.velocityOption(Channel, C4) should equal(Some(100))
  }

  it should "preserve queried state after close" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.VolumeMsb, value = 90))
    tracker.send(PitchBendScMidiMessage(Channel, value = 1234))

    // When
    tracker.close()

    // Then
    tracker.velocityOption(Channel, C4) should equal(Some(100))
    tracker.ccOption(Channel, ScMidiCc.VolumeMsb) should equal(Some(90))
    tracker.pitchBend(Channel) should equal(1234)
  }

  it should "be idempotent for repeated close calls" in new TrackerFixture {
    // When
    tracker.close()
    tracker.close()

    // Then
    tracker.isClosed shouldBe true
  }

  behavior of "ScMidiChannelStateTracker channel validation"

  it should "throw on activeNotes with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.activeNotes(-1)
    an[IllegalArgumentException] should be thrownBy tracker.activeNotes(16)
  }

  it should "throw on orderedActiveNotes with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.orderedActiveNotes(-1)
    an[IllegalArgumentException] should be thrownBy tracker.orderedActiveNotes(16)
  }

  it should "throw on isNoteActive with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.isNoteActive(-1, C4)
    an[IllegalArgumentException] should be thrownBy tracker.isNoteActive(16, C4)
  }

  it should "throw on velocity / velocityOption / polyPressure / polyPressureOption with an invalid channel" in
    new TrackerFixture {
      // When / Then
      an[IllegalArgumentException] should be thrownBy tracker.velocity(-1, C4)
      an[IllegalArgumentException] should be thrownBy tracker.velocityOption(-1, C4)
      an[IllegalArgumentException] should be thrownBy tracker.polyPressure(16, C4)
      an[IllegalArgumentException] should be thrownBy tracker.polyPressureOption(16, C4)
    }

  it should "throw on ccOption / cc with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.ccOption(-1, ScMidiCc.VolumeMsb)
    an[IllegalArgumentException] should be thrownBy tracker.cc(16, ScMidiCc.VolumeMsb)
  }

  it should "throw on channelPressure / pitchBend / programChange with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.channelPressure(-1)
    an[IllegalArgumentException] should be thrownBy tracker.pitchBend(16)
    an[IllegalArgumentException] should be thrownBy tracker.programChange(-1)
  }

  it should "throw on bankSelect with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.bankSelect(-1)
    an[IllegalArgumentException] should be thrownBy tracker.bankSelect(16)
  }

  it should "throw on rpnOption / nrpnOption with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.rpnOption(-1, 0, 0)
    an[IllegalArgumentException] should be thrownBy tracker.nrpnOption(16, 0, 0)
  }

  behavior of "ScMidiChannelStateTracker Channel Mode messages"

  it should "cancel active notes on the channel when All Sound Off is received" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, E4, velocity = 110))
    tracker.send(NoteOnScMidiMessage(OtherChannel, G4, velocity = 90))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllSoundOff, value = 0))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.activeNotes(OtherChannel) should contain only G4
  }

  it should "cancel active notes on the channel when All Notes Off is received" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, G4, velocity = 90))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllNotesOff, value = 0))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.activeNotes(OtherChannel) should contain only G4
  }

  it should "clear resettable CCs when Reset All Controllers is received" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ModulationMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ExpressionMsb, value = 50))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.SustainPedal, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.PortamentoPedal, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.SostenutoPedal, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.SoftPedal, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.LegatoFootswitch, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.Hold2Pedal, value = 127))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

    // Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.ExpressionMsb) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.SustainPedal) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.PortamentoPedal) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.SostenutoPedal) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.SoftPedal) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.LegatoFootswitch) shouldBe None
    tracker.ccOption(Channel, ScMidiCc.Hold2Pedal) shouldBe None
  }

  it should "clear Data Entry, Data Increment, and Data Decrement CCs when Reset All Controllers is received" in
    new TrackerFixture {
      // Given
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 34))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

      // Then
      tracker.ccOption(Channel, ScMidiCc.DataEntryMsb) shouldBe None
      tracker.ccOption(Channel, ScMidiCc.DataEntryLsb) shouldBe None
      tracker.ccOption(Channel, ScMidiCc.DataIncrement) shouldBe None
      tracker.ccOption(Channel, ScMidiCc.DataDecrement) shouldBe None
    }

  it should "clear Channel Pressure, Pitch Bend, and the RPN/NRPN selector when Reset All Controllers is received" in
    new TrackerFixture {
      // Given
      tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
      tracker.send(PitchBendScMidiMessage(Channel, value = 1234))
      selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

      // Then
      tracker.channelPressure(Channel) should equal(0)
      tracker.pitchBend(Channel) should equal(0)
      tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
      tracker.ccOption(Channel, ScMidiCc.RpnMsb) shouldBe None
      tracker.ccOption(Channel, ScMidiCc.RpnLsb) shouldBe None
    }

  it should "clear the RPN/NRPN selector so subsequent Data Entry is ignored after Reset All Controllers" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

      // Then — the selector was cleared, so the new Data Entry must not affect the previous RPN
      tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
        equal(Some((12, 0)))
    }

  it should "preserve Bank Select, Volume, Pan, Program Change, and RPN/NRPN values on Reset All Controllers" in
    new TrackerFixture {
      // Given
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.BankSelectMsb, value = 3))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.VolumeMsb, value = 90))
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.PanMsb, value = 32))
      tracker.send(ProgramChangeScMidiMessage(Channel, program = 7))
      selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))

      // When
      tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

      // Then
      tracker.ccOption(Channel, ScMidiCc.BankSelectMsb) should equal(Some(3))
      tracker.ccOption(Channel, ScMidiCc.VolumeMsb) should equal(Some(90))
      tracker.ccOption(Channel, ScMidiCc.PanMsb) should equal(Some(32))
      tracker.programChange(Channel) should equal(7)
      tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
        equal(Some((12, 0)))
    }

  it should "scope Reset All Controllers to the channel it was received on" in new TrackerFixture {
    // Given
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ModulationMsb, value = 64))
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.ModulationMsb, value = 90))
    tracker.send(ChannelPressureScMidiMessage(OtherChannel, value = 70))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

    // Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) shouldBe None
    tracker.ccOption(OtherChannel, ScMidiCc.ModulationMsb) should equal(Some(90))
    tracker.channelPressure(OtherChannel) should equal(70)
  }

  behavior of "ScMidiChannelStateTracker reset"

  it should "clear all per-channel state across all channels" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, E4, velocity = 110))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.VolumeMsb, value = 90))
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.BankSelectMsb, value = 3))
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(PitchBendScMidiMessage(OtherChannel, value = 1234))
    tracker.send(ProgramChangeScMidiMessage(Channel, program = 7))
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))
    selectNrpn(tracker, OtherChannel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.DataEntryMsb, value = 5))

    // When
    tracker.reset()

    // Then
    for (channel <- 0 to 15) {
      tracker.activeNotes(channel) shouldBe empty
      tracker.ccOption(channel, ScMidiCc.VolumeMsb) shouldBe None
      tracker.ccOption(channel, ScMidiCc.BankSelectMsb) shouldBe None
      tracker.channelPressure(channel) should equal(0)
      tracker.pitchBend(channel) should equal(0)
      tracker.programChange(channel) should equal(0)
      tracker.rpnSelector(channel) shouldEqual RpnSelector.None
    }
    tracker.rpnOption(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpnOption(OtherChannel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "clear the RPN/NRPN selector so subsequent Data Entry is ignored after reset" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

    // When
    tracker.reset()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpnOption(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) shouldBe None
  }

  it should "leave isClosed unchanged when called on an open tracker" in new TrackerFixture {
    // When
    tracker.reset()

    // Then
    tracker.isClosed shouldBe false
  }

  it should "be a no-op after close() has been called" in new TrackerFixture {
    // Given
    tracker.send(NoteOnScMidiMessage(Channel, C4, velocity = 100))
    tracker.close()

    // When
    tracker.reset()

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.isClosed shouldBe true
  }
}

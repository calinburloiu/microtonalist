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

import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.Selector
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScMidiChannelStateTrackerTest extends AnyFlatSpec with Matchers {

  private val Channel = 3
  private val OtherChannel = 7
  private val NoteC4: MidiNote = MidiNote.C4
  private val NoteE4: MidiNote = 64
  private val NoteG4: MidiNote = 67

  behavior of "ScMidiChannelStateTracker per note tracking"

  it should "have no active notes on any channel when empty" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    for (channel <- 0 to 15) {
      tracker.activeNoteSet(channel) shouldBe empty
    }
  }

  it should "record a Note On as an active note with its velocity" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))

    // Then
    tracker.activeNoteSet(Channel) should contain only NoteC4
    tracker.velocity(Channel, NoteC4) should equal(Some(100))
  }

  it should "remove a note from the active set on Note Off" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))

    // When
    tracker.send(NoteOffScMidiMessage(Channel, NoteC4))

    // Then
    tracker.activeNoteSet(Channel) shouldBe empty
    tracker.velocity(Channel, NoteC4) shouldBe None
  }

  it should "treat a Note On with velocity 0 as a Note Off" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = NoteOnScMidiMessage.NoteOffVelocity))

    // Then
    tracker.activeNoteSet(Channel) shouldBe empty
  }

  it should "preserve insertion order of active notes" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteG4, velocity = 80))
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 90))
    tracker.send(NoteOnScMidiMessage(Channel, NoteE4, velocity = 70))

    // Then
    tracker.activeNoteSeq(Channel) should contain theSameElementsInOrderAs Seq(NoteG4, NoteC4, NoteE4)
  }

  it should "track active notes independently per channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, NoteE4, velocity = 110))

    // Then
    tracker.activeNoteSet(Channel) should contain only NoteC4
    tracker.activeNoteSet(OtherChannel) should contain only NoteE4
    tracker.velocity(Channel, NoteE4) shouldBe None
    tracker.velocity(OtherChannel, NoteC4) shouldBe None
  }

  it should "default Polyphonic Key Pressure to 0 for an active note that has not received one" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))

    // When / Then
    tracker.polyPressure(Channel, NoteC4) should equal(Some(0))
  }

  it should "update Polyphonic Key Pressure for an active note" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))

    // When
    tracker.send(PolyPressureScMidiMessage(Channel, NoteC4, value = 90))

    // Then
    tracker.polyPressure(Channel, NoteC4) should equal(Some(90))
  }

  it should "ignore Polyphonic Key Pressure for an inactive note" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(PolyPressureScMidiMessage(Channel, NoteC4, value = 90))

    // Then
    tracker.polyPressure(Channel, NoteC4) shouldBe None
    tracker.activeNoteSet(Channel) shouldBe empty
  }

  it should "reset Polyphonic Key Pressure to its default when a note is re-triggered with Note On" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(PolyPressureScMidiMessage(Channel, NoteC4, value = 90))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 110))

    // Then
    tracker.polyPressure(Channel, NoteC4) should equal(Some(0))
  }

  behavior of "ScMidiChannelStateTracker Control Change tracking"

  it should "return None for ccOption when the CC has not been set" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) shouldBe None
  }

  it should "record the value of a Control Change message" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, number = ScMidiCc.ModulationMsb, value = 42))

    // Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) should equal(Some(42))
    tracker.cc(Channel, ScMidiCc.ModulationMsb) should equal(42)
  }

  it should "track CC values independently per channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, number = ScMidiCc.VolumeMsb, value = 80))
    tracker.send(CcScMidiMessage(OtherChannel, number = ScMidiCc.VolumeMsb, value = 50))

    // Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb) should equal(80)
    tracker.cc(OtherChannel, ScMidiCc.VolumeMsb) should equal(50)
  }

  it should "fall back to the companion's DefaultCcValues for known CCs when nothing is recorded" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.cc(Channel, ScMidiCc.VolumeMsb) should equal(100)
    tracker.cc(Channel, ScMidiCc.PanMsb) should equal(64)
    tracker.cc(Channel, ScMidiCc.ExpressionMsb) should equal(127)
    tracker.cc(Channel, ScMidiCc.SustainPedal) should equal(0)
  }

  it should "throw NoSuchElementException for an unknown CC with no value, override, or default" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    val unknownCc = 50

    // When / Then
    a[NoSuchElementException] should be thrownBy tracker.cc(Channel, unknownCc)
  }

  it should "use overrideDefaultValue when set and no value was recorded" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    val unknownCc = 50

    // When / Then
    tracker.cc(Channel, unknownCc, overrideDefaultValue = Some(33)) should equal(33)
    // override also wins over the companion default
    tracker.cc(Channel, ScMidiCc.VolumeMsb, overrideDefaultValue = Some(7)) should equal(7)
  }

  it should "prefer the recorded value over override and defaults" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
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

  it should "default Channel Pressure / Pitch Bend / Program Change to None" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.channelPressure(Channel) shouldBe None
    tracker.pitchBend(Channel) shouldBe None
    tracker.programChange(Channel) shouldBe None
  }

  it should "record the latest Channel Pressure value" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 95))

    // Then
    tracker.channelPressure(Channel) should equal(Some(95))
  }

  it should "record the latest Pitch Bend value (signed)" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(PitchBendScMidiMessage(Channel, value = -2048))

    // Then
    tracker.pitchBend(Channel) should equal(Some(-2048))
  }

  it should "record the latest Program Change value" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(ProgramChangeScMidiMessage(Channel, program = 42))

    // Then
    tracker.programChange(Channel) should equal(Some(42))
  }

  it should "track Channel Pressure / Pitch Bend / Program Change independently per channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(PitchBendScMidiMessage(OtherChannel, value = 1024))
    tracker.send(ProgramChangeScMidiMessage(Channel, program = 5))

    // Then
    tracker.channelPressure(Channel) should equal(Some(80))
    tracker.channelPressure(OtherChannel) shouldBe None
    tracker.pitchBend(OtherChannel) should equal(Some(1024))
    tracker.pitchBend(Channel) shouldBe None
    tracker.programChange(Channel) should equal(Some(5))
    tracker.programChange(OtherChannel) shouldBe None
  }

  behavior of "ScMidiChannelStateTracker bankSelect"

  it should "default Bank Select to (0, 0) when nothing is recorded" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.bankSelect(Channel) should equal((0, 0))
  }

  it should "reflect Bank Select MSB and LSB recorded via CC messages" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

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

  it should "return None for an RPN that has not been written" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
  }

  it should "initially have no RPN/NRPN selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // Then
    tracker.selector(Channel) shouldEqual Selector.None
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.NullMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.NullLsb
  }

  it should "select a RPN" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

    // Then
    tracker.selector(Channel) shouldEqual Selector.Rpn(ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.FineTuningMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.FineTuningLsb
  }

  it should "record an RPN value via Data Entry MSB after RPN selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 24))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  it should "update the LSB component of an RPN value via Data Entry LSB" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 50)))
  }

  it should "seed the unrecorded RPN value from the resolved default when only Data Entry LSB is sent" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 50)))
  }

  it should "keep recorded RPN values when a different RPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))

    // When
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 0)))
  }

  it should "ignore data changes when an initial RPN has only MSB selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.selector(Channel) shouldEqual Selector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.NullLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual 0
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual ScMidiRpn.NullLsb
  }

  it should "ignore data changes when an initial RPN has only LSB selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.selector(Channel) shouldEqual Selector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.cc(Channel, ScMidiCc.RpnMsb) shouldEqual ScMidiRpn.NullMsb
    tracker.cc(Channel, ScMidiCc.RpnLsb) shouldEqual 0
  }

  it should "ignore Data Entry when no RPN/NRPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpn(Channel, 0, 0) shouldBe None
  }

  it should "ignore Data Entry after Null RPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 8))
    selectRpn(tracker, Channel, ScMidiRpn.NullMsb, ScMidiRpn.NullLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((8, 0)))
    tracker.rpn(Channel, ScMidiRpn.NullMsb, ScMidiRpn.NullLsb) shouldBe None
  }

  it should "track RPN values independently per channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))
    selectRpn(tracker, OtherChannel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.DataEntryMsb, value = 24))

    // When / Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpn(OtherChannel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  // TODO #155 Are tests for NRPN tracking on par with those for RPN tracking? They should be similar.
  behavior of "ScMidiChannelStateTracker NRPN tracking"

  private val NrpnA = (10, 20)
  private val NrpnB = (10, 21)

  private def selectNrpn(tracker: ScMidiChannelStateTracker, channel: Int, msb: Int, lsb: Int): Unit = {
    tracker.send(CcScMidiMessage(channel, ScMidiCc.NrpnMsb, msb))
    tracker.send(CcScMidiMessage(channel, ScMidiCc.NrpnLsb, lsb))
  }

  it should "return None for an NRPN that has not been written" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "select a NRPN" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    selectNrpn(tracker, Channel, 1, 2)

    // Then
    tracker.selector(Channel) shouldEqual Selector.Nrpn(1, 2)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual 1
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual 2
  }

  it should "record an NRPN value via Data Entry MSB after NRPN selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 17))

    // Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) should equal(Some((17, 0)))
  }

  it should "let NRPN selection take over a previous RPN selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 5))

    // Then
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) should equal(Some((5, 0)))
  }

  it should "keep recorded NRPN values when a different NRPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 1))

    // When
    selectNrpn(tracker, Channel, NrpnB._1, NrpnB._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 2))

    // Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) should equal(Some((1, 0)))
    tracker.nrpn(Channel, NrpnB._1, NrpnB._2) should equal(Some((2, 0)))
  }

  it should "ignore Data Entry after Null NRPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 8))
    selectNrpn(tracker, Channel, ScMidiNrpn.NullMsb, ScMidiNrpn.NullLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) should equal(Some((8, 0)))
    tracker.nrpn(Channel, ScMidiNrpn.NullMsb, ScMidiNrpn.NullLsb) shouldBe None
  }

  it should "ignore data changes when an initial NRPN has only MSB selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnMsb, 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.nrpn(Channel, 0, 0) shouldBe empty
    tracker.selector(Channel) shouldEqual Selector.Nrpn(0, ScMidiNrpn.NullLsb)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual 0
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual ScMidiNrpn.NullLsb
  }

  it should "ignore data changes when an initial NRPN has only LSB selection" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.NrpnLsb, 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 6))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, 0))

    // Then
    tracker.nrpn(Channel, 0, 0) shouldBe empty
    tracker.selector(Channel) shouldEqual Selector.Nrpn(ScMidiRpn.NullMsb, 0)
    tracker.cc(Channel, ScMidiCc.NrpnMsb) shouldEqual ScMidiNrpn.NullMsb
    tracker.cc(Channel, ScMidiCc.NrpnLsb) shouldEqual 0
  }

  behavior of "ScMidiChannelStateTracker Data Increment / Decrement"

  it should "increment the recorded RPN value by 1 (combined 14-bit)" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 51)))
  }

  it should "decrement the recorded RPN value by 1 (combined 14-bit)" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 49)))
  }

  it should "carry across LSB and MSB when incrementing past 127 LSB" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((1, 0)))
  }

  it should "clamp at 0 when decrementing below 0" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 0))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((0, 0)))
  }

  it should "clamp at 16383 when incrementing past the 14-bit maximum" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 127))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((127, 127)))
  }

  it should "ignore the data byte of Data Increment / Decrement (always ±1)" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 64))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 100))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 5))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) should equal(Some((64, 52)))
  }

  it should "seed the value from the companion's DefaultRpnValues when nothing is recorded" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then — default is (2, 0); +1 → (2, 1)
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((2, 1)))
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
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 1)))
  }

  it should "ignore Data Increment when no RPN/NRPN is selected" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpn(Channel, 0, 0) shouldBe None
    tracker.nrpn(Channel, 0, 0) shouldBe None
  }

  it should "ignore Data Increment for an unknown RPN with no recorded value and no default" in {
    // Given
    val unknownRpnMsb = 5
    val unknownRpnLsb = 9
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, unknownRpnMsb, unknownRpnLsb)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpn(Channel, unknownRpnMsb, unknownRpnLsb) shouldBe None
  }

  it should "still record CC 96 / 97 in ccValues even when ignored as Data Increment / Decrement" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataDecrement, value = 1))

    // Then
    tracker.ccOption(Channel, ScMidiCc.DataIncrement) should equal(Some(0))
    tracker.ccOption(Channel, ScMidiCc.DataDecrement) should equal(Some(1))
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
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 6)))
  }

  it should "ignore Data Increment for NRPN with neither recorded value nor default" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpn(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  behavior of "ScMidiChannelStateTracker (continued)"

  it should "overwrite the velocity of an active note when a Note On is re-sent" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 50))

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 120))

    // Then
    tracker.velocity(Channel, NoteC4) should equal(Some(120))
  }

  behavior of "ScMidiChannelStateTracker close"

  it should "report isClosed as false initially" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    tracker.isClosed shouldBe false
  }

  it should "report isClosed as true after close" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.close()

    // Then
    tracker.isClosed shouldBe true
  }

  it should "make send a no-op after close" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.close()

    // When
    tracker.send(NoteOnScMidiMessage(Channel, NoteE4, velocity = 80))
    tracker.send(NoteOffScMidiMessage(Channel, NoteC4))

    // Then
    tracker.activeNoteSet(Channel) should contain only NoteC4
    tracker.velocity(Channel, NoteC4) should equal(Some(100))
  }

  it should "preserve queried state after close" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.VolumeMsb, value = 90))
    tracker.send(PitchBendScMidiMessage(Channel, value = 1234))

    // When
    tracker.close()

    // Then
    tracker.velocity(Channel, NoteC4) should equal(Some(100))
    tracker.ccOption(Channel, ScMidiCc.VolumeMsb) should equal(Some(90))
    tracker.pitchBend(Channel) should equal(Some(1234))
  }

  it should "be idempotent for repeated close calls" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.close()
    tracker.close()

    // Then
    tracker.isClosed shouldBe true
  }

  behavior of "ScMidiChannelStateTracker channel validation"

  it should "throw on activeNoteSet with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.activeNoteSet(-1)
    an[IllegalArgumentException] should be thrownBy tracker.activeNoteSet(16)
  }

  it should "throw on activeNoteSeq with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.activeNoteSeq(-1)
    an[IllegalArgumentException] should be thrownBy tracker.activeNoteSeq(16)
  }

  it should "throw on velocity / polyPressure with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.velocity(-1, NoteC4)
    an[IllegalArgumentException] should be thrownBy tracker.polyPressure(16, NoteC4)
  }

  it should "throw on ccOption / cc with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.ccOption(-1, ScMidiCc.VolumeMsb)
    an[IllegalArgumentException] should be thrownBy tracker.cc(16, ScMidiCc.VolumeMsb)
  }

  it should "throw on channelPressure / pitchBend / programChange with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.channelPressure(-1)
    an[IllegalArgumentException] should be thrownBy tracker.pitchBend(16)
    an[IllegalArgumentException] should be thrownBy tracker.programChange(-1)
  }

  it should "throw on bankSelect with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.bankSelect(-1)
    an[IllegalArgumentException] should be thrownBy tracker.bankSelect(16)
  }

  it should "throw on rpn / nrpn with an invalid channel" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.rpn(-1, 0, 0)
    an[IllegalArgumentException] should be thrownBy tracker.nrpn(16, 0, 0)
  }

  behavior of "ScMidiChannelStateTracker Channel Mode messages"

  it should "cancel active notes on the channel when All Sound Off is received" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(Channel, NoteE4, velocity = 110))
    tracker.send(NoteOnScMidiMessage(OtherChannel, NoteG4, velocity = 90))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllSoundOff, value = 0))

    // Then
    tracker.activeNoteSet(Channel) shouldBe empty
    tracker.activeNoteSet(OtherChannel) should contain only NoteG4
  }

  it should "cancel active notes on the channel when All Notes Off is received" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, NoteG4, velocity = 90))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllNotesOff, value = 0))

    // Then
    tracker.activeNoteSet(Channel) shouldBe empty
    tracker.activeNoteSet(OtherChannel) should contain only NoteG4
  }

  it should "clear resettable CCs when Reset All Controllers is received" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
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

  it should "clear Channel Pressure and Pitch Bend when Reset All Controllers is received" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(ChannelPressureScMidiMessage(Channel, value = 80))
    tracker.send(PitchBendScMidiMessage(Channel, value = 1234))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

    // Then
    tracker.channelPressure(Channel) shouldBe None
    tracker.pitchBend(Channel) shouldBe None
  }

  it should "clear the RPN/NRPN selector when Reset All Controllers is received" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 12))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then — the selector was cleared, so the new Data Entry must not affect the previous RPN
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
  }

  it should "preserve Bank Select, Volume, Pan, Program Change, and RPN/NRPN values on Reset All Controllers" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
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
    tracker.programChange(Channel) should equal(Some(7))
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
  }

  it should "scope Reset All Controllers to the channel it was received on" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ModulationMsb, value = 64))
    tracker.send(CcScMidiMessage(OtherChannel, ScMidiCc.ModulationMsb, value = 90))
    tracker.send(ChannelPressureScMidiMessage(OtherChannel, value = 70))

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))

    // Then
    tracker.ccOption(Channel, ScMidiCc.ModulationMsb) shouldBe None
    tracker.ccOption(OtherChannel, ScMidiCc.ModulationMsb) should equal(Some(90))
    tracker.channelPressure(OtherChannel) should equal(Some(70))
  }

  behavior of "ScMidiChannelStateTracker reset"

  it should "clear all per-channel state across all channels" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.send(NoteOnScMidiMessage(OtherChannel, NoteE4, velocity = 110))
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
      tracker.activeNoteSet(channel) shouldBe empty
      tracker.ccOption(channel, ScMidiCc.VolumeMsb) shouldBe None
      tracker.ccOption(channel, ScMidiCc.BankSelectMsb) shouldBe None
      tracker.channelPressure(channel) shouldBe None
      tracker.pitchBend(channel) shouldBe None
      tracker.programChange(channel) shouldBe None
    }
    tracker.rpn(Channel, ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpn(OtherChannel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "clear the RPN/NRPN selector so subsequent Data Entry is ignored" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    selectRpn(tracker, Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb)

    // When
    tracker.reset()
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpn(Channel, ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) shouldBe None
  }

  it should "leave isClosed unchanged when called on an open tracker" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.reset()

    // Then
    tracker.isClosed shouldBe false
  }

  it should "be a no-op after close" in {
    // Given
    val tracker = ScMidiChannelStateTracker()
    tracker.send(NoteOnScMidiMessage(Channel, NoteC4, velocity = 100))
    tracker.close()

    // When
    tracker.reset()

    // Then
    tracker.activeNoteSet(Channel) should contain only NoteC4
    tracker.isClosed shouldBe true
  }

  it should "still record Channel Mode CC numbers in ccValues" in {
    // Given
    val tracker = ScMidiChannelStateTracker()

    // When
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllSoundOff, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.ResetAllControllers, value = 0))
    tracker.send(CcScMidiMessage(Channel, ScMidiCc.AllNotesOff, value = 0))

    // Then
    tracker.ccOption(Channel, ScMidiCc.AllSoundOff) should equal(Some(0))
    tracker.ccOption(Channel, ScMidiCc.ResetAllControllers) should equal(Some(0))
    tracker.ccOption(Channel, ScMidiCc.AllNotesOff) should equal(Some(0))
  }
}

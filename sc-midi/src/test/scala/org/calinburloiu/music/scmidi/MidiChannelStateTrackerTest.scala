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
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiChannelStateTrackerTest extends AnyFlatSpec with Matchers {

  private val Channel = 3
  private val OtherChannel = 7

  private val NrpnA = (10, 20)
  private val NrpnB = (10, 21)

  private trait TrackerFixture {
    val tracker: MidiChannelStateTracker = MidiChannelStateTracker()
  }

  /**
   * A tracker that models a receiver known to act on All Sound Off, Reset All Controllers, and All Notes Off, including
   * the All Notes Off that the MIDI Mode messages 124–127 imply.
   */
  private trait ResettableTrackerFixture {
    val tracker: MidiChannelStateTracker = MidiChannelStateTracker(shallRespondToResetMessages = true)
  }

  behavior of "MidiChannelStateTracker per note tracking"

  it should "have no active notes on any channel when empty" in new TrackerFixture {
    // When / Then
    for (channel <- 0 to 15) {
      tracker.activeNotes(channel) shouldBe empty
    }
  }

  it should "record a Note On as an active note with its velocity" in new TrackerFixture {
    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.isNoteActive(Channel, C4) shouldBe true
    tracker.velocityOption(Channel, C4) should equal(Some(100))
    tracker.velocity(Channel, C4) should equal(100)
  }

  it should "remove a note from the active set on Note Off" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // When
    tracker.send(NoteOffMidiMsg(Channel, C4))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.isNoteActive(Channel, C4) shouldBe false
    tracker.velocityOption(Channel, C4) shouldBe None
    tracker.velocity(Channel, C4) should equal(0)
  }

  it should "treat a Note On with velocity 0 as a Note Off" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = NoteOnMidiMsg.NoteOffVelocity))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.isNoteActive(Channel, C4) shouldBe false
  }

  it should "order active notes by their Note On" in new TrackerFixture {
    // When
    tracker.send(NoteOnMidiMsg(Channel, G4, velocity = 80))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 90))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 70))

    // Then
    tracker.orderedActiveNotes(Channel) should contain theSameElementsInOrderAs Seq(G4, C4, E4)
  }

  it should "track active notes independently per channel" in new TrackerFixture {
    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 110))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.activeNotes(OtherChannel) should contain only E4
    tracker.velocityOption(Channel, E4) shouldBe None
    tracker.velocityOption(OtherChannel, C4) shouldBe None
  }

  it should "default Polyphonic Key Pressure to 0 for an active note that has not received one" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // When / Then
    tracker.polyPressureOption(Channel, C4) should equal(Some(0))
    tracker.polyPressure(Channel, C4) should equal(0)
  }

  it should "update Polyphonic Key Pressure for an active note" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // When
    tracker.send(PolyPressureMidiMsg(Channel, C4, value = 90))

    // Then
    tracker.polyPressureOption(Channel, C4) should equal(Some(90))
    tracker.polyPressure(Channel, C4) should equal(90)
  }

  it should "ignore Polyphonic Key Pressure for an inactive note" in new TrackerFixture {
    // When
    tracker.send(PolyPressureMidiMsg(Channel, C4, value = 90))

    // Then
    tracker.polyPressureOption(Channel, C4) shouldBe None
    tracker.polyPressure(Channel, C4) should equal(0)
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "preserve Polyphonic Key Pressure when a note is re-triggered with Note On" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(PolyPressureMidiMsg(Channel, C4, value = 90))

    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

    // Then — two voices sound for one key, so pressure addressed to that key belongs to both of them
    tracker.polyPressureOption(Channel, C4) should equal(Some(90))
    tracker.polyPressure(Channel, C4) should equal(90)
  }

  it should "overwrite the velocity of an active note when a Note On is re-sent" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 50))

    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 120))

    // Then
    tracker.velocityOption(Channel, C4) should equal(Some(120))
    tracker.referenceCount(Channel, C4) should equal(2)
  }

  it should "count a single Note On as one reference" in new TrackerFixture {
    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

    // Then
    tracker.referenceCount(Channel, C4) should equal(1)
  }

  it should "increment the reference count when an already-active note receives another Note On" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))

      // When
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

      // Then
      tracker.referenceCount(Channel, C4) should equal(2)
      tracker.activeNotes(Channel) should contain only C4
    }

  it should "decrement the reference count on Note Off while a reference remains, keeping the note active" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

      // When
      tracker.send(NoteOffMidiMsg(Channel, C4))

      // Then
      tracker.referenceCount(Channel, C4) should equal(1)
      tracker.isNoteActive(Channel, C4) shouldBe true
      tracker.activeNotes(Channel) should contain only C4
    }

  it should "remove the note when the last Note On is discharged" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))
    tracker.send(NoteOffMidiMsg(Channel, C4))

    // When
    tracker.send(NoteOffMidiMsg(Channel, C4))

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.isNoteActive(Channel, C4) shouldBe false
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "decrement the reference count on a Note On with velocity 0 exactly as on a Note Off" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

      // When
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = NoteOnMidiMsg.NoteOffVelocity))

      // Then
      tracker.referenceCount(Channel, C4) should equal(1)
      tracker.isNoteActive(Channel, C4) shouldBe true
    }

  it should "leave the reference count at 0 when a Note Off arrives for an inactive note" in new TrackerFixture {
    // When
    tracker.send(NoteOffMidiMsg(Channel, C4))

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.activeNotes(Channel) shouldBe empty
  }

  it should "report a reference count of 0 for a note that was never played" in new TrackerFixture {
    // When / Then
    tracker.referenceCount(Channel, C4) should equal(0)
  }

  it should "track reference counts independently per channel and per note" in new TrackerFixture {
    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 100))
    tracker.send(NoteOnMidiMsg(OtherChannel, C4, velocity = 100))

    // Then
    tracker.referenceCount(Channel, C4) should equal(2)
    tracker.referenceCount(Channel, E4) should equal(1)
    tracker.referenceCount(OtherChannel, C4) should equal(1)
    tracker.referenceCount(OtherChannel, E4) should equal(0)
  }

  it should "move a note to the end of the ordered active notes on a duplicate Note On" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 90))
    tracker.send(NoteOnMidiMsg(Channel, G4, velocity = 80))

    // When
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

    // Then — active notes are ordered by their most recent Note On, not by first insertion
    tracker.orderedActiveNotes(Channel) should contain theSameElementsInOrderAs Seq(E4, G4, C4)
    tracker.referenceCount(Channel, C4) should equal(2)
  }

  behavior of "MidiChannelStateTracker Control Change tracking"

  it should "return None for ccOption when the CC has not been set" in new TrackerFixture {
    // When / Then
    tracker.ccOption(Channel, MidiCc.ModulationMsb) shouldBe None
  }

  it should "record the value of a Control Change message" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, number = MidiCc.ModulationMsb, value = 42))

    // Then
    tracker.ccOption(Channel, MidiCc.ModulationMsb) should equal(Some(42))
    tracker.cc(Channel, MidiCc.ModulationMsb) should equal(42)
  }

  it should "track CC values independently per channel" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, number = MidiCc.VolumeMsb, value = 80))
    tracker.send(CcMidiMsg(OtherChannel, number = MidiCc.VolumeMsb, value = 50))

    // Then
    tracker.cc(Channel, MidiCc.VolumeMsb) should equal(80)
    tracker.cc(OtherChannel, MidiCc.VolumeMsb) should equal(50)
  }

  it should "fall back to the companion's DefaultCcValues for known CCs when nothing is recorded" in new
      TrackerFixture {
    // When / Then
    tracker.cc(Channel, MidiCc.VolumeMsb) should equal(100)
    tracker.cc(Channel, MidiCc.PanMsb) should equal(64)
    tracker.cc(Channel, MidiCc.ExpressionMsb) should equal(127)
    tracker.cc(Channel, MidiCc.SustainPedal) should equal(0)
  }

  it should "throw NoSuchElementException for an unknown CC with no value, override, or default" in new TrackerFixture {
    // Given
    val unknownCc = 50

    // When / Then
    a[NoSuchElementException] should be thrownBy tracker.cc(Channel, unknownCc)
  }

  it should "reject ccDefaults keyed by a number that is not a controller number" in {
    // Given — a Channel Mode number and the two numbers bordering the MIDI data byte range
    val numbers = Seq(-1, AllNotesOffMidiMsg.Number, 128)

    for (number <- numbers) {
      // When / Then
      withClue(number) {
        an[IllegalArgumentException] should be thrownBy MidiChannelStateTracker(ccDefaults = Map(number -> 0))
      }
    }
  }

  it should "use overrideDefaultValue when set and no value was recorded" in new TrackerFixture {
    // Given
    val unknownCc = 50

    // When / Then
    tracker.cc(Channel, unknownCc, overrideDefaultValue = Some(33)) should equal(33)
    // override also wins over the companion default
    tracker.cc(Channel, MidiCc.VolumeMsb, overrideDefaultValue = Some(7)) should equal(7)
  }

  it should "prefer the recorded value over override and defaults" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.VolumeMsb, value = 12))

    // When / Then
    tracker.cc(Channel, MidiCc.VolumeMsb, overrideDefaultValue = Some(99)) should equal(12)
  }

  it should "honor a constructor-supplied ccDefault for an unknown CC" in {
    // Given
    val unknownCc = 50
    val tracker = MidiChannelStateTracker(ccDefaults = Map(unknownCc -> 21))

    // When / Then
    tracker.cc(Channel, unknownCc) should equal(21)
  }

  it should "let constructor-supplied ccDefaults override the companion's defaults" in {
    // Given
    val tracker = MidiChannelStateTracker(ccDefaults = Map(MidiCc.VolumeMsb -> 5))

    // When / Then
    tracker.cc(Channel, MidiCc.VolumeMsb) should equal(5)
  }

  behavior of "MidiChannelStateTracker Channel Pressure / Pitch Bend / Program Change tracking"

  it should "default Channel Pressure / Pitch Bend / Program Change to 0" in new TrackerFixture {
    // When / Then
    tracker.channelPressure(Channel) should equal(0)
    tracker.pitchBend(Channel) should equal(0)
    tracker.programChange(Channel) should equal(0)
  }

  it should "record the latest Channel Pressure value" in new TrackerFixture {
    // When
    tracker.send(ChannelPressureMidiMsg(Channel, value = 80))
    tracker.send(ChannelPressureMidiMsg(Channel, value = 95))

    // Then
    tracker.channelPressure(Channel) should equal(95)
  }

  it should "record the latest Pitch Bend value (signed)" in new TrackerFixture {
    // When
    tracker.send(PitchBendMidiMsg(Channel, value = -2048))

    // Then
    tracker.pitchBend(Channel) should equal(-2048)
  }

  it should "record the latest Program Change value" in new TrackerFixture {
    // When
    tracker.send(ProgramChangeMidiMsg(Channel, program = 42))

    // Then
    tracker.programChange(Channel) should equal(42)
  }

  it should "track Channel Pressure / Pitch Bend / Program Change independently per channel" in new TrackerFixture {
    // When
    tracker.send(ChannelPressureMidiMsg(Channel, value = 80))
    tracker.send(PitchBendMidiMsg(OtherChannel, value = 1024))
    tracker.send(ProgramChangeMidiMsg(Channel, program = 5))

    // Then
    tracker.channelPressure(Channel) should equal(80)
    tracker.channelPressure(OtherChannel) should equal(0)
    tracker.pitchBend(OtherChannel) should equal(1024)
    tracker.pitchBend(Channel) should equal(0)
    tracker.programChange(Channel) should equal(5)
    tracker.programChange(OtherChannel) should equal(0)
  }

  behavior of "MidiChannelStateTracker bankSelect"

  it should "default Bank Select to (0, 0) when nothing is recorded" in new TrackerFixture {
    // When / Then
    tracker.bankSelect(Channel) should equal((0, 0))
  }

  it should "reflect Bank Select MSB and LSB recorded via CC messages" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.BankSelectMsb, value = 3))
    tracker.send(CcMidiMsg(Channel, MidiCc.BankSelectLsb, value = 7))

    // Then
    tracker.bankSelect(Channel) should equal((3, 7))
  }

  it should "honour constructor-supplied Bank Select defaults" in {
    // Given
    val tracker = MidiChannelStateTracker(
      ccDefaults = Map(MidiCc.BankSelectMsb -> 1, MidiCc.BankSelectLsb -> 2)
    )

    // When / Then
    tracker.bankSelect(Channel) should equal((1, 2))
  }

  behavior of "MidiChannelStateTracker RPN tracking"

  private def selectRpn(tracker: MidiChannelStateTracker, channel: Int, msb: Int, lsb: Int): Unit = {
    tracker.send(CcMidiMsg(channel, MidiCc.RpnMsb, msb))
    tracker.send(CcMidiMsg(channel, MidiCc.RpnLsb, lsb))
  }

  it should "return None for an RPN that has not been written" in new TrackerFixture {
    // When / Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe None
  }

  it should "throw NoSuchElementException for rpn() when no value, override, or default is available" in
    new TrackerFixture {
      // When / Then
      a[NoSuchElementException] should be thrownBy tracker.rpn(Channel, 5, 9)
    }

  it should "return the companion default for rpn() when no value has been recorded" in new TrackerFixture {
    // When / Then
    tracker.rpn(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should equal((2, 0))
    tracker.rpn(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal((64, 0))
  }

  it should "use overrideDefaultValue for rpn() when no value has been recorded" in new TrackerFixture {
    // When / Then
    tracker.rpn(Channel, 5, 9, overrideDefaultValue = Some((10, 0))) should equal((10, 0))
    // recorded value wins over override
    selectRpn(tracker, Channel, 5, 9)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 3))
    tracker.rpn(Channel, 5, 9, overrideDefaultValue = Some((10, 0))) should equal((3, 0))
  }

  it should "initially have no RPN/NRPN selected" in new TrackerFixture {
    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, MidiCc.RpnMsb) shouldEqual MidiRpn.NullMsb
    tracker.cc(Channel, MidiCc.RpnLsb) shouldEqual MidiRpn.NullLsb
  }

  it should "select a RPN" in new TrackerFixture {
    // When
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.cc(Channel, MidiCc.RpnMsb) shouldEqual MidiRpn.FineTuningMsb
    tracker.cc(Channel, MidiCc.RpnLsb) shouldEqual MidiRpn.FineTuningLsb
  }

  it should "select a RPN when LSB is sent before MSB (reversed order)" in new TrackerFixture {
    // When — LSB first, then MSB
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.FineTuningLsb))
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.FineTuningMsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
  }

  it should "deselect the parameter when the Null RPN is selected MSB before LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.NullMsb))
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.NullLsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "deselect the parameter when the Null RPN is selected LSB before MSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)

    // When — the order MIDI 1.0 allows just as much as the other, and a third-party sender may well emit
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.NullLsb))
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.NullMsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "keep a lone RPN MSB of 127 pending rather than reading it as a Null" in new TrackerFixture {
    // When — only the pair 127/127 is the Null Function; a lone Null MSB deselects nothing on its own
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.NullMsb))

    // Then nothing is selected yet, the LSB not having arrived
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None

    // When the LSB arrives, completing RPN 7F/00
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, 0))

    // Then — had the lone Null MSB been read as a Null, it would have cleared the pending half and left this
    // selecting nothing
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(MidiRpn.NullMsb, 0)
  }

  it should "initially have no RPN/NRPN partially assembled" in new TrackerFixture {
    // When / Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.None
  }

  it should "report an RPN whose LSB has not arrived as a half still pending" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.FineTuningMsb))

    // Then — the LSB is pending, which rpnSelector cannot express and reports as None
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(Some(MidiRpn.FineTuningMsb), None)
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None

    // When the LSB completes the pair
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.FineTuningLsb))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual
      PartialRpnSelector.Rpn(Some(MidiRpn.FineTuningMsb), Some(MidiRpn.FineTuningLsb))
  }

  it should "report an RPN whose MSB has not arrived as a half still pending" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.FineTuningLsb))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(None, Some(MidiRpn.FineTuningLsb))
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "tell a lone RPN half carrying 127 apart from a Null that deselects" in new TrackerFixture {
    // When — a lone Null MSB, which is a pending half of a parameter, not a deselection
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.NullMsb))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(Some(MidiRpn.NullMsb), None)

    // When the Null LSB completes the Null pair
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.NullLsb))

    // Then nothing is left half-assembled either
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.None
  }

  it should "start a fresh partial RPN rather than inherit a pending NRPN half" in new TrackerFixture {
    // Given — an NRPN with its LSB still pending
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, NrpnA._1))

    // When a selector CC of the other kind arrives
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.FineTuningLsb))

    // Then the pending NRPN MSB is dropped rather than becoming the new parameter's MSB
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(None, Some(MidiRpn.FineTuningLsb))
  }

  it should "track the partially assembled RPN independently per channel" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.FineTuningMsb))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(Some(MidiRpn.FineTuningMsb), None)
    tracker.partialRpnSelector(OtherChannel) shouldEqual PartialRpnSelector.None
  }

  it should "record an RPN value via Data Entry MSB after RPN selection" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 24))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  it should "update the LSB component of an RPN value via Data Entry LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 50))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 50)))
  }

  it should "seed the unrecorded RPN value from the resolved default when only Data Entry LSB is sent" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)

      // When
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 50))

      // Then
      tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 50)))
    }

  it should "record an RPN value for a parameter whose LSB is 127" in new TrackerFixture {
    // Given — 127 is a legitimate half of a parameter number; only the pair 127/127 is the Null Function
    selectRpn(tracker, Channel, msb = 0, lsb = MidiRpn.NullLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 24))

    // Then
    tracker.rpnOption(Channel, 0, MidiRpn.NullLsb) should equal(Some((24, 0)))
  }

  it should "keep recorded RPN values when a different RPN is selected" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))

    // When
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 0)))
  }

  it should "ignore data changes when an initial RPN has only MSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.PitchBendSensitivityMsb))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 6))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.NullLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, MidiCc.RpnMsb) shouldEqual 0
    tracker.cc(Channel, MidiCc.RpnLsb) shouldEqual MidiRpn.NullLsb
  }

  it should "ignore data changes when an initial RPN has only LSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.PitchBendSensitivityLsb))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 6))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnOption(Channel, MidiRpn.NullMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, MidiCc.RpnMsb) shouldEqual MidiRpn.NullMsb
    tracker.cc(Channel, MidiCc.RpnLsb) shouldEqual 0
  }

  it should "ignore data changes when no RPN/NRPN is selected" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 99))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, 0, 0) shouldBe None
  }

  it should "ignore Data Entry after Null RPN is selected" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 8))
    selectRpn(tracker, Channel, MidiRpn.NullMsb, MidiRpn.NullLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((8, 0)))
    tracker.rpnOption(Channel, MidiRpn.NullMsb, MidiRpn.NullLsb) shouldBe None
  }

  it should "track RPN values independently per channel" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))
    selectRpn(tracker, OtherChannel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(OtherChannel, MidiCc.DataEntryMsb, value = 24))

    // When / Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 0)))
    tracker.rpnOption(OtherChannel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((24, 0)))
  }

  it should "increment the recorded RPN value by 1 (combined 14-bit)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 51)))
  }

  it should "decrement the recorded RPN value by 1 (combined 14-bit)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 49)))
  }

  it should "carry across LSB and MSB when incrementing past 127 LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((1, 0)))
  }

  it should "carry across MSB and LSB when decrementing past 0 LSB" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 1))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 0))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((0, 127)))
  }

  it should "clamp at 0 when decrementing below 0" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 0))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((0, 0)))
  }

  it should "clamp at 16383 when incrementing past the 14-bit maximum" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 127))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((127, 127)))
  }

  it should "ignore the data byte of Data Increment / Decrement (always ±1)" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 50))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 100))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 5))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) should equal(Some((64, 52)))
  }

  it should "seed the value from the companion's DefaultRpnValues when nothing is recorded for Data Increment" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

      // Then — default is (2, 0); +1 → (2, 1)
      tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
        equal(Some((2, 1)))
    }

  it should "seed the value from the companion's DefaultRpnValues when nothing is recorded for Data Decrement" in
    new TrackerFixture {
      // Given
      selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

      // Then — default is (2, 0); -1 → (1, 127)
      tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
        equal(Some((1, 127)))
    }

  it should "prefer the constructor's rpnDefaults over the companion's" in {
    // Given
    val tracker = MidiChannelStateTracker(
      rpnDefaults = Map(
        (MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) -> (12, 0)
      )
    )
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((12, 1)))
  }

  it should "ignore Data Increment when no RPN/NRPN is selected" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

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
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.rpnOption(Channel, unknownRpnMsb, unknownRpnLsb) shouldBe None
  }

  it should "ignore Data Decrement for an unknown RPN with no recorded value and no default" in new TrackerFixture {
    // Given
    val unknownRpnMsb = 5
    val unknownRpnLsb = 9
    selectRpn(tracker, Channel, unknownRpnMsb, unknownRpnLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.rpnOption(Channel, unknownRpnMsb, unknownRpnLsb) shouldBe None
  }

  it should "still record Data Increment / Decrement values even when their effect is ignored" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 1))

    // Then
    tracker.ccOption(Channel, MidiCc.DataIncrement) should equal(Some(0))
    tracker.ccOption(Channel, MidiCc.DataDecrement) should equal(Some(1))
  }

  it should "let RPN selection take over a previous NRPN selection" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 5))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
      equal(Some((5, 0)))
  }

  behavior of "MidiChannelStateTracker NRPN tracking"

  private def selectNrpn(tracker: MidiChannelStateTracker, channel: Int, msb: Int, lsb: Int): Unit = {
    tracker.send(CcMidiMsg(channel, MidiCc.NrpnMsb, msb))
    tracker.send(CcMidiMsg(channel, MidiCc.NrpnLsb, lsb))
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
    tracker.cc(Channel, MidiCc.NrpnMsb) shouldEqual 1
    tracker.cc(Channel, MidiCc.NrpnLsb) shouldEqual 2
  }

  it should "select a NRPN when LSB is sent before MSB (reversed order)" in new TrackerFixture {
    // When — LSB first, then MSB
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, NrpnA._2))
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, NrpnA._1))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(NrpnA._1, NrpnA._2)
  }

  it should "deselect the parameter when the Null NRPN is selected MSB before LSB" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, MidiNrpn.NullMsb))
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, MidiNrpn.NullLsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "deselect the parameter when the Null NRPN is selected LSB before MSB" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When — the order MIDI 1.0 allows just as much as the other, and a third-party sender may well emit
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, MidiNrpn.NullLsb))
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, MidiNrpn.NullMsb))

    // Then
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "keep a lone NRPN MSB of 127 pending rather than reading it as a Null" in new TrackerFixture {
    // When — only the pair 127/127 is the Null Function; a lone Null MSB deselects nothing on its own
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, MidiNrpn.NullMsb))

    // Then nothing is selected yet, the LSB not having arrived
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None

    // When the LSB arrives, completing NRPN 7F/22
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, 34))

    // Then — had the lone Null MSB been read as a Null, it would have cleared the pending half and left this
    // selecting nothing
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Nrpn(MidiNrpn.NullMsb, 34)
  }

  it should "report an NRPN whose LSB has not arrived as a half still pending" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, NrpnA._1))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Nrpn(Some(NrpnA._1), None)
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None

    // When the LSB completes the pair
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, NrpnA._2))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Nrpn(Some(NrpnA._1), Some(NrpnA._2))
  }

  it should "report an NRPN whose MSB has not arrived as a half still pending" in new TrackerFixture {
    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, NrpnA._2))

    // Then
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Nrpn(None, Some(NrpnA._2))
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "start a fresh partial NRPN rather than inherit a pending RPN half" in new TrackerFixture {
    // Given — an RPN with its LSB still pending
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.FineTuningMsb))

    // When an NRPN selector CC of the other kind arrives
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, NrpnA._2))

    // Then the pending RPN MSB is dropped rather than becoming the new parameter's MSB
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Nrpn(None, Some(NrpnA._2))
  }

  it should "record an NRPN value via Data Entry MSB after NRPN selection" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 17))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((17, 0)))
  }

  it should "record an NRPN value for a parameter whose MSB is 127" in new TrackerFixture {
    // Given — NRPN 7F/22, an ordinary parameter on current gear, whose MSB happens to be the Null value
    selectNrpn(tracker, Channel, msb = MidiNrpn.NullMsb, lsb = 34)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 17))

    // Then
    tracker.nrpnOption(Channel, MidiNrpn.NullMsb, 34) should equal(Some((17, 0)))
  }

  it should "record an NRPN value for a parameter whose LSB is 127" in new TrackerFixture {
    // Given — NRPN 00/7F, the other half of the same case
    selectNrpn(tracker, Channel, msb = 0, lsb = MidiNrpn.NullLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 17))

    // Then
    tracker.nrpnOption(Channel, 0, MidiNrpn.NullLsb) should equal(Some((17, 0)))
  }

  it should "let NRPN selection take over a previous RPN selection" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

    // When
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 5))

    // Then
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((5, 0)))
  }

  it should "keep recorded NRPN values when a different NRPN is selected" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 1))

    // When
    selectNrpn(tracker, Channel, NrpnB._1, NrpnB._2)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 2))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((1, 0)))
    tracker.nrpnOption(Channel, NrpnB._1, NrpnB._2) should equal(Some((2, 0)))
  }

  it should "ignore Data Entry after Null NRPN is selected" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 8))
    selectNrpn(tracker, Channel, MidiNrpn.NullMsb, MidiNrpn.NullLsb)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((8, 0)))
    tracker.nrpnOption(Channel, MidiNrpn.NullMsb, MidiNrpn.NullLsb) shouldBe None
  }

  it should "ignore data changes when an initial NRPN has only MSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnMsb, 0))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 6))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, 0))

    // Then
    tracker.nrpnOption(Channel, 0, 0) shouldBe empty
    tracker.nrpnOption(Channel, 0, MidiNrpn.NullLsb) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, MidiCc.NrpnMsb) shouldEqual 0
    tracker.cc(Channel, MidiCc.NrpnLsb) shouldEqual MidiNrpn.NullLsb
  }

  it should "ignore data changes when an initial NRPN has only LSB selection" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.NrpnLsb, 0))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 6))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, 0))

    // Then
    tracker.nrpnOption(Channel, 0, 0) shouldBe empty
    tracker.nrpnOption(Channel, MidiNrpn.NullMsb, 0) shouldBe empty
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
    tracker.cc(Channel, MidiCc.NrpnMsb) shouldEqual MidiNrpn.NullMsb
    tracker.cc(Channel, MidiCc.NrpnLsb) shouldEqual 0
  }

  it should "increment a recorded NRPN value for a parameter whose LSB is 127" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, msb = 0, lsb = MidiNrpn.NullLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 17))

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, 0, MidiNrpn.NullLsb) should equal(Some((17, 1)))
  }

  it should "increment a recorded NRPN value with constructor-supplied default" in {
    // Given
    val tracker = MidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 6)))
  }

  it should "decrement a recorded NRPN value with constructor-supplied default" in {
    // Given
    val tracker = MidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 4)))
  }

  it should "apply multiple Increments and a Decrement to an NRPN value" in {
    // Given
    val tracker = MidiChannelStateTracker(
      nrpnDefaults = Map(NrpnA -> (10, 5))
    )
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then — (10, 5) + 1 + 1 - 1 = (10, 6)
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) should equal(Some((10, 6)))
  }

  it should "ignore Data Increment for NRPN with neither recorded value nor default" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "ignore Data Decrement for NRPN with neither recorded value nor default" in new TrackerFixture {
    // Given
    selectNrpn(tracker, Channel, NrpnA._1, NrpnA._2)

    // When
    tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

    // Then
    tracker.nrpnOption(Channel, NrpnA._1, NrpnA._2) shouldBe None
  }

  behavior of "MidiChannelStateTracker channel validation"

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

  it should "throw on referenceCount with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.referenceCount(-1, C4)
    an[IllegalArgumentException] should be thrownBy tracker.referenceCount(16, C4)
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
    an[IllegalArgumentException] should be thrownBy tracker.ccOption(-1, MidiCc.VolumeMsb)
    an[IllegalArgumentException] should be thrownBy tracker.cc(16, MidiCc.VolumeMsb)
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

  it should "throw on rpnSelector / partialRpnSelector with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.rpnSelector(-1)
    an[IllegalArgumentException] should be thrownBy tracker.rpnSelector(16)
    an[IllegalArgumentException] should be thrownBy tracker.partialRpnSelector(-1)
    an[IllegalArgumentException] should be thrownBy tracker.partialRpnSelector(16)
  }

  it should "throw on the mode and Local Control accessors with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.isOmniModeOn(-1)
    an[IllegalArgumentException] should be thrownBy tracker.isOmniModeOn(16)
    an[IllegalArgumentException] should be thrownBy tracker.isPolyModeOn(-1)
    an[IllegalArgumentException] should be thrownBy tracker.isPolyModeOn(16)
    an[IllegalArgumentException] should be thrownBy tracker.isMonoModeOn(-1)
    an[IllegalArgumentException] should be thrownBy tracker.isMonoModeOn(16)
    an[IllegalArgumentException] should be thrownBy tracker.monoModeChannelCount(-1)
    an[IllegalArgumentException] should be thrownBy tracker.monoModeChannelCount(16)
    an[IllegalArgumentException] should be thrownBy tracker.isLocalControlOn(-1)
    an[IllegalArgumentException] should be thrownBy tracker.isLocalControlOn(16)
  }

  it should "throw on reset(channel) with an invalid channel" in new TrackerFixture {
    // When / Then
    an[IllegalArgumentException] should be thrownBy tracker.reset(-1)
    an[IllegalArgumentException] should be thrownBy tracker.reset(16)
  }

  behavior of "MidiChannelStateTracker Channel Mode messages"

  it should "cancel active notes on the channel when All Sound Off is received" in new ResettableTrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 110))
    tracker.send(NoteOnMidiMsg(OtherChannel, G4, velocity = 90))

    // When
    tracker.send(AllSoundOffMidiMsg(Channel))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.activeNotes(OtherChannel) should contain only G4
  }

  it should "cancel active notes on the channel when All Notes Off is received" in new ResettableTrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(OtherChannel, G4, velocity = 90))

    // When
    tracker.send(AllNotesOffMidiMsg(Channel))

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.activeNotes(OtherChannel) should contain only G4
  }

  it should "clear the reference counts of the channel's notes when All Sound Off is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

      // When
      tracker.send(AllSoundOffMidiMsg(Channel))

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.activeNotes(Channel) shouldBe empty
    }

  it should "clear the reference counts of the channel's notes when All Notes Off is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))

      // When
      tracker.send(AllNotesOffMidiMsg(Channel))

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.activeNotes(Channel) shouldBe empty
    }

  it should "clear resettable CCs when Reset All Controllers is received" in new ResettableTrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))
    tracker.send(CcMidiMsg(Channel, MidiCc.ExpressionMsb, value = 50))
    tracker.send(CcMidiMsg(Channel, MidiCc.SustainPedal, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.PortamentoPedal, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.SostenutoPedal, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.SoftPedal, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.LegatoFootswitch, value = 127))
    tracker.send(CcMidiMsg(Channel, MidiCc.Hold2Pedal, value = 127))

    // When
    tracker.send(ResetAllControllersMidiMsg(Channel))

    // Then
    tracker.ccOption(Channel, MidiCc.ModulationMsb) shouldBe None
    tracker.ccOption(Channel, MidiCc.ExpressionMsb) shouldBe None
    tracker.ccOption(Channel, MidiCc.SustainPedal) shouldBe None
    tracker.ccOption(Channel, MidiCc.PortamentoPedal) shouldBe None
    tracker.ccOption(Channel, MidiCc.SostenutoPedal) shouldBe None
    tracker.ccOption(Channel, MidiCc.SoftPedal) shouldBe None
    tracker.ccOption(Channel, MidiCc.LegatoFootswitch) shouldBe None
    tracker.ccOption(Channel, MidiCc.Hold2Pedal) shouldBe None
  }

  it should "clear Data Entry, Data Increment, and Data Decrement CCs when Reset All Controllers is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryLsb, value = 34))
      tracker.send(CcMidiMsg(Channel, MidiCc.DataIncrement, value = 0))
      tracker.send(CcMidiMsg(Channel, MidiCc.DataDecrement, value = 0))

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))

      // Then
      tracker.ccOption(Channel, MidiCc.DataEntryMsb) shouldBe None
      tracker.ccOption(Channel, MidiCc.DataEntryLsb) shouldBe None
      tracker.ccOption(Channel, MidiCc.DataIncrement) shouldBe None
      tracker.ccOption(Channel, MidiCc.DataDecrement) shouldBe None
    }

  it should "clear Channel Pressure, Pitch Bend, and the RPN/NRPN selector when Reset All Controllers is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(ChannelPressureMidiMsg(Channel, value = 80))
      tracker.send(PitchBendMidiMsg(Channel, value = 1234))
      selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))

      // Then
      tracker.channelPressure(Channel) should equal(0)
      tracker.pitchBend(Channel) should equal(0)
      tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
      tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.None
      tracker.ccOption(Channel, MidiCc.RpnMsb) shouldBe None
      tracker.ccOption(Channel, MidiCc.RpnLsb) shouldBe None
    }

  it should "leave reference counts intact while zeroing Polyphonic Key Pressure on Reset All Controllers" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))
      tracker.send(PolyPressureMidiMsg(Channel, C4, value = 90))

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))

      // Then — Reset All Controllers is not a note-off, so nothing is discharged
      tracker.referenceCount(Channel, C4) should equal(2)
      tracker.isNoteActive(Channel, C4) shouldBe true
      tracker.polyPressure(Channel, C4) should equal(0)
    }

  it should "clear a half-assembled RPN when Reset All Controllers is received" in new ResettableTrackerFixture {
    // Given — only the MSB has arrived, so nothing is selected yet but a half is pending
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnMsb, MidiRpn.FineTuningMsb))

    // When
    tracker.send(ResetAllControllersMidiMsg(Channel))

    // Then the pending half is gone, so the LSB that follows starts a parameter of its own rather than completing
    // the one begun before the reset
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.None
    tracker.send(CcMidiMsg(Channel, MidiCc.RpnLsb, MidiRpn.FineTuningLsb))
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(None, Some(MidiRpn.FineTuningLsb))
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.None
  }

  it should "clear the RPN/NRPN selector so subsequent Data Entry is ignored after Reset All Controllers" in
    new ResettableTrackerFixture {
      // Given
      selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 99))

      // Then — the selector was cleared, so the new Data Entry must not affect the previous RPN
      tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
        equal(Some((12, 0)))
    }

  it should "preserve Bank Select, Volume, Pan, Program Change, and RPN/NRPN values on Reset All Controllers" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(CcMidiMsg(Channel, MidiCc.BankSelectMsb, value = 3))
      tracker.send(CcMidiMsg(Channel, MidiCc.VolumeMsb, value = 90))
      tracker.send(CcMidiMsg(Channel, MidiCc.PanMsb, value = 32))
      tracker.send(ProgramChangeMidiMsg(Channel, program = 7))
      selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
      tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))

      // Then
      tracker.ccOption(Channel, MidiCc.BankSelectMsb) should equal(Some(3))
      tracker.ccOption(Channel, MidiCc.VolumeMsb) should equal(Some(90))
      tracker.ccOption(Channel, MidiCc.PanMsb) should equal(Some(32))
      tracker.programChange(Channel) should equal(7)
      tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) should
        equal(Some((12, 0)))
    }

  it should "reset Polyphonic Key Pressure on every active note when Reset All Controllers is received" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 110))
      tracker.send(NoteOnMidiMsg(OtherChannel, G4, velocity = 90))
      tracker.send(PolyPressureMidiMsg(Channel, C4, value = 70))
      tracker.send(PolyPressureMidiMsg(Channel, E4, value = 80))
      tracker.send(PolyPressureMidiMsg(OtherChannel, G4, value = 90))

      // When
      tracker.send(ResetAllControllersMidiMsg(Channel))

      // Then — the notes stay active, only their pressure is reset, and only on the addressed channel
      tracker.activeNotes(Channel) should contain theSameElementsAs Seq(C4, E4)
      tracker.polyPressure(Channel, C4) should equal(0)
      tracker.polyPressure(Channel, E4) should equal(0)
      tracker.polyPressure(OtherChannel, G4) should equal(90)
    }

  it should "scope Reset All Controllers to the channel it was received on" in new ResettableTrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))
    tracker.send(CcMidiMsg(OtherChannel, MidiCc.ModulationMsb, value = 90))
    tracker.send(ChannelPressureMidiMsg(OtherChannel, value = 70))

    // When
    tracker.send(ResetAllControllersMidiMsg(Channel))

    // Then
    tracker.ccOption(Channel, MidiCc.ModulationMsb) shouldBe None
    tracker.ccOption(OtherChannel, MidiCc.ModulationMsb) should equal(Some(90))
    tracker.channelPressure(OtherChannel) should equal(70)
  }

  it should "not cancel active notes on All Sound Off by default" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 105))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 110))

    // When
    tracker.send(AllSoundOffMidiMsg(Channel))

    // Then — a Note Off is still owed for each Note On, so the record of them must survive with its counts intact
    tracker.activeNotes(Channel) should contain theSameElementsAs Seq(C4, E4)
    tracker.referenceCount(Channel, C4) should equal(2)
    tracker.referenceCount(Channel, E4) should equal(1)
  }

  it should "not cancel active notes on All Notes Off by default" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 105))
    tracker.send(NoteOnMidiMsg(Channel, E4, velocity = 110))

    // When
    tracker.send(AllNotesOffMidiMsg(Channel))

    // Then — a Note Off is still owed for each Note On, so the record of them must survive with its counts intact
    tracker.activeNotes(Channel) should contain theSameElementsAs Seq(C4, E4)
    tracker.referenceCount(Channel, C4) should equal(2)
    tracker.referenceCount(Channel, E4) should equal(1)
  }

  it should "not cancel active notes on the MIDI Mode messages 124-127 by default" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 105))

    // When
    tracker.send(OmniModeOffMidiMsg(Channel))
    tracker.send(OmniModeOnMidiMsg(Channel))
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))
    tracker.send(PolyModeOnMidiMsg(Channel))

    // Then — a Note Off is still owed for each Note On, so the record of them must survive with its counts intact
    tracker.activeNotes(Channel) should contain only C4
    tracker.referenceCount(Channel, C4) should equal(2)
  }

  it should "not clear controller state on Reset All Controllers by default" in new TrackerFixture {
    // Given
    tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))
    tracker.send(ChannelPressureMidiMsg(Channel, value = 80))
    tracker.send(PitchBendMidiMsg(Channel, value = 1234))
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

    // When
    tracker.send(ResetAllControllersMidiMsg(Channel))

    // Then
    tracker.ccOption(Channel, MidiCc.ModulationMsb) should equal(Some(64))
    tracker.channelPressure(Channel) should equal(80)
    tracker.pitchBend(Channel) should equal(1234)
    tracker.rpnSelector(Channel) shouldEqual RpnSelector.Rpn(
      MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.partialRpnSelector(Channel) shouldEqual PartialRpnSelector.Rpn(
      Some(MidiRpn.PitchBendSensitivityMsb), Some(MidiRpn.PitchBendSensitivityLsb))
  }

  it should "reject a Channel Mode number as a Control Change number in ccOption / cc" in new TrackerFixture {
    // When / Then — Channel Mode messages are not controllers, so none of their numbers can hold a CC value
    ChannelModeMidiMsg.NumberRange.foreach { number =>
      an[IllegalArgumentException] should be thrownBy tracker.ccOption(Channel, number)
      an[IllegalArgumentException] should be thrownBy tracker.cc(Channel, number, overrideDefaultValue = Some(0))
    }
  }

  it should "cancel active notes on the channel when a MIDI Mode message 124-127 is received" in
    new ResettableTrackerFixture {
      // Given
      val modeMessages: Seq[ChannelModeMidiMsg] = Seq(
        OmniModeOffMidiMsg(Channel),
        OmniModeOnMidiMsg(Channel),
        MonoModeOnMidiMsg(Channel, channelCount = 4),
        PolyModeOnMidiMsg(Channel)
      )
      tracker.send(NoteOnMidiMsg(OtherChannel, G4, velocity = 90))
      tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))

      for (modeMessage <- modeMessages) {
        // Given
        tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
        tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 105))

        // When
        tracker.send(modeMessage)

        // Then — MIDI 1.0 makes every MIDI Mode message act as an All Notes Off too
        withClue(modeMessage) {
          tracker.activeNotes(Channel) shouldBe empty
          tracker.referenceCount(Channel, C4) should equal(0)
        }
      }

      // Then — only the addressed channel's notes go, and no controller is reset
      tracker.activeNotes(OtherChannel) should contain only G4
      tracker.ccOption(Channel, MidiCc.ModulationMsb) should equal(Some(64))
    }

  it should "leave active notes and controller values untouched on Local Control" in new ResettableTrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(CcMidiMsg(Channel, MidiCc.ModulationMsb, value = 64))

    // When
    tracker.send(LocalControlMidiMsg(Channel, isOn = false))

    // Then
    tracker.activeNotes(Channel) should contain only C4
    tracker.ccOption(Channel, MidiCc.ModulationMsb) should equal(Some(64))
  }

  it should "start every channel in Omni On/Poly mode (Mode 1) with Local Control on" in new TrackerFixture {
    // Then — the power-up state MIDI 1.0 recommends
    for (channel <- 0 to 15) {
      tracker.isOmniModeOn(channel) shouldBe true
      tracker.isPolyModeOn(channel) shouldBe true
      tracker.isMonoModeOn(channel) shouldBe false
      tracker.isLocalControlOn(channel) shouldBe true
    }
  }

  it should "track Omni Mode Off and Omni Mode On per channel" in new TrackerFixture {
    // When
    tracker.send(OmniModeOffMidiMsg(Channel))

    // Then
    tracker.isOmniModeOn(Channel) shouldBe false
    tracker.isOmniModeOn(OtherChannel) shouldBe true

    // When
    tracker.send(OmniModeOnMidiMsg(Channel))

    // Then
    tracker.isOmniModeOn(Channel) shouldBe true
  }

  it should "track Mono Mode On and Poly Mode On per channel" in new TrackerFixture {
    // When
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))

    // Then
    tracker.isMonoModeOn(Channel) shouldBe true
    tracker.isPolyModeOn(Channel) shouldBe false
    tracker.isMonoModeOn(OtherChannel) shouldBe false
    tracker.isPolyModeOn(OtherChannel) shouldBe true

    // When
    tracker.send(PolyModeOnMidiMsg(Channel))

    // Then
    tracker.isMonoModeOn(Channel) shouldBe false
    tracker.isPolyModeOn(Channel) shouldBe true
  }

  it should "track the channel count a Mono Mode On asks for, until a Poly Mode On" in new TrackerFixture {
    // Then — Poly mode until a Mono Mode On is received
    tracker.monoModeChannelCount(Channel) shouldBe None

    // When
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))

    // Then
    tracker.monoModeChannelCount(Channel) shouldBe Some(4)
    tracker.monoModeChannelCount(OtherChannel) shouldBe None

    // When — 0 asks the receiver to use as many channels as it has voices
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 0))

    // Then
    tracker.monoModeChannelCount(Channel) shouldBe Some(0)

    // When
    tracker.send(PolyModeOnMidiMsg(Channel))

    // Then
    tracker.monoModeChannelCount(Channel) shouldBe None
  }

  it should "keep the Omni and the Poly/Mono flags independent of each other" in new TrackerFixture {
    // When — MIDI 1.0 lets the two kinds of Mode message arrive in any order, each setting its own flag
    tracker.send(OmniModeOffMidiMsg(Channel))
    tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 2))
    tracker.send(OmniModeOnMidiMsg(Channel))

    // Then
    tracker.isOmniModeOn(Channel) shouldBe true
    tracker.isMonoModeOn(Channel) shouldBe true
  }

  it should "track Local Control per channel" in new TrackerFixture {
    // When
    tracker.send(LocalControlMidiMsg(Channel, isOn = false))

    // Then
    tracker.isLocalControlOn(Channel) shouldBe false
    tracker.isLocalControlOn(OtherChannel) shouldBe true

    // When
    tracker.send(LocalControlMidiMsg(Channel, isOn = true))

    // Then
    tracker.isLocalControlOn(Channel) shouldBe true
  }

  it should "leave the mode and Local Control untouched by All Sound Off, Reset All Controllers and All Notes Off" in
    new ResettableTrackerFixture {
      // Given
      tracker.send(OmniModeOffMidiMsg(Channel))
      tracker.send(MonoModeOnMidiMsg(Channel, channelCount = 4))
      tracker.send(LocalControlMidiMsg(Channel, isOn = false))

      // When — RP-015 lists the other Channel Mode messages among what Reset All Controllers leaves unchanged
      tracker.send(AllSoundOffMidiMsg(Channel))
      tracker.send(ResetAllControllersMidiMsg(Channel))
      tracker.send(AllNotesOffMidiMsg(Channel))

      // Then
      tracker.isOmniModeOn(Channel) shouldBe false
      tracker.isMonoModeOn(Channel) shouldBe true
      tracker.monoModeChannelCount(Channel) shouldBe Some(4)
      tracker.isLocalControlOn(Channel) shouldBe false
    }

  behavior of "MidiChannelStateTracker reset"

  it should "clear all per-channel state across all channels" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 110))
    tracker.send(CcMidiMsg(Channel, MidiCc.VolumeMsb, value = 90))
    tracker.send(CcMidiMsg(OtherChannel, MidiCc.BankSelectMsb, value = 3))
    tracker.send(ChannelPressureMidiMsg(Channel, value = 80))
    tracker.send(PitchBendMidiMsg(OtherChannel, value = 1234))
    tracker.send(ProgramChangeMidiMsg(Channel, program = 7))
    selectRpn(tracker, Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 12))
    selectNrpn(tracker, OtherChannel, NrpnA._1, NrpnA._2)
    tracker.send(CcMidiMsg(OtherChannel, MidiCc.DataEntryMsb, value = 5))

    // When
    tracker.reset()

    // Then
    for (channel <- 0 to 15) {
      tracker.activeNotes(channel) shouldBe empty
      tracker.ccOption(channel, MidiCc.VolumeMsb) shouldBe None
      tracker.ccOption(channel, MidiCc.BankSelectMsb) shouldBe None
      tracker.channelPressure(channel) should equal(0)
      tracker.pitchBend(channel) should equal(0)
      tracker.programChange(channel) should equal(0)
      tracker.rpnSelector(channel) shouldEqual RpnSelector.None
      tracker.partialRpnSelector(channel) shouldEqual PartialRpnSelector.None
    }
    tracker.rpnOption(Channel, MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) shouldBe None
    tracker.nrpnOption(OtherChannel, NrpnA._1, NrpnA._2) shouldBe None
  }

  it should "clear reference counts across all channels" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))
    tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 90))
    tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 95))

    // When
    tracker.reset()

    // Then
    tracker.referenceCount(Channel, C4) should equal(0)
    tracker.referenceCount(OtherChannel, E4) should equal(0)
  }

  it should "restore the power-up mode and Local Control across all channels" in new TrackerFixture {
    // Given
    tracker.send(OmniModeOffMidiMsg(Channel))
    tracker.send(MonoModeOnMidiMsg(OtherChannel, channelCount = 1))
    tracker.send(LocalControlMidiMsg(Channel, isOn = false))

    // When
    tracker.reset()

    // Then
    tracker.isOmniModeOn(Channel) shouldBe true
    tracker.isPolyModeOn(OtherChannel) shouldBe true
    tracker.isLocalControlOn(Channel) shouldBe true
  }

  it should "clear the RPN/NRPN selector so subsequent Data Entry is ignored after reset" in new TrackerFixture {
    // Given
    selectRpn(tracker, Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)

    // When
    tracker.reset()
    tracker.send(CcMidiMsg(Channel, MidiCc.DataEntryMsb, value = 99))

    // Then
    tracker.rpnOption(Channel, MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) shouldBe None
  }

  it should "clear the state of a single channel, leaving the other fifteen untouched" in new TrackerFixture {
    // Given
    tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
    tracker.send(CcMidiMsg(Channel, MidiCc.SustainPedal, 127))
    tracker.send(PitchBendMidiMsg(Channel, 1000))
    tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 90))
    tracker.send(CcMidiMsg(OtherChannel, MidiCc.SustainPedal, 127))
    tracker.send(PitchBendMidiMsg(OtherChannel, 2000))

    // When
    tracker.reset(Channel)

    // Then
    tracker.activeNotes(Channel) shouldBe empty
    tracker.ccOption(Channel, MidiCc.SustainPedal) shouldBe None
    tracker.pitchBend(Channel) shouldEqual 0
    tracker.activeNotes(OtherChannel) should contain(E4)
    tracker.ccOption(OtherChannel, MidiCc.SustainPedal) shouldEqual Some(127)
    tracker.pitchBend(OtherChannel) shouldEqual 2000
  }

  it should "clear the reference counts of a single channel, leaving the other fifteen untouched" in
    new TrackerFixture {
      // Given
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 100))
      tracker.send(NoteOnMidiMsg(Channel, C4, velocity = 110))
      tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 90))
      tracker.send(NoteOnMidiMsg(OtherChannel, E4, velocity = 95))

      // When
      tracker.reset(Channel)

      // Then
      tracker.referenceCount(Channel, C4) should equal(0)
      tracker.referenceCount(OtherChannel, E4) should equal(2)
    }

  it should "restore the power-up mode and Local Control of a single channel, leaving the other fifteen untouched" in
    new TrackerFixture {
      // Given
      for (channel <- Seq(Channel, OtherChannel)) {
        tracker.send(OmniModeOffMidiMsg(channel))
        tracker.send(MonoModeOnMidiMsg(channel, channelCount = 1))
        tracker.send(LocalControlMidiMsg(channel, isOn = false))
      }

      // When
      tracker.reset(Channel)

      // Then
      tracker.isOmniModeOn(Channel) shouldBe true
      tracker.isPolyModeOn(Channel) shouldBe true
      tracker.monoModeChannelCount(Channel) shouldBe None
      tracker.isLocalControlOn(Channel) shouldBe true
      tracker.isOmniModeOn(OtherChannel) shouldBe false
      tracker.isMonoModeOn(OtherChannel) shouldBe true
      tracker.monoModeChannelCount(OtherChannel) shouldBe Some(1)
      tracker.isLocalControlOn(OtherChannel) shouldBe false
    }
}

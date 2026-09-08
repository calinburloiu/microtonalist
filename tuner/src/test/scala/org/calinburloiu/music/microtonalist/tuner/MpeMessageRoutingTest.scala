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

import org.calinburloiu.music.microtonalist.tuner.MpeRoutingVerdict.*
import org.calinburloiu.music.scmidi.message.*
import org.calinburloiu.music.scmidi.{MidiNote, RpnMessages, RpnSelector}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Tests for [[MpeMessageRouting]].
 *
 * == Test Organization ==
 *
 * One `behavior of` block per public function. The `route` block is the paper's message-handling table ("How the MPE
 * Tuner Handles Common MIDI Messages in MPE Input Mode") expressed as table-driven checks, one `Table` row per cell:
 * the role supplies the column and the message the row. Add a new case by adding a row, not a new test, unless the
 * cell needs a rule the table cannot express.
 */
class MpeMessageRoutingTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private val lower7: MpeZone = MpeZone(MpeZoneType.Lower, 7)
  private val upper7: MpeZone = MpeZone(MpeZoneType.Upper, 7)
  private val disabledLower: MpeZone = MpeZone(MpeZoneType.Lower, 0)
  private val disabledUpper: MpeZone = MpeZone(MpeZoneType.Upper, 0)

  private val dualZones: MpeZones = MpeZones(lower7, upper7)
  private val lowerOnlyZones: MpeZones = MpeZones(lower7, disabledUpper)
  private val upperOnlyZones: MpeZones = MpeZones(disabledLower, upper7)
  private val noZones: MpeZones = MpeZones(disabledLower, disabledUpper)

  private val mcmSelector: RpnSelector =
    RpnSelector.Rpn(MidiRpn.MpeConfigurationMessageMsb, MidiRpn.MpeConfigurationMessageLsb)
  private val pbsSelector: RpnSelector =
    RpnSelector.Rpn(MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb)

  behavior of "MpeMessageRouting.roleOf"

  // ---- MPE Input Mode ----

  it should "classify every channel of a dual-Zone configuration" in {
    // Given
    val expectations = Table(
      ("channel", "role"),
      (0, MpeChannelRole.Master(lower7)),
      (1, MpeChannelRole.Member(lower7)),
      (7, MpeChannelRole.Member(lower7)),
      (8, MpeChannelRole.Member(upper7)),
      (14, MpeChannelRole.Member(upper7)),
      (15, MpeChannelRole.Master(upper7))
    )
    forAll(expectations) { (channel, role) =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, dualZones, channel) shouldEqual role
    }
  }

  it should "classify a channel of no enabled Zone as Outside" in {
    // Given
    val channels = Table("channel", 8, 12, 14, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, lowerOnlyZones, channel) shouldEqual MpeChannelRole.Outside
    }
  }

  it should "classify every channel as Outside when no Zone is enabled" in {
    // Given
    val channels = Table("channel", 0, 1, 8, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.Mpe, noZones, channel) shouldEqual MpeChannelRole.Outside
    }
  }

  // ---- Non-MPE Input Mode ----

  it should "classify every channel as NonMpeInput of the Lower Zone when it is enabled" in {
    // Given
    val channels = Table("channel", 0, 3, 15)
    forAll(channels) { channel =>
      // When / Then
      MpeMessageRouting.roleOf(MpeInputMode.NonMpe, dualZones, channel) shouldEqual
        MpeChannelRole.NonMpeInput(lower7)
    }
  }

  it should "fall back to the Upper Zone only when it is enabled" in {
    // When / Then
    MpeMessageRouting.roleOf(MpeInputMode.NonMpe, upperOnlyZones, 3) shouldEqual MpeChannelRole.NonMpeInput(upper7)
  }

  it should "classify every channel as Outside when no Zone is enabled in Non-MPE Input Mode" in {
    // When / Then
    MpeMessageRouting.roleOf(MpeInputMode.NonMpe, noZones, 3) shouldEqual MpeChannelRole.Outside
  }

  behavior of "MpeMessageRouting.route"

  private val memberRole: MpeChannelRole = MpeChannelRole.Member(lower7)
  private val masterRole: MpeChannelRole = MpeChannelRole.Master(lower7)
  private val nonMpeRole: MpeChannelRole = MpeChannelRole.NonMpeInput(lower7)
  private val outsideRole: MpeChannelRole = MpeChannelRole.Outside

  private val noSelector: RpnSelector = RpnSelector.None

  /**
   * The input channel every table row below uses: Member Channel 3 of the Lower Zone, so that a forward on the
   * Zone's Master Channel is visibly different from a forward on the arrival channel. The same message is replayed
   * against all four roles, and both forwarding columns name the role's Zone Master Channel rather than the channel
   * the message carries, which is what makes `route` a function of its arguments alone.
   */
  private val inputChannel: Int = 3

  /** The Master Channel of the Lower Zone that every role below is built from. */
  private val zoneMasterChannel: Int = lower7.masterChannel

  // ---- Channel Voice messages ----

  it should "route the message classes of the paper's table" in {
    // Given
    val verdicts = Table(
      ("description", "message", "member", "master", "nonMpe", "outside"),
      ("Note On",
        NoteOnMidiMsg(inputChannel, MidiNote.C4, 100),
        Interpret, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Note Off",
        NoteOffMidiMsg(inputChannel, MidiNote.C4),
        Interpret, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Pitch Bend",
        PitchBendMidiMsg(inputChannel, 1000),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Channel Pressure",
        ChannelPressureMidiMsg(inputChannel, 90),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("CC #74",
        CcMidiMsg(inputChannel, MidiCc.MpeSlide, 100),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Polyphonic Key Pressure",
        PolyPressureMidiMsg(inputChannel, MidiNote.C4, 80),
        Discard, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Program Change",
        ProgramChangeMidiMsg(inputChannel, 5),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Bank Select MSB",
        CcMidiMsg(inputChannel, MidiCc.BankSelectMsb, 1),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Damper Pedal",
        CcMidiMsg(inputChannel, MidiCc.SustainPedal, 127),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("All Sound Off",
        AllSoundOffMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Reset All Controllers",
        ResetAllControllersMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Local Control",
        LocalControlMidiMsg(inputChannel, isOn = false),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("All Notes Off",
        AllNotesOffMidiMsg(inputChannel),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Omni Mode Off",
        OmniModeOffMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard),
      ("Omni Mode On",
        OmniModeOnMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard),
      ("Mono Mode On",
        MonoModeOnMidiMsg(inputChannel, channelCount = 1),
        Discard, Discard, Discard, Discard),
      ("Poly Mode On",
        PolyModeOnMidiMsg(inputChannel),
        Discard, Discard, Discard, Discard)
    )
    forAll(verdicts) { (_, message, member, master, nonMpe, outside) =>
      // When / Then
      MpeMessageRouting.route(memberRole, message, noSelector) shouldEqual member
      MpeMessageRouting.route(masterRole, message, noSelector) shouldEqual master
      MpeMessageRouting.route(nonMpeRole, message, noSelector) shouldEqual nonMpe
      MpeMessageRouting.route(outsideRole, message, noSelector) shouldEqual outside
    }
  }

  it should "forward a Master Channel message on the Zone's Master Channel" in {
    // Given
    val message = CcMidiMsg(zoneMasterChannel, MidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(masterRole, message, noSelector) shouldEqual ForwardOn(zoneMasterChannel)
  }

  it should "forward an Upper Zone Master Channel message on the Upper Zone Master Channel" in {
    // Given
    val message = CcMidiMsg(upper7.masterChannel, MidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(MpeChannelRole.Master(upper7), message, noSelector) shouldEqual ForwardOn(15)
  }

  it should "redirect a Non-MPE input message to the Upper Zone Master Channel only when it is enabled" in {
    // Given
    val message = CcMidiMsg(inputChannel, MidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(MpeChannelRole.NonMpeInput(upper7), message, noSelector) shouldEqual ForwardOn(15)
  }

  // ---- Interpreted parameters ----

  it should "interpret an MCM Data Entry MSB on MIDI Channel 1 or 16 (1-based) whatever the role" in {
    // Given
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    val channels = Table("channel", 0, 15)
    // A Zone holds between 0 Member Channels, which deactivates it, and 15, which is the whole rest of the port.
    val memberCounts = Table("memberCount", 0, 7, 15)
    forAll(channels) { channel =>
      forAll(memberCounts) { memberCount =>
        val message = CcMidiMsg(channel, MidiCc.DataEntryMsb, memberCount)
        forAll(roles) { role =>
          // When / Then
          MpeMessageRouting.route(role, message, mcmSelector) shouldEqual Interpret
        }
      }
    }
  }

  it should "discard an MCM Data Entry MSB requesting more Member Channels than a Zone can hold" in {
    // Given
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    val channels = Table("channel", 0, 15)
    val memberCounts = Table("memberCount", 16, 100, 127)
    forAll(channels) { channel =>
      forAll(memberCounts) { memberCount =>
        val message = CcMidiMsg(channel, MidiCc.DataEntryMsb, memberCount)
        forAll(roles) { role =>
          // When / Then
          MpeMessageRouting.route(role, message, mcmSelector) shouldEqual Discard
        }
      }
    }
  }

  it should "interpret a PBS Data Entry at every role but Outside" in {
    // Given
    val ccNumbers = Table("ccNumber", MidiCc.DataEntryMsb, MidiCc.DataEntryLsb)
    forAll(ccNumbers) { ccNumber =>
      val message = CcMidiMsg(inputChannel, ccNumber, 24)
      // When / Then
      MpeMessageRouting.route(memberRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(masterRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(nonMpeRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(outsideRole, message, pbsSelector) shouldEqual Discard
    }
  }

  it should "consume the selector CCs of an interpreted parameter as well" in {
    // Given
    val selectors = Table("selector", mcmSelector, pbsSelector)
    val ccNumbers = Table("ccNumber", MidiCc.RpnMsb, MidiCc.RpnLsb)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        forAll(roles) { role =>
          // When / Then
          MpeMessageRouting.route(role, CcMidiMsg(0, ccNumber, 0), selector) shouldEqual Discard
        }
      }
    }
  }

  // ---- Uninterpreted Registered and Non-Registered Parameters ----

  private val fineTuningSelector: RpnSelector =
    RpnSelector.Rpn(MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb)
  private val nrpnSelector: RpnSelector = RpnSelector.Nrpn(12, 34)

  it should "consume every RPN and NRPN selector CC" in {
    // Given
    val ccNumbers = Table("ccNumber", MidiCc.RpnMsb, MidiCc.RpnLsb, MidiCc.NrpnMsb, MidiCc.NrpnLsb)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(ccNumbers) { ccNumber =>
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, CcMidiMsg(inputChannel, ccNumber, 0), fineTuningSelector) shouldEqual
          Discard
      }
    }
  }

  it should "re-emit a complete sequence for a data value of an uninterpreted parameter" in {
    // Given
    val selectors = Table("selector", fineTuningSelector, nrpnSelector)
    val ccNumbers = Table("ccNumber",
      MidiCc.DataEntryMsb, MidiCc.DataEntryLsb, MidiCc.DataIncrement, MidiCc.DataDecrement)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        val message = CcMidiMsg(inputChannel, ccNumber, 64)
        // When / Then
        MpeMessageRouting.route(memberRole, message, selector) shouldEqual Discard
        MpeMessageRouting.route(masterRole, message, selector) shouldEqual ForwardRpnSequenceOn(zoneMasterChannel)
        MpeMessageRouting.route(nonMpeRole, message, selector) shouldEqual ForwardRpnSequenceOn(zoneMasterChannel)
        MpeMessageRouting.route(outsideRole, message, selector) shouldEqual Discard
      }
    }
  }

  it should "discard a data value when no parameter is selected" in {
    // Given — what `MidiChannelStateTracker` reports for a parameter with a selector CC still to arrive as much as
    // for one a Null deselected: either way the value has no parameter to apply to.
    val ccNumbers = Table("ccNumber",
      MidiCc.DataEntryMsb, MidiCc.DataEntryLsb, MidiCc.DataIncrement, MidiCc.DataDecrement)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(ccNumbers) { ccNumber =>
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, CcMidiMsg(inputChannel, ccNumber, 64), noSelector) shouldEqual Discard
      }
    }
  }

  it should "re-emit a sequence for a data value of an NRPN whose MSB or LSB is 127" in {
    // Given — 127 is a parameter number like any other, and NRPN 7F/22 and 0C/7F are ordinary parameters on current
    // gear; only the pair 127/127 is the Null Function, which the tracker reports as no selection at all.
    val selectors = Table("selector",
      RpnSelector.Nrpn(msb = 127, lsb = 34),
      RpnSelector.Nrpn(msb = 12, lsb = 127))
    val message = CcMidiMsg(inputChannel, MidiCc.DataEntryMsb, 64)
    forAll(selectors) { selector =>
      // When / Then
      MpeMessageRouting.route(masterRole, message, selector) shouldEqual ForwardRpnSequenceOn(zoneMasterChannel)
    }
  }

  it should "route an NRPN that shares the numbers of an interpreted RPN as uninterpreted" in {
    // Given
    val selectors = Table("selector",
      RpnSelector.Nrpn(MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb),
      RpnSelector.Nrpn(MidiRpn.MpeConfigurationMessageMsb, MidiRpn.MpeConfigurationMessageLsb))
    forAll(selectors) { selector =>
      // When / Then: an MCM is valid on MIDI Channel 1 (1-based), so the NRPN of the same numbers is the case that
      // could be mistaken for one.
      MpeMessageRouting.route(masterRole, CcMidiMsg(0, MidiCc.DataEntryMsb, 7), selector) shouldEqual
        ForwardRpnSequenceOn(zoneMasterChannel)
      MpeMessageRouting.route(memberRole, CcMidiMsg(0, MidiCc.DataEntryMsb, 7), selector) shouldEqual Discard
    }
  }

  it should "discard an MCM Data Entry received on a channel other than MIDI Channel 1 or 16 (1-based)" in {
    // Given
    val channels = Table("channel", 1, 5, 14)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(channels) { channel =>
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, CcMidiMsg(channel, MidiCc.DataEntryMsb, 7), mcmSelector) shouldEqual
          Discard
      }
    }
  }

  it should "discard a Data Entry LSB and a Data Increment or Decrement of the MCM" in {
    // Given
    val ccNumbers = Table("ccNumber", MidiCc.DataEntryLsb, MidiCc.DataIncrement, MidiCc.DataDecrement)
    forAll(ccNumbers) { ccNumber =>
      // When / Then
      MpeMessageRouting.route(masterRole, CcMidiMsg(0, ccNumber, 7), mcmSelector) shouldEqual Discard
    }
  }

  it should "discard a Data Increment or Decrement of Pitch Bend Sensitivity at every role" in {
    // Given
    val ccNumbers = Table("ccNumber", MidiCc.DataIncrement, MidiCc.DataDecrement)
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    forAll(ccNumbers) { ccNumber =>
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, CcMidiMsg(0, ccNumber, 1), pbsSelector) shouldEqual Discard
      }
    }
  }

  behavior of "MpeMessageRouting.deselectsOnRelay"

  it should "hold for Reset All Controllers alone" in {
    // When / Then
    MpeMessageRouting.deselectsOnRelay(ResetAllControllersMidiMsg(inputChannel)) shouldBe true
    MpeMessageRouting.deselectsOnRelay(AllSoundOffMidiMsg(inputChannel)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(AllNotesOffMidiMsg(inputChannel)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(LocalControlMidiMsg(inputChannel, isOn = true)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(CcMidiMsg(inputChannel, MidiCc.SustainPedal, 127)) shouldBe false
    MpeMessageRouting.deselectsOnRelay(NoteOnMidiMsg(inputChannel, MidiNote.C4, 100)) shouldBe false
  }

  behavior of "MpeMessageRouting.rpnSequence"

  /**
   * A value message as it arrived, on an input channel that is deliberately none of the output channels the
   * sequences below are rendered on, so that the re-addressing to the output channel is visible in the result.
   */
  private def receivedValueCc(number: Int, value: Int): CcMidiMsg = CcMidiMsg(9, number, value)

  it should "render an RPN selector ahead of its value message" in {
    // When
    val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(fineTuningSelector,
      receivedValueCc(MidiCc.DataEntryMsb, 64), outputChannel = 0, latchedSelector = RpnSelector.None)
    // Then the value message is re-addressed from its input channel to the output one
    messages shouldEqual Seq(
      CcMidiMsg(0, MidiCc.RpnLsb, MidiRpn.FineTuningLsb),
      CcMidiMsg(0, MidiCc.RpnMsb, MidiRpn.FineTuningMsb),
      CcMidiMsg(0, MidiCc.DataEntryMsb, 64)
    )
    latchedSelector shouldEqual fineTuningSelector
  }

  it should "render an NRPN selector ahead of its value message" in {
    // When
    val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(nrpnSelector,
      receivedValueCc(MidiCc.DataIncrement, 1), outputChannel = 15, latchedSelector = RpnSelector.None)
    // Then
    messages shouldEqual Seq(
      CcMidiMsg(15, MidiCc.NrpnLsb, 34),
      CcMidiMsg(15, MidiCc.NrpnMsb, 12),
      CcMidiMsg(15, MidiCc.DataIncrement, 1)
    )
    latchedSelector shouldEqual nrpnSelector
  }

  it should "render nothing when no parameter is selected" in {
    // When
    val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(RpnSelector.None,
      receivedValueCc(MidiCc.DataEntryMsb, 64), outputChannel = 0, latchedSelector = nrpnSelector)
    // Then the output channel keeps the parameter it already held: nothing was emitted to change it
    messages shouldBe empty
    latchedSelector shouldEqual nrpnSelector
  }

  it should "omit the selector when the output channel already holds the parameter selected" in {
    // Given
    val selectors = Table("selector", fineTuningSelector, nrpnSelector)
    forAll(selectors) { selector =>
      // When
      val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(selector,
        receivedValueCc(MidiCc.DataEntryLsb, 5), outputChannel = 0, latchedSelector = selector)
      // Then
      messages shouldEqual Seq(CcMidiMsg(0, MidiCc.DataEntryLsb, 5))
      latchedSelector shouldEqual selector
    }
  }

  it should "render the selector when the output channel holds a different parameter selected" in {
    // When
    val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(fineTuningSelector,
      receivedValueCc(MidiCc.DataEntryMsb, 64), outputChannel = 0, latchedSelector = nrpnSelector)
    // Then
    messages shouldEqual Seq(
      CcMidiMsg(0, MidiCc.RpnLsb, MidiRpn.FineTuningLsb),
      CcMidiMsg(0, MidiCc.RpnMsb, MidiRpn.FineTuningMsb),
      CcMidiMsg(0, MidiCc.DataEntryMsb, 64)
    )
    latchedSelector shouldEqual fineTuningSelector
  }

  it should "render the selector when the output channel was left deselected by an RPN Null" in {
    // Given the state the Tuner's own MCM and Pitch Bend Sensitivity sequences leave behind, both closing with one
    // When
    val (messages, latchedSelector) = MpeMessageRouting.rpnSequence(fineTuningSelector,
      receivedValueCc(MidiCc.DataEntryMsb, 64), outputChannel = 0,
      latchedSelector = RpnSelector.None)
    // Then
    messages shouldEqual Seq(
      CcMidiMsg(0, MidiCc.RpnLsb, MidiRpn.FineTuningLsb),
      CcMidiMsg(0, MidiCc.RpnMsb, MidiRpn.FineTuningMsb),
      CcMidiMsg(0, MidiCc.DataEntryMsb, 64)
    )
    latchedSelector shouldEqual fineTuningSelector
  }
}

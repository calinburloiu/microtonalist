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
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Tests for [[MpeMessageRouting]].
 *
 * == Test Organization ==
 *
 * Two `behavior of` blocks, one per public function: `roleOf` and `route`. The `route` block is the paper's
 * message-handling table ("How the MPE Tuner Handles Common MIDI Messages in MPE Input Mode") expressed as
 * table-driven checks, one `Table` row per cell: the role supplies the column and the message the row. Add a new
 * case by adding a row, not a new test, unless the cell needs a rule the table cannot express.
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
    RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)
  private val pbsSelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

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

  it should "fall back to the Upper Zone when only it is enabled" in {
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

  // The input channel every table row below uses: Member Channel 3 of the Lower Zone, so that a forward on the
  // Zone's Master Channel is visibly different from a forward on the arrival channel. The same message is replayed
  // against all four roles, and both forwarding columns name the role's Zone Master Channel rather than the channel
  // the message carries, which is what makes `route` a function of its arguments alone.
  private val inputChannel: Int = 3

  /** The Master Channel of the Lower Zone that every role below is built from. */
  private val zoneMasterChannel: Int = lower7.masterChannel

  // ---- Channel Voice messages ----

  it should "route the message classes of the paper's table" in {
    // Given
    val verdicts = Table(
      ("description", "message", "member", "master", "nonMpe", "outside"),
      ("Note On",
        NoteOnScMidiMessage(inputChannel, MidiNote.C4, 100),
        Interpret, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Note Off",
        NoteOffScMidiMessage(inputChannel, MidiNote.C4),
        Interpret, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Pitch Bend",
        PitchBendScMidiMessage(inputChannel, 1000),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Channel Pressure",
        ChannelPressureScMidiMessage(inputChannel, 90),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("CC #74",
        CcScMidiMessage(inputChannel, ScMidiCc.MpeSlide, 100),
        Interpret, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Polyphonic Key Pressure",
        PolyPressureScMidiMessage(inputChannel, MidiNote.C4, 80),
        Discard, ForwardOn(zoneMasterChannel), Interpret, Discard),
      ("Program Change",
        ProgramChangeScMidiMessage(inputChannel, 5),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Bank Select MSB",
        CcScMidiMessage(inputChannel, ScMidiCc.BankSelectMsb, 1),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Damper Pedal",
        CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 127),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("All Sound Off (CC #120)",
        CcScMidiMessage(inputChannel, ScMidiCc.AllSoundOff, 0),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Reset All Controllers (CC #121)",
        CcScMidiMessage(inputChannel, ScMidiCc.ResetAllControllers, 0),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Local Control (CC #122)",
        CcScMidiMessage(inputChannel, ScMidiCc.LocalControl, 0),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("All Notes Off (CC #123)",
        CcScMidiMessage(inputChannel, ScMidiCc.AllNotesOff, 0),
        Discard, ForwardOn(zoneMasterChannel), ForwardOn(zoneMasterChannel), Discard),
      ("Omni Mode Off (CC #124)",
        CcScMidiMessage(inputChannel, ScMidiCc.OmniModeOff, 0),
        Discard, Discard, Discard, Discard),
      ("Omni Mode On (CC #125)",
        CcScMidiMessage(inputChannel, ScMidiCc.OmniModeOn, 0),
        Discard, Discard, Discard, Discard),
      ("Mono Mode On (CC #126)",
        CcScMidiMessage(inputChannel, ScMidiCc.MonoModeOn, 1),
        Discard, Discard, Discard, Discard),
      ("Poly Mode On (CC #127)",
        CcScMidiMessage(inputChannel, ScMidiCc.PolyModeOn, 0),
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
    val message = CcScMidiMessage(zoneMasterChannel, ScMidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(masterRole, message, noSelector) shouldEqual ForwardOn(zoneMasterChannel)
  }

  it should "forward an Upper Zone Master Channel message on the Upper Zone Master Channel" in {
    // Given
    val message = CcScMidiMessage(upper7.masterChannel, ScMidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(MpeChannelRole.Master(upper7), message, noSelector) shouldEqual ForwardOn(15)
  }

  it should "redirect a Non-MPE input message to the Upper Zone Master Channel when only it is enabled" in {
    // Given
    val message = CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 127)
    // When / Then
    MpeMessageRouting.route(MpeChannelRole.NonMpeInput(upper7), message, noSelector) shouldEqual ForwardOn(15)
  }

  // ---- Interpreted parameters ----

  it should "interpret an MCM Data Entry MSB on MIDI Channel 1 or 16 whatever the role" in {
    // Given
    val roles = Table("role", memberRole, masterRole, nonMpeRole, outsideRole)
    val channels = Table("channel", 0, 15)
    forAll(channels) { channel =>
      val message = CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, 7)
      forAll(roles) { role =>
        // When / Then
        MpeMessageRouting.route(role, message, mcmSelector) shouldEqual Interpret
      }
    }
  }

  it should "interpret a PBS Data Entry at every role but Outside" in {
    // Given
    val ccNumbers = Table("ccNumber", ScMidiCc.DataEntryMsb, ScMidiCc.DataEntryLsb)
    forAll(ccNumbers) { ccNumber =>
      val message = CcScMidiMessage(inputChannel, ccNumber, 24)
      // When / Then
      MpeMessageRouting.route(memberRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(masterRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(nonMpeRole, message, pbsSelector) shouldEqual Interpret
      MpeMessageRouting.route(outsideRole, message, pbsSelector) shouldEqual Discard
    }
  }

  it should "consume the completing selector CC of an interpreted parameter" in {
    // Given
    val selectors = Table("selector", mcmSelector, pbsSelector)
    val ccNumbers = Table("ccNumber", ScMidiCc.RpnMsb, ScMidiCc.RpnLsb)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        // When / Then
        MpeMessageRouting.route(masterRole, CcScMidiMessage(0, ccNumber, 0), selector) shouldEqual Discard
      }
    }
  }

  // ---- Uninterpreted parameter traffic, until #261 ----
  //
  // These two cases pin what phase 2 deliberately leaves on the ordinary-CC path, so that #261 starts from a state
  // a test asserts rather than one only a comment describes.

  it should "still forward the opening CC of an interpreted parameter's selector pair (TODO #261)" in {
    // Given the mid-sequence states the tracker reports after the first CC of either selector order
    val incompleteSelectors = Table("selector",
      RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.NullLsb),
      RpnSelector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.MpeConfigurationMessageLsb))
    forAll(incompleteSelectors) { selector =>
      // When / Then
      MpeMessageRouting.route(masterRole, CcScMidiMessage(inputChannel, ScMidiCc.RpnMsb, 0), selector) shouldEqual
        ForwardOn(zoneMasterChannel)
    }
  }

  it should "leave uninterpreted RPN and NRPN traffic on the ordinary-CC path (TODO #261)" in {
    // Given a parameter the Tuner does not interpret, selected by either an RPN or an NRPN
    val selectors = Table("selector",
      RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, 1),
      RpnSelector.Nrpn(1, 2))
    val ccNumbers = Table("ccNumber",
      ScMidiCc.RpnMsb, ScMidiCc.RpnLsb, ScMidiCc.NrpnMsb, ScMidiCc.NrpnLsb,
      ScMidiCc.DataEntryMsb, ScMidiCc.DataEntryLsb)
    forAll(selectors) { selector =>
      forAll(ccNumbers) { ccNumber =>
        val message = CcScMidiMessage(inputChannel, ccNumber, 5)
        // When / Then
        MpeMessageRouting.route(memberRole, message, selector) shouldEqual Discard
        MpeMessageRouting.route(masterRole, message, selector) shouldEqual ForwardOn(zoneMasterChannel)
        MpeMessageRouting.route(nonMpeRole, message, selector) shouldEqual ForwardOn(zoneMasterChannel)
        MpeMessageRouting.route(outsideRole, message, selector) shouldEqual Discard
      }
    }
  }
}

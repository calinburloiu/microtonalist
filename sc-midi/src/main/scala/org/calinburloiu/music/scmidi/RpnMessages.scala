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

import org.calinburloiu.music.scmidi.message.{CcScMidiMessage, ScMidiCc, ScMidiRpn}

/**
 * The home of MIDI 1.0's Registered and Non-Registered Parameter message vocabulary: the parameters Microtonalist
 * works with, named, and the renderers that turn a step of the parameter procedure into the Control Change messages
 * carrying it.
 *
 * Any step of that procedure belongs here — selecting a parameter, entering, incrementing or decrementing its value,
 * deselecting it — so that each has a single encoding for the whole application. What it currently provides is the
 * selector step: [[select]] renders the Control Change pair that selects a parameter, and because every sequence
 * Microtonalist emits selects through it, the transmission order of that pair is decided in exactly one place.
 */
object RpnMessages {

  /** Pitch Bend Sensitivity (RPN 00 00), the pitch bend range of a channel in semitones and cents. */
  val PitchBendSensitivitySelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

  /** The MPE Configuration Message (RPN 00 06), which configures an MPE Zone. */
  val MpeConfigurationMessageSelector: RpnSelector =
    RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)

  /**
   * Renders the pair of Control Change messages that select `selector` on `channel`, ahead of the Data Entry, Data
   * Increment or Data Decrement messages that apply a value to it.
   *
   * The selector is emitted LSB (CC #100 for an RPN, CC #98 for an NRPN) before MSB (CC #101 and CC #99). MIDI 1.0
   * mandates no order for the pair — it requires only that the parameter be selected before the Data Entry, and that
   * a receiver wait for both bytes — but every byte-level RPN example it gives is LSB-first, as is RP-053 §2.1.1's
   * MPE Configuration Message format. Either order selects the same parameter on a conformant receiver, so this is a
   * matter of speaking with one voice rather than of correctness on the wire.
   *
   * [[RpnSelector.None]] renders as the Null Function (RPN 7F 7F), the encoding MIDI 1.0 gives to holding no
   * parameter selected: deselecting is a selector pair on the wire like any other, and it is what stops a later
   * stray Data Entry from reaching the parameter this sequence just set. Rendering it here rather than at each call
   * site is what makes the two directions mirror each other — [[ScMidiChannelStateTracker]] reads that same pair
   * back as [[RpnSelector.None]], so every selector survives a round trip through the two.
   *
   * The Null is emitted as an RPN whatever the parameter it closes, an NRPN Null having no separate encoding here.
   * MIDI 1.0 gives the RPN Null the job of cancelling the current ''RPN or NRPN'' selection, and the tracker
   * likewise clears either on it.
   *
   * @param channel  The MIDI 0-based channel number to emit the selector on.
   * @param selector The parameter to select, or [[RpnSelector.None]] to deselect.
   * @return the two selector messages.
   */
  def select(channel: Int, selector: RpnSelector): Seq[CcScMidiMessage] = selector match {
    case RpnSelector.Rpn(msb, lsb) => Seq(
      CcScMidiMessage(channel, ScMidiCc.RpnLsb, lsb),
      CcScMidiMessage(channel, ScMidiCc.RpnMsb, msb))
    case RpnSelector.Nrpn(msb, lsb) => Seq(
      CcScMidiMessage(channel, ScMidiCc.NrpnLsb, lsb),
      CcScMidiMessage(channel, ScMidiCc.NrpnMsb, msb))
    case RpnSelector.None => Seq(
      CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb))
  }
}

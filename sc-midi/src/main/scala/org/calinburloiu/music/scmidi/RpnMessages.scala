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

import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.{CcScMidiMessage, ScMidiCc, ScMidiNrpn, ScMidiRpn}

/**
 * Renders the selector half of MIDI 1.0's Registered and Non-Registered Parameter procedure, and names the parameters
 * Microtonalist selects by name.
 *
 * Every sequence Microtonalist emits selects its parameter through [[select]], so that the transmission order of the
 * selector pair is decided in exactly one place.
 */
object RpnMessages {

  /**
   * The Null Function (RPN 7F 7F), which deselects the current parameter so that a later stray Data Entry cannot
   * change it.
   */
  val NullSelector: RpnSelector = RpnSelector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.NullLsb)

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
   * @param channel  The MIDI channel to emit the selector on.
   * @param selector The parameter to select.
   * @return the two selector messages, or an empty sequence for [[RpnSelector.None]], which selects no parameter and
   *         therefore has no encoding of its own.
   */
  def select(channel: Int, selector: RpnSelector): Seq[CcScMidiMessage] = selector match {
    case RpnSelector.Rpn(msb, lsb) => Seq(
      CcScMidiMessage(channel, ScMidiCc.RpnLsb, lsb),
      CcScMidiMessage(channel, ScMidiCc.RpnMsb, msb))
    case RpnSelector.Nrpn(msb, lsb) => Seq(
      CcScMidiMessage(channel, ScMidiCc.NrpnLsb, lsb),
      CcScMidiMessage(channel, ScMidiCc.NrpnMsb, msb))
    case RpnSelector.None => Seq.empty
  }
}

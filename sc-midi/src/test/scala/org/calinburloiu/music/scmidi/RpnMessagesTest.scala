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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RpnMessagesTest extends AnyFlatSpec with Matchers {

  behavior of "RpnMessages selector constants"

  it should "name the Null Function parameter" in {
    // When / Then
    RpnMessages.NullSelector shouldEqual RpnSelector.Rpn(ScMidiRpn.NullMsb, ScMidiRpn.NullLsb)
  }

  it should "name the Pitch Bend Sensitivity parameter" in {
    // When / Then
    RpnMessages.PitchBendSensitivitySelector shouldEqual
      RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)
  }

  it should "name the MPE Configuration Message parameter" in {
    // When / Then
    RpnMessages.MpeConfigurationMessageSelector shouldEqual
      RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)
  }

  behavior of "RpnMessages.select"

  it should "emit a Registered Parameter selector LSB before MSB" in {
    // Given
    val selector = RpnSelector.Rpn(msb = 0x12, lsb = 0x34)

    // When
    val messages = RpnMessages.select(channel = 5, selector)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(5, ScMidiCc.RpnLsb, 0x34),
      CcScMidiMessage(5, ScMidiCc.RpnMsb, 0x12)
    )
  }

  it should "emit a Non-Registered Parameter selector LSB before MSB" in {
    // Given
    val selector = RpnSelector.Nrpn(msb = 0x12, lsb = 0x34)

    // When
    val messages = RpnMessages.select(channel = 5, selector)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(5, ScMidiCc.NrpnLsb, 0x34),
      CcScMidiMessage(5, ScMidiCc.NrpnMsb, 0x12)
    )
  }

  it should "emit the Null Function as a Registered Parameter selector" in {
    // When
    val messages = RpnMessages.select(channel = 3, RpnMessages.NullSelector)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(3, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      CcScMidiMessage(3, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb)
    )
  }

  it should "emit nothing when no parameter is selected" in {
    // When / Then
    RpnMessages.select(channel = 5, RpnSelector.None) shouldBe empty
  }

  it should "emit the Non-Registered Null Function selector when it is the one selected" in {
    // Given
    val selector = RpnSelector.Nrpn(ScMidiNrpn.NullMsb, ScMidiNrpn.NullLsb)

    // When
    val messages = RpnMessages.select(channel = 7, selector)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(7, ScMidiCc.NrpnLsb, ScMidiNrpn.NullLsb),
      CcScMidiMessage(7, ScMidiCc.NrpnMsb, ScMidiNrpn.NullMsb)
    )
  }
}

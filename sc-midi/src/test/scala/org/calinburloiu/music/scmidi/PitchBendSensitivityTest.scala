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

import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcScMidiMessage, ScMidiCc, ScMidiRpn}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PitchBendSensitivityTest extends AnyFlatSpec with Matchers {

  behavior of "PitchBendSensitivity"

  it should "compute the total range in cents" in {
    // Given / When / Then
    PitchBendSensitivity(2).totalCents shouldEqual 200
    PitchBendSensitivity(3, 37).totalCents shouldEqual 337
  }

  it should "reject values outside the 7-bit MIDI range" in {
    // When / Then
    an[IllegalArgumentException] should be thrownBy PitchBendSensitivity(128)
    an[IllegalArgumentException] should be thrownBy PitchBendSensitivity(2, -1)
  }

  behavior of "PitchBendSensitivityMessages"

  it should "emit the RPN sequence with the selector LSB before its MSB, closed by an RPN Null" in {
    // Given
    val pbs = PitchBendSensitivity(3, 37)

    // When
    val messages = PitchBendSensitivityMessages.create(channel = 5, pbs).map(_.asScala)

    // Then
    messages shouldEqual Seq(
      CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(5, ScMidiCc.DataEntryMsb, 3),
      CcScMidiMessage(5, ScMidiCc.DataEntryLsb, 37),
      CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb)
    )
  }
}

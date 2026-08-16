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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RpnMessagesTest extends AnyFlatSpec with Matchers {

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

  it should "emit the Null Function when no parameter is selected" in {
    // When
    val messages = RpnMessages.select(channel = 3, RpnSelector.None)

    // Then — holding no parameter selected is not the absence of a selector on the wire; it is the Null Function,
    // RPN 7F 7F, which is what deselects at the receiver
    messages shouldEqual Seq(
      CcScMidiMessage(3, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      CcScMidiMessage(3, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb)
    )
  }

  it should "round-trip every selector through the tracker that reads it back" in {
    // Given
    val channel = 9
    val tracker = ScMidiChannelStateTracker()
    val selectors = Seq(
      RpnSelector.Rpn(msb = 0x12, lsb = 0x34),
      RpnSelector.Nrpn(msb = 0x56, lsb = 0x78),
      // A parameter whose halves are the Null value without being the Null pair, the case #267 turned on.
      RpnSelector.Rpn(msb = ScMidiRpn.NullMsb, lsb = 0x00),
      RpnSelector.None)

    // When / Then — what `select` renders is exactly what the tracker reads back as selected
    for (selector <- selectors) {
      RpnMessages.select(channel, selector).foreach(tracker.send(_))
      tracker.rpnSelector(channel) shouldEqual selector
    }
  }
}

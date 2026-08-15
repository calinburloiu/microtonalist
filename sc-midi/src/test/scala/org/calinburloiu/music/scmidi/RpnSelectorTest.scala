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

import org.calinburloiu.music.scmidi.message.{ScMidiNrpn, ScMidiRpn}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RpnSelectorTest extends AnyFlatSpec with Matchers {

  behavior of "RpnSelector.isComplete"

  it should "be false when no parameter is selected" in {
    // When / Then
    RpnSelector.None.isComplete shouldBe false
  }

  it should "be true when both halves of an RPN or NRPN have been received" in {
    // When / Then
    RpnSelector.Rpn(Some(ScMidiRpn.FineTuningMsb), Some(ScMidiRpn.FineTuningLsb)).isComplete shouldBe true
    RpnSelector.Nrpn(Some(12), Some(34)).isComplete shouldBe true
  }

  it should "be true for a parameter whose MSB or LSB is the Null value" in {
    // Given — 127 is a legitimate half of a parameter number, telling nothing about whether the other has arrived
    // When / Then
    RpnSelector.Rpn(Some(ScMidiRpn.NullMsb), Some(0)).isComplete shouldBe true
    RpnSelector.Rpn(Some(0), Some(ScMidiRpn.NullLsb)).isComplete shouldBe true
    RpnSelector.Nrpn(Some(ScMidiNrpn.NullMsb), Some(34)).isComplete shouldBe true
    RpnSelector.Nrpn(Some(12), Some(ScMidiNrpn.NullLsb)).isComplete shouldBe true
  }

  it should "be false while either half of an RPN or NRPN is still unset" in {
    // When / Then
    RpnSelector.Rpn(Some(ScMidiRpn.FineTuningMsb), None).isComplete shouldBe false
    RpnSelector.Rpn(None, Some(ScMidiRpn.FineTuningLsb)).isComplete shouldBe false
    RpnSelector.Nrpn(Some(12), None).isComplete shouldBe false
    RpnSelector.Nrpn(None, Some(34)).isComplete shouldBe false
    RpnSelector.Rpn(None, None).isComplete shouldBe false
    RpnSelector.Nrpn(None, None).isComplete shouldBe false
  }
}

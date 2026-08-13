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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Tests for [[MpeMessageRouting]].
 *
 * == Test Organization ==
 *
 * One `behavior of` block for the public function `roleOf`.
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
}

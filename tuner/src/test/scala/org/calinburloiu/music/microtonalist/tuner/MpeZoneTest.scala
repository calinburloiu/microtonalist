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

import org.calinburloiu.music.scmidi.PitchBendSensitivity
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MpeZoneTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  behavior of "MpeZone"

  it should "create a Lower Zone with 15 member channels" in {
    val zone = MpeZone(MpeZoneType.Lower, 15)
    zone.masterChannel shouldBe 0
    zone.memberChannels shouldBe (1 to 15)
    zone.isEnabled shouldBe true
    zone.pitchClassGroupSize shouldBe 12
    zone.expressionGroupSize shouldBe 3
  }

  it should "create a Lower Zone with 7 member channels" in {
    val zone = MpeZone(MpeZoneType.Lower, 7)
    zone.masterChannel shouldBe 0
    zone.memberChannels shouldBe (1 to 7)
    zone.pitchClassGroupSize shouldBe 5
    zone.expressionGroupSize shouldBe 2
  }

  it should "create an Upper Zone with 7 member channels" in {
    val zone = MpeZone(MpeZoneType.Upper, 7)
    zone.masterChannel shouldBe 15
    zone.memberChannels shouldBe (8 to 14)
    zone.pitchClassGroupSize shouldBe 5
    zone.expressionGroupSize shouldBe 2
  }

  it should "create a Lower Zone with 3 member channels" in {
    val zone = MpeZone(MpeZoneType.Lower, 3)
    zone.pitchClassGroupSize shouldBe 1
    zone.expressionGroupSize shouldBe 2
  }

  it should "create a Lower Zone with 2 member channels" in {
    val zone = MpeZone(MpeZoneType.Lower, 2)
    zone.pitchClassGroupSize shouldBe 1
    zone.expressionGroupSize shouldBe 1
  }

  it should "create a Lower Zone with 1 member channel" in {
    val zone = MpeZone(MpeZoneType.Lower, 1)
    zone.pitchClassGroupSize shouldBe 1
    zone.expressionGroupSize shouldBe 0
  }

  it should "create a disabled Lower Zone with 0 member channels" in {
    val zone = MpeZone(MpeZoneType.Lower, 0)
    zone.isEnabled shouldBe false
    zone.memberChannels shouldBe empty
  }

  it should "create a disabled Upper Zone with 0 member channels" in {
    val zone = MpeZone(MpeZoneType.Upper, 0)
    zone.isEnabled shouldBe false
    zone.memberChannels shouldBe empty
  }

  it should "not overlap channels for Lower 7 + Upper 7" in {
    val lower = MpeZone(MpeZoneType.Lower, 7)
    val upper = MpeZone(MpeZoneType.Upper, 7)
    lower.memberChannels shouldBe (1 to 7)
    upper.memberChannels shouldBe (8 to 14)
    MpeZones.wouldOverlap(lower, upper) shouldBe false
  }

  it should "match Appendix A table for all n from 1 to 15" in {
    //@formatter:off
    val table = Table(
      ("n", "expectedPitchClassGroupSize", "expectedExpressionGroupSize"),
      (1,   1,                             0),
      (2,   1,                             1),
      (3,   1,                             2),
      (4,   2,                             2),
      (5,   3,                             2),
      (6,   4,                             2),
      (7,   5,                             2),
      (8,   6,                             2),
      (9,   7,                             2),
      (10,  7,                             3),
      (11,  8,                             3),
      (12,  9,                             3),
      (13,  10,                            3),
      (14,  11,                            3),
      (15,  12,                            3)
    )
    //@formatter:on

    forAll(table) { (n, expectedA, expectedB) =>
      val zone = MpeZone(MpeZoneType.Lower, n)
      zone.pitchClassGroupSize shouldBe expectedA
      zone.expressionGroupSize shouldBe expectedB
    }
  }

  // --- MpeZones ---

  behavior of "MpeZones"

  it should "preserve both zones when there is no overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    zones.lower.memberCount shouldBe 7
    zones.upper.memberCount shouldBe 7
  }

  it should "shrink lower zone on construction when zones overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 10), MpeZone(MpeZoneType.Upper, 10))
    zones.lower.memberCount shouldBe 4
    zones.upper.memberCount shouldBe 10
  }

  it should "disable lower zone on construction when upper zone takes all channels" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 14), MpeZone(MpeZoneType.Upper, 15))
    zones.lower.memberCount shouldBe 0
    zones.lower.isEnabled shouldBe false
    zones.upper.memberCount shouldBe 15
  }

  it should "preserve pitch bend sensitivities of lower zone when shrunk on construction" in {
    val masterPbs = PitchBendSensitivity(2)
    val memberPbs = PitchBendSensitivity(24)
    val lower = MpeZone(MpeZoneType.Lower, 10,
      masterPitchBendSensitivity = masterPbs, memberPitchBendSensitivity = memberPbs)
    val upper = MpeZone(MpeZoneType.Upper, 10)
    val zones = MpeZones(lower, upper)
    zones.lower.memberCount shouldBe 4
    zones.lower.masterPitchBendSensitivity shouldBe masterPbs
    zones.lower.memberPitchBendSensitivity shouldBe memberPbs
  }

  it should "fail to construct with wrong zone types" in {
    an[IllegalArgumentException] should be thrownBy {
      MpeZones(MpeZone(MpeZoneType.Upper, 7), MpeZone(MpeZoneType.Upper, 7))
    }
    an[IllegalArgumentException] should be thrownBy {
      MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Lower, 7))
    }
  }

  behavior of "MpeZones.wouldOverlap"

  it should "return false when zones do not overlap" in {
    MpeZones.wouldOverlap(MpeZone(MpeZoneType.Lower, 6), MpeZone(MpeZoneType.Upper, 7)) shouldBe false
  }

  it should "return false when exactly 14 member channels are used" in {
    MpeZones.wouldOverlap(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7)) shouldBe false
  }

  it should "return true when combined member counts exceed 14" in {
    MpeZones.wouldOverlap(MpeZone(MpeZoneType.Lower, 8), MpeZone(MpeZoneType.Upper, 7)) shouldBe true
  }

  it should "return false when either zone is disabled" in {
    MpeZones.wouldOverlap(MpeZone(MpeZoneType.Lower, 15), MpeZone(MpeZoneType.Upper, 0)) shouldBe false
    MpeZones.wouldOverlap(MpeZone(MpeZoneType.Lower, 0), MpeZone(MpeZoneType.Upper, 15)) shouldBe false
  }

  it should "fail with wrong zone types" in {
    an[IllegalArgumentException] should be thrownBy {
      MpeZones.wouldOverlap(MpeZone(MpeZoneType.Upper, 7), MpeZone(MpeZoneType.Lower, 7))
    }
  }

  behavior of "MpeZones.update"

  it should "replace lower zone without affecting upper when no overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    val updated = zones.update(MpeZone(MpeZoneType.Lower, 5))
    updated.lower.memberCount shouldBe 5
    updated.upper.memberCount shouldBe 7
  }

  it should "replace upper zone without affecting lower when no overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    val updated = zones.update(MpeZone(MpeZoneType.Upper, 5))
    updated.lower.memberCount shouldBe 7
    updated.upper.memberCount shouldBe 5
  }

  it should "shrink upper zone when updating lower causes overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    val updated = zones.update(MpeZone(MpeZoneType.Lower, 10))
    updated.lower.memberCount shouldBe 10
    updated.upper.memberCount shouldBe 4
  }

  it should "shrink lower zone when updating upper causes overlap" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    val updated = zones.update(MpeZone(MpeZoneType.Upper, 10))
    updated.lower.memberCount shouldBe 4
    updated.upper.memberCount shouldBe 10
  }

  it should "preserve pitch bend sensitivities of other zone when shrunk" in {
    val customPbs = PitchBendSensitivity(24)
    val zones = MpeZones(
      MpeZone(MpeZoneType.Lower, 7, memberPitchBendSensitivity = customPbs),
      MpeZone(MpeZoneType.Upper, 7)
    )
    val updated = zones.update(MpeZone(MpeZoneType.Upper, 10))
    updated.lower.memberCount shouldBe 4
    updated.lower.memberPitchBendSensitivity shouldBe customPbs
  }

  it should "disable other zone when updated zone takes all channels" in {
    val zones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
    val updated = zones.update(MpeZone(MpeZoneType.Upper, 15))
    updated.lower.memberCount shouldBe 0
    updated.lower.isEnabled shouldBe false
    updated.upper.memberCount shouldBe 15
  }

  it should "follow the full example flow from the requirements" in {
    // Start: lower=15, upper=0
    val initial = MpeZones.DefaultZones
    initial.lower.memberCount shouldBe 15
    initial.upper.memberCount shouldBe 0

    // Update lower to 14 members
    val step1 = initial.update(MpeZone(MpeZoneType.Lower, 14))
    step1.lower.memberCount shouldBe 14
    step1.upper.memberCount shouldBe 0

    // Update upper to 7 members -> lower shrinks to 7
    val step2 = step1.update(MpeZone(MpeZoneType.Upper, 7))
    step2.lower.memberCount shouldBe 7
    step2.upper.memberCount shouldBe 7

    // Update lower to 6 members -> upper unchanged (no overlap)
    val step3 = step2.update(MpeZone(MpeZoneType.Lower, 6))
    step3.lower.memberCount shouldBe 6
    step3.upper.memberCount shouldBe 7
  }
}

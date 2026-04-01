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
    lower.memberChannels.toSet.intersect(upper.memberChannels.toSet) shouldBe empty
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
}

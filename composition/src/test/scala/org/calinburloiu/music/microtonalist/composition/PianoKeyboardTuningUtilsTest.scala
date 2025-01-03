/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.composition

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PianoKeyboardTuningUtilsTest extends AnyFlatSpec with Matchers {

  import org.calinburloiu.music.microtonalist.composition.PianoKeyboardTuningUtils._

  private val tuning = OctaveTuning("foo", 0.0, 12.0, 4.0, 16.0, -14.0, -2.0, -17.0, 2.0, -16.0, 14.0, -35.0, -12.0)

  "note names implicit methods" should "return the correct deviations" in {
    //@formatter:off
    // White keys and flats
    tuning.c       shouldEqual 0.0
    tuning.cSharp  shouldEqual 12.0
    tuning.d       shouldEqual 4.0
    tuning.dSharp  shouldEqual 16.0
    tuning.e       shouldEqual -14.0
    tuning.f       shouldEqual -2.0
    tuning.fSharp  shouldEqual -17.0
    tuning.g       shouldEqual 2.0
    tuning.gSharp  shouldEqual -16.0
    tuning.a       shouldEqual 14.0
    tuning.aSharp  shouldEqual -35.0
    tuning.b       shouldEqual -12.0
    //@formatter:on

    // Enharmonic equivalences for black keys
    tuning.cSharp shouldEqual tuning.dFlat
    tuning.dSharp shouldEqual tuning.eFlat
    tuning.fSharp shouldEqual tuning.gFlat
    tuning.gSharp shouldEqual tuning.aFlat
    tuning.aSharp shouldEqual tuning.bFlat
  }

  it should "throw IllegalArgumentException when a tuning does not have 12 deviation value" in {
    // < 12
    assertThrows[IllegalArgumentException] {
      val tuning1 = OctaveTuning("bar", Seq(1.0, 2.0))
      tuning1.cSharp
    }

    // > 12
    assertThrows[IllegalArgumentException] {
      val tuning2 = OctaveTuning("bar",
        Seq(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0))
      tuning2.cSharp
    }
  }
}

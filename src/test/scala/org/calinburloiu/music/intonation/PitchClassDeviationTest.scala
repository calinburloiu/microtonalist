/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.intonation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PitchClassDeviationTest extends AnyFlatSpec with Matchers {
  "PitchClassDeviation" should "should provide implicit conversions" in {
    withClue("fromPitchClass") {
      val pitchClassDeviation: PitchClassDeviation = PitchClass.B
      pitchClassDeviation shouldEqual PitchClassDeviation(PitchClass.B, 0)
    }
    withClue("toPitchClass") {
      val pitchClass: PitchClass = PitchClassDeviation(PitchClass.F, -33.33)
      pitchClass shouldEqual PitchClass.F
    }
    withClue("toInt") {
      val n: Int = PitchClassDeviation(PitchClass.A, 50.0)
      n shouldEqual 9
    }
  }

  "isOverflowing" should "return false if deviation absolute value is less than 100" in {
    PitchClassDeviation(PitchClass.EFlat, 0).isOverflowing shouldBe false
    PitchClassDeviation(PitchClass.EFlat, 34.2).isOverflowing shouldBe false
    PitchClassDeviation(PitchClass.EFlat, 99.99).isOverflowing shouldBe false
    PitchClassDeviation(PitchClass.EFlat, -73.8).isOverflowing shouldBe false
    PitchClassDeviation(PitchClass.EFlat, -99.99).isOverflowing shouldBe false
  }

  it should "return true if deviation absolute value is 100 or more" in {
    PitchClassDeviation(PitchClass.EFlat, 100.0).isOverflowing shouldBe true
    PitchClassDeviation(PitchClass.EFlat, -100.0).isOverflowing shouldBe true
    PitchClassDeviation(PitchClass.EFlat, 103.2).isOverflowing shouldBe true
    PitchClassDeviation(PitchClass.EFlat, -157.9).isOverflowing shouldBe true
  }
}

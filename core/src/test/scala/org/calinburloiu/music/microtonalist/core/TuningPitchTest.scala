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

package org.calinburloiu.music.microtonalist.core

import org.calinburloiu.music.scmidi.PitchClass
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningPitchTest extends AnyFlatSpec with Matchers {
  "TuningPitch" should "should provide implicit conversions" in {
    withClue("fromPitchClass") {
      val tuningPitch: TuningPitch = PitchClass.B
      tuningPitch shouldEqual TuningPitch(PitchClass.B, 0)
    }
    withClue("toPitchClass") {
      val pitchClass: PitchClass = TuningPitch(PitchClass.F, -33.33)
      pitchClass shouldEqual PitchClass.F
    }
    withClue("toInt") {
      val n: Int = TuningPitch(PitchClass.A, 50.0)
      n shouldEqual 9
    }
  }

  "isOverflowing" should "return false if deviation absolute value is less than 100" in {
    TuningPitch(PitchClass.EFlat, 0).isOverflowing shouldBe false
    TuningPitch(PitchClass.EFlat, 34.2).isOverflowing shouldBe false
    TuningPitch(PitchClass.EFlat, 99.99).isOverflowing shouldBe false
    TuningPitch(PitchClass.EFlat, -73.8).isOverflowing shouldBe false
    TuningPitch(PitchClass.EFlat, -99.99).isOverflowing shouldBe false
  }

  it should "return true if deviation absolute value is 100 or more" in {
    TuningPitch(PitchClass.EFlat, 100.0).isOverflowing shouldBe true
    TuningPitch(PitchClass.EFlat, -100.0).isOverflowing shouldBe true
    TuningPitch(PitchClass.EFlat, 103.2).isOverflowing shouldBe true
    TuningPitch(PitchClass.EFlat, -157.9).isOverflowing shouldBe true
  }

  "isQuarterTone" should "tell if a TuningPitch almost between two adjacent TuningPitches with no deviation" in {
    TuningPitch(PitchClass.B, 0).isQuarterTone() shouldBe false
    TuningPitch(PitchClass.C, -30).isQuarterTone() shouldBe false
    TuningPitch(PitchClass.C, 25).isQuarterTone() shouldBe false
    TuningPitch(PitchClass.C, 49).isQuarterTone(0.5) shouldBe false
    TuningPitch(PitchClass.AFlat, 59).isQuarterTone(8.0) shouldBe false

    TuningPitch(PitchClass.D, -50).isQuarterTone() shouldBe true
    TuningPitch(PitchClass.E, 50).isQuarterTone() shouldBe true
    TuningPitch(PitchClass.FSharp, -40).isQuarterTone() shouldBe true
    TuningPitch(PitchClass.AFlat, 59).isQuarterTone() shouldBe true
  }
}

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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{CentsScale, RatiosScale}
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ManualTuningMapperTest extends AnyFlatSpec with Matchers {
  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  behavior of "mapScale"

  it should "only allow valid mappings" in {
    assertThrows[IllegalArgumentException] {
      // Not 12 mapping values
      ManualTuningMapper(KeyboardMapping(Seq(Some(1), None)))
    }

    assertThrows[IllegalArgumentException] {
      // Given
      val keyboardMapping = KeyboardMapping(c = Some(6), d = Some(0))
      // Then: Negative scale degrees
      ManualTuningMapper(keyboardMapping.updated(PitchClass.C, Some(-6)))
    }
  }

  it should "map a scale with just quarter tones to custom keys" in {
    // Given
    val scale = RatiosScale(1 /: 1, 13 /: 12, 32 /: 27, 4 /: 3, 3 /: 2, 13 /: 8, 16 /: 9)
    val keyboardMapping = KeyboardMapping(c = Some(6), d = Some(0), e = Some(1), f = Some(2), g = Some(3),
      a = Some(4), aSharpOrBFlat = Some(5))
    val mapper = ManualTuningMapper(keyboardMapping)
    val tuningReference = ConcertPitchTuningReference(2 /: 3, MidiNote.D4)

    // When
    val partialTuning = mapper.mapScale(scale, tuningReference)

    // Then
    partialTuning.completedCount shouldEqual 7
    partialTuning.d should contain(-1.95)
    partialTuning.e should contain(-63.38)
    partialTuning.f should contain(-7.82)
    partialTuning.g should contain(-3.91)
    partialTuning.a should contain(0.0)
    partialTuning.bFlat should contain(38.57)
    partialTuning.c should contain(-5.87)
  }

  it should "map a large scale that can't be automatically mapped" in {
    // Given
    val scale = RatiosScale(
      1 /: 1, 81 /: 80, 10 /: 9, 9 /: 8, 5 /: 4, 81 /: 64, 4 /: 3, 27 /: 20, 40 /: 27, 3 /: 2, 128 /: 81, 8 /: 5,
      15 /: 8, 243 /: 128)
    val keyboardMapping = KeyboardMapping(c = Some(0), d = Some(3), e = Some(4), f = Some(6), g = Some(9),
      gSharpOrAFlat = Some(11), b = Some(12))
    val mapper = ManualTuningMapper(keyboardMapping)
    val tuningReference = StandardTuningReference(PitchClass.C)

    // When
    val partialTuning = mapper.mapScale(scale, tuningReference)

    // Then
    partialTuning.completedCount shouldEqual 7
    partialTuning.c should contain(0.0)
    partialTuning.d should contain(3.91)
    partialTuning.e should contain(-13.69)
    partialTuning.f should contain(-1.96)
    partialTuning.g should contain(1.96)
    partialTuning.aFlat should contain(13.69)
    partialTuning.b should contain(-11.73)
  }

  it should "fail to map a scale if a deviation overflows" in {
    // Given
    val scale = CentsScale(0.0, 233.33, 400.0, 500.0)
    val mapping = KeyboardMapping(c = Some(0), cSharpOrDFlat = Some(1), e = Some(2), f = Some(3))
    val mapper = ManualTuningMapper(mapping)
    val tuningReference = StandardTuningReference(PitchClass.C)
    // Then
    assertThrows[TuningMapperOverflowException] {
      mapper.mapScale(scale, tuningReference)
    }
  }

  it should "fail to map scale if the mapping scale pitches indexes are out of bounds" in {
    // Given
    val scale = CentsScale(0.0, 200.0, 383.33, 500.0)
    val mapping = KeyboardMapping(c = Some(0), g = Some(4))
    val mapper = ManualTuningMapper(mapping)
    val tuningReference = StandardTuningReference(PitchClass.C)
    // Then
    assertThrows[IllegalArgumentException] {
      mapper.mapScale(scale, tuningReference)
    }
  }
}

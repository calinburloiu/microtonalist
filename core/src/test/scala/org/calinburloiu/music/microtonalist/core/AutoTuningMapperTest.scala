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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class AutoTuningMapperTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  import org.calinburloiu.music.microtonalist.core.PianoKeyboardTuningUtils._

  private val cTuningRef = StandardTuningRef(PitchClass.C)

  private val quarterToneTolerance = 5
  private val autoTuningMapperWithLowQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = true,
    quarterToneTolerance)
  private val autoTuningMapperWithHighQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = false,
    quarterToneTolerance)

  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  behavior of "mapScale"

  it should "map a just major scale with a custom tuning reference to a PartialTuning" in {
    val major = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
    val major2 = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (27, 16), (15, 8), (2, 1))
    // The "major" scale will be tuned starting from C on the piano. A4 is kept at 440 Hz, but the 5/3 note mapped to
    // A is considered an A flattened by a synthonic comma, so the A on the piano will not have 440 Hz, but less.
    // The 27/16 note from "major2", also mapped to A is considered natural and have the 440 Hz concert pitch.
    val tuningRef = ConcertPitchTuningRef(32 /: 27, MidiNote(PitchClass.C, 5))
    val majorTuningWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(major, tuningRef)
    val major2TuningWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(major2, tuningRef)

    majorTuningWithLowQuarterTones.completedCount shouldEqual 7
    majorTuningWithLowQuarterTones.c should contain(-5.87)
    majorTuningWithLowQuarterTones.d should contain(-1.96)
    majorTuningWithLowQuarterTones.e should contain(-19.56)
    majorTuningWithLowQuarterTones.f should contain(-7.83)
    majorTuningWithLowQuarterTones.g should contain(-3.91)
    majorTuningWithLowQuarterTones.a should contain(-21.51)
    majorTuningWithLowQuarterTones.b should contain(-17.60)

    major2TuningWithLowQuarterTones.completedCount shouldEqual 7
    major2TuningWithLowQuarterTones.a should contain(0.0)

    // major and major2 only differ in A by a synthonic comma
    (0 until 12).filterNot(_ == 9).foreach { i =>
      majorTuningWithLowQuarterTones(i) == major2TuningWithLowQuarterTones(i)
    }

    majorTuningWithLowQuarterTones.cSharp should be(empty)
    majorTuningWithLowQuarterTones.dSharp should be(empty)
    majorTuningWithLowQuarterTones.fSharp should be(empty)
    majorTuningWithLowQuarterTones.gSharp should be(empty)
    majorTuningWithLowQuarterTones.aSharp should be(empty)

    val majorTuningWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(major, tuningRef)
    val major2TuningWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(major2, tuningRef)
    majorTuningWithHighQuarterTones.completedCount shouldEqual 7
    major2TuningWithHighQuarterTones.completedCount shouldEqual 7
    majorTuningWithLowQuarterTones.almostEquals(majorTuningWithHighQuarterTones, testTolerance) shouldBe true
    major2TuningWithLowQuarterTones.almostEquals(major2TuningWithHighQuarterTones, testTolerance) shouldBe true
  }

  it should "transpose a scale before mapping it" in {
    // Given
    val maj4 = EdoScale(72, (0, 0), (2, 0), (4, -1), (5, 0))

    // When
    var result = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef)
    // Then
    result.completedCount shouldEqual 4
    result.c should contain(0.0)
    result.d should contain(0.0)
    result.e should contain(-16.67)
    result.f should contain(0.0)

    // When
    result = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef, EdoInterval(72, (5, 0)))
    // Then
    result.completedCount shouldEqual 4
    result.f should contain(0.0)
    result.g should contain(0.0)
    result.a should contain(-16.67)
    result.bFlat should contain(0.0)
  }

  it should "prepend tuning name with the tuning base pitch class when the scale has a unison" in {
    // Given
    val maj4 = RatiosScale("maj-4", 1 /: 1, 9 /: 8, 5 /: 4, 4 /: 3)

    // When
    var tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef)
    // Then
    tuning.name shouldEqual "C maj-4"

    // When
    tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef, 3 /: 2)
    // Then
    tuning.name shouldEqual "G maj-4"

    // When
    tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef, 6 /: 5)
    // Then
    tuning.name shouldEqual "D♯/E♭ maj-4"
  }

  it should "not prepend tuning name with the tuning base pitch class when the scale does not have a unison" in {
    // Given
    val maj4 = RatiosScale("maj-4", 9 /: 8, 5 /: 4, 4 /: 3)

    // When
    var tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef)
    // Then
    tuning.name shouldEqual "maj-4"

    // When
    tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj4, cTuningRef, 3 /: 2)
    tuning.name shouldEqual "maj-4"
  }

  it should "map scales with negative intervals" in {
    val maj5 = RatiosScale("maj-5", (15, 16), (1, 1), (9, 8), (5, 4), (4, 3), (3, 2))
    val tuningRef = ConcertPitchTuningRef(32 /: 27, MidiNote(PitchClass.C, 5))
    val tuning = autoTuningMapperWithHighQuarterTones.mapScale(maj5, tuningRef)

    tuning.name shouldEqual "C maj-5"
    tuning.c should contain(-5.87)
    tuning.d should contain(-1.96)
    tuning.e should contain(-19.56)
    tuning.f should contain(-7.83)
    tuning.g should contain(-3.91)
    tuning.a shouldBe empty
    tuning.b should contain(-17.60)
  }

  it should "fail to map a scale with conflicting pitches when the conflicts can't be avoided" in {
    // 5/4 and 81/64 are concurrent on E
    val concurrency = RatiosScale((1, 1), (9, 8), (5, 4), (81, 64), (4, 3))

    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithLowQuarterTones.mapScale(concurrency, cTuningRef))
    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithHighQuarterTones.mapScale(concurrency, cTuningRef))
  }

  it should "map a scale with concurrent pitches on the same tuning pitch class, if " +
    "those concurrent pitches are equivalent (have the same normalized interval)" in {
    val octaveRedundancy = RatiosScale((1, 1), (5, 4), (3, 2), (7, 4), (2, 1), (5, 2), (3, 1))

    // Note: Here we also get a precision error that is ignored by the tolerance
    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(octaveRedundancy, cTuningRef)
    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(octaveRedundancy, cTuningRef)

    resultWithHighQuarterTones.almostEquals(resultWithLowQuarterTones, testTolerance) shouldBe true

    resultWithHighQuarterTones.c should contain(0.0)
    resultWithHighQuarterTones.e should contain(-13.69)
    resultWithHighQuarterTones.g should contain(1.96)
    resultWithHighQuarterTones.bFlat should contain(-31.18)

    resultWithHighQuarterTones.size shouldEqual 12
    resultWithHighQuarterTones.count(_.nonEmpty) shouldEqual 4
  }

  it should "map a scale without unison or octave to a tuning without the base pitch class" in {
    val tetrachord = RatiosScale("maj-4", (9, 8), (5, 4), (4, 3))

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(tetrachord, cTuningRef)
    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(tetrachord, cTuningRef)
    resultWithLowQuarterTones shouldEqual resultWithHighQuarterTones
    resultWithLowQuarterTones.name shouldEqual "maj-4"
    resultWithHighQuarterTones.name shouldEqual "maj-4"

    resultWithLowQuarterTones.head should be(empty)
  }

  it should "map a tetrachord with quarter tones differently based on the shouldMapQuarterTonesLow parameter" in {
    val dTuningRef: TuningRef = StandardTuningRef(PitchClass.D)
    val bayatiTetrachord = CentsScale(0.0, 150.0, 300.0, 500.0)

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(bayatiTetrachord, dTuningRef)

    resultWithLowQuarterTones.d should contain(0.0)
    resultWithLowQuarterTones.eFlat should contain(50.0)
    resultWithLowQuarterTones.e should be(empty)
    resultWithLowQuarterTones.f should contain(0.0)
    resultWithLowQuarterTones.g should contain(0.0)
    resultWithLowQuarterTones.a should be(empty)

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(bayatiTetrachord, dTuningRef)

    resultWithHighQuarterTones.d should contain(0.0)
    resultWithHighQuarterTones.eFlat should be(empty)
    resultWithHighQuarterTones.e should contain(-50.0)
    resultWithHighQuarterTones.f should contain(0.0)
    resultWithHighQuarterTones.g should contain(0.0)
    resultWithHighQuarterTones.a should be(empty)
  }

  it should "map quarter tones by taking quarter-tone tolerance into account" in {
    val scale = CentsScale(145.1, 344.3, 453.4, 856.9)

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(scale, cTuningRef)
    resultWithLowQuarterTones.dFlat should contain(45.1)
    resultWithLowQuarterTones.eFlat should contain(44.3)
    resultWithLowQuarterTones.e should contain(53.4)
    resultWithLowQuarterTones.a should contain(-43.1)

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(scale, cTuningRef)
    resultWithHighQuarterTones.d should contain(-54.9)
    resultWithHighQuarterTones.eFlat should contain(44.3)
    resultWithHighQuarterTones.f should contain(-46.6)
    resultWithHighQuarterTones.a should contain(-43.1)
  }

  it should "avoid conflicts with quarter-tones when mapping them to opposite direction is possible" in {
    val scale = CentsScale(0.0, 150.0, 183.33, 300.0, 500.0, 616.67, 650)

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(scale, cTuningRef)
    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(scale, cTuningRef)
    for (result <- Seq(resultWithLowQuarterTones, resultWithHighQuarterTones)) {
      result.dFlat should contain(50.0)
      result.d should contain(-16.67)
      result.gFlat should contain(16.67)
      result.g should contain(-50.0)
    }
  }

  it should "fail when conflicts with quarter-tones can't be avoided by mapping them to opposite direction" in {
    val concurrency = CentsScale(0.0, 116.67, 150.0, 183.33, 300.0, 500.0)

    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithLowQuarterTones.mapScale(concurrency, cTuningRef))
    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithHighQuarterTones.mapScale(concurrency, cTuningRef))
  }

  private val karcigarWithSoftChromatic = EdoScale(72, (0, 0), (2, -3), (3, 0), (5, 0), (6, 3), (9, -1), (10, 0))
  private val karcigarWithSoftChromatic2 = EdoScale(72, (0, 0), (2, -3), (3, 0), (5, 0), (6, 1), (9, -3), (10, 0))
  private val karcigarWithPseudoChromatic = EdoScale(72, (0, 0), (2, -3), (3, 0), (5, 0), (6, 3), (9, -3), (10, 0))

  it should "map a scale with softChromaticGenusMapping set to off" in {
    // Given
    val mapperWithLowQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = true,
      softChromaticGenusMapping = SoftChromaticGenusMapping.Off)
    val mapperWithHighQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = false,
      softChromaticGenusMapping = SoftChromaticGenusMapping.Off)

    // When
    var tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.g should contain(-50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.aFlat should contain(50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.aFlat should contain(50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.g should contain(-50.0)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)
  }

  it should "map a scale with softChromaticGenusMapping set to strict" in {
    // Given
    val mapperWithLowQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = true,
      softChromaticGenusMapping = SoftChromaticGenusMapping.Strict)
    val mapperWithHighQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = false,
      softChromaticGenusMapping = SoftChromaticGenusMapping.Strict)

    // When
    var tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.aFlat should contain(50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.g should contain(-50.0)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)
  }

  it should "map a scale with softChromaticGenusMapping set to pseudoChromatic" in {
    // Given
    val mapperWithLowQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = true,
      softChromaticGenusMapping = SoftChromaticGenusMapping.PseudoChromatic)
    val mapperWithHighQuarterTones = AutoTuningMapper(shouldMapQuarterTonesLow = false,
      softChromaticGenusMapping = SoftChromaticGenusMapping.PseudoChromatic)

    // When
    var tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-16.67)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithSoftChromatic2, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(16.67)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithLowQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.dFlat should contain(50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)

    // When
    tuning = mapperWithHighQuarterTones.mapScale(karcigarWithPseudoChromatic, cTuningRef)
    // Then
    tuning.c should contain(0.0)
    tuning.d should contain(-50.0)
    tuning.eFlat should contain(0.0)
    tuning.f should contain(0.0)
    tuning.gFlat should contain(50.0)
    tuning.a should contain(-50.0)
    tuning.bFlat should contain(0.0)
  }

  it should "map a scale on different pitch classes based on basePitchClass parameter" in {
    val tetrachord = CentsScale(0.0, 150.0, 301.0, 498.04)

    def testScale(pitchClassNumber: Int, result: PartialTuning, map: Map[Int, Double]): Unit = {
      map.foreach {
        case (relativePitchClass, expectedCents) =>
          val finalPitchClass = (pitchClassNumber + relativePitchClass) % 12

          withClue(s"Deviation of ${PianoKeyboardTuningUtils.noteNames(finalPitchClass)}") {
            result(finalPitchClass) should contain(expectedCents)
          }
      }
    }

    for (basePitchClass <- 0 until 12) {
      val tuningRef: TuningRef = StandardTuningRef(PitchClass.fromNumber(basePitchClass))
      val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(tetrachord, tuningRef)

      testScale(basePitchClass, resultWithLowQuarterTones,
        Map(0 -> 0.0, 1 -> 50.0, 3 -> 1.0, 5 -> -1.96)
      )

      val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(tetrachord, tuningRef)

      testScale(basePitchClass, resultWithHighQuarterTones,
        Map(0 -> 0.0, 2 -> -50.0, 3 -> 1.0, 5 -> -1.96)
      )
    }
  }

  it should "manually map some pitches mentioned in overrideKeyboardMapper" in {
    // Given
    val scale = RatiosScale(1 /: 1, 12 /: 11, 32 /: 27, 4 /: 3, 3 /: 2, 13 /: 8, 16 /: 9)
    val tuningRef = ConcertPitchTuningRef(2 /: 3, MidiNote.D4)
    val overrideKeyboardMapping = KeyboardMapping(dSharpOrEFlat = Some(1))
    val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 5.0,
      overrideKeyboardMapping = overrideKeyboardMapping)
    // When
    val partialTuning = mapper.mapScale(scale, tuningRef)
    // Then
    partialTuning.completedCount shouldEqual 7
    partialTuning.d should contain(-1.95)
    partialTuning.eFlat should contain(48.68)
    partialTuning.f should contain(-7.82)
    partialTuning.g should contain(-3.91)
    partialTuning.a should contain(0.0)
    partialTuning.bFlat should contain(38.57)
    partialTuning.c should contain(-5.87)
  }

  it should "avoid conflict with manually mapped pitches" in {
    // Given
    val scale = RatiosScale(1 /: 1, 88 /: 81, 12 /: 11, 32 /: 27, 4 /: 3)
    val tuningRef = StandardTuningRef(PitchClass.D)
    val overrideKeyboardMapping = KeyboardMapping(e = Some(1))
    val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13.0,
      overrideKeyboardMapping = overrideKeyboardMapping)

    // When
    val partialTuning = mapper.mapScale(scale, tuningRef)

    // Then
    partialTuning.completedCount shouldEqual 5
    partialTuning.eFlat should contain(50.64)
    partialTuning.e should contain(-56.50)
  }

  // Test for bugfix https://github.com/calinburloiu/microtonalist/issues/76
  it should "map just Cireșar scale" in {
    // Given
    val ciresar = RatiosScale("Cireșar", 1 /: 1, 9 /: 8, 6 /: 5, 9 /: 7, 3 /: 2, 8 /: 5, 9 /: 5, 27 /: 14, 9 /: 4)
    val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13)

    // When
    val partialTuning = mapper.mapScale(ciresar, cTuningRef)

    // Then
    partialTuning.completedCount shouldEqual 8
    partialTuning.eFlat should contain(15.64)
    partialTuning.e should contain(35.08)
    partialTuning.bFlat should contain(17.6)
    partialTuning.b should contain(37.04)
  }

  behavior of "mapInterval"

  it should "map an interval to a pitch class with a deviation in cents" in {
    val tolerance = 10
    val downMapper = AutoTuningMapper(shouldMapQuarterTonesLow = true, tolerance)
    val upMapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, tolerance)

    //@formatter:off
    val table = Table[Double, AutoTuningMapper, TuningPitch](
      ("Input Cents", "AutoTuningMapper", "TuningPitch"),

      (-498.04,        downMapper,         TuningPitch(PitchClass.G, 1.96)),
      (0.0,            downMapper,         TuningPitch(PitchClass.C, 0.0)),
      (386.31,         downMapper,         TuningPitch(PitchClass.E, -13.69)),
      (701.96,         downMapper,         TuningPitch(PitchClass.G, 1.96)),
      (1200.0,         downMapper,         TuningPitch(PitchClass.C, 0.0)),
      (2400.0,         downMapper,         TuningPitch(PitchClass.C, 0.0)),

      (145.0,          downMapper,         TuningPitch(PitchClass.CSharp, 45.0)),
      (150.0,          downMapper,         TuningPitch(PitchClass.DFlat, 50.0)),
      (155.0,          downMapper,         TuningPitch(PitchClass.CSharp, 55.0)),
      (145.0,          upMapper,           TuningPitch(PitchClass.D, -55.0)),
      (150.0,          upMapper,           TuningPitch(PitchClass.D, -50.0)),
      (155.0,          upMapper,           TuningPitch(PitchClass.D, -45.0)),

      (161.0,          downMapper,         TuningPitch(PitchClass.D, -39.0)),
      (139.0,          upMapper,           TuningPitch(PitchClass.CSharp, 39.0)),

      (-145.0,         downMapper,         TuningPitch(PitchClass.BFlat, 55.0)),
      (-150.0,         downMapper,         TuningPitch(PitchClass.ASharp, 50.0)),
      (-155.0,         downMapper,         TuningPitch(PitchClass.BFlat, 45.0)),
      (-145.0,         upMapper,           TuningPitch(PitchClass.B, -45.0)),
      (-150.0,         upMapper,           TuningPitch(PitchClass.B, -50.0)),
      (-155.0,         upMapper,           TuningPitch(PitchClass.B, -55.0)),

      (-161.0,         upMapper,           TuningPitch(PitchClass.BFlat, 39.0)),
      (-139.0,         downMapper,         TuningPitch(PitchClass.B, -39.0)),
    )
    //@formatter:on

    val tuningRef = StandardTuningRef(PitchClass.C)
    forAll(table) { (inputCents, autoTuningMapper, expectedTuningPitch) =>
      val TuningPitch(pitchClass, deviation) = autoTuningMapper.mapInterval(CentsInterval(inputCents), tuningRef)
      pitchClass should equal(expectedTuningPitch.pitchClass)
      deviation should equal(expectedTuningPitch.deviation)
    }
  }

  behavior of "keyboardMappingOf"

  it should "return the keyboard mapping automatically found" in {
    // Given
    val major = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
    val tuningRef = ConcertPitchTuningRef(32 /: 27, MidiNote(PitchClass.C, 5))
    // When
    val keyboardMapping = autoTuningMapperWithLowQuarterTones.keyboardMappingOf(major, tuningRef)
    // Then
    keyboardMapping shouldEqual KeyboardMapping(
      c = Some(0),
      d = Some(1),
      e = Some(2),
      f = Some(3),
      g = Some(4),
      a = Some(5),
      b = Some(6)
    )
  }

  it should "return the keyboard mapping with some pitches manually mapped" in {
    // Given
    val scale = RatiosScale(1 /: 1, 12 /: 11, 32 /: 27, 4 /: 3, 3 /: 2, 13 /: 8, 16 /: 9)
    val tuningRef = ConcertPitchTuningRef(2 /: 3, MidiNote.D4)
    val overrideKeyboardMapping = KeyboardMapping(dSharpOrEFlat = Some(1), b = Some(5))
    val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 15.0,
      overrideKeyboardMapping = overrideKeyboardMapping)
    // When
    val keyboardMapping = mapper.keyboardMappingOf(scale, tuningRef)
    // Then
    keyboardMapping shouldEqual KeyboardMapping(d = Some(0), dSharpOrEFlat = Some(1), f = Some(2), g = Some(3),
      a = Some(4), b = Some(5), c = Some(6))
  }
}

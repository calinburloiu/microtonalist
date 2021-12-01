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

package org.calinburloiu.music.tuning

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import org.calinburloiu.music.microtuner.midi.MidiNote
import org.calinburloiu.music.microtuner.{ConcertPitchTuningRef, StandardTuningRef, TuningRef}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class AutoTuningMapperTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  import PianoKeyboardTuningUtils._

  val cTuningRef: TuningRef = StandardTuningRef(PitchClass.C)

  val halfTolerance: Int = 5
  val autoTuningMapperWithLowQuarterTones: AutoTuningMapper = AutoTuningMapper(mapQuarterTonesLow = true, halfTolerance)
  val autoTuningMapperWithHighQuarterTones: AutoTuningMapper = AutoTuningMapper(mapQuarterTonesLow = false, halfTolerance)

  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  behavior of "mapScale"

  it should "map a just major scale with a custom tuning reference to a PartialTuning" in {
    val major = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
    val major2 = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (27, 16), (15, 8), (2, 1))
    // The "major" scale will be tuned starting from C on the piano. A4 is kept at 440 Hz, but the 5/3 note mapped to
    // A is considered an A flattened by a synthonic comma, so the A on the piano will not have 440 Hz, but less.
    // The 27/16 note from "major2", also mapped to A is considered natural and have the 440 Hz concert pitch.
    val tuningRef = ConcertPitchTuningRef(32/:27, MidiNote(PitchClass.C, 5))
    val majorTuningWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(major, tuningRef)
    val major2TuningWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(major2, tuningRef)

    majorTuningWithLowQuarterTones.c should contain(-5.87)
    majorTuningWithLowQuarterTones.d should contain(-1.96)
    majorTuningWithLowQuarterTones.e should contain(-19.56)
    majorTuningWithLowQuarterTones.f should contain(-7.83)
    majorTuningWithLowQuarterTones.g should contain(-3.91)
    majorTuningWithLowQuarterTones.a should contain(-21.51)
    majorTuningWithLowQuarterTones.b should contain(-17.60)

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
    majorTuningWithLowQuarterTones should equal(majorTuningWithHighQuarterTones)
    major2TuningWithLowQuarterTones should equal(major2TuningWithHighQuarterTones)
  }

  it should "fail to map a scale with conflicting pitches " +
    "(concurrent pitches on the same tuning pitch class)" in {
    // 5/4 and 81/64 are concurrent on E
    val concurrency = RatiosScale((1, 1), (9, 8), (5, 4), (81, 64), (4, 3))

    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithLowQuarterTones.mapScale(concurrency, cTuningRef))
    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithHighQuarterTones.mapScale(concurrency, cTuningRef))
  }

  it should "map a scale with concurrent pitches on the same tuning pitch class, iff " +
    "those concurrent pitches are equivalent (have the same normalized interval)" in {
    val octaveRedundancy = RatiosScale((1, 1), (5, 4), (3, 2), (7, 4), (2, 1), (5, 2), (3, 1))

    // Note: Here we also get a precision error that is ignored by the tolerance
    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(octaveRedundancy, cTuningRef)
    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(octaveRedundancy, cTuningRef)

    resultWithHighQuarterTones shouldEqual resultWithLowQuarterTones

    resultWithHighQuarterTones.c should contain(0.0)
    resultWithHighQuarterTones.e should contain(-13.69)
    resultWithHighQuarterTones.g should contain(1.96)
    resultWithHighQuarterTones.bFlat should contain(-31.18)

    resultWithHighQuarterTones.size shouldEqual 12
    resultWithHighQuarterTones.count(_.nonEmpty) shouldEqual 4
  }

  it should "map a scale without unison or octave to a tuning without the base pitch class" in {
    val tetrachord = RatiosScale((9, 8), (5, 4), (4, 3))

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(tetrachord, cTuningRef)
    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(tetrachord, cTuningRef)
    resultWithLowQuarterTones shouldEqual resultWithHighQuarterTones

    resultWithLowQuarterTones.head should be(empty)
  }

  it should "map a tetrachord with quarter tones differently based on the " +
    "mapQuarterTonesLow parameter" in {
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

  it should "map quarter tones by taking half tolerance into account" in {
    val scale = CentsScale(145.1, 347.3, 453.4, 854.9)

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones.mapScale(scale, cTuningRef)
    resultWithLowQuarterTones.dFlat should contain(45.1)
    resultWithLowQuarterTones.eFlat should contain(47.3)
    resultWithLowQuarterTones.e should contain(53.4)
    resultWithLowQuarterTones.aFlat should contain(54.9)

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones.mapScale(scale, cTuningRef)
    resultWithHighQuarterTones.d should contain(-54.9)
    resultWithHighQuarterTones.e should contain(-52.7)
    resultWithHighQuarterTones.f should contain(-46.6)
    resultWithHighQuarterTones.a should contain(-45.1)
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
      val tuningRef: TuningRef = StandardTuningRef(PitchClass.fromInt(basePitchClass))
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
    val scale = RatiosScale(1/:1, 12/:11, 32/:27, 4/:3, 3/:2, 13/:8, 16/:9)
    val tuningRef = ConcertPitchTuningRef(2/:3, MidiNote.D4)
    val overrideKeyboardMapping = KeyboardMapping(dSharpOrEFlat = Some(1))
    val mapper = AutoTuningMapper(mapQuarterTonesLow = false, halfTolerance = 5.0,
      overrideKeyboardMapping = overrideKeyboardMapping)
    // When
    val partialTuning = mapper.mapScale(scale, tuningRef)
    // Then
    partialTuning.completedCount shouldEqual 7
    partialTuning.d should contain (-1.95)
    partialTuning.eFlat should contain (48.68)
    partialTuning.f should contain (-7.82)
    partialTuning.g should contain (-3.91)
    partialTuning.a should contain (0.0)
    partialTuning.bFlat should contain (38.57)
    partialTuning.c should contain (-5.87)
  }

  behavior of "mapInterval"

  it should "map an interval to a pitch class with a deviation in cents" in {
    val tolerance = 10
    val downMapper = AutoTuningMapper(mapQuarterTonesLow = true, tolerance)
    val upMapper = AutoTuningMapper(mapQuarterTonesLow = false, tolerance)

    //@formatter:off
    val table = Table[Double, AutoTuningMapper, TuningPitch](
      ("Input Cents", "AutoTuningMapper", "TuningPitch"),
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
    forAll(table) { (inputCents, autoTuningMapper, tuningPitch) =>
      autoTuningMapper.mapInterval(CentsInterval(inputCents), tuningRef) shouldEqual tuningPitch
    }
  }

  behavior of "keyboardMappingOf"

  it should "return the keyboard mapping automatically found" in {
    // Given
    val major = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
    val tuningRef = ConcertPitchTuningRef(32/:27, MidiNote(PitchClass.C, 5))
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
    val scale = RatiosScale(1/:1, 12/:11, 32/:27, 4/:3, 3/:2, 13/:8, 16/:9)
    val tuningRef = ConcertPitchTuningRef(2/:3, MidiNote.D4)
    val overrideKeyboardMapping = KeyboardMapping(dSharpOrEFlat = Some(1), b = Some(5))
    val mapper = AutoTuningMapper(mapQuarterTonesLow = false, halfTolerance = 15.0,
      overrideKeyboardMapping = overrideKeyboardMapping)
    // When
    val keyboardMapping = mapper.keyboardMappingOf(scale, tuningRef)
    // Then
    keyboardMapping shouldEqual KeyboardMapping(d = Some(0), dSharpOrEFlat = Some(1), f = Some(2), g = Some(3),
      a = Some(4), b = Some(5), c = Some(6))
  }
}

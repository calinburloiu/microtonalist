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

import org.calinburloiu.music.intonation.{CentsScale, PitchClass, RatiosScale}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoTuningMapperTest extends AnyFlatSpec with Matchers {

  import PianoKeyboardTuningUtils._

  val cPitchClass: PitchClass = 0

  val autoTuningMapperWithLowQuarterTones = new AutoTuningMapper(mapQuarterTonesLow = true)
  val autoTuningMapperWithHighQuarterTones = new AutoTuningMapper(mapQuarterTonesLow = false)

  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  it should "map a just major scale to a PartialTuning" in {
    val major = RatiosScale((1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones(cPitchClass, major)

    resultWithLowQuarterTones.c should contain(0.0)
    resultWithLowQuarterTones.d should contain(3.91)
    resultWithLowQuarterTones.e should contain(-13.69)
    resultWithLowQuarterTones.f should contain(-1.96)
    resultWithLowQuarterTones.g should contain(1.96)
    resultWithLowQuarterTones.a should contain(-15.64)
    resultWithLowQuarterTones.b should contain(-11.73)

    resultWithLowQuarterTones.cSharp should be(empty)
    resultWithLowQuarterTones.dSharp should be(empty)
    resultWithLowQuarterTones.fSharp should be(empty)
    resultWithLowQuarterTones.gSharp should be(empty)
    resultWithLowQuarterTones.aSharp should be(empty)

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones(cPitchClass, major)
    resultWithLowQuarterTones should equal(resultWithHighQuarterTones)
  }

  it should "fail to map a scale with conflicting pitches " +
    "(concurrent pitches on the same tuning pitch class)" in {
    // 5/4 and 81/64 are concurrent on E
    val concurrency = RatiosScale((1, 1), (9, 8), (5, 4), (81, 64), (4, 3))

    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithLowQuarterTones(cPitchClass, concurrency))
    assertThrows[TuningMapperConflictException](
      autoTuningMapperWithHighQuarterTones(cPitchClass, concurrency))
  }

  it should "map a scale with concurrent pitches on the same tuning pitch class, iff " +
    "those concurrent pitches are equivalent (have the same normalized interval)" in {
    val octaveRedundancy = RatiosScale((1, 1), (5, 4), (3, 2), (7, 4), (2, 1), (5, 2), (3, 1))

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones(cPitchClass, octaveRedundancy)
    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones(cPitchClass, octaveRedundancy)

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
    val basePitchClass = 3

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones(cPitchClass, tetrachord)
    val resultWithHighQuarterTones =
      autoTuningMapperWithHighQuarterTones(cPitchClass, tetrachord)
    resultWithLowQuarterTones shouldEqual resultWithHighQuarterTones

    resultWithLowQuarterTones(basePitchClass) should be(empty)
  }

  it should "map a tetrachord with quarter tones differently based on the " +
    "mapQuarterTonesLow parameter" in {
    val dPitchClass: PitchClass = 2
    val bayatiTetrachord = CentsScale(0.0, 150.0, 300.0, 500.0)

    val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones(dPitchClass, bayatiTetrachord)

    resultWithLowQuarterTones.d should contain(0.0)
    resultWithLowQuarterTones.eFlat should contain(50.0)
    resultWithLowQuarterTones.e should be(empty)
    resultWithLowQuarterTones.f should contain(0.0)
    resultWithLowQuarterTones.g should contain(0.0)
    resultWithLowQuarterTones.a should be(empty)

    val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones(dPitchClass, bayatiTetrachord)

    resultWithHighQuarterTones.d should contain(0.0)
    resultWithHighQuarterTones.eFlat should be(empty)
    resultWithHighQuarterTones.e should contain(-50.0)
    resultWithHighQuarterTones.f should contain(0.0)
    resultWithHighQuarterTones.g should contain(0.0)
    resultWithHighQuarterTones.a should be(empty)
  }

  it should "map a scale on different pitch classes based on basePitchClass parameter" in {
    val tetrachord = CentsScale(0.0, 150.0, 301.0, 498.04)

    def testScale(pitchClassSemitone: Int, result: PartialTuning, map: Map[Int, Double]): Unit = {
      map.foreach {
        case (relativePitchClass, expectedCents) =>
          val finalPitchClass = (pitchClassSemitone + relativePitchClass) % 12

          withClue(s"Deviation of ${PianoKeyboardTuningUtils.noteNames(finalPitchClass)}") {
            result(finalPitchClass) should contain(expectedCents)
          }
      }
    }

    for (semitone <- 0 until 12) {
      val pitchClass: PitchClass = semitone
      val resultWithLowQuarterTones = autoTuningMapperWithLowQuarterTones(pitchClass, tetrachord)

      testScale(semitone, resultWithLowQuarterTones,
        Map(0 -> 0.0, 1 -> 50.0, 3 -> 1.0, 5 -> -1.96)
      )

      val resultWithHighQuarterTones = autoTuningMapperWithHighQuarterTones(pitchClass, tetrachord)

      testScale(semitone, resultWithHighQuarterTones,
        Map(0 -> 0.0, 2 -> -50.0, 3 -> 1.0, 5 -> -1.96)
      )
    }
  }
}

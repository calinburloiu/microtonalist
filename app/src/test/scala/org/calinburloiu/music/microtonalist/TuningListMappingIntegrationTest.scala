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

package org.calinburloiu.music.microtonalist

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{JustIntonationStandard, RatioInterval, RatiosScale}
import org.calinburloiu.music.microtonalist.core.PianoKeyboardTuningUtils._
import org.calinburloiu.music.microtonalist.core.TuningList
import org.calinburloiu.music.microtonalist.format.{FormatModule, FormatTestUtils}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningListMappingIntegrationTest extends AnyFlatSpec with Matchers {
  private val formatModule = new FormatModule(FormatTestUtils.uriOfResource("app/"))

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  it should "successfully create a tuning list out of 'minor-major.mtlist' file" in {
    val compositionResource = "app/minor-major.mtlist"
    val composition = FormatTestUtils.readCompositionFromResources(compositionResource, formatModule
      .defaultCompositionRepo)
    val tuningList = TuningList.fromComposition(composition)

    val justMinorThirdDeviation = 15.64 // cents

    tuningList.size shouldEqual 3

    val minorTuning = tuningList(0)
    withClue("minor tuning scale:") {
      minorTuning.d shouldEqual 0.00
      minorTuning.e shouldEqual 3.91
      minorTuning.f shouldEqual 15.64
      minorTuning.g shouldEqual -1.96
      minorTuning.a shouldEqual 1.96
      minorTuning.bFlat shouldEqual 13.69
      minorTuning.c shouldEqual 17.60
    }
    withClue("minor tuning fill:") {
      minorTuning.cSharp shouldEqual -11.73
      minorTuning.eFlat shouldEqual 11.73
      minorTuning.gFlat shouldEqual -13.69
      minorTuning.gSharp shouldEqual -17.49
      minorTuning.b shouldEqual 5.87
    }

    val majorTuning = tuningList(1)
    withClue("major tuning scale:") {
      majorTuning.f - justMinorThirdDeviation shouldEqual 0.00
      majorTuning.g - justMinorThirdDeviation shouldEqual 3.91
      majorTuning.a - justMinorThirdDeviation shouldEqual -13.69
      majorTuning.bFlat - justMinorThirdDeviation shouldEqual -1.96
      majorTuning.c - justMinorThirdDeviation shouldEqual 1.96
      majorTuning.d - justMinorThirdDeviation shouldEqual -15.64
      majorTuning.e - justMinorThirdDeviation shouldEqual -11.73
    }
    withClue("major tuning global fill") {
      majorTuning.cSharp shouldEqual -11.73
      majorTuning.eFlat shouldEqual 11.73
      majorTuning.fSharp shouldEqual -13.69
      majorTuning.aFlat shouldEqual -17.49
      majorTuning.b shouldEqual 5.87
    }

    val romanianMinorTuning = tuningList(2)
    withClue("romanian minor scale:") {
      romanianMinorTuning.d shouldEqual 0.00
      romanianMinorTuning.e shouldEqual 3.91
      romanianMinorTuning.f shouldEqual 15.64
      romanianMinorTuning.gSharp shouldEqual -17.49
      romanianMinorTuning.a shouldEqual 1.96
      romanianMinorTuning.b shouldEqual 5.87
      romanianMinorTuning.c shouldEqual -3.91
    }
    withClue("romanian minor fill:") {
      romanianMinorTuning.cSharp shouldEqual -11.73
      romanianMinorTuning.eFlat shouldEqual 11.73
      romanianMinorTuning.fSharp shouldEqual -13.69
      romanianMinorTuning.g shouldEqual 19.55
      romanianMinorTuning.bFlat shouldEqual 13.69
    }
  }

  it should "successfully create a tuning list for a a composition with global settings, baseUri and a $ref" in {
    val composition = FormatTestUtils.readCompositionFromResources("app/huseyni.mtlist",
      formatModule.defaultCompositionRepo)

    composition.intonationStandard shouldEqual JustIntonationStandard
    composition.tuningReference.basePitchClass.number shouldEqual 2

    composition.tuningSpecs.head.scale shouldEqual RatiosScale("Hüseyni",
      1 /: 1, 12 /: 11, 32 /: 27, 4 /: 3, 3 /: 2, 18 /: 11, 16 /: 9, 2 /: 1)
    composition.tuningSpecs.head.transposition shouldEqual RatioInterval(1, 1)

    val tuningList = TuningList.fromComposition(composition)

    tuningList.size shouldEqual 1

    val huseyni = tuningList.head
    huseyni.d shouldEqual 0.0
    huseyni.eFlat shouldEqual 50.64
    huseyni.f shouldEqual -5.87
    huseyni.g shouldEqual -1.96
    huseyni.a shouldEqual 1.96
    huseyni.bFlat shouldEqual 52.59
    huseyni.c shouldEqual -3.91
  }
}

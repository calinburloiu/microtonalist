/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{JustIntonationStandard, RatioInterval, RatiosScale}
import org.calinburloiu.music.microtonalist.common.CommonTestUtils
import org.calinburloiu.music.microtonalist.composition.TuningList
import org.calinburloiu.music.microtonalist.format.{FormatModule, FormatTestUtils}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningSeqMappingIntegrationTest extends AnyFlatSpec with Matchers {
  private val businessync = Businessync(EventBus())
  private val formatModule = FormatModule(businessync, CommonTestUtils.uriOfResource("app/"))

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  it should "successfully create a tuning sequence out of 'minor-major.mtlist' file" in {
    val compositionResource = "app/minor-major.mtlist"
    val composition = FormatTestUtils.readCompositionFromResources(compositionResource, formatModule
      .defaultCompositionRepo)
    val tuningList = TuningList.fromComposition(composition)

    val justMinorThirdOffset = 15.64 // cents

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
      majorTuning.f - justMinorThirdOffset shouldEqual 0.00
      majorTuning.g - justMinorThirdOffset shouldEqual 3.91
      majorTuning.a - justMinorThirdOffset shouldEqual -13.69
      majorTuning.bFlat - justMinorThirdOffset shouldEqual -1.96
      majorTuning.c - justMinorThirdOffset shouldEqual 1.96
      majorTuning.d - justMinorThirdOffset shouldEqual -15.64
      majorTuning.e - justMinorThirdOffset shouldEqual -11.73
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

  it should "successfully create a tuning sequence out of \"La Cornu\" composition  file" in {
    val compositionResource = "app/La-Cornu--Calin-Andrei-Burloiu--72edo.mtlist"
    val composition = FormatTestUtils.readCompositionFromResources(compositionResource, formatModule
      .defaultCompositionRepo)
    val tunings = TuningList.fromComposition(composition).tunings

    tunings.size shouldEqual 3

    var tuning = tunings.head
    tuning.name shouldEqual "B Segâh + B Acemli Segâh + G rast-5 + C buselik-3"
    tuning(0) shouldEqual 0.0
    tuning(1) shouldEqual 16.67
    tuning(2) shouldEqual 0.0
    tuning(3) shouldEqual 0.0
    tuning(4) shouldEqual -16.67
    tuning(5) shouldEqual 0.0
    tuning(6) shouldEqual -16.67
    tuning(7) shouldEqual 0.0
    tuning(8) shouldEqual 50.0
    tuning(9) shouldEqual 0.0
    tuning(10) shouldEqual -33.33
    tuning(11) shouldEqual -16.67

    tuning = tunings(1)
    tuning.name shouldEqual "E Segâh + E Acemli Segâh + E Hüzzam"
    tuning(0) shouldEqual 0.0
    tuning(1) shouldEqual 16.67
    tuning(2) shouldEqual 0.0
    tuning(3) shouldEqual -33.33
    tuning(4) shouldEqual -16.67
    tuning(5) shouldEqual 0.0
    tuning(6) shouldEqual -16.67
    tuning(7) shouldEqual 0.0
    tuning(8) shouldEqual 50.0
    tuning(9) shouldEqual -16.67
    tuning(10) shouldEqual 0.0
    tuning(11) shouldEqual -16.67

    tuning = tunings(2)
    tuning.name shouldEqual "B Zirgüleli Hicaz"
    tuning(0) shouldEqual 0.0
    tuning(1) shouldEqual 16.67
    tuning(2) shouldEqual 0.0
    tuning(3) shouldEqual -33.33
    tuning(4) shouldEqual -16.67
    tuning(5) shouldEqual 0.0
    tuning(6) shouldEqual -16.67
    tuning(7) shouldEqual 0.0
    tuning(8) shouldEqual 50.0
    tuning(9) shouldEqual -16.67
    tuning(10) shouldEqual -33.33
    tuning(11) shouldEqual -16.67
  }

  it should "successfully create a tuning sequence for a a composition with global settings, baseUrl and a $ref" in {
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

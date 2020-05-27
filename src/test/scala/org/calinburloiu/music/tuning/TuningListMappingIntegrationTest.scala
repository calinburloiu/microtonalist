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

import org.calinburloiu.music.microtuner.JsonScaleListFormatTest
import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.{FlatSpec, Matchers}

class TuningListMappingIntegrationTest extends FlatSpec with Matchers {

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  it should "successfully create a tuning list out of 'minor_major.scalist' file" in {
    val scaleListResource = "scale_lists/minor_major.scalist"
    val scaleList = JsonScaleListFormatTest.readScaleListFromResources(scaleListResource)
    val tuningList = TuningList.fromScaleList(scaleList)

    val justMinorThirdDeviation = 15.64 // cents

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
      minorTuning.gFlat shouldEqual 35.08
    }
    withClue("minor tuning global fill:") {
      minorTuning.cSharp shouldEqual -11.73
      minorTuning.eFlat shouldEqual 11.73
      minorTuning.gSharp shouldEqual -17.49
      minorTuning.b shouldEqual -15.64
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
    withClue("major tuning fill:") {
      majorTuning.dFlat - justMinorThirdDeviation shouldEqual 13.69
      majorTuning.eFlat - justMinorThirdDeviation shouldEqual -31.18
      majorTuning.gFlat - justMinorThirdDeviation shouldEqual 11.73
      majorTuning.aFlat - justMinorThirdDeviation shouldEqual 15.64
      majorTuning.b - justMinorThirdDeviation shouldEqual -17.49
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
    withClue("romanian minor global fill:") {
      romanianMinorTuning.cSharp shouldEqual -11.73
      romanianMinorTuning.eFlat shouldEqual 11.73
      romanianMinorTuning.fSharp shouldEqual -13.69
      romanianMinorTuning.g shouldEqual -1.96
      romanianMinorTuning.bFlat shouldEqual 13.69
    }
  }
}

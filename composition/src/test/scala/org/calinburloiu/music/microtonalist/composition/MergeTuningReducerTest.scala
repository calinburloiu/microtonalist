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

package org.calinburloiu.music.microtonalist.composition

import org.calinburloiu.music.microtonalist.tuner.Tuning
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MergeTuningReducerTest extends AnyFlatSpec with Matchers {

  import org.calinburloiu.music.microtonalist.tuner.TestTunings.*

  private val reducer = MergeTuningReducer()

  behavior of "MergeTuningReducer"

  it should "return an empty tuning list with no tunings" in {
    val tuningList = reducer.reduceTunings(Seq.empty)

    tuningList.size shouldEqual 0
    tuningList.tunings should have size 0
  }

  it should "resolve a single tuning into a tuning list with a single tuning" in {
    val tuningList = reducer.reduceTunings(Seq(justCMaj))

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual Tuning("Just C Major",
      c = 0.0,
      cSharpOrDFlat = 0.0,
      d = 3.91,
      dSharpOrEFlat = 0.0,
      e = -13.69,
      f = -1.96,
      fSharpOrGFlat = 0.0,
      g = 1.96,
      gSharpOrAFlat = 0.0,
      a = -15.64,
      aSharpOrBFlat = 0.0,
      b = -11.73
    )
  }

  it should "resolve a single tuning and apply global fill into a tuning list with a single tuning" in {
    val tuningList = reducer.reduceTunings(Seq(justCMaj), customGlobalFill)

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual Tuning("Just C Major",
      c = 0.0,
      cSharpOrDFlat = 2.0,
      d = 3.91,
      dSharpOrEFlat = 4.0,
      e = -13.69,
      f = -1.96,
      fSharpOrGFlat = 7.0,
      g = 1.96,
      gSharpOrAFlat = 9.0,
      a = -15.64,
      aSharpOrBFlat = 11.0,
      b = -11.73
    )
  }

  it should "merge two tunings and apply global fill into a tuning list with a single tuning" in {
    val tuningList = reducer.reduceTunings(Seq(eSegah, bEvic), customGlobalFill)

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual Tuning("Segah + Evic",
      c = 0.0,
      cSharpOrDFlat = 2.0,
      d = 0.0,
      dSharpOrEFlat = -33.33,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = 7.0,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = -16.67,
      aSharpOrBFlat = -33.33,
      b = -16.67
    )
  }

  it should "not merge two tunings that have conflicts, " +
    "but apply fills into a tuning list with two tunings" in {
    val tuningList = reducer.reduceTunings(Seq(eSegah, gMaj), customGlobalFill)

    tuningList.size shouldEqual 2
    tuningList.tunings.head shouldEqual Tuning("Segah",
      c = 0.0,
      cSharpOrDFlat = 2.0,
      d = 0.0,
      dSharpOrEFlat = -33.33,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = -16.67,
      aSharpOrBFlat = 11.0,
      b = -16.67
    )
    tuningList.tunings(1) shouldEqual Tuning("G Major",
      c = 0.0,
      cSharpOrDFlat = 2.0,
      d = 0.0,
      dSharpOrEFlat = -33.33,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = 0.0,
      aSharpOrBFlat = 11.0,
      b = -16.67
    )
  }

  it should "should merge more tunings (1)" in {
    val tuningList = reducer.reduceTunings(Seq(bEvic, gMaj, cNihavent5, eSegah, eSegahDesc, eHuzzam))

    tuningList.size shouldEqual 2
    tuningList.tunings.head shouldEqual Tuning("Evic + G Major + Nihavent Pentachord",
      c = 0.0,
      cSharpOrDFlat = 0.0,
      d = 0.0,
      dSharpOrEFlat = 0.0,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 50.0,
      a = 0.0,
      aSharpOrBFlat = -33.33,
      b = -16.67
    )
    tuningList.tunings(1) shouldEqual Tuning("Segah + Segah Descending + Huzzam",
      c = 0.0,
      cSharpOrDFlat = 0.0,
      d = 0.0,
      dSharpOrEFlat = -33.33,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 50.0,
      a = -16.67,
      aSharpOrBFlat = 0.0,
      b = -16.67
    )
  }

  it should "should merge more tunings (2)" in {
    val tuningList = reducer.reduceTunings(Seq(cRast, cNikriz, dZengule, dUssak, dSaba), customGlobalFill)

    tuningList.size shouldEqual 3
    tuningList.tunings.head shouldEqual Tuning("Rast + Nikriz",
      c = 0.0,
      cSharpOrDFlat = -16.67,
      d = 0.0,
      dSharpOrEFlat = 16.67,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = 0.0,
      aSharpOrBFlat = 0.0,
      b = -16.67
    )
    tuningList.tunings(1) shouldEqual Tuning("Zengule",
      c = 0.0,
      cSharpOrDFlat = -16.67,
      d = 0.0,
      dSharpOrEFlat = 16.67,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = -16.67,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = 0.0,
      aSharpOrBFlat = 16.67,
      b = -16.67
    )
    tuningList.tunings(2) shouldEqual Tuning("Ussak + Saba",
      c = 0.0,
      cSharpOrDFlat = -16.67,
      d = 0.0,
      dSharpOrEFlat = 50.0,
      e = -16.67,
      f = 0.0,
      fSharpOrGFlat = 33.33,
      g = 0.0,
      gSharpOrAFlat = 9.0,
      a = 0.0,
      aSharpOrBFlat = 0.0,
      b = -16.67
    )
  }
}

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

package org.calinburloiu.music.tuning

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MergeTuningReducerTest extends AnyFlatSpec with Matchers {
  private val reducer = new MergeTuningReducer()
  private val evic = PartialTuning("Evic",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = None,
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = None,
    aSharpOrBFlat = Some(-33.33),
    b = Some(-16.67)
  )
  private val gMajor = PartialTuning("G Major",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = None,
    e = Some(-16.67),
    f = None,
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )
  private val segah = PartialTuning("Segah",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(-16.67),
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )
  private val segahDesc = PartialTuning("Segah Descending",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = Some(50.0),
    a = Some(-16.67),
    aSharpOrBFlat = Some(0.0),
    b = None
  )

  private val justCMajor = PartialTuning("Just C Major",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(3.91),
    dSharpOrEFlat = None,
    e = Some(-13.69),
    f = Some(-1.96),
    fSharpOrGFlat = None,
    g = Some(1.96),
    gSharpOrAFlat = None,
    a = Some(15.64),
    aSharpOrBFlat = None,
    b = Some(-11.73)
  )

  private val customGlobalFill = PartialTuning((1 to 12).map(Some(_)))

  behavior of "MergeTuningReducer"

  it should "return an empty tuning list with no partial tunings" in {
    val tuningList = reducer(Seq.empty)

    tuningList.size shouldEqual 0
    tuningList.tunings should have size 0
  }

  it should "resolve a single partial tuning into a tuning list with a single tuning" in {
    val tuningList = reducer(Seq(justCMajor))

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual OctaveTuning("Just C Major",
      c = 0.0,
      cSharpOrDFlat = 0.0,
      d = 3.91,
      dSharpOrEFlat = 0.0,
      e = -13.69,
      f = -1.96,
      fSharpOrGFlat = 0.0,
      g = 1.96,
      gSharpOrAFlat = 0.0,
      a = 15.64,
      aSharpOrBFlat = 0.0,
      b = -11.73
    )
  }

  it should "resolve a single partial tuning and apply global fill into a tuning list with a single tuning" in {
    val tuningList = reducer(Seq(justCMajor), customGlobalFill)

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual OctaveTuning("Just C Major",
      c = 0.0,
      cSharpOrDFlat = 2.0,
      d = 3.91,
      dSharpOrEFlat = 4.0,
      e = -13.69,
      f = -1.96,
      fSharpOrGFlat = 7.0,
      g = 1.96,
      gSharpOrAFlat = 9.0,
      a = 15.64,
      aSharpOrBFlat = 11.0,
      b = -11.73
    )
  }

  it should "merge two partial tunings and apply global fill into a tuning list with a single tuning" in {
    val tuningList = reducer(Seq(segah, evic), customGlobalFill)

    tuningList.size shouldEqual 1
    tuningList.tunings.head shouldEqual OctaveTuning("Segah | Evic",
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

  it should "not merge two partial tunings that have conflicts, but apply fills into a tuning list with two tunings" in {
    val tuningList = reducer(Seq(segah, gMajor), customGlobalFill)

    tuningList.size shouldEqual 2
    tuningList.tunings.head shouldEqual OctaveTuning("Segah",
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
    tuningList.tunings(1) shouldEqual OctaveTuning("G Major",
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

  // TODO #2 Make it like La Cornu
  ignore should "should merge two partial tunings with conflicts, back-fill and fore-fill" in {
    val tuningList = reducer(Seq(evic, gMajor, segah, segahDesc))

    tuningList.size shouldEqual 2
    tuningList.tunings.head shouldEqual OctaveTuning("Evic | G Major",
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
    tuningList.tunings(1) shouldEqual OctaveTuning("Segah | Segah Descending",
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

  // TODO #2
  ignore should "a yet more complicated pattern" in {

  }
}

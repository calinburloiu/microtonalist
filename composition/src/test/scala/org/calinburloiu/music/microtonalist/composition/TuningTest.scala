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

class TuningTest extends AnyFlatSpec with Matchers {
  private val mergeTolerance: Double = 0.5e-2

  private val completeTuning = Tuning("foo",
    Some(100.0), Some(200.0), Some(300.0),
    Some(400.0), Some(500.0), Some(600.0),
    Some(700.0), Some(800.0), Some(900.0),
    Some(1000.0), Some(1100.0), Some(1200.0))
  private val incompleteTuning = Tuning(
    Some(0.0), None, Some(270.0),
    Some(400.0), Some(500.0), Some(600.0),
    Some(700.0), Some(800.0), Some(900.0),
    Some(1000.0), Some(1100.0), Some(1200.0))
  private val incompleteTuning2 = Tuning(
    None, None, Some(250.0),
    None, None, None,
    None, None, None,
    None, None, None)
  private val emptyTuning = Tuning(
    None, None, None,
    None, None, None,
    None, None, None,
    None, None, None)

  "constructor" should "throw IllegalArgumentException when the number of offsets is not 12" in {
    assertThrows[IllegalArgumentException] {
      Tuning(Seq(Some(10.0), Some(20.0), Some(30.0), Some(40.0), Some(50.0), Some(60.0)))
    }

    assertThrows[IllegalArgumentException] {
      Tuning(Seq.fill(13)(Some(10.0)))
    }
  }

  Tuning(c = Some(0), d = Some(1), e = Some(2), f = Some(3))

  "apply, size and iterator" should "work as an indexed sequence" in {
    incompleteTuning.size shouldEqual 12
    incompleteTuning.get(1) should be(empty)
    incompleteTuning.get(2) should contain(270.0)
    incompleteTuning.iterator.toSeq shouldEqual Seq(
      Some(0.0), None, Some(270.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))

    assertThrows[IndexOutOfBoundsException](incompleteTuning(13))
  }

  "note names implicit methods" should "return the correct offsets" in {
    val tuning = Tuning(
      Some(0.0), Some(12.0), Some(4.0),
      Some(16.0), Some(-14.0), Some(-2.0),
      None, Some(2.0), Some(-16.0),
      Some(14.0), Some(-35.0), Some(-12.0)
    )

    //@formatter:off
    // White keys and flats
    tuning.c       shouldEqual 0.0
    tuning.cSharp  shouldEqual 12.0
    tuning.d       shouldEqual 4.0
    tuning.dSharp  shouldEqual 16.0
    tuning.e       shouldEqual -14.0
    tuning.f       shouldEqual -2.0
    tuning.fSharp  shouldEqual 0.0
    tuning.g       shouldEqual 2.0
    tuning.gSharp  shouldEqual -16.0
    tuning.a       shouldEqual 14.0
    tuning.aSharp  shouldEqual -35.0
    tuning.b       shouldEqual -12.0
    //@formatter:on

    // Enharmonic equivalences for black keys
    tuning.cSharp shouldEqual tuning.dFlat
    tuning.dSharp shouldEqual tuning.eFlat
    tuning.fSharp shouldEqual tuning.gFlat
    tuning.gSharp shouldEqual tuning.aFlat
    tuning.aSharp shouldEqual tuning.bFlat
  }

  "isComplete" should "return true if all offset are non-empty" in {
    emptyTuning.isComplete shouldEqual false
    completeTuning.isComplete shouldEqual true
    incompleteTuning.isComplete shouldEqual false
  }

  "enrich" should "correctly do a best effort combine of Tunings" in {
    incompleteTuning `fill` emptyTuning shouldEqual incompleteTuning

    incompleteTuning `fill` completeTuning shouldEqual
      Tuning(
        Some(0.0), Some(200.0), Some(270.0),
        Some(400.0), Some(500.0), Some(600.0),
        Some(700.0), Some(800.0), Some(900.0),
        Some(1000.0), Some(1100.0), Some(1200.0))

    incompleteTuning2 `fill` incompleteTuning shouldEqual Tuning(
      Some(0.0), None, Some(250.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))
  }

  "merge" should "correctly combine Tunings" in {
    val tuning1 = Tuning(
      Some(100.0), Some(200.0), Some(300.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))
    val tuning2 = Tuning(
      Some(100.0), None, Some(300.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))
    val tuning3 = Tuning(
      Some(100.0), Some(200.0), Some(301.1),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))

    withClue("an empty Tuning causes no conflicts:") {
      tuning2.merge(emptyTuning, mergeTolerance) should contain(tuning2)
      emptyTuning.merge(tuning2, mergeTolerance) should contain(tuning2)
    }
    withClue("an identical Tuning has identical offsets, which cause no conflict") {
      tuning1.merge(tuning1.copy(), mergeTolerance) should contain(tuning1)
    }
    withClue("identical corresponding offsets cause no conflict") {
      tuning1.merge(tuning2, mergeTolerance) should contain(tuning1)
    }
    withClue("non-identical corresponding offset cause a conflict") {
      tuning1.merge(tuning3, mergeTolerance) should be(empty)
      tuning2.merge(tuning3, mergeTolerance) should be(empty)
    }
  }

  it should "combine the names" in {
    // Given
    val offsets1: Seq[Option[Double]] = Seq.fill[Option[Double]](11)(None) :+ Some(5.0)
    val offsets2: Seq[Option[Double]] = Some(-5.0) +: Seq.fill[Option[Double]](11)(None)
    val expectedOffsets: Seq[Option[Double]] = Some(-5.0) +: Seq.fill[Option[Double]](10)(None) :+ Some(5.0)
    val tuningWithName1 = Tuning(name = "Foo", offsetOptions = offsets1)
    val tuningWithName2 = Tuning(name = "Bar", offsetOptions = offsets2)
    val tuningWithoutName = Tuning(name = "", offsetOptions = offsets2)

    // Then
    tuningWithName1.merge(tuningWithoutName, mergeTolerance) should contain(Tuning(name = "Foo", offsetOptions =
      expectedOffsets))
    tuningWithoutName.merge(tuningWithName1, mergeTolerance) should contain(Tuning(name = "Foo", offsetOptions =
      expectedOffsets))
    tuningWithName1.merge(tuningWithName2, mergeTolerance) should contain(Tuning(name = "Foo + Bar", offsetOptions =
      expectedOffsets))
  }
}

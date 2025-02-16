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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningTest extends AnyFlatSpec with Matchers {
  private val mergeTolerance: Double = 0.5e-2

  private val completePartialTuning = Tuning("foo",
    Some(100.0), Some(200.0), Some(300.0),
    Some(400.0), Some(500.0), Some(600.0),
    Some(700.0), Some(800.0), Some(900.0),
    Some(1000.0), Some(1100.0), Some(1200.0))
  private val incompletePartialTuning = Tuning(
    Some(0.0), None, Some(270.0),
    Some(400.0), Some(500.0), Some(600.0),
    Some(700.0), Some(800.0), Some(900.0),
    Some(1000.0), Some(1100.0), Some(1200.0))
  private val incompletePartialTuning2 = Tuning(
    None, None, Some(250.0),
    None, None, None,
    None, None, None,
    None, None, None)
  private val emptyPartialTuning = Tuning(
    None, None, None,
    None, None, None,
    None, None, None,
    None, None, None)

  "constructor" should "throw IllegalArgumentException when the number of deviations is not 12" in {
    assertThrows[IllegalArgumentException] {
      Tuning(Seq(Some(10.0), Some(20.0), Some(30.0), Some(40.0), Some(50.0), Some(60.0)))
    }

    assertThrows[IllegalArgumentException] {
      Tuning(Seq.fill(13)(Some(10.0)))
    }
  }

  Tuning(c = Some(0), d = Some(1), e = Some(2), f = Some(3))

  "apply, size and iterator" should "work as an indexed sequence" in {
    incompletePartialTuning.size shouldEqual 12
    incompletePartialTuning.get(1) should be(empty)
    incompletePartialTuning.get(2) should contain(270.0)
    incompletePartialTuning.iterator.toSeq shouldEqual Seq(
      Some(0.0), None, Some(270.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))

    assertThrows[IndexOutOfBoundsException](incompletePartialTuning(13))
  }

  "note names implicit methods" should "return the correct deviations" in {
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

  "isComplete" should "return true if all deviation are non-empty" in {
    emptyPartialTuning.isComplete shouldEqual false
    completePartialTuning.isComplete shouldEqual true
    incompletePartialTuning.isComplete shouldEqual false
  }

  "resolve" should "return a Tuning if this PartialTuning isComplete and None otherwise" in {
    val standardTuning = Tuning.Standard
    emptyPartialTuning.resolve shouldEqual standardTuning
    completePartialTuning.resolve shouldEqual Tuning("foo",
      100.0, 200.0, 300.0,
      400.0, 500.0, 600.0,
      700.0, 800.0, 900.0,
      1000.0, 1100.0, 1200.0)
    incompletePartialTuning.resolve shouldEqual Tuning("",
      0.0, 0.0, 270.0,
      400.0, 500.0, 600.0,
      700.0, 800.0, 900.0,
      1000.0, 1100.0, 1200.0
    )
  }

  "enrich" should "correctly do a best effort combine of PartialTunings" in {
    incompletePartialTuning fill emptyPartialTuning shouldEqual incompletePartialTuning

    incompletePartialTuning fill completePartialTuning shouldEqual
      Tuning(
        Some(0.0), Some(200.0), Some(270.0),
        Some(400.0), Some(500.0), Some(600.0),
        Some(700.0), Some(800.0), Some(900.0),
        Some(1000.0), Some(1100.0), Some(1200.0))

    incompletePartialTuning2 fill incompletePartialTuning shouldEqual Tuning(
      Some(0.0), None, Some(250.0),
      Some(400.0), Some(500.0), Some(600.0),
      Some(700.0), Some(800.0), Some(900.0),
      Some(1000.0), Some(1100.0), Some(1200.0))
  }

  "merge" should "correctly combine PartialTunings" in {
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

    withClue("an empty PartialTuning causes no conflicts:") {
      tuning2.merge(emptyPartialTuning, mergeTolerance) should contain(tuning2)
      emptyPartialTuning.merge(tuning2, mergeTolerance) should contain(tuning2)
    }
    withClue("an identical PartialTuning has identical deviations, which cause no conflict") {
      tuning1.merge(tuning1.copy(), mergeTolerance) should contain(tuning1)
    }
    withClue("identical corresponding deviations cause no conflict") {
      tuning1.merge(tuning2, mergeTolerance) should contain(tuning1)
    }
    withClue("non-identical corresponding deviation cause a conflict") {
      tuning1.merge(tuning3, mergeTolerance) should be(empty)
      tuning2.merge(tuning3, mergeTolerance) should be(empty)
    }
  }

  it should "combine the names" in {
    // Given
    val deviations1: Seq[Option[Double]] = Seq.fill[Option[Double]](11)(None) :+ Some(5.0)
    val deviations2: Seq[Option[Double]] = Some(-5.0) +: Seq.fill[Option[Double]](11)(None)
    val expectedDeviations: Seq[Option[Double]] = Some(-5.0) +: Seq.fill[Option[Double]](10)(None) :+ Some(5.0)
    val tuningWithName1 = Tuning(name = "Foo", offsetOptions = deviations1)
    val tuningWithName2 = Tuning(name = "Bar", offsetOptions = deviations2)
    val tuningWithoutName = Tuning(deviations = deviations2)

    // Then
    tuningWithName1.merge(tuningWithoutName, mergeTolerance) should contain(Tuning(name = "Foo", offsetOptions =
      expectedDeviations))
    tuningWithoutName.merge(tuningWithName1, mergeTolerance) should contain(Tuning(name = "Foo", offsetOptions =
      expectedDeviations))
    tuningWithName1.merge(tuningWithName2, mergeTolerance) should contain(Tuning(name = "Foo + Bar", offsetOptions =
      expectedDeviations))
  }
}

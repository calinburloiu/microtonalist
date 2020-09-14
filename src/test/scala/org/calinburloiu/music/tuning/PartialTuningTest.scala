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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PartialTuningTest extends AnyFlatSpec with Matchers {

  private val completePartialTuning = PartialTuning(Some(100.0), Some(200.0), Some(300.0))
  private val incompletePartialTuning = PartialTuning(Some(0.0), None, Some(270.0))
  private val incompletePartialTuning2 = PartialTuning(None, None, Some(250.0))
  private val emptyPartialTuning = PartialTuning(None, None, None)
  private val smallerPartialTuning = PartialTuning(Some(101.1), None)

  "apply, size and iterator" should "work as an indexed sequence" in {
    incompletePartialTuning.size shouldEqual 3
    incompletePartialTuning(1) should be(empty)
    incompletePartialTuning(2) should contain(270.0)
    incompletePartialTuning.iterator.toSeq shouldEqual Seq(Some(0.0), None, Some(270.0))

    assertThrows[IndexOutOfBoundsException](incompletePartialTuning(3))
  }

  "isComplete" should "return true if all deviation are non-empty" in {
    emptyPartialTuning.isComplete shouldEqual false
    completePartialTuning.isComplete shouldEqual true
    incompletePartialTuning.isComplete shouldEqual false
  }

  "resolve" should "return some Tuning if this PartialTuning isComplete and None otherwise" in {
    emptyPartialTuning.resolve("foo") should be(empty)
    completePartialTuning.resolve("foo") should contain(Tuning("foo", 100.0, 200.0, 300.0))
    incompletePartialTuning.resolve("foo") should be(empty)
  }

  "enrich" should "correctly do a best effort combine of PartialTunings" in {
    incompletePartialTuning enrich emptyPartialTuning shouldEqual incompletePartialTuning

    incompletePartialTuning enrich completePartialTuning shouldEqual
      PartialTuning(Some(0.0), Some(200.0), Some(270.0))

    incompletePartialTuning2 enrich incompletePartialTuning shouldEqual
      PartialTuning(Some(0.0), None, Some(250.0))

    assertThrows[IllegalArgumentException](incompletePartialTuning enrich smallerPartialTuning)
  }

  "merge" should "correctly combine PartialTunings" in {
    val pt1 = PartialTuning(Some(100.0), Some(200.0), Some(300.0))
    val pt2 = PartialTuning(Some(100.0), None, Some(300.0))
    val pt3 = PartialTuning(Some(100.0), Some(200.0), Some(301.1))

    withClue("an empty PartialTuning causes no conflicts:") {
      pt2 merge emptyPartialTuning should contain(pt2)
      emptyPartialTuning merge pt2 should contain(pt2)
    }
    withClue("an identical PartialTuning has identical deviations, which cause no conflict") {
      pt1 merge pt1.copy() should contain(pt1)
    }
    withClue("identical corresponding deviations cause no conflict") {
      pt1 merge pt2 should contain(pt1)
    }
    withClue("non-identical corresponding deviation cause a conflict") {
      pt1 merge pt3 should be(empty)
      pt2 merge pt3 should be(empty)
    }

    assertThrows[IllegalArgumentException](incompletePartialTuning merge smallerPartialTuning)
  }
}

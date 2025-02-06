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

package org.calinburloiu.music.microtonalist.tuner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningChangeTest extends AnyFlatSpec with Matchers {

  "isTriggering" should "return the correct value depending on the case instance" in {
    NoTuningChange.isTriggering shouldBe false
    MayTriggerTuningChange.isTriggering shouldBe false
    PreviousTuningChange.isTriggering shouldBe true
    NextTuningChange.isTriggering shouldBe true
    IndexTuningChange(1).isTriggering shouldBe true
  }

  "mayTrigger" should "return the correct value depending on the case instance" in {
    NoTuningChange.mayTrigger shouldBe false
    MayTriggerTuningChange.mayTrigger shouldBe true
    PreviousTuningChange.mayTrigger shouldBe true
    NextTuningChange.mayTrigger shouldBe true
    IndexTuningChange(2).mayTrigger shouldBe true
  }

  "IndexTuningChange" should "return the correct value for index" in {
    IndexTuningChange(0).index shouldEqual 0
    IndexTuningChange(1).index shouldEqual 1
  }

  it should "throw IllegalArgumentException if index is negative" in {
    assertThrows[IllegalArgumentException] {
      IndexTuningChange(-1)
    }
  }
}

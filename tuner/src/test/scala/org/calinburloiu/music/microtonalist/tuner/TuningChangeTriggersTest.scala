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

import org.calinburloiu.music.scmidi.message.CcScMidiMessage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningChangeTriggersTest extends AnyFlatSpec with Matchers {
  val triggers: TuningChangeTriggers[PedalTuningChanger.Cc] = TuningChangeTriggers(
    previous = Some(CcScMidiMessage.SoftPedal),
    next = Some(CcScMidiMessage.SostenutoPedal),
    index = Map(
      1 -> 10,
      2 -> 20
    )
  )

  "constructor" should "fail if there is no trigger configured" in {
    assertThrows[IllegalArgumentException] {
      TuningChangeTriggers[PedalTuningChanger.Cc](
        previous = None,
        next = None,
        index = Map.empty[Int, PedalTuningChanger.Cc]
      )
    }

    assertThrows[IllegalArgumentException] {
      TuningChangeTriggers[PedalTuningChanger.Cc]()
    }
  }

  it should "fail if index has negative keys" in {
    assertThrows[IllegalArgumentException] {
      TuningChangeTriggers[Char](index = Map(-1 -> 'a'))
    }
  }

  "hasPreviousWithTrigger" should "return true if there is a trigger for previous tuning" in {
    triggers.hasPreviousWithTrigger(CcScMidiMessage.SostenutoPedal) shouldBe false
    triggers.hasPreviousWithTrigger(CcScMidiMessage.SoftPedal) shouldBe true
  }

  "hasNextWithTrigger" should "return true if there is a trigger for next tuning" in {
    triggers.hasNextWithTrigger(CcScMidiMessage.SostenutoPedal) shouldBe true
    triggers.hasNextWithTrigger(CcScMidiMessage.SoftPedal) shouldBe false
  }

  "hasIndexWithTrigger" should "return true if there is a trigger for tuning to specific index" in {
    triggers.hasIndexWithTrigger(10) shouldBe true
    triggers.hasIndexWithTrigger(20) shouldBe true
    triggers.hasIndexWithTrigger(1) shouldBe false
    triggers.hasIndexWithTrigger(2) shouldBe false
  }

  "hasTrigger" should "return true if there is any trigger for given value" in {
    triggers.hasTrigger(CcScMidiMessage.SoftPedal) shouldBe true
    triggers.hasTrigger(CcScMidiMessage.SostenutoPedal) shouldBe true
    triggers.hasTrigger(10) shouldBe true
    triggers.hasTrigger(20) shouldBe true

    triggers.hasTrigger(CcScMidiMessage.SustainPedal) shouldBe false
  }

  "tuningChangeForTrigger" should "compute the tuning change operation for the given trigger" in {
    triggers.tuningChangeForTrigger(CcScMidiMessage.SoftPedal) shouldEqual PreviousTuningChange
    triggers.tuningChangeForTrigger(CcScMidiMessage.SostenutoPedal) shouldEqual NextTuningChange
    triggers.tuningChangeForTrigger(10) shouldEqual IndexTuningChange(1)
    triggers.tuningChangeForTrigger(20) shouldEqual IndexTuningChange(2)
    triggers.tuningChangeForTrigger(CcScMidiMessage.SustainPedal) shouldEqual NoTuningChange
  }
}

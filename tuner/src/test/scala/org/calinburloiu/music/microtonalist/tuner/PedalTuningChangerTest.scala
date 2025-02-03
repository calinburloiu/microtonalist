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

class PedalTuningChangerTest extends AnyFlatSpec with Matchers {
  "PedalTuningChanger.apply" should "instantiate a class the default parameters" in {
    val tuningChanger = PedalTuningChanger()
    tuningChanger shouldBe a[TuningChanger]
    tuningChanger.previousTuningCc shouldEqual 67
    tuningChanger.nextTuningCc shouldEqual 66
    tuningChanger.threshold shouldBe 0
    tuningChanger.familyName shouldEqual TuningChanger.familyName
    tuningChanger.typeName shouldEqual "pedal"
  }
}

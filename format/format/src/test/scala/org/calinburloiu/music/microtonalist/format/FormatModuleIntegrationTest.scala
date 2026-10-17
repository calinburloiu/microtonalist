/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.format

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{JustIntonationStandard, RatiosScale}
import org.calinburloiu.music.microtonalist.common.CommonTestUtils
import org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger
import org.calinburloiu.music.microtonalist.tuner.mts.MtsOctave1ByteNonRealTimeTuner
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FormatModuleIntegrationTest extends AnyWordSpec with Matchers {

  private val formatModule = FormatModule(Businessync(EventBus()), CommonTestUtils.uriOfResource("format/"))

  "FormatModule" should {
    "read a composition and the scales it references" in {
      // When
      val composition = formatModule.defaultCompositionRepo.read(
        CommonTestUtils.uriOfResource("format/format-module.mtlist"))

      // Then
      composition.intonationStandard shouldEqual JustIntonationStandard
      composition.tuningReference.basePitchClass.number shouldEqual 2
      composition.tuningSpecs.map(_.scale) shouldEqual Seq(
        RatiosScale("Major", 1 /: 1, 9 /: 8, 5 /: 4, 4 /: 3, 3 /: 2, 5 /: 3, 15 /: 8, 2 /: 1))
    }

    "read a rig file's tuners and tuning changers with their family formats" in {
      // When
      val rigSpec = formatModule.defaultRigRepo.readRig(
        CommonTestUtils.uriOfResource("format/format-module.mtlist.tracks"))

      // Then
      rigSpec.tracks.map(_.tuner) shouldEqual Seq(
        Some(MtsOctave1ByteNonRealTimeTuner(thru = false, altTuningOutput = None)))
      rigSpec.tracks.map(_.tuningChangers) shouldEqual Seq(Seq(PedalTuningChanger()))
    }
  }
}

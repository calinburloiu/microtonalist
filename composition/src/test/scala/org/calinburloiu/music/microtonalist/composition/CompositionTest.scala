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

import org.calinburloiu.music.intonation.CentsIntonationStandard
import org.calinburloiu.music.scmidi.PitchClass
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class CompositionTest extends AnyFlatSpec with Matchers {
  val sampleComposition: Composition = Composition(
    url = Some(new URI("file:///path/to/composition.mtlist")),
    intonationStandard = CentsIntonationStandard,
    tuningReference = StandardTuningReference(PitchClass.C),
    tuningSpecs = Seq(),
    tuningReducer = TuningReducer.Default,
    fill = FillSpec()
  )

  "tracksUrl" should "be derived from URI when there is no override" in {
    sampleComposition.tracksUrl should contain(new URI("file:///path/to/composition.mtlist.tracks"))
  }

  it should "be empty when URI is not defined" in {
    val composition = sampleComposition.copy(url = None)
    composition.tracksUrl shouldBe empty
  }

  it should "overridden" in {
    val uri = new URI("file:///path/to/special.mtlist.tracks")
    val composition = sampleComposition.copy(tracksUrlOverride = Some(uri))
    composition.tracksUrl should contain(uri)
  }
}

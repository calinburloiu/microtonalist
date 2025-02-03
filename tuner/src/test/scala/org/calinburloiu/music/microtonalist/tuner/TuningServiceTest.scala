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

import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.microtonalist.tuner.TunerTestUtils.{majTuning, rastTuning}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningServiceTest extends AnyFlatSpec with Matchers with MockFactory {

  trait Fixture {
    val sessionStub: TuningSession = stub[TuningSession]
    val businessyncStub: Businessync = stub[Businessync]
    val tunings: Seq[OctaveTuning] = Seq(majTuning, rastTuning)

    (sessionStub.tunings _).when().returns(tunings)

    (businessyncStub.runIf(_: Boolean)(_: () => Unit)).when(*, *).onCall { (condition: Boolean, fn: () => Unit) =>
      if (condition) fn()
    }

    val tuningService = new TuningService(sessionStub, businessyncStub)
  }

  "tunings" should "return the sequence of tunings from the session" in new Fixture {
    tuningService.tunings shouldEqual tunings
  }

  "changeTuning" should "call previousTuning in the session when PreviousTuningChange is provided" in new Fixture {
    // When
    tuningService.changeTuning(PreviousTuningChange)
    // Then
    (sessionStub.previousTuning _).verify().once()
  }

  it should "call nextTuning in the session when NextTuningChange is provided" in new Fixture {
    // When
    tuningService.changeTuning(NextTuningChange)
    // Then
    (sessionStub.nextTuning _).verify().once()
  }

  it should "update tuning index in the session when IndexTuningChange is provided" in new Fixture {
    // Given
    val newIndex = 2
    // When
    tuningService.changeTuning(IndexTuningChange(newIndex))
    // Then
    (sessionStub.tuningIndex_= _).verify(newIndex).once()
  }

  it should "not perform any action for NoTuningChange" in {
    val sessionStub = stub[TuningSession]
    val businessyncStub = stub[Businessync]

    (businessyncStub.runIf(_: Boolean)(_: () => Unit)).when(*, *).onCall { (condition: Boolean, _: () => Unit) =>
      if (condition) fail("No code should be executed for NoTuningChange")
    }

    val tuningService = new TuningService(sessionStub, businessyncStub)
    tuningService.changeTuning(NoTuningChange)
  }
}
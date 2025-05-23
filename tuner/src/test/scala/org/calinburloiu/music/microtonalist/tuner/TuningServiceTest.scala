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

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningServiceTest extends AnyFlatSpec with Matchers with MockFactory {

  trait Fixture {
    val sessionStub: TuningSession = stub[TuningSession]
    val businessync: Businessync = Businessync(EventBus())
    val tunings: Seq[Tuning] = Seq(TestTunings.justCMaj, TestTunings.justCRast)

    (() => sessionStub.tunings).when().returns(tunings)

    val tuningService = new TuningService(sessionStub, businessync)
  }

  "tunings" should "return the sequence of tunings from the session" in new Fixture {
    tuningService.tunings shouldEqual tunings
  }

  "changeTuning" should "call previousTuning in the session when PreviousTuningChange is provided" in new Fixture {
    // When
    tuningService.changeTuning(PreviousTuningChange)
    // Then
    (() => sessionStub.previousTuning()).verify().once()
  }

  it should "call nextTuning in the session when NextTuningChange is provided" in new Fixture {
    // When
    tuningService.changeTuning(NextTuningChange)
    // Then
    (() => sessionStub.nextTuning()).verify().once()
  }

  it should "update tuning index in the session when IndexTuningChange is provided" in new Fixture {
    // Given
    val newIndex = 2
    // When
    tuningService.changeTuning(IndexTuningChange(newIndex))
    // Then
    sessionStub.tuningIndex_=.verify(newIndex).once()
  }
}

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
import org.calinburloiu.music.microtonalist.composition.Tuning
import org.calinburloiu.music.microtonalist.tuner.TunerTestUtils.{majTuning, rastTuning, ussakTuning}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningSessionTest extends AnyFlatSpec with Matchers with MockFactory {

  trait Fixture {
    val businessyncStub: Businessync = stub[Businessync]
    val tuningSession: TuningSession = new TuningSession(businessyncStub)
  }

  "constructor" should "initialize with empty tunings and tuningIndex set to 0" in new Fixture {
    tuningSession.tunings shouldBe empty
    tuningSession.tuningIndex shouldBe 0
    tuningSession.tuningCount shouldBe 0
    tuningSession.currentTuning shouldBe Tuning.Edo12
  }

  "tunings" should "set tunings" in new Fixture {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    tuningSession.tunings shouldBe Seq(majTuning, rastTuning)
    tuningSession.tuningCount shouldBe 2

    // When: Setting an empty list should reset tuningIndex
    tuningSession.tunings = Seq()
    // Then
    tuningSession.tunings shouldBe empty
    tuningSession.tuningCount shouldBe 0
  }

  "tuningIndex" should "throw an exception when setting an invalid tuningIndex" in new Fixture {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    assertThrows[IllegalArgumentException] {
      tuningSession.tuningIndex = -1
    }
  }

  it should "allow setting a valid tuningIndex" in new Fixture {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning, ussakTuning)
    // When
    tuningSession.tuningIndex = 1
    // Then
    tuningSession.tuningIndex shouldBe 1
    tuningSession.currentTuning shouldBe rastTuning
  }

  it should "allow setting a value >= the sequence size and set it to the last index" in new Fixture {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning, ussakTuning)
    // When
    tuningSession.tuningIndex = 4
    // Then
    tuningSession.tuningIndex shouldBe 2
    tuningSession.currentTuning shouldBe ussakTuning
  }

  it should "publish TuningIndexUpdatedEvent when tuningIndex changes" in new Fixture {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // When
    tuningSession.tuningIndex = 1 // Set a new index
    // Then
    businessyncStub.publish _ verify TuningIndexUpdatedEvent(1, rastTuning)
  }

  it should "not publish TuningIndexUpdatedEvent when tuningIndex remains the same" in new Fixture {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // When
    tuningSession.tuningIndex = 0 // Setting the same index
    // Then
    (businessyncStub.publish _).verify(*).once()
  }

  "tunings" should "set tunings and adjust tuningIndex if necessary" in new Fixture {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    tuningSession.tunings shouldBe Seq(majTuning, rastTuning)
    // The default index should remain valid
    tuningSession.tuningIndex shouldBe 0
    tuningSession.tuningCount shouldBe 2

    // When: Adding an extra tuning
    tuningSession.tuningIndex = 1
    tuningSession.tunings = Seq(majTuning, ussakTuning, rastTuning)
    // Then: The index is maintained
    tuningSession.tunings shouldBe Seq(majTuning, ussakTuning, rastTuning)
    tuningSession.tuningIndex shouldBe 1
    tuningSession.tuningCount shouldBe 3
    tuningSession.currentTuning shouldBe ussakTuning

    // When: Removing tunings such that the count is less than the index
    tuningSession.tunings = Seq(rastTuning)
    // Then: The index is changed
    tuningSession.tuningIndex shouldBe 0

    // When: Setting an empty list should reset tuningIndex
    tuningSession.tunings = Seq()
    // Then
    tuningSession.tuningIndex shouldBe 0
    tuningSession.tuningCount shouldBe 0
    tuningSession.currentTuning shouldBe Tuning.Edo12
  }

  it should "publish TuningsUpdatedEvent when tunings are set" in new Fixture {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Setting it twice with the same tunings, only publishes one event
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    (businessyncStub.publish _).verify(TuningsUpdatedEvent(Seq(majTuning, rastTuning), 0)).once()
  }

  it should "not publish TuningsUpdatedEvent when the same tunings is set" in new Fixture {
    // Given
    val tunings: Seq[Tuning] = Seq(majTuning, rastTuning)
    tuningSession.tunings = tunings
    // When
    tuningSession.tunings = tunings
    // Then
    (businessyncStub.publish _).verify(TuningsUpdatedEvent(Seq(majTuning, rastTuning), 0)).once()
  }

  "previousTuning, nextTuning and nextBy" should "cycle to the previous and next tunings correctly" in new Fixture {
    tuningSession.tunings = Seq(majTuning, rastTuning, ussakTuning)
    tuningSession.tuningIndex = 0

    tuningSession.nextTuning()
    tuningSession.tuningIndex shouldBe 1
    tuningSession.currentTuning shouldBe rastTuning

    tuningSession.previousTuning()
    tuningSession.tuningIndex shouldBe 0
    tuningSession.currentTuning shouldBe majTuning

    // Should cycle to the last tuning
    tuningSession.previousTuning()
    tuningSession.tuningIndex shouldBe 2
    tuningSession.currentTuning shouldBe ussakTuning

    // Should cycle back to the first tuning
    tuningSession.nextTuning()
    tuningSession.tuningIndex shouldBe 0
    tuningSession.currentTuning shouldBe majTuning

    tuningSession.nextBy(2)
    tuningSession.tuningIndex shouldBe 2
    tuningSession.currentTuning shouldBe ussakTuning

    tuningSession.nextBy(2)
    tuningSession.tuningIndex shouldBe 1
    tuningSession.currentTuning shouldBe rastTuning

    tuningSession.nextBy(-3)
    tuningSession.tuningIndex shouldBe 1
    tuningSession.currentTuning shouldBe rastTuning
  }
}

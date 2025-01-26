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
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningSessionTest extends AnyFlatSpec with Matchers with BeforeAndAfter with MockFactory {

  private var tuningSession: TuningSession = _

  // Mocks for dependencies
  private val businessyncStub: Businessync = stub[Businessync]

  private val majTuning = OctaveTuning("Just C Major",
    Seq(0.0, 0.0, 3.91, 0.0, -13.69, -1.96, 0.0, 1.96, 0.0, -15.64, 0.0, -11.73))
  private val rastTuning = OctaveTuning("C Rast",
    Seq(0.0, 0.0, 3.91, 0.0, -13.69, -1.96, 0.0, 1.96, 0.0, 5.87, -3.91, -11.73))
  // TODO #95
  private val ussakTuning = OctaveTuning("D Ussak",
    Seq(0.0, 0.0, 3.91, 0.0, -45.45, -1.96, 0.0, 1.96, 0.0, 5.87, -3.91, 0))

  before {
    tuningSession = new TuningSession(businessyncStub)
  }

  "constructor" should "initialize with empty tunings and tuningIndex set to 0" in {
    tuningSession.tunings shouldBe empty
    tuningSession.tuningIndex shouldBe 0
    tuningSession.tuningCount shouldBe 0
    tuningSession.currentTuning shouldBe OctaveTuning.Edo12
  }

  "tunings" should "set tunings" in {
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

  "tuningIndex" should "throw an exception when setting an invalid tuningIndex" in {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    assertThrows[IllegalArgumentException] {
      tuningSession.tuningIndex = -1
    }
    assertThrows[IllegalArgumentException] {
      // Invalid index since we have only 2 tunings (0 and 1)
      tuningSession.tuningIndex = 2
    }
  }

  it should "allow setting a valid tuningIndex" in {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning, ussakTuning)
    // Then
    tuningSession.tuningIndex = 1
    tuningSession.tuningIndex shouldBe 1
    tuningSession.currentTuning shouldBe rastTuning
  }

  it should "publish TuningIndexUpdatedEvent when tuningIndex changes" in {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning)
    val oldTuningIndex = tuningSession.tuningIndex
    // When
    tuningSession.tuningIndex = 1 // Set a new index
    // Then
    businessyncStub.publish _ verify TuningIndexUpdatedEvent(1, rastTuning)
  }

  it should "not publish TuningIndexUpdatedEvent when tuningIndex remains the same" in {
    // Given
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // When
    tuningSession.tuningIndex = 0 // Setting the same index
    // Then
    (businessyncStub.publish _).verify(*).once()
  }

  "tunings" should "set tunings and adjust tuningIndex if necessary" in {
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
    tuningSession.currentTuning shouldBe OctaveTuning.Edo12
  }

  it should "publish TuningsUpdatedEvent when tunings are set" in {
    // When
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Setting it twice with the same tunings, only publishes one event
    tuningSession.tunings = Seq(majTuning, rastTuning)
    // Then
    (businessyncStub.publish _).verify(TuningsUpdatedEvent(Seq(majTuning, rastTuning), 0)).once()
  }

  "previousTuning, nextTuning and nextBy" should "cycle to the previous and next tunings correctly" in {
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
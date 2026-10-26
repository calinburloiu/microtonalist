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

package org.calinburloiu.music.microtonalist.perf.run

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Using

class PacerTest extends AnyWordSpec with Matchers {

  private val Period: FiniteDuration = 20.millis

  "Pacer" should {
    "wait for the next tick" in {
      Using.resource(Pacer(Period)) { pacer =>
        // Given
        val start = System.nanoTime()

        // When
        for (_ <- 1 to 5) pacer.awaitNextTick()

        // Then
        (System.nanoTime() - start) should be >= (Period * 4).toNanos
      }
    }

    "not let missed ticks pile up" in {
      Using.resource(Pacer(Period)) { pacer =>
        // Given
        pacer.awaitNextTick()
        // A pause of 3 periods, during which ticks are missed
        CountDownLatch(1).await((Period * 3).toNanos, TimeUnit.NANOSECONDS)
        val start = System.nanoTime()

        // When
        pacer.awaitNextTick()
        pacer.awaitNextTick()

        // Then
        (System.nanoTime() - start) should be >= Period.toNanos
      }
    }
  }
}

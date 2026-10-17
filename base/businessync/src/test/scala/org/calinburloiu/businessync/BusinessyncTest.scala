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

package org.calinburloiu.businessync

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BusinessyncTest extends AnyWordSpec with Matchers {
  /** An event that isn't a [[BusinessyncEvent]], like the events of a library that doesn't know the bus. */
  case class PlainEvent(value: Int)

  "a Businessync" should {
    "accept an event of any type" in {
      // When / Then
      "Businessync(com.google.common.eventbus.EventBus()).publish(PlainEvent(1))" should compile
    }
  }
}

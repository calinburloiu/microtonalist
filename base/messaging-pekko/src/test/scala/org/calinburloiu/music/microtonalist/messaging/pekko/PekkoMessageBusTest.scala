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

package org.calinburloiu.music.microtonalist.messaging.pekko

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.calinburloiu.music.microtonalist.messaging.{MessageBusBehaviors, MessagingTestRuntime}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Runs the shared behaviors of a [[org.calinburloiu.music.microtonalist.messaging.MessageBus]] on a
 * [[PekkoMessagingRuntime]], over one shared test kit.
 */
class PekkoMessageBusTest extends AnyWordSpec with Matchers with BeforeAndAfterAll with MessageBusBehaviors {
  private val testKit: ActorTestKit = PekkoTestSupport.startTestKit("PekkoMessageBusTest")
  private val pekkoRuntime: PekkoMessagingRuntime = PekkoMessagingRuntime(testKit.system)

  override protected def newTestRuntime(): MessagingTestRuntime = PekkoTestRuntime(pekkoRuntime)

  override protected def afterAll(): Unit = testKit.shutdownTestKit()

  "a MessageBus" when {
    behave like aMessageBus()
  }
}

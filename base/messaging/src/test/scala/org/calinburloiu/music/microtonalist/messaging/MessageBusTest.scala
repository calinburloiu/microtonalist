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

package org.calinburloiu.music.microtonalist.messaging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MessageBusTest extends AnyWordSpec with Matchers with MessageBusBehaviors {

  import MessagingFixtures.*

  override protected def newTestRuntime(): MessagingTestRuntime = ManualTestRuntime()

  /** A bus over a [[ManualMessagingRuntime]], whose queues a case inspects. */
  private class ManualFixture {
    val runtime: ManualMessagingRuntime = ManualMessagingRuntime()
    val messageBus: MessageBus = MessageBus(runtime)
  }

  "a MessageBus" when {
    behave like aMessageBus()

    "running on the ManualMessagingRuntime" should {
      "start the Business mailbox at normal priority" in new ManualFixture {
        // When / Then
        runtime.mailbox("Business").priority shouldEqual Some(Thread.NORM_PRIORITY)
      }

      "start the UI mailbox after the Business one" in new ManualFixture {
        // When / Then
        runtime.mailboxes.map(_.name) shouldEqual Seq("Business", "UI")
      }

      "enqueue an event to the UI mailbox as soon as it's published" in new ManualFixture {
        // Given
        private var uiQueued = false
        messageBus.subscribeOnUi(classOf[Dog])(_ => ())
        messageBus.handleCommand(classOf[Retune]) { _ =>
          messageBus.publish(Dog("Rex"))
          uiQueued = !runtime.mailbox("UI").isEmpty
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.mailbox("Business").drain()

        // Then
        uiQueued shouldBe true
      }
    }
  }
}

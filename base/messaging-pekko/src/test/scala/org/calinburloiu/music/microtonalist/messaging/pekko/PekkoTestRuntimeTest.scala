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

import ch.qos.logback.classic.Level
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.calinburloiu.music.microtonalist.common.LogCapture.messagesAt
import org.calinburloiu.music.microtonalist.messaging.Mailbox
import org.calinburloiu.music.microtonalist.messaging.pekko.PekkoTestSupport.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Tests [[PekkoTestRuntime]] on one shared test kit.
 */
class PekkoTestRuntimeTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import PekkoTestRuntimeTest.*

  private val testKit: ActorTestKit = PekkoTestSupport.startTestKit("PekkoTestRuntimeTest")
  private val pekkoRuntime: PekkoMessagingRuntime = PekkoMessagingRuntime(testKit.system)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /**
   * A case's test runtime, with the default idle timeout.
   */
  class Fixture {
    /** The test runtime under test. */
    val testRuntime: PekkoTestRuntime = PekkoTestRuntime(pekkoRuntime)
  }

  "a PekkoTestRuntime" should {
    "return from awaitIdle once a chain of messages across its mailboxes has run, with their writes visible" in
      new Fixture {
        // Given
        // Members, unlike a block's local values, may refer to later ones from a lambda: each handler reaches the
        // other mailbox, and runs only once both are built.
        private var count = 0 // touched by one mailbox at a time, never by two at once
        private val ui: Mailbox[String] = testRuntime.runtime.startUiMailbox[String] { _ =>
          count += 1
          if (count < 50) business.enqueue("ping")
        }
        private val business: Mailbox[String] =
          testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY) { _ =>
            count += 1
            ui.enqueue("pong")
          }

        // When
        business.enqueue("ping")
        testRuntime.awaitIdle()

        // Then
        count shouldEqual 50
        testRuntime.close()
      }

    "throw from awaitIdle when a message runs past the timeout" in {
      // Given
      val testRuntime = PekkoTestRuntime(pekkoRuntime, idleTimeout = ShortIdleTimeout)
      val release = CountDownLatch(1)

      // When
      testRuntime.runtime.startMailbox[String]("Stuck", Thread.NORM_PRIORITY)(_ =>
        release.await(Timeout.toMillis, TimeUnit.MILLISECONDS)).enqueue("hold")

      // Then
      an[IllegalStateException] should be thrownBy testRuntime.awaitIdle()
      release.countDown()
      testRuntime.close()
    }

    "run onToolkit's body on the UI mailbox's thread, outside its messages" in {
      // Given
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val ui = testRuntime.runtime.startUiMailbox[String](_ => ())

      // When / Then
      testRuntime.onToolkit(ui.isCurrentThread) shouldBe true
      testRuntime.close()
    }

    "capture the logs of its mailboxes' threads" in {
      // Given
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val loggerName = s"${getClass.getName}.mailboxes"
      val business = testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY)(text =>
        Logger(loggerName).error(text))

      // When
      val (_, events) = testRuntime.capturingLogs(loggerName) {
        business.enqueue("logged")
        testRuntime.awaitIdle()
      }

      // Then
      events.messagesAt(Level.ERROR) shouldEqual Seq("logged")
      testRuntime.close()
    }

    "stop its mailboxes on close" in {
      // Given
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val business = testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY)(_ => ())

      // When
      testRuntime.close()

      // Then
      business.stop().isCompleted shouldBe true
    }
  }
}

/**
 * The constants of [[PekkoTestRuntimeTest]].
 */
object PekkoTestRuntimeTest {
  /** The idle timeout of a test runtime whose mailbox never goes idle in time, which its case waits out. */
  val ShortIdleTimeout: FiniteDuration = 100.millis
}

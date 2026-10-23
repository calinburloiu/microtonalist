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

package org.calinburloiu.music.microtonalist.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.typesafe.scalalogging.Logger
import org.calinburloiu.music.microtonalist.common.LogCapture.messagesAt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}

class LogCaptureTest extends AnyWordSpec with Matchers {

  private val TimeoutSeconds = 5L

  "LogCapture.capturing" should {
    "return the result of the body with the events logged on the calling thread" in {
      // Given
      val loggerName = s"${getClass.getName}.single"

      // When
      val (result, events) = LogCapture.capturing(loggerName) {
        Logger(loggerName).debug("hello")
        42
      }

      // Then
      result shouldEqual 42
      events.messagesAt(Level.DEBUG) shouldEqual Seq("hello")
    }

    "keep capturing a logger while another capture of it ends" in {
      // Given
      val loggerName = s"${getClass.getName}.overlapping"
      val otherStarted = CountDownLatch(1)
      val firstEnded = CountDownLatch(1)
      val otherEvents = CompletableFuture[Seq[ILoggingEvent]]()
      val other = Thread(() => {
        val (_, events) = LogCapture.capturing(loggerName) {
          otherStarted.countDown()
          firstEnded.await(TimeoutSeconds, TimeUnit.SECONDS)
          Logger(loggerName).debug("after the first capture")
        }
        otherEvents.complete(events)
        ()
      })

      // When
      LogCapture.capturing(loggerName) {
        other.start()
        otherStarted.await(TimeoutSeconds, TimeUnit.SECONDS) shouldBe true
      }
      firstEnded.countDown()

      // Then
      otherEvents.get(TimeoutSeconds, TimeUnit.SECONDS).messagesAt(Level.DEBUG) shouldEqual
        Seq("after the first capture")
    }
  }
}

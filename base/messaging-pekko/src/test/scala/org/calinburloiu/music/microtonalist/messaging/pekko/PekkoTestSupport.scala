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

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * What the suites of `messaging-pekko` share: their test kit, their timeout and a way onto the UI dispatcher.
 */
object PekkoTestSupport {
  /** How long a test waits for a mailbox's message or `Future`, which only a failing case waits out. */
  val Timeout: FiniteDuration = 5.seconds

  /**
   * Starts a test kit on `application-test.conf`, once SLF4J is initialised.
   *
   * @param name the name of the test kit's actor system
   * @return the started test kit, which the suite shuts down
   */
  def startTestKit(name: String): ActorTestKit = {
    // Pekko's SLF4J logger, started with the system, must not get SLF4J's substitute loggers.
    LoggerFactory.getILoggerFactory
    ActorTestKit(name, ConfigFactory.load("application-test"))
  }

  /** Runs `body` on the UI dispatcher's thread, outside any actor's message, and returns its result. */
  def onUiDispatcher[T](system: ActorSystem[?])(body: => T): T = {
    val uiDispatcher = system.dispatchers.lookup(DispatcherSelector.fromConfig(PekkoMessagingRuntime.UiDispatcherPath))
    Await.result(Future(body)(using uiDispatcher), Timeout)
  }
}

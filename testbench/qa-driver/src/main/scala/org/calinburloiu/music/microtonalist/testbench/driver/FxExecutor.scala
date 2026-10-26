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

package org.calinburloiu.music.microtonalist.testbench.driver

import javafx.application.Platform

import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit, TimeoutException}
import scala.concurrent.duration.*

/**
 * Runs code on the JavaFX Application Thread for the driver's thread.
 *
 * Every method throws an `IllegalStateException` while the JavaFX toolkit isn't running.
 *
 * @param timeout how long [[apply]] waits for the Application Thread
 */
final class FxExecutor(timeout: FiniteDuration = 10.seconds) {
  /**
   * Runs `body` on the JavaFX Application Thread and returns its result.
   *
   * Must not be called on the Application Thread itself, which would wait for itself until the timeout.
   *
   * @throws DriverException with the message of what `body` throws, or when it doesn't finish within the timeout
   */
  def apply[T](body: => T): T = {
    val result = CompletableFuture[T]()
    Platform.runLater { () =>
      try {
        result.complete(body)
      } catch {
        case e: Throwable => result.completeExceptionally(e)
      }
    }

    try {
      result.get(timeout.toMillis, TimeUnit.MILLISECONDS)
    } catch {
      case e: ExecutionException =>
        val cause = e.getCause
        throw DriverException(Option(cause.getMessage).getOrElse(cause.toString))
      case _: TimeoutException =>
        throw DriverException(s"The JavaFX Application Thread timed out after $timeout")
    }
  }

  /** Queues `body` on the JavaFX Application Thread without waiting for it, e.g. an input that may open a dialog. */
  def post(body: => Unit): Unit = Platform.runLater(() => body)

  /** Waits until the JavaFX Application Thread has run what is queued, twice, to let a pulse pass. */
  def idle(): Unit = {
    apply(())
    apply(())
  }
}

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
import javafx.stage.Window

import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutionException, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
 * Runs JavaFX in a test JVM, on the platform that `glass.platform` selects: the headless one in `qa-driver`'s forked
 * test JVM.
 */
object HeadlessFx {
  /** How long [[fx]] and [[start]] wait for the FX thread, in seconds. */
  val TimeoutSeconds: Long = 10

  private var isStarted: Boolean = false

  /** Starts the JavaFX toolkit, unless already started in this JVM, so that every suite may call it. */
  def start(): Unit = synchronized {
    if (!isStarted) {
      // Closing a suite's last window must not stop the toolkit, which can't be started again in the same JVM.
      Platform.setImplicitExit(false)
      val started = CountDownLatch(1)
      Platform.startup(() => started.countDown())
      require(started.await(TimeoutSeconds, TimeUnit.SECONDS), "The JavaFX toolkit did not start")
      isStarted = true
    }
  }

  /**
   * Runs `body` on the FX thread and returns its result, after waiting at most [[TimeoutSeconds]].
   *
   * Rethrows what `body` throws, and throws a `java.util.concurrent.TimeoutException` on a timeout.
   */
  def fx[T](body: => T): T = {
    if (Platform.isFxApplicationThread) {
      body
    } else {
      val result = CompletableFuture[T]()
      Platform.runLater { () =>
        try {
          result.complete(body)
        } catch {
          case e: Throwable => result.completeExceptionally(e)
        }
      }

      try {
        result.get(TimeoutSeconds, TimeUnit.SECONDS)
      } catch {
        case e: ExecutionException => throw e.getCause
      }
    }
  }

  /** Hides every window that JavaFX shows, so that the next test starts without any. */
  def closeAllWindows(): Unit = fx {
    Window.getWindows.asScala.toList.foreach(_.hide())
  }
}

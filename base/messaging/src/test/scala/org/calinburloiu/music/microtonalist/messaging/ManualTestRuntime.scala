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

import ch.qos.logback.classic.spi.ILoggingEvent
import org.calinburloiu.music.microtonalist.common.LogCapture

import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import javax.annotation.concurrent.GuardedBy
import scala.concurrent.Future

/**
 * A [[MessagingTestRuntime]] over a [[ManualMessagingRuntime]], whose messages run on the caller's thread when it
 * awaits idleness.
 *
 * Its UI toolkit is a thread of its own, started by the first [[onToolkit]], which the UI mailbox also counts as its
 * thread outside its messages, as a production runtime counts the toolkit's thread.
 */
final class ManualTestRuntime extends MessagingTestRuntime {

  private val manualRuntime: ManualMessagingRuntime = ManualMessagingRuntime()

  @GuardedBy("this")
  private var toolkit: Option[ExecutorService] = None

  @volatile private var toolkitThread: Option[Thread] = None

  override val runtime: MessagingRuntime = new MessagingRuntime {
    override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M] =
      manualRuntime.startMailbox(name, priority)(handler)

    override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M] = {
      val mailbox = manualRuntime.startUiMailbox(handler)
      new Mailbox[M] {
        override def name: String = mailbox.name

        override def enqueue(message: M): Unit = mailbox.enqueue(message)

        override def isCurrentThread: Boolean =
          mailbox.isCurrentThread || toolkitThread.contains(Thread.currentThread())

        override def stop(): Future[Unit] = mailbox.stop()
      }
    }
  }

  /**
   * Drains every mailbox with [[ManualMessagingRuntime.drainAll]].
   */
  override def awaitIdle(): Unit = manualRuntime.drainAll()

  override def onToolkit[T](body: => T): T = CompletableFuture.supplyAsync(() => body, startedToolkit()).join()

  override def capturingLogs[R](loggerName: String)(body: => R): (R, Seq[ILoggingEvent]) =
    LogCapture.capturing(loggerName)(body)

  /**
   * Shuts the toolkit's thread down, if it was started.
   */
  override def close(): Unit = synchronized {
    toolkit.foreach(_.shutdown())
  }

  private def startedToolkit(): ExecutorService = synchronized {
    toolkit.getOrElse {
      val executor = Executors.newSingleThreadExecutor()
      toolkitThread = Some(CompletableFuture.supplyAsync(() => Thread.currentThread(), executor).join())
      toolkit = Some(executor)
      executor
    }
  }
}

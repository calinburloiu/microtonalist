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

import ch.qos.logback.classic.spi.ILoggingEvent
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, MessagingRuntime, MessagingTestRuntime}

import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * A [[MessagingTestRuntime]] over a [[PekkoMessagingRuntime]] whose mailboxes count their messages in flight.
 *
 * Its count is meaningless once it's closed.
 *
 * @param pekkoRuntime the runtime that starts the mailboxes
 * @param idleTimeout how long [[awaitIdle]] and [[close]] wait
 */
@ThreadSafe
final class PekkoTestRuntime(pekkoRuntime: PekkoMessagingRuntime,
                             idleTimeout: FiniteDuration = PekkoTestSupport.Timeout)
  extends MessagingTestRuntime {

  // A message is counted in when enqueued and out once run. Since it enqueues its follow-ups before it's counted out,
  // a count of zero means idle; and the monitor publishes the handlers' writes to the thread that awaits idleness.
  private val idleness: AnyRef = new Object

  @GuardedBy("idleness")
  private var inFlight: Long = 0

  @GuardedBy("started")
  private val started: mutable.ArrayBuffer[Mailbox[?]] = mutable.ArrayBuffer()

  // The threads that ran a message or the toolkit's code, whose logs `capturingLogs` captures.
  private val threads: java.util.Set[Thread] = ConcurrentHashMap.newKeySet()

  override val runtime: MessagingRuntime = new MessagingRuntime {
    override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M] =
      counted(pekkoRuntime.startMailbox[M](name, priority)(countingOut(handler)))

    override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M] =
      counted(pekkoRuntime.startUiMailbox[M](countingOut(handler)))
  }

  /**
   * @inheritdoc
   *
   * @throws IllegalStateException if the mailboxes don't go idle within `idleTimeout`
   */
  override def awaitIdle(): Unit = idleness.synchronized {
    val deadline = idleTimeout.fromNow
    while (inFlight > 0) {
      if (deadline.isOverdue()) {
        throw IllegalStateException(s"The mailboxes didn't go idle within $idleTimeout: $inFlight messages in flight.")
      }
      idleness.wait(deadline.timeLeft.toMillis.max(1))
    }
  }

  override def onToolkit[T](body: => T): T = PekkoTestSupport.onUiDispatcher(pekkoRuntime.system) {
    threads.add(Thread.currentThread())
    body
  }

  override def capturingLogs[R](loggerName: String)(body: => R): (R, Seq[ILoggingEvent]) = {
    val caller = Thread.currentThread()
    LogCapture.capturing(loggerName, thread => (thread eq caller) || threads.contains(thread))(body)
  }

  /**
   * Stops every mailbox it started, and waits for them to stop.
   *
   * @throws java.util.concurrent.TimeoutException if they don't stop within `idleTimeout`
   */
  override def close(): Unit = {
    given ExecutionContext = ExecutionContext.parasitic
    val mailboxes = started.synchronized(started.toSeq)
    Await.ready(Future.sequence(mailboxes.map(_.stop())), idleTimeout)
  }

  private def counted[M <: AnyRef](mailbox: Mailbox[M]): Mailbox[M] = {
    val countingIn = new Mailbox[M] {
      override def name: String = mailbox.name

      override def enqueue(message: M): Unit = {
        idleness.synchronized {
          inFlight += 1
        }
        mailbox.enqueue(message)
      }

      override def isCurrentThread: Boolean = mailbox.isCurrentThread

      override def stop(): Future[Unit] = mailbox.stop()
    }
    started.synchronized {
      started += countingIn
    }
    countingIn
  }

  private def countingOut[M <: AnyRef](handler: M => Unit): M => Unit = { message =>
    threads.add(Thread.currentThread())
    try {
      handler(message)
    } finally {
      idleness.synchronized {
        inFlight -= 1
        if (inFlight == 0) {
          idleness.notifyAll()
        }
      }
    }
  }
}

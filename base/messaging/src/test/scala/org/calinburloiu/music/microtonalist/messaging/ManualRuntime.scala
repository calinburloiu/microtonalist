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

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

/**
 * A [[MessagingRuntime]] for tests, whose mailboxes run their messages only when a test drains them, on the test's
 * thread.
 *
 * Its mailboxes accept messages from any thread. Draining isn't thread-safe: drain from one thread at a time. An
 * exception thrown by a mailbox's handler propagates to the caller of the drain.
 */
final class ManualRuntime extends MessagingRuntime {

  import ManualRuntime.MaxDrainedMessages

  @volatile private var _mailboxes: Vector[ManualMailbox[?]] = Vector.empty

  /**
   * Starts a [[ManualMailbox]] with the given name and priority.
   */
  override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): ManualMailbox[M] =
    register(ManualMailbox(name, Some(priority), handler, this))

  /**
   * Starts a [[ManualMailbox]] named `UI`, with no priority.
   */
  override def startUiMailbox[M <: AnyRef](handler: M => Unit): ManualMailbox[M] =
    register(ManualMailbox("UI", None, handler, this))

  /**
   * The mailboxes started, in start order.
   *
   * Safe to call from any thread.
   */
  def mailboxes: Seq[ManualMailbox[?]] = _mailboxes

  /**
   * Returns the first mailbox started with the given name.
   *
   * @throws NoSuchElementException if no mailbox has that name
   */
  def mailbox(name: String): ManualMailbox[?] = _mailboxes.find(_.name == name).getOrElse {
    throw new NoSuchElementException(s"No mailbox named $name was started.")
  }

  /**
   * Drains every mailbox in start order, round after round, until all are empty, including the mailboxes started and
   * the messages enqueued meanwhile.
   *
   * @throws IllegalStateException if a mailbox of this runtime is running a message, or once this call has run
   *   [[ManualRuntime.MaxDrainedMessages]] messages
   */
  def drainAll(): Unit = drain(() => _mailboxes)

  /**
   * Drains the mailboxes that `targets` returns, round after round, asking it again before each round.
   */
  private[messaging] def drain(targets: () => Seq[ManualMailbox[?]]): Unit = {
    if (_mailboxes.exists(_.isRunning)) {
      throw new IllegalStateException("Cannot drain while a mailbox of the runtime is running a message.")
    }

    var drainedCount = 0
    var round = targets()
    while (round.exists(!_.isEmpty)) {
      for (mailbox <- round) {
        while (!mailbox.isEmpty) {
          if (drainedCount >= MaxDrainedMessages) {
            throw new IllegalStateException(s"A drain ran $MaxDrainedMessages messages without emptying the mailboxes.")
          }
          mailbox.runNext()
          drainedCount += 1
        }
      }
      round = targets()
    }
  }

  private def register[M <: AnyRef](mailbox: ManualMailbox[M]): ManualMailbox[M] = {
    synchronized {
      _mailboxes = _mailboxes :+ mailbox
    }
    mailbox
  }
}

/**
 * Constants of [[ManualRuntime]] and helpers for the tests that run a [[MessageBus]] on it.
 */
object ManualRuntime {
  /**
   * The most messages that one drain runs before it fails, assuming that its messages keep enqueuing others.
   */
  val MaxDrainedMessages: Int = 10000

  /**
   * Returns the id of the command that caused the message of `messageBus` that the calling thread runs, or `None`
   * outside its Business and UI messages or for a message that no command caused.
   */
  def causingCommandId(using messageBus: MessageBus): Option[Long] = messageBus.causingCommandId
}

/**
 * A [[Mailbox]] of a [[ManualRuntime]], which runs its messages only when drained.
 *
 * After [[stop]], [[enqueue]] drops its message.
 *
 * @param name the name it was started with
 * @param priority the thread priority it was started with, or `None` for the UI mailbox
 * @tparam M the type of the messages
 */
final class ManualMailbox[M <: AnyRef] private[messaging] (val name: String,
                                                           val priority: Option[Int],
                                                           handler: M => Unit,
                                                           runtime: ManualRuntime) extends Mailbox[M] {

  private val urgentQueue: mutable.Queue[M] = mutable.Queue()
  private val normalQueue: mutable.Queue[M] = mutable.Queue()
  private var stopPromise: Option[Promise[Unit]] = None

  @volatile private var runningThread: Option[Thread] = None

  override def enqueue(message: M): Unit = synchronized {
    if (stopPromise.isEmpty) {
      message match {
        case _: Urgent => urgentQueue.enqueue(message)
        case _ => normalQueue.enqueue(message)
      }
    }
  }

  /**
   * Tells whether the calling thread is running one of this mailbox's messages.
   */
  override def isCurrentThread: Boolean = runningThread.contains(Thread.currentThread())

  /**
   * Stops the mailbox; the returned `Future` completes at once when nothing is queued, otherwise when a drain empties
   * the mailbox.
   */
  override def stop(): Future[Unit] = synchronized {
    val promise = stopPromise.getOrElse(Promise[Unit]())
    stopPromise = Some(promise)
    completeStopIfEmpty()
    promise.future
  }

  /**
   * Tells whether no message is queued.
   */
  def isEmpty: Boolean = synchronized {
    urgentQueue.isEmpty && normalQueue.isEmpty
  }

  /**
   * Runs this mailbox's messages, the urgent ones first, until none is queued, including those enqueued meanwhile.
   *
   * @throws IllegalStateException if a mailbox of its runtime is running a message, or once this call has run
   *   [[ManualRuntime.MaxDrainedMessages]] messages
   */
  def drain(): Unit = runtime.drain(() => Seq(this))

  /**
   * Tells whether this mailbox is running a message.
   */
  private[messaging] def isRunning: Boolean = runningThread.isDefined

  /**
   * Runs the next queued message, if any, on the calling thread, outside the mailbox's monitor.
   */
  private[messaging] def runNext(): Unit = {
    val next = synchronized {
      if (urgentQueue.nonEmpty) {
        Some(urgentQueue.dequeue())
      } else if (normalQueue.nonEmpty) {
        Some(normalQueue.dequeue())
      } else {
        None
      }
    }

    for (message <- next) {
      runningThread = Some(Thread.currentThread())
      try {
        handler(message)
      } finally {
        runningThread = None
        synchronized {
          completeStopIfEmpty()
        }
      }
    }
  }

  private def completeStopIfEmpty(): Unit = {
    if (urgentQueue.isEmpty && normalQueue.isEmpty) {
      stopPromise.foreach(_.trySuccess(()))
    }
  }
}

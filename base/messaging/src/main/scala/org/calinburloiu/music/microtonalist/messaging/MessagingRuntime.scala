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

import scala.concurrent.Future

/**
 * Starts the [[Mailbox]]es that the messaging runs its messages in.
 */
trait MessagingRuntime {

  /**
   * Starts a mailbox on a new thread of its own.
   *
   * Returns at once: the mailbox records its thread before it runs its first message.
   *
   * @param name the name of the mailbox and of its thread
   * @param priority the Java thread priority of the mailbox's thread, between `Thread.MIN_PRIORITY` and
   *   `Thread.MAX_PRIORITY`
   * @param handler runs each message, on the mailbox's thread
   * @tparam M the type of the messages
   * @return the started mailbox
   */
  def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M]

  /**
   * Starts the mailbox of the UI toolkit's thread.
   *
   * Returns only once the mailbox has recorded its thread, since the toolkit also calls UI code there outside the
   * mailbox's messages.
   *
   * @param handler runs each message, on the UI toolkit's thread
   * @tparam M the type of the messages
   * @return the started mailbox
   */
  def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M]
}

/**
 * A queue of messages that runs them one at a time on its thread: a thread of its own, or the UI toolkit's.
 *
 * An [[Urgent]] message overtakes the non-urgent ones queued; each kind keeps its arrival order.
 *
 * @tparam M the type of the messages
 */
trait Mailbox[-M <: AnyRef] {

  /**
   * Queues a message to run on the mailbox's thread.
   *
   * Safe to call from any thread.
   *
   * @param message the message to run
   */
  def enqueue(message: M): Unit

  /**
   * Tells whether the calling thread is the mailbox's.
   */
  def isCurrentThread: Boolean

  /**
   * Stops the mailbox: the messages already queued still run, and the returned `Future` completes after them.
   *
   * @return a `Future` that completes once the mailbox has stopped
   */
  def stop(): Future[Unit]
}

/**
 * Marks a message that overtakes the non-urgent messages queued in its [[Mailbox]].
 */
trait Urgent

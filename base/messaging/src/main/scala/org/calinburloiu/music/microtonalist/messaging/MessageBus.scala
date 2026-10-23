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

import com.typesafe.scalalogging.StrictLogging

import java.util.concurrent.atomic.AtomicLong
import javax.annotation.concurrent.ThreadSafe
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Runs the [[Command]]s dispatched from any thread on the Business Thread, the thread of the `Business` mailbox, which
 * runs one message at a time, and delivers the events published from any thread to their subscribers, on the Business
 * Thread or on the UI Thread, the thread of the `UI` mailbox.
 *
 * Constructing it starts the `Business` mailbox, at `Thread.NORM_PRIORITY`, then the `UI` mailbox. An exception that a
 * handler or a subscriber throws is logged at ERROR, and its mailbox goes on.
 *
 * @param runtime starts the mailboxes
 */
@ThreadSafe
class MessageBus(runtime: MessagingRuntime) extends StrictLogging {

  import MessageBus.{BusinessMessage, CommandMessage, EventMessage, UiMessage}

  private val commandHandlers: CommandHandlers = CommandHandlers()

  private val subscribers: Subscribers = Subscribers()

  private val nextCommandId: AtomicLong = AtomicLong(1)

  /** The causing command id of the running Business message, touched only on the Business Thread. */
  private var businessCausingCommandId: Option[Long] = None

  /** The causing command id of the running UI message, touched only on the UI Thread. */
  private var uiCausingCommandId: Option[Long] = None

  /** The events published by the running Business message, touched only on the Business Thread. */
  private val localEvents: mutable.Queue[EventMessage] = mutable.Queue()

  private val business: Mailbox[BusinessMessage] =
    runtime.startMailbox[BusinessMessage]("Business", Thread.NORM_PRIORITY)(runBusinessMessage)

  private val ui: Mailbox[UiMessage] = runtime.startUiMailbox[UiMessage](runUiMessage)

  /**
   * Queues a command to run on the Business Thread, after the messages already queued there, even when called from it.
   *
   * @throws IllegalStateException on the calling thread, if no handler or several match the command's class
   */
  def dispatch(command: Command): Unit = {
    val handler = commandHandlers.resolve(command.getClass)
    business.enqueue(CommandMessage(command, handler, Some(nextCommandId.getAndIncrement())))
  }

  /**
   * Registers the handler of the commands of `commandClass` and of its subtypes, which runs on the Business Thread.
   *
   * @throws IllegalArgumentException if `commandClass` is, extends or is extended by a class that already has a handler
   */
  def handle[C <: Command](commandClass: Class[C])(handler: C => Unit): Unit = {
    commandHandlers.register(commandClass, handler)
  }

  /**
   * Delivers an event to the subscribers of its class and of its supertypes subscribed by now, once per subscription,
   * in subscription order on each thread.
   *
   * Published from the Business Thread, it reaches the Business subscribers after the running message, before the next
   * one; otherwise, after the messages already queued on the Business Thread. It reaches the UI subscribers after the
   * messages already queued on the UI Thread.
   */
  def publish(event: AnyRef): Unit = {
    val (uiSubscribers, businessSubscribers) = subscribers.matching(event).partition(_.onUi)
    val stamp = causingCommandId

    if (uiSubscribers.nonEmpty) {
      ui.enqueue(EventMessage(event, uiSubscribers, stamp))
    }

    if (businessSubscribers.nonEmpty) {
      val message = EventMessage(event, businessSubscribers, stamp)
      if (business.isCurrentThread) {
        localEvents.enqueue(message)
      } else {
        business.enqueue(message)
      }
    }
  }

  /**
   * Subscribes a handler, which runs on the Business Thread, to the events of `eventClass` and of its subtypes
   * published from now on.
   */
  def subscribe[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription =
    subscribers.add(eventClass, onUi = false, handler)

  /**
   * Subscribes a handler, which runs on the UI Thread, to the events of `eventClass` and of its subtypes published from
   * now on.
   */
  def subscribeOnUi[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription =
    subscribers.add(eventClass, onUi = true, handler)

  /**
   * Checks that the calling thread is the Business Thread.
   *
   * @throws IllegalStateException if it isn't
   */
  def requireBusinessThread(): Unit = {
    requireThread(business, "Business")
  }

  /**
   * Checks that the calling thread is the UI Thread.
   *
   * @throws IllegalStateException if it isn't
   */
  def requireUiThread(): Unit = {
    requireThread(ui, "UI")
  }

  /**
   * The id of the command that caused the message that the calling thread runs, or `None` off the Business and UI
   * Threads or for a message that no command caused.
   */
  private[messaging] def causingCommandId: Option[Long] = {
    if (business.isCurrentThread) {
      businessCausingCommandId
    } else if (ui.isCurrentThread) {
      uiCausingCommandId
    } else {
      None
    }
  }

  private def requireThread(mailbox: Mailbox[?], threadName: String): Unit = {
    if (!mailbox.isCurrentThread) {
      throw IllegalStateException(s"Expected the $threadName Thread, but running on ${Thread.currentThread().getName}.")
    }
  }

  private def runBusinessMessage(message: BusinessMessage): Unit = {
    businessCausingCommandId = message.causingCommandId
    message match {
      case CommandMessage(command, handler, _) => runCommand(command, handler)
      case eventMessage: EventMessage => deliver(eventMessage)
    }

    while (localEvents.nonEmpty) {
      deliver(localEvents.dequeue())
    }
  }

  private def runUiMessage(message: UiMessage): Unit = {
    uiCausingCommandId = message.causingCommandId
    message match {
      case eventMessage: EventMessage => deliver(eventMessage)
    }
  }

  private def runCommand(command: Command, handler: Command => Unit): Unit = {
    try {
      handler(command)
    } catch {
      case NonFatal(exception) => logger.error(s"The handler of $command failed.", exception)
    }
  }

  private def deliver(message: EventMessage): Unit = {
    for (subscriber <- message.subscribers if subscriber.isActive) {
      try {
        subscriber.deliver(message.event)
      } catch {
        case NonFatal(exception) => logger.error(s"A subscriber of ${message.event} failed.", exception)
      }
    }
  }
}

/**
 * The internals of [[MessageBus]].
 */
object MessageBus {

  /** A message of a mailbox, stamped with the id of the command that caused it, if any. */
  private[messaging] sealed trait StampedMessage {
    def causingCommandId: Option[Long]
  }

  /** A message of the Business mailbox. */
  private[messaging] sealed trait BusinessMessage extends StampedMessage

  /** A message of the UI mailbox. */
  private[messaging] sealed trait UiMessage extends StampedMessage

  /** A message of the Business mailbox that runs a command with its resolved handler, stamped with its own id. */
  private[messaging] final case class CommandMessage(command: Command,
                                                     handler: Command => Unit,
                                                     causingCommandId: Option[Long]) extends BusinessMessage

  /** A message, of either mailbox, that delivers an event to the subscribers matched when it was published. */
  private[messaging] final case class EventMessage(event: AnyRef,
                                                   subscribers: Vector[Subscribers.Subscriber],
                                                   causingCommandId: Option[Long])
    extends BusinessMessage with UiMessage
}

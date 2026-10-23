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
import scala.concurrent.{BlockContext, CanAwait, ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Runs the [[Command]]s dispatched from any thread on the Business Thread, the thread of the `Business` mailbox, which
 * runs one message at a time, and delivers the events published from any thread to their subscribers, on the Business
 * Thread or on the UI Thread, the thread of the `UI` mailbox.
 *
 * Constructing it starts the `Business` mailbox, at `Thread.NORM_PRIORITY`, then the `UI` mailbox. An exception that a
 * handler, a subscriber or an `onComplete…` continuation throws is logged at ERROR, and its mailbox goes on; on the
 * Business Thread, it also fails the reply of the [[Request]] whose `ask` caused it.
 *
 * Inside a Business or UI message, `Await` on a `Future` that hasn't completed, and `scala.concurrent.blocking`, throw
 * `IllegalStateException` instead of blocking the thread; blocking by other means, such as on a latch, isn't caught.
 * Such code continues the `Future` with `mapOnBusiness`, `flatMapOnBusiness`, `onCompleteOnBusiness`, `mapOnUi` or
 * `onCompleteOnUi`, each of which runs in a message of its own on its thread. On the Business Thread, the events that a
 * continuation publishes reach their Business subscribers after it, as a handler's do.
 *
 * @param runtime starts the mailboxes
 */
@ThreadSafe
class MessageBus(runtime: MessagingRuntime) extends StrictLogging {

  import CommandHandlers.CommandHandler
  import MessageBus.{BusinessMessage, Cause, CommandMessage, ContinuationMessage, EventMessage, UiMessage}

  private val commandHandlers: CommandHandlers = CommandHandlers()

  private val subscribers: Subscribers = Subscribers()

  private val nextCommandId: AtomicLong = AtomicLong(1)

  /** The cause of the running Business message, touched only on the Business Thread. */
  private var businessCause: Option[Cause] = None

  /** The cause of the running UI message, touched only on the UI Thread. */
  private var uiCause: Option[Cause] = None

  /** The events published by the running Business message, touched only on the Business Thread. */
  private val localEvents: mutable.Queue[EventMessage] = mutable.Queue()

  /** The failure reported by the running Business continuation, touched only on the Business Thread. */
  private var continuationFailure: Option[Throwable] = None

  private val businessBlockContext: BlockContext =
    refusingBlockContext("Business", "mapOnBusiness, flatMapOnBusiness or onCompleteOnBusiness")

  private val uiBlockContext: BlockContext = refusingBlockContext("UI", "mapOnUi or onCompleteOnUi")

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
    business.enqueue(CommandMessage(command, handler, Some(Cause(nextCommandId.getAndIncrement(), None))))
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
   * Queues a request to run on the Business Thread, like [[dispatch]], and returns its reply.
   *
   * The reply completes on the Business Thread with the outcome of the handler's `Future`, once the events published by
   * the handler, or by the Business continuation that completed that `Future`, have reached their Business subscribers.
   * It fails instead with the first exception thrown on the Business Thread before it completes, by the handler, by an
   * `onCompleteOnBusiness` function or by a Business subscriber that the request caused. A failure of the handler's
   * `Future` after that is logged at ERROR like a thrown exception.
   *
   * @throws IllegalStateException on the calling thread, if no handler or several match the request's class, or if its
   *   handler was registered with [[handle]]
   */
  def ask[R](request: Request[R]): Future[R] = {
    val handler = commandHandlers.resolve(request.getClass)
    handler match {
      case _: CommandHandler.Plain => throw IllegalStateException(
          s"Cannot ask ${request.getClass.getName}, whose handler was registered with handle and gives no reply.")
      case _: CommandHandler.Replying =>
    }

    val reply = Promise[Any]()
    business.enqueue(CommandMessage(request, handler, Some(Cause(nextCommandId.getAndIncrement(), Some(reply)))))
    // The handler of `request`'s class replies with a `Future[R]`, save for a generic request class, whose one handler
    // serves every type argument (see `Request`).
    reply.future.asInstanceOf[Future[R]]
  }

  /**
   * Registers the handler of the requests of `requestClass` and of its subtypes, which runs on the Business Thread and
   * replies with a `Future`. A handler with a value at hand returns it in `Future.successful`.
   *
   * A request [[dispatch]]ed instead of asked runs its handler and drops its reply; a failure of the handler's
   * `Future`, then or later, is logged at ERROR like a thrown exception.
   *
   * @throws IllegalArgumentException if `requestClass` is, extends or is extended by a class that already has a handler
   */
  def handleRequest[R, C <: Request[R]](requestClass: Class[C])(handler: C => Future[R]): Unit = {
    commandHandlers.registerRequest(requestClass, handler)
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
    val stamp = cause

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
   * The id of the command that caused the message that the calling thread runs, or `None` outside a Business or UI
   * message, such as in code that the UI toolkit calls directly, or for a message that no command caused.
   */
  private[messaging] def causingCommandId: Option[Long] = cause.map(_.commandId)

  /**
   * An `ExecutionContext` that runs each task in a Business message of its own, stamped with the cause of the message
   * that the calling thread runs. It logs a failure that a task reports as `A continuation failed.`, and keeps it to
   * fail the reply of the causing `ask` once the message's drain is over.
   */
  private[messaging] def businessContinuations: ExecutionContext =
    continuations(business, cause)(exception => continuationFailure = continuationFailure.orElse(Some(exception)))

  /**
   * An `ExecutionContext` that runs each task in a UI message of its own, stamped with the cause of the message that
   * the calling thread runs. It only logs a failure that a task reports, as `A continuation failed.`.
   */
  private[messaging] def uiContinuations: ExecutionContext = continuations(ui, cause)(_ => ())

  /** The cause of the message that the calling thread runs, or `None` where [[causingCommandId]] is `None`. */
  private def cause: Option[Cause] = {
    if (business.isCurrentThread) {
      businessCause
    } else if (ui.isCurrentThread) {
      uiCause
    } else {
      None
    }
  }

  /**
   * An `ExecutionContext` that queues each task to `mailbox`, stamped with `stamp`, and logs the failure that a task
   * reports, then passes it to `keepFailure` on the mailbox's thread.
   */
  private def continuations(mailbox: Mailbox[ContinuationMessage], stamp: Option[Cause])(
      keepFailure: Throwable => Unit): ExecutionContext = new ExecutionContext {
    override def execute(runnable: Runnable): Unit = mailbox.enqueue(ContinuationMessage(runnable, stamp))

    override def reportFailure(exception: Throwable): Unit = {
      logger.error("A continuation failed.", exception)
      keepFailure(exception)
    }
  }

  /**
   * A `BlockContext` that refuses to block the thread named `threadName`, which `Await` consults only for a `Future`
   * that hasn't completed.
   */
  private def refusingBlockContext(threadName: String, continuations: String): BlockContext = new BlockContext {
    override def blockOn[T](thunk: => T)(using CanAwait): T = throw IllegalStateException(
      s"Cannot await on the $threadName Thread, which must not block; continue the Future with $continuations.")
  }

  private def requireThread(mailbox: Mailbox[?], threadName: String): Unit = {
    if (!mailbox.isCurrentThread) {
      throw IllegalStateException(s"Expected the $threadName Thread, but running on ${Thread.currentThread().getName}.")
    }
  }

  private def runBusinessMessage(message: BusinessMessage): Unit = BlockContext.withBlockContext(businessBlockContext) {
    businessCause = message.cause
    try {
      val (handlerReply, bodyFailure) = message match {
        case CommandMessage(command, handler, _) =>
          val outcome = runCommand(command, handler)
          (outcome.toOption.flatten.map(command -> _), outcome.failed.toOption)
        case eventMessage: EventMessage => (None, deliver(eventMessage))
        case ContinuationMessage(runnable, _) =>
          runnable.run()
          (None, continuationFailure)
      }

      var failure = bodyFailure
      while (localEvents.nonEmpty) {
        val subscriberFailure = deliver(localEvents.dequeue())
        failure = failure.orElse(subscriberFailure)
      }

      // Inside the try, since it reads the cause when it continues a pending handler's reply.
      settleReply(message.cause, handlerReply, failure)
    } finally {
      businessCause = None
      localEvents.clear()
      continuationFailure = None
    }
  }

  /**
   * Fails the reply of `cause` with `failure`, if any, then completes it with the outcome of the handler's reply, at
   * once if it has completed, otherwise in a Business message of its own once it completes; the first outcome wins.
   */
  private def settleReply(cause: Option[Cause],
                          handlerReply: Option[(Command, Future[Any])],
                          failure: Option[Throwable]): Unit = {
    for (reply <- cause.flatMap(_.reply); exception <- failure) {
      reply.tryFailure(exception)
    }

    for ((command, future) <- handlerReply) {
      future.value match {
        case Some(outcome) => completeReply(cause, command, outcome)
        case None =>
          future.onComplete(completeReply(cause, command, _))(using businessContinuations)
      }
    }
  }

  /**
   * Completes the reply of `cause` with the outcome of its handler's reply; a failed outcome that no reply carries,
   * since there is none or it has already completed, is logged.
   */
  private def completeReply(cause: Option[Cause], command: Command, outcome: Try[Any]): Unit = {
    val isCarried = cause.flatMap(_.reply).exists(_.tryComplete(outcome))
    if (!isCarried) {
      outcome.failed.foreach(exception => logger.error(s"The handler of $command failed.", exception))
    }
  }

  private def runUiMessage(message: UiMessage): Unit = BlockContext.withBlockContext(uiBlockContext) {
    uiCause = message.cause
    try {
      message match {
        case eventMessage: EventMessage => deliver(eventMessage)
        case ContinuationMessage(runnable, _) => runnable.run()
      }
    } finally {
      // The toolkit also runs code on this thread outside the messages, which no command caused.
      uiCause = None
    }
  }

  /** Runs a command's handler, logging its failure, and returns the reply of a request handler. */
  private def runCommand(command: Command, handler: CommandHandler): Try[Option[Future[Any]]] = {
    val outcome = Try(handler.run(command))
    outcome.failed.foreach(exception => logger.error(s"The handler of $command failed.", exception))
    outcome
  }

  /** Delivers an event to its active subscribers, logging their failures, and returns the first one. */
  private def deliver(message: EventMessage): Option[Throwable] = {
    var failure: Option[Throwable] = None
    for (subscriber <- message.subscribers if subscriber.isActive) {
      try {
        subscriber.deliver(message.event)
      } catch {
        case NonFatal(exception) =>
          logger.error(s"A subscriber of ${message.event} failed.", exception)
          failure = failure.orElse(Some(exception))
      }
    }

    failure
  }
}

/**
 * Holds the private message types of [[MessageBus]]'s mailboxes.
 */
object MessageBus {

  /**
   * What caused a message: the id of a command, with the reply of the `ask` that sent it, if one did.
   *
   * @param commandId the id of the command
   * @param reply the reply of the `ask` that sent the command, or `None` for a dispatched command
   */
  private[messaging] final case class Cause(commandId: Long, reply: Option[Promise[Any]])

  /** A message of a mailbox, stamped with its cause, if a command caused it. */
  private[messaging] sealed trait StampedMessage {
    def cause: Option[Cause]
  }

  /** A message of the Business mailbox. */
  private[messaging] sealed trait BusinessMessage extends StampedMessage

  /** A message of the UI mailbox. */
  private[messaging] sealed trait UiMessage extends StampedMessage

  /** A message of the Business mailbox that runs a command with its resolved handler, stamped with its own id. */
  private[messaging] final case class CommandMessage(command: Command,
                                                     handler: CommandHandlers.CommandHandler,
                                                     cause: Option[Cause]) extends BusinessMessage

  /** A message, of either mailbox, that delivers an event to the subscribers matched when it was published. */
  private[messaging] final case class EventMessage(event: AnyRef,
                                                   subscribers: Vector[Subscribers.Subscriber],
                                                   cause: Option[Cause])
    extends BusinessMessage with UiMessage

  /** A message, of either mailbox, that runs a task of a `Future`'s continuation. */
  private[messaging] final case class ContinuationMessage(runnable: Runnable, cause: Option[Cause])
    extends BusinessMessage with UiMessage
}

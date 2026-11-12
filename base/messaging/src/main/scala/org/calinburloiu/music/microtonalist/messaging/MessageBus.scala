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
import scala.util.{Failure, Success, Try}

/**
 * Runs the [[Command]]s dispatched from any thread on the Business Thread, the thread of the `Business` mailbox, which
 * runs one message at a time, and delivers the events published from any thread to their subscribers, on the Business
 * Thread or on the UI Thread, the thread of the `UI` mailbox.
 *
 * Constructing it starts the `Business` mailbox, at `Thread.NORM_PRIORITY`, then the `UI` mailbox, so it must not be
 * constructed on the UI toolkit's thread, which that start waits for. An exception that a handler, a subscriber or an
 * `onComplete…` continuation throws is logged at ERROR, and its mailbox goes on; on the Business Thread, it also fails
 * the reply of the [[Request]] whose `ask` caused it, and so the `Future`s continued from that reply.
 *
 * Inside a Business or UI message, `Await` on a `Future` that hasn't completed, and `scala.concurrent.blocking`, throw
 * `IllegalStateException` instead of blocking the thread; blocking by other means, such as on a latch, isn't caught.
 * Such code continues the `Future` with what it imports from [[BusinessContinuations]] or [[UiContinuations]], such as
 * `mapOnBusiness` or `onCompleteOnUi`, each of which runs in a message of its own on its thread. On the Business
 * Thread, the events that a continuation publishes reach their Business subscribers after it, as a handler's do.
 *
 * @param runtime starts the mailboxes
 */
@ThreadSafe
class MessageBus(runtime: MessagingRuntime) extends StrictLogging {

  import CommandHandlerRegistry.CommandHandler
  import MessageBus.{
    BusinessMessage, BusinessThreadName, Cause, CommandMessage, ContinuationMessage, EventMessage, UiMessage
  }

  private val commandHandlerRegistry: CommandHandlerRegistry = CommandHandlerRegistry()

  private val subscriberRegistry: SubscriberRegistry = SubscriberRegistry()

  private val nextCommandId: AtomicLong = AtomicLong(1)

  /** The cause of the running Business message, touched only on the Business Thread. */
  private var businessCause: Option[Cause] = None

  /** The cause of the running UI message, touched only on the UI Thread. */
  private var uiCause: Option[Cause] = None

  /** The events published by the running Business message, touched only on the Business Thread. */
  private val localEvents: mutable.Queue[EventMessage] = mutable.Queue()

  /** The failure reported by the running Business continuation, if any, touched only on the Business Thread. */
  private var continuationFailure: Try[Unit] = Success(())

  private val businessBlockContext: BlockContext =
    refusingBlockContext(BusinessThreadName, "BusinessContinuations")

  private val uiBlockContext: BlockContext = refusingBlockContext(MessagingRuntime.UiMailboxName, "UiContinuations")

  private val businessMailbox: Mailbox[BusinessMessage] =
    runtime.startMailbox[BusinessMessage](BusinessThreadName, Thread.NORM_PRIORITY)(runBusinessMessage)

  private val uiMailbox: Mailbox[UiMessage] = runtime.startUiMailbox[UiMessage](runUiMessage)

  /**
   * Queues a command to run on the Business Thread, after the messages already queued there, even when called from it.
   *
   * @throws IllegalStateException on the calling thread, if no handler or several match the command's class
   */
  def dispatch(command: Command): Unit = {
    val handler = commandHandlerRegistry.resolve(command.getClass)
    businessMailbox.enqueue(CommandMessage(command, handler, Some(Cause(nextCommandId.getAndIncrement(), None))))
  }

  /**
   * Registers the handler of the commands of `commandClass` and of its subtypes, which runs on the Business Thread.
   *
   * @throws IllegalArgumentException if `commandClass` is, extends or is extended by a class that already has a handler
   */
  def handleCommand[C <: Command](commandClass: Class[C])(handler: C => Unit): Unit = {
    commandHandlerRegistry.registerCommand(commandClass, handler)
  }

  /**
   * Queues a request to run on the Business Thread, like [[dispatch]], and returns its reply.
   *
   * The reply completes on the Business Thread with the outcome of the handler's `Future`, once the events published by
   * the handler, or by the Business continuation that completed that `Future`, have reached their Business subscribers.
   * It fails instead with the first exception thrown on the Business Thread before it completes, by the handler, by an
   * `onCompleteOnBusiness` function or by a Business subscriber that the request caused, which suppresses the others
   * thrown by then. A failure of the handler's `Future` after that is logged at ERROR like a thrown exception.
   *
   * A continuation that the handler starts on the `Future` it returns may thus run after the reply completes, too late
   * for its events to precede the reply and for its failure to fail it.
   *
   * @throws IllegalStateException on the calling thread, if no handler or several match the request's class, or if its
   *   handler was registered with [[handleCommand]]
   */
  def ask[R](request: Request[R]): Future[R] = {
    val handler = commandHandlerRegistry.resolve(request.getClass)
    handler match {
      case _: CommandHandler.NonReplying => throw IllegalStateException(
          s"Cannot ask ${request.getClass.getName}, whose handler was registered with handleCommand and gives no reply.")
      case _: CommandHandler.Replying =>
    }

    val reply = Promise[Any]()
    businessMailbox.enqueue(CommandMessage(request, handler, Some(Cause(nextCommandId.getAndIncrement(), Some(reply)))))
    // The handler of `request`'s class replies with a `Future[R]`, save for a generic request class, whose one handler
    // serves every type argument (see `Request`).
    reply.future.asInstanceOf[Future[R]]
  }

  /**
   * Registers the handler of the requests of `requestClass` and of its subtypes, which runs on the Business Thread and
   * replies with a `Future`. A handler with a value at hand returns it in `Future.successful`; one that continues a
   * `Future` returns the `Future` of its last continuation, such as the one that `mapOnBusiness` returns, since the
   * reply of [[ask]] waits for no other continuation.
   *
   * A request [[dispatch]]ed instead of asked runs its handler and drops its reply; a failure of the handler's
   * `Future`, then or later, is logged at ERROR like a thrown exception.
   *
   * @throws IllegalArgumentException if `requestClass` is, extends or is extended by a class that already has a handler
   */
  def handleRequest[R, C <: Request[R]](requestClass: Class[C])(handler: C => Future[R]): Unit = {
    commandHandlerRegistry.registerRequest(requestClass, handler)
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
    val (uiSubscribers, businessSubscribers) = subscriberRegistry.subscribersOf(event).partition(_.onUi)
    val stamp = cause

    if (uiSubscribers.nonEmpty) {
      uiMailbox.enqueue(EventMessage(event, uiSubscribers, stamp))
    }

    if (businessSubscribers.nonEmpty) {
      val message = EventMessage(event, businessSubscribers, stamp)
      if (businessMailbox.isCurrentThread) {
        localEvents.enqueue(message)
      } else {
        businessMailbox.enqueue(message)
      }
    }
  }

  /**
   * Subscribes a handler, which runs on the Business Thread, to the events of `eventClass` and of its subtypes
   * published from now on.
   */
  def subscribeOnBusiness[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription =
    subscriberRegistry.register(eventClass, onUi = false, handler)

  /**
   * Subscribes a handler, which runs on the UI Thread, to the events of `eventClass` and of its subtypes published from
   * now on.
   */
  def subscribeOnUi[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription =
    subscriberRegistry.register(eventClass, onUi = true, handler)

  /**
   * Checks that the calling thread is the Business Thread.
   *
   * @throws IllegalStateException if it isn't
   */
  def requireBusinessThread(): Unit = {
    requireThread(businessMailbox)
  }

  /**
   * Checks that the calling thread is the UI Thread.
   *
   * @throws IllegalStateException if it isn't
   */
  def requireUiThread(): Unit = {
    requireThread(uiMailbox)
  }

  /**
   * The id of the command that caused the message that the calling thread runs, or `None` outside a Business or UI
   * message, such as in code that the UI toolkit calls directly, or for a message that no command caused.
   */
  private[messaging] def causingCommandId: Option[Long] = cause.map(_.commandId)

  /**
   * An `ExecutionContext` that runs each task in a Business message of its own, stamped with the cause of the message
   * that the calling thread runs. It logs a failure that a task reports as `A continuation failed.`, and keeps one
   * reported on the Business Thread to fail the reply of the causing `ask` once the message's drain is over.
   */
  private[messaging] def businessContinuationsExecutionContext: ExecutionContext =
    continuationsExecutionContext(businessMailbox, cause)(exception => {
      requireBusinessThread()
      continuationFailure = continuationFailure.followedBy(Failure(exception))
    })

  /**
   * An `ExecutionContext` that runs each task in a UI message of its own, stamped with the cause of the message that
   * the calling thread runs. It only logs a failure that a task reports, as `A continuation failed.`.
   */
  private[messaging] def uiContinuationsExecutionContext: ExecutionContext =
    continuationsExecutionContext(uiMailbox, cause)(_ => ())

  /** The cause of the message that the calling thread runs, or `None` where [[causingCommandId]] is `None`. */
  private def cause: Option[Cause] = {
    if (businessMailbox.isCurrentThread) {
      businessCause
    } else if (uiMailbox.isCurrentThread) {
      uiCause
    } else {
      None
    }
  }

  /**
   * An `ExecutionContext` that queues each task to `mailbox`, stamped with `stamp`, and logs the failure that a task
   * reports, then passes it to `keepFailure` if it is reported on the mailbox's thread.
   */
  private def continuationsExecutionContext(mailbox: Mailbox[ContinuationMessage], stamp: Option[Cause])(
      keepFailure: Throwable => Unit): ExecutionContext = new ExecutionContext {
    override def execute(runnable: Runnable): Unit = mailbox.enqueue(ContinuationMessage(runnable, stamp))

    override def reportFailure(exception: Throwable): Unit = {
      logger.error("A continuation failed.", exception)
      // Scala reports a failure of `execute` itself on the thread that completes the Future, but `keepFailure` touches
      // state confined to the mailbox's thread.
      if (mailbox.isCurrentThread) {
        keepFailure(exception)
      }
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

  private def requireThread(mailbox: Mailbox[?]): Unit = {
    if (!mailbox.isCurrentThread) {
      throw IllegalStateException(
        s"Expected the ${mailbox.name} Thread, but running on ${Thread.currentThread().getName}.")
    }
  }

  private def runBusinessMessage(message: BusinessMessage): Unit = BlockContext.withBlockContext(businessBlockContext) {
    businessCause = message.cause
    try {
      // Each case settles the reply inside the try, since continuing a pending handler's reply reads the cause.
      message match {
        case CommandMessage(command, handler, cause) =>
          runCommand(command, handler) match {
            case Success(handlerReply) => settleReply(cause, command, handlerReply, drainLocalEvents())
            case Failure(exception) => carryFailure(cause, Failure(exception).followedBy(drainLocalEvents()))
          }
        case eventMessage: EventMessage =>
          carryFailure(eventMessage.cause, deliver(eventMessage).followedBy(drainLocalEvents()))
        case ContinuationMessage(runnable, cause) =>
          runnable.run()
          carryFailure(cause, continuationFailure.followedBy(drainLocalEvents()))
      }
    } finally {
      businessCause = None
      localEvents.clear()
      continuationFailure = Success(())
    }
  }

  /**
   * Delivers the events published by the running Business message, then those that their subscribers publish, and
   * returns the first failure of the subscribers, suppressing the rest.
   */
  private def drainLocalEvents(): Try[Unit] = {
    var outcome: Try[Unit] = Success(())
    while (localEvents.nonEmpty) {
      outcome = outcome.followedBy(deliver(localEvents.dequeue()))
    }

    outcome
  }

  /**
   * Fails the reply of `cause` with the failure of `drained`, if any, which suppresses the failure of a handler's reply
   * that has already failed, then completes it with the outcome of the handler's reply, at once if it has completed,
   * otherwise in a Business message of its own once it completes; the first outcome wins.
   */
  private def settleReply(cause: Option[Cause],
                          command: Command,
                          handlerReply: Future[Any],
                          drained: Try[Unit]): Unit = {
    for (primary <- drained.failed; outcome <- handlerReply.value; other <- outcome.failed) {
      suppress(primary, other)
    }
    carryFailure(cause, drained)

    handlerReply.value match {
      case Some(outcome) => completeReply(cause, command, outcome)
      case None =>
        handlerReply.onComplete(completeReply(cause, command, _))(using businessContinuationsExecutionContext)
    }
  }

  /** Has the reply of `cause` carry the failure of `outcome`, if any, unless the reply has already completed. */
  private def carryFailure(cause: Option[Cause], outcome: Try[Unit]): Unit = {
    for (reply <- cause.flatMap(_.reply); exception <- outcome.failed) {
      reply.tryFailure(exception)
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

  /** Runs a command's handler, logging what it throws, and returns its reply. */
  private def runCommand(command: Command, handler: CommandHandler): Try[Future[Any]] = {
    val outcome = Try(handler.run(command))
    outcome.failed.foreach(exception => logger.error(s"The handler of $command failed.", exception))
    outcome
  }

  /**
   * Delivers an event to its active subscribers, logging their failures, and returns the first, suppressing the rest.
   */
  private def deliver(message: EventMessage): Try[Unit] = {
    var outcome: Try[Unit] = Success(())
    for (subscriber <- message.subscribers if subscriber.isActive) {
      val delivery = Try(subscriber.deliver(message.event))
      delivery.failed.foreach(exception => logger.error(s"A subscriber of ${message.event} failed.", exception))
      outcome = outcome.followedBy(delivery)
    }

    outcome
  }

  extension (outcome: Try[Unit]) {
    /** This outcome if it failed, otherwise `later`; a failure of this suppresses that of `later`. */
    private def followedBy(later: Try[Unit]): Try[Unit] = {
      for (primary <- outcome.failed; other <- later.failed) {
        suppress(primary, other)
      }

      if (outcome.isFailure) outcome else later
    }
  }

  private def suppress(primary: Throwable, other: Throwable): Unit = {
    // The same exception can fail twice, and `addSuppressed` refuses to suppress an exception in itself.
    if (primary ne other) {
      primary.addSuppressed(other)
    }
  }
}

/**
 * Holds the private message types of [[MessageBus]]'s mailboxes.
 */
object MessageBus {

  /** The name of the Business mailbox and of its thread. */
  private val BusinessThreadName: String = "Business"

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
                                                     handler: CommandHandlerRegistry.CommandHandler,
                                                     cause: Option[Cause]) extends BusinessMessage

  /** A message, of either mailbox, that delivers an event to the subscribers matched when it was published. */
  private[messaging] final case class EventMessage(event: AnyRef,
                                                   subscribers: Vector[SubscriberRegistry.Subscriber],
                                                   cause: Option[Cause])
    extends BusinessMessage with UiMessage

  /** A message, of either mailbox, that runs a task of a `Future`'s continuation. */
  private[messaging] final case class ContinuationMessage(runnable: Runnable,
                                                          override val cause: Option[Cause])
    extends BusinessMessage with UiMessage
}

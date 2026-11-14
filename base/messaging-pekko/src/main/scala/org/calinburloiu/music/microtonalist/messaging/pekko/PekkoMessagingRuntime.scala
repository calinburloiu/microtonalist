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

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector, Props}
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, MessagingRuntime}
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicLong
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise, TimeoutException}

/**
 * The [[MessagingRuntime]] on Pekko Typed, which runs each mailbox in an actor of its own.
 *
 * Its [[shutdown]] runs the [[ShutdownPhase]]s' tasks, then stops the pinned mailboxes still running, the last started
 * first, then terminates the actor system, which ends the UI mailbox.
 *
 * @param system the actor system that runs the mailboxes' actors
 * @param uiStartTimeout how long [[startUiMailbox]] waits for the UI mailbox to record its thread
 */
@ThreadSafe
final class PekkoMessagingRuntime private[pekko] (
    private[pekko] val system: ActorSystem[?],
    uiStartTimeout: FiniteDuration = PekkoMessagingRuntime.UiStartTimeout
) extends MessagingRuntime {

  // The pinned mailboxes started and not yet stopped, in start order.
  @GuardedBy("running")
  private val running: mutable.ArrayBuffer[Mailbox[?]] = mutable.ArrayBuffer()

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "stop-mailboxes") { () =>
    // Each step only sends `Stop`, so it may run on the thread that stopped the previous mailbox.
    given ExecutionContext = ExecutionContext.parasitic
    PekkoMessagingRuntime.stopLastStartedFirst(runningMailboxes).map(_ => Done)
  }

  /**
   * @throws IllegalArgumentException if `priority` is outside `Thread.MIN_PRIORITY..Thread.MAX_PRIORITY`
   */
  override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M] = {
    require(Thread.MIN_PRIORITY <= priority && priority <= Thread.MAX_PRIORITY,
      s"The priority of mailbox $name, $priority, is outside ${Thread.MIN_PRIORITY}..${Thread.MAX_PRIORITY}.")
    val props: Props = DispatcherSelector.fromConfig(PekkoMessagingRuntime.PinnedDispatcherPath)
      .withMailboxFromConfig(PekkoMessagingRuntime.MailboxPath)
    val onStart = () => {
      val thread = Thread.currentThread()
      thread.setName(name)
      thread.setPriority(priority)
    }

    // Held while the mailbox starts, so that a mailbox stopped at once, e.g. by termination, is forgotten after it's
    // added.
    running.synchronized {
      lazy val mailbox: Mailbox[M] = PekkoMailbox[M](name,
        behavior => system.systemActorOf(behavior, PekkoMessagingRuntime.nextActorName(), props), onStart,
        () => running.synchronized { running.filterInPlace(_ ne mailbox) })(handler)
      running += mailbox
      mailbox
    }
  }

  /**
   * @throws IllegalStateException if the mailbox didn't record its thread within the start timeout, e.g. because the
   *   call ran on that thread
   */
  override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M] = {
    val props: Props = DispatcherSelector.fromConfig(PekkoMessagingRuntime.UiDispatcherPath)
      .withMailboxFromConfig(PekkoMessagingRuntime.MailboxPath)
    // The toolkit's thread isn't the runtime's to name or to prioritise. The mailbox isn't one of the running pinned
    // ones, since the actor system's termination ends it.
    val started = Promise[Unit]()
    val mailbox = PekkoMailbox[M](MessagingRuntime.UiMailboxName,
      behavior => system.systemActorOf(behavior, PekkoMessagingRuntime.nextActorName(), props),
      () => started.success(()), () => ())(handler)

    try {
      Await.ready(started.future, uiStartTimeout)
    } catch {
      case _: TimeoutException => throw IllegalStateException(
          s"The UI mailbox didn't start within $uiStartTimeout: startUiMailbox must not run on the UI toolkit's thread.")
    }
    mailbox
  }

  /**
   * Adds a task that [[shutdown]] runs in `phase`, alongside the phase's other tasks, and waits for before the next
   * phase. Safe to call from any thread; a task added once its phase has run never runs.
   *
   * @param phase the phase that runs the task
   * @param name the name of the task, for the logs
   * @param task starts the task, and returns a `Future` that completes once the task is done
   */
  def addShutdownTask(phase: ShutdownPhase, name: String)(task: () => Future[Unit]): Unit = {
    CoordinatedShutdown(system).addTask(phase.pekkoPhase, name) { () =>
      task().map(_ => Done)(using ExecutionContext.parasitic)
    }
  }

  /**
   * Shuts the runtime down, as the class describes, or returns the shutdown already in progress. Safe to call from any
   * thread.
   *
   * @return a `Future` that completes once the actor system has terminated
   */
  def shutdown(): Future[Unit] = {
    // Parasitic, since the system's own dispatchers have shut down once the run completes.
    CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason).map(_ => ())(using ExecutionContext.parasitic)
  }

  /** The pinned mailboxes started and not yet stopped, in start order. */
  private[pekko] def runningMailboxes: Seq[Mailbox[?]] = running.synchronized {
    running.toSeq
  }
}

/**
 * The configuration paths and defaults of [[PekkoMessagingRuntime]].
 */
object PekkoMessagingRuntime {
  /** How long [[PekkoMessagingRuntime.startUiMailbox]] waits, by default, for the UI mailbox to record its thread. */
  val UiStartTimeout: FiniteDuration = 10.seconds

  private[pekko] val PinnedDispatcherPath: String = "microtonalist.messaging.pinned-dispatcher"
  private[pekko] val UiDispatcherPath: String = "microtonalist.messaging.ui-dispatcher"
  private[pekko] val MailboxPath: String = "microtonalist.messaging.mailbox"

  /**
   * Starts an actor system named `microtonalist` and a runtime on it.
   *
   * @param config the configuration of the actor system
   * @return the started runtime, which [[PekkoMessagingRuntime.shutdown]] shuts down with its actor system
   */
  def start(config: Config = ConfigFactory.load()): PekkoMessagingRuntime = {
    // Pekko's SLF4J logger, started with the system, must not get SLF4J's substitute loggers.
    LoggerFactory.getILoggerFactory
    PekkoMessagingRuntime(ActorSystem[Nothing](Behaviors.empty[Nothing], "microtonalist", config))
  }

  /**
   * Stops `mailboxes` one at a time, from the last to the first, each once the one after it has stopped.
   *
   * @param mailboxes the mailboxes, in start order
   * @return a `Future` that completes once the first mailbox has stopped
   */
  private[pekko] def stopLastStartedFirst(mailboxes: Seq[Mailbox[?]])(using ExecutionContext): Future[Unit] = {
    mailboxes.reverse.foldLeft(Future.unit) { (previous, mailbox) => previous.flatMap(_ => mailbox.stop()) }
  }

  // Shared by the runtimes, since several may run over one actor system, as in the tests: a mailbox's name may be
  // reused, or be no valid actor name, so it only names the thread.
  private val nextMailboxNumber: AtomicLong = AtomicLong()

  private def nextActorName(): String = s"mailbox-${nextMailboxNumber.getAndIncrement()}"
}

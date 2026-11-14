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

import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.dispatch.ControlMessage
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, Urgent}

import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.concurrent.ThreadSafe
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/**
 * A [[Mailbox]] over an actor that `spawn` starts, which runs its messages with `handler`. A non-fatal exception that
 * escapes `handler` is logged at ERROR and the mailbox goes on, so Pekko's supervision never stops it. An [[Urgent]]
 * message overtakes the queued normal ones, which requires `spawn` to use a control-aware mailbox. Once [[stop]] is
 * called, from any thread, [[enqueue]] drops its messages.
 *
 * @param name the name of the mailbox
 * @param spawn starts the mailbox's actor with the given behavior
 * @param onStart runs in the actor's setup, on its thread, once the mailbox has recorded that thread
 * @param onStopped runs once the actor has stopped, on its thread, before the `Future` of [[stop]] completes
 * @param handler runs each message, on the actor's thread
 * @tparam M the type of the messages
 */
@ThreadSafe
private[pekko] final class PekkoMailbox[M <: AnyRef](override val name: String,
                                                     spawn: Behavior[AnyRef] => ActorRef[AnyRef],
                                                     onStart: () => Unit,
                                                     onStopped: () => Unit)(handler: M => Unit)
  extends Mailbox[M] with StrictLogging {

  // Declared before `actor`, whose setup may record the thread before the constructor goes on.
  @volatile private var thread: Option[Thread] = None
  private val isStopping: AtomicBoolean = AtomicBoolean(false)
  private val stopped: Promise[Unit] = Promise()

  private val actor: ActorRef[AnyRef] =
    spawn(PekkoMailbox.behavior(onStart, recordThread, runSafely, onStopped, stopped))

  override def enqueue(message: M): Unit = {
    if (!isStopping.get()) {
      message match {
        case _: Urgent => actor ! PekkoMailbox.UrgentEnvelope(message)
        case _ => actor ! message
      }
    }
  }

  override def isCurrentThread: Boolean = thread.exists(_ eq Thread.currentThread())

  override def stop(): Future[Unit] = {
    // Only the first call sends `Stop`: the actor would never read a second one, which would go to dead letters.
    if (isStopping.compareAndSet(false, true)) {
      actor ! PekkoMailbox.Stop
    }
    stopped.future
  }

  private def recordThread(): Unit = {
    thread = Some(Thread.currentThread())
  }

  private def runSafely(message: M): Unit = {
    try {
      handler(message)
    } catch {
      case NonFatal(exception) => logger.error("A message of mailbox {} failed.", name, exception)
    }
  }
}

private object PekkoMailbox {
  /**
   * Carries an [[Urgent]] message. As a [[ControlMessage]], it goes to the front of the control-aware mailbox, ahead of
   * the queued normal messages.
   *
   * @param message the urgent message
   */
  private[pekko] final case class UrgentEnvelope(message: AnyRef) extends ControlMessage

  /** Stops the actor once the messages queued before it have run. */
  private case object Stop

  private def behavior[M <: AnyRef](onStart: () => Unit, recordThread: () => Unit, handler: M => Unit,
                                    onStopped: () => Unit, stopped: Promise[Unit]): Behavior[AnyRef] =
    Behaviors.setup { _ =>
      recordThread()
      onStart()
      Behaviors.receiveMessage[AnyRef] {
        case Stop => Behaviors.stopped
        case received =>
          val message = received match {
            case UrgentEnvelope(urgent) => urgent
            case normal => normal
          }
          // Only `enqueue` sends to the actor, with an `M`; a type test on `M` would be unchecked.
          handler(message.asInstanceOf[M])
          Behaviors.same
      }.receiveSignal {
        case (_, PostStop) =>
          onStopped()
          stopped.success(())
          Behaviors.same
      }
    }
}

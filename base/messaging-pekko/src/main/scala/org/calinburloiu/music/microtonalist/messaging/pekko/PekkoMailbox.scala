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

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.calinburloiu.music.microtonalist.messaging.Mailbox

import javax.annotation.concurrent.ThreadSafe
import scala.concurrent.Future

/**
 * A [[Mailbox]] over an actor that `spawn` starts, which runs its messages with `handler`.
 *
 * @param name the name of the mailbox
 * @param spawn starts the mailbox's actor with the given behavior
 * @param onStart runs in the actor's setup, on its thread, before the mailbox records that thread
 * @param handler runs each message, on the actor's thread
 * @tparam M the type of the messages
 */
@ThreadSafe
private[pekko] final class PekkoMailbox[M <: AnyRef](override val name: String,
                                                     spawn: Behavior[AnyRef] => ActorRef[AnyRef],
                                                     onStart: () => Unit)(handler: M => Unit)
  extends Mailbox[M] {

  // Declared before `actor`, whose setup may record the thread before the constructor goes on.
  @volatile private var thread: Option[Thread] = None

  private val actor: ActorRef[AnyRef] = spawn(PekkoMailbox.behavior(onStart, recordThread, handler))

  override def enqueue(message: M): Unit = actor ! message

  override def isCurrentThread: Boolean = thread.exists(_ eq Thread.currentThread())

  override def stop(): Future[Unit] = ???

  private def recordThread(): Unit = {
    thread = Some(Thread.currentThread())
  }
}

private object PekkoMailbox {
  private def behavior[M <: AnyRef](onStart: () => Unit, recordThread: () => Unit,
                                    handler: M => Unit): Behavior[AnyRef] = Behaviors.setup { _ =>
    onStart()
    recordThread()
    Behaviors.receiveMessage { message =>
      // Only `enqueue` sends to the actor, with an `M`; a type test on `M` would be unchecked.
      handler(message.asInstanceOf[M])
      Behaviors.same
    }
  }
}

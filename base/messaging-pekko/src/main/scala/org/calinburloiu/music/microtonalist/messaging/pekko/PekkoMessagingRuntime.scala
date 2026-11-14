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

import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector, Props}
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, MessagingRuntime}

import java.util.concurrent.atomic.AtomicLong
import javax.annotation.concurrent.ThreadSafe

/**
 * The [[MessagingRuntime]] on Pekko Typed, which runs each mailbox in an actor of its own.
 *
 * @param system the actor system that runs the mailboxes' actors
 */
@ThreadSafe
final class PekkoMessagingRuntime private[pekko] (private[pekko] val system: ActorSystem[?])
  extends MessagingRuntime {

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

    PekkoMailbox[M](name, behavior => system.systemActorOf(behavior, PekkoMessagingRuntime.nextActorName(), props),
      onStart)(handler)
  }

  override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M] = ???
}

/**
 * The configuration paths of [[PekkoMessagingRuntime]].
 */
object PekkoMessagingRuntime {
  private[pekko] val PinnedDispatcherPath: String = "microtonalist.messaging.pinned-dispatcher"
  private[pekko] val UiDispatcherPath: String = "microtonalist.messaging.ui-dispatcher"
  private[pekko] val MailboxPath: String = "microtonalist.messaging.mailbox"

  // Shared by the runtimes, since several may run over one actor system, as in the tests: a mailbox's name may be
  // reused, or be no valid actor name, so it only names the thread.
  private val nextMailboxNumber: AtomicLong = AtomicLong()

  private def nextActorName(): String = s"mailbox-${nextMailboxNumber.getAndIncrement()}"
}

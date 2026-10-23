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

import javax.annotation.concurrent.ThreadSafe
import scala.util.control.NonFatal

/**
 * Runs the [[Command]]s dispatched from any thread on the Business Thread, the thread of the `Business` mailbox, which
 * runs one message at a time.
 *
 * Constructing it starts the `Business` mailbox, at `Thread.NORM_PRIORITY`. An exception that a handler throws is
 * logged at ERROR, and the mailbox goes on with its next message.
 *
 * @param runtime starts the mailboxes
 */
@ThreadSafe
class MessageBus(runtime: MessagingRuntime) extends StrictLogging {

  import MessageBus.CommandMessage

  private val commandHandlers: CommandHandlers = CommandHandlers()

  private val business: Mailbox[CommandMessage] =
    runtime.startMailbox[CommandMessage]("Business", Thread.NORM_PRIORITY)(runBusinessMessage)

  /**
   * Queues a command to run on the Business Thread, after the messages already queued there, even when called from it.
   *
   * @throws IllegalStateException on the calling thread, if no handler or several match the command's class
   */
  def dispatch(command: Command): Unit = {
    val handler = commandHandlers.resolve(command.getClass)
    business.enqueue(CommandMessage(command, handler))
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
   * Checks that the calling thread is the Business Thread.
   *
   * @throws IllegalStateException if it isn't
   */
  def requireBusinessThread(): Unit = {
    if (!business.isCurrentThread) {
      throw IllegalStateException(s"Expected the Business Thread, but running on ${Thread.currentThread().getName}.")
    }
  }

  private def runBusinessMessage(message: CommandMessage): Unit = {
    try {
      message.handler(message.command)
    } catch {
      case NonFatal(exception) => logger.error(s"The handler of ${message.command} failed.", exception)
    }
  }
}

/**
 * The internals of [[MessageBus]].
 */
object MessageBus {

  /** A message of the Business mailbox that runs a command with its resolved handler. */
  private[messaging] final case class CommandMessage(command: Command, handler: Command => Unit)
}

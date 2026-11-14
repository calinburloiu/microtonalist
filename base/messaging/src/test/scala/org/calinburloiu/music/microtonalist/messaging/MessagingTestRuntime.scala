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

import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * A [[MessagingRuntime]] under test, with what the shared behaviors of the messaging layer need to drive it.
 *
 * Closing it releases its threads.
 */
trait MessagingTestRuntime extends AutoCloseable {
  /**
   * The runtime that a case's [[MessageBus]] is built on.
   */
  def runtime: MessagingRuntime

  /**
   * Returns once no mailbox of [[runtime]] holds or runs a message, including those enqueued meanwhile; the writes of
   * those messages are then visible to the caller.
   */
  def awaitIdle(): Unit

  /**
   * Runs `body` on the UI toolkit's thread, outside any message, as the toolkit runs UI code, and returns its result.
   */
  def onToolkit[T](body: => T): T

  /**
   * Runs `body` and returns its result together with the events that the logger named `loggerName` and its descendants
   * emitted meanwhile on the caller's thread and on the threads of the mailboxes.
   */
  def capturingLogs[R](loggerName: String)(body: => R): (R, Seq[ILoggingEvent])
}

/**
 * Helpers for the tests that run a [[MessageBus]] on a [[MessagingTestRuntime]].
 */
object MessagingTestRuntime {
  /**
   * Returns the id of the command that caused the message of `messageBus` that the calling thread runs, or `None`
   * outside its Business and UI messages or for a message that no command caused.
   */
  def causingCommandId(using messageBus: MessageBus): Option[Long] = messageBus.causingCommandId
}

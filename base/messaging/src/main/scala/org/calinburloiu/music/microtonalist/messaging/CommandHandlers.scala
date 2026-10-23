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

import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe

/**
 * The registry of the command handlers of a [[MessageBus]]: at most one handler per class, and none for two related
 * classes, so that a command's class resolves to a handler unambiguously unless it extends several handled ones.
 */
@ThreadSafe
private[messaging] final class CommandHandlers {

  import CommandHandlers.Snapshot

  /** Replaced under the lock of `this`, read without it. */
  @volatile private var snapshot: Snapshot = Snapshot(Map.empty)

  /**
   * Registers the handler of the commands of `commandClass` and of its subtypes.
   *
   * @throws IllegalArgumentException if `commandClass` is, extends or is extended by a registered class
   */
  def register[C <: Command](commandClass: Class[C], handler: C => Unit): Unit = synchronized {
    val related = snapshot.handlers.keys.filter { registered =>
      registered.isAssignableFrom(commandClass) || commandClass.isAssignableFrom(registered)
    }
    require(related.isEmpty,
      s"Cannot handle ${commandClass.getName}, related to the handled ${related.map(_.getName).mkString(", ")}.")

    snapshot = Snapshot(snapshot.handlers + (commandClass -> (command => handler(commandClass.cast(command)))))
  }

  /**
   * Returns the handler registered for `commandClass` or for a supertype of it.
   *
   * @throws IllegalStateException if no handler or several match
   */
  def resolve(commandClass: Class[? <: Command]): Command => Unit = snapshot.resolve(commandClass)
}

private[messaging] object CommandHandlers {

  /**
   * The handlers registered at one time, with the resolutions made against them; a registration replaces the snapshot,
   * so that no resolution against older handlers outlives it.
   */
  private final class Snapshot(val handlers: Map[Class[?], Command => Unit]) {
    private val resolved: ConcurrentHashMap[Class[?], Command => Unit] = ConcurrentHashMap()

    /** `computeIfAbsent` caches no failure, so an unresolvable class throws on each call. */
    def resolve(commandClass: Class[? <: Command]): Command => Unit =
      resolved.computeIfAbsent(commandClass, _ => find(commandClass))

    private def find(commandClass: Class[? <: Command]): Command => Unit = {
      handlers.filter { case (registered, _) => registered.isAssignableFrom(commandClass) }.toSeq match {
        case Seq((_, handler)) => handler
        case Seq() => throw IllegalStateException(s"No handler is registered for ${commandClass.getName}.")
        case matching => throw IllegalStateException(
            s"Several handlers match ${commandClass.getName}: ${matching.map(_._1.getName).mkString(", ")}.")
      }
    }
  }
}

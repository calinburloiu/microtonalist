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
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.concurrent.ThreadSafe
import scala.collection.immutable.VectorMap

/**
 * The subscription registry of a [[MessageBus]], whose snapshot each change replaces so that matching an event reads
 * one snapshot of it, unaffected by the subscriptions made or cancelled meanwhile.
 */
@ThreadSafe
private[messaging] final class SubscriberRegistry {

  import SubscriberRegistry.{Snapshot, Subscriber}

  private val snapshot: AtomicReference[Snapshot] = AtomicReference(Snapshot(VectorMap.empty))

  /**
   * Registers a subscriber of the events of `eventClass` and of its subtypes.
   *
   * @param onUi whether the subscriber runs on the UI Thread, rather than on the Business Thread
   */
  def register[E <: AnyRef](eventClass: Class[E], onUi: Boolean, handler: E => Unit): Subscriber = {
    val subscriber = Subscriber(eventClass, onUi, event => handler(eventClass.cast(event)), this)
    snapshot.updateAndGet(current => Snapshot(current.subscribers.updated(subscriber, ())))
    subscriber
  }

  /**
   * Returns the subscribers of the event's class or of one of its supertypes, in subscription order.
   */
  def matching(event: AnyRef): Vector[Subscriber] = snapshot.get.matching(event.getClass)

  private def unregister(subscriber: Subscriber): Unit = {
    snapshot.updateAndGet(current => Snapshot(current.subscribers.removed(subscriber)))
  }
}

private[messaging] object SubscriberRegistry {

  /**
   * The subscribers at one time, as the keys of an insertion-ordered map, with the matches made against them; a change
   * replaces the snapshot, so that no match against older subscribers outlives it.
   */
  private final class Snapshot(val subscribers: VectorMap[Subscriber, Unit]) {
    private val matches: ConcurrentHashMap[Class[?], Vector[Subscriber]] = ConcurrentHashMap()

    def matching(eventClass: Class[?]): Vector[Subscriber] = matches.computeIfAbsent(eventClass,
      _ => subscribers.keysIterator.filter(_.eventClass.isAssignableFrom(eventClass)).toVector)
  }

  /**
   * A registered subscriber, which its [[Subscription]] deactivates before removing it from the registry, so that the
   * deliveries already matched to it skip it.
   */
  final class Subscriber(val eventClass: Class[?],
                         val onUi: Boolean,
                         handler: AnyRef => Unit,
                         registry: SubscriberRegistry) extends Subscription {
    @volatile private var _isActive: Boolean = true

    /** Tells whether the subscription hasn't been cancelled. */
    def isActive: Boolean = _isActive

    /** Calls the subscriber's handler with the event, which is an instance of `eventClass`. */
    def deliver(event: AnyRef): Unit = handler(event)

    override def cancel(): Unit = {
      _isActive = false
      registry.unregister(this)
    }
  }
}

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

import java.util.concurrent.atomic.AtomicReference
import javax.annotation.concurrent.ThreadSafe

/**
 * The subscription registry of a [[MessageBus]], copied on write so that matching an event reads one snapshot of it,
 * unaffected by the subscriptions made or cancelled meanwhile.
 */
@ThreadSafe
private[messaging] final class Subscribers {

  import Subscribers.Subscriber

  private val subscribers: AtomicReference[Vector[Subscriber]] = AtomicReference(Vector.empty)

  /**
   * Registers a subscriber of the events of `eventClass` and of its subtypes.
   *
   * @param onUi whether the subscriber runs on the UI Thread, rather than on the Business Thread
   */
  def add[E <: AnyRef](eventClass: Class[E], onUi: Boolean, handler: E => Unit): Subscriber = {
    val subscriber = Subscriber(eventClass, onUi, event => handler(eventClass.cast(event)), this)
    subscribers.updateAndGet(_ :+ subscriber)
    subscriber
  }

  /**
   * Returns the subscribers of the event's class or of one of its supertypes, in subscription order.
   */
  def matching(event: AnyRef): Vector[Subscriber] = {
    val eventClass = event.getClass
    subscribers.get.filter(_.eventClass.isAssignableFrom(eventClass))
  }

  private def remove(subscriber: Subscriber): Unit = {
    subscribers.updateAndGet(_.filterNot(_ eq subscriber))
  }
}

private[messaging] object Subscribers {

  /**
   * A registered subscriber, which its [[Subscription]] deactivates before removing it from the registry, so that the
   * deliveries already matched to it skip it.
   */
  final class Subscriber(val eventClass: Class[?],
                         val onUi: Boolean,
                         handler: AnyRef => Unit,
                         registry: Subscribers) extends Subscription {
    @volatile private var _isActive: Boolean = true

    /** Tells whether the subscription hasn't been cancelled. */
    def isActive: Boolean = _isActive

    /** Calls the subscriber's handler with the event, which is an instance of `eventClass`. */
    def deliver(event: AnyRef): Unit = handler(event)

    override def cancel(): Unit = {
      _isActive = false
      registry.remove(this)
    }
  }
}

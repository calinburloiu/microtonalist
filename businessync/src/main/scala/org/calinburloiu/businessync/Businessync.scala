/*
 * Copyright 2025 Calin-Andrei Burloiu
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

package org.calinburloiu.businessync

import com.google.common.eventbus.EventBus

import scala.concurrent.Future

class Businessync(@deprecated eventBus: EventBus) {
  /**
   * Publishes an event to its subscribers and delivers it on either the Business or the UI Thread based on which
   * method subscribers used for subscribing, [[subscribe]] or [[subscribeOnUi]], respectively.
   *
   * TODO #90 Details
   *
   * @param event
   */
  def publish(event: BusinessyncEvent): Unit = eventBus.post(event)

  /**
   * If called from the Business Thread, delivers an event to its Business Thread subscribers immediately. Otherwise,
   * [[IllegalStateException]] is thrown. Subscribers from UI are omitted.
   *
   * TODO #90 Details
   *
   * @param event
   */
  def handle(event: BusinessyncEvent): Unit = publish(event)

  /**
   * If called from the UI Thread, delivers an event to its subscribers immediately. Otherwise,
   * [[IllegalStateException]] is thrown. Subscribers from the Business Thread are omitted.
   *
   * TODO #90 Details
   *
   * @param event
   */
  def handleOnUi(event: BusinessyncEvent): Unit = ???

  /**
   * Subscribes to events that match the given class to be delivered on the Business Thread by calling the given
   * function handler.
   *
   * TODO #90 Details
   *
   * @param eventClass
   * @param handler
   * @tparam E
   */
  def subscribe[E >: BusinessyncEvent](eventClass: Class[E], handler: E => Unit): Unit = {}

  /**
   * Subscribes to events that match the given class to be delivered on the UI Thread by calling the given
   * function handler.
   *
   * TODO #90 Details
   *
   * @param eventClass
   * @param handler
   * @tparam E
   */
  def subscribeOnUi[E >: BusinessyncEvent](eventClass: Class[E], handler: E => Unit): Unit = {}

  @deprecated("Will be replaced by subscribe")
  def register(obj: AnyRef): Unit = eventBus.register(obj)

  /**
   * Runs the given function on the Business Thread.
   *
   * TODO #90 Details
   *
   * @param fn
   */
  def run(fn: () => Unit): Unit = ???

  /**
   * Runs the given function on the UI Thread.
   *
   * TODO #90 Details
   *
   * @param fn
   */
  def runOnUi(fn: () => Unit): Unit = ???

  /**
   * Calls the given function on the Business Thread and returns a [[Future]] with its result.
   *
   * TODO #90 Details
   *
   * @param fn
   */
  def call[R](fn: () => R): Future[R] = ???

  /**
   * Calls the given function on the UI Thread and returns a [[Future]] with its result.
   *
   * TODO #90 Details
   *
   * @param fn
   */
  def callOnUi[R](fn: () => R): Future[R] = ???
}

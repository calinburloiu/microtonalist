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
  def publish(event: BusinessyncEvent): Unit = eventBus.post(event)

  def subscribe[E >: BusinessyncEvent](eventClass: Class[E], handler: E => Unit): Unit = {}

  @deprecated("Will be replaced by subscribe")
  def register(obj: AnyRef): Unit = eventBus.register(obj)

  def run(fn: () => Unit): Unit = ???

  def runInUi(fn: () => Unit): Unit = ???

  def call[R](fn: () => R): Future[R] = ???

  def callInUi[R](fn: () => R): Future[R] = ???
}

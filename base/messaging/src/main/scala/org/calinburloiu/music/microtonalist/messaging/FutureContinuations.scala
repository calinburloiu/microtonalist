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

import scala.concurrent.Future
import scala.util.Try

/**
 * Continuations of a `Future` that run on the Business Thread or on the UI Thread, each in a message of its own on that
 * thread, stamped with the command that caused the message that started it.
 *
 * Like a handler, a Business continuation's published events reach their Business subscribers after it, and an
 * exception thrown on the Business Thread by its `onComplete…` function or by those subscribers fails the reply of the
 * `ask` that caused it.
 */
extension [T](future: Future[T])(using messageBus: MessageBus) {

  /**
   * Maps the `Future`'s value with `f` on the Business Thread, once it completes; a failure skips `f`.
   *
   * @return a `Future` that completes with the result of `f`, or fails with the `Future`'s failure or with the
   *   exception that `f` throws
   * @throws IllegalStateException off the Business Thread
   */
  def mapOnBusiness[U](f: T => U): Future[U] = {
    messageBus.requireBusinessThread()
    future.map(f)(using messageBus.businessContinuations)
  }

  /**
   * Continues with the `Future` that `f` returns for the `Future`'s value, calling `f` on the Business Thread, once the
   * `Future` completes; a failure skips `f`.
   *
   * @return a `Future` that completes like the `Future` returned by `f`, or fails with the `Future`'s failure or with
   *   the exception that `f` throws
   * @throws IllegalStateException off the Business Thread
   */
  def flatMapOnBusiness[U](f: T => Future[U]): Future[U] = {
    messageBus.requireBusinessThread()
    future.flatMap(f)(using messageBus.businessContinuations)
  }

  /**
   * Calls `f` with the `Future`'s outcome on the Business Thread, once it completes.
   *
   * An exception that `f` throws is logged at ERROR as `A continuation failed.`.
   *
   * @throws IllegalStateException off the Business Thread
   */
  def onCompleteOnBusiness(f: Try[T] => Unit): Unit = {
    messageBus.requireBusinessThread()
    future.onComplete(f)(using messageBus.businessContinuations)
  }

  /**
   * Maps the `Future`'s value with `f` on the UI Thread, once it completes; a failure skips `f`.
   *
   * @return a `Future` that completes with the result of `f`, or fails with the `Future`'s failure or with the
   *   exception that `f` throws
   * @throws IllegalStateException off the UI Thread
   */
  def mapOnUi[U](f: T => U): Future[U] = {
    messageBus.requireUiThread()
    future.map(f)(using messageBus.uiContinuations)
  }

  /**
   * Calls `f` with the `Future`'s outcome on the UI Thread, once it completes.
   *
   * An exception that `f` throws is only logged at ERROR, as `A continuation failed.`.
   *
   * @throws IllegalStateException off the UI Thread
   */
  def onCompleteOnUi(f: Try[T] => Unit): Unit = {
    messageBus.requireUiThread()
    future.onComplete(f)(using messageBus.uiContinuations)
  }
}

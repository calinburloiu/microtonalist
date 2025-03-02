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

package org.calinburloiu.music.microtonalist.format

import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.*

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** Supertype for each of the statuses of a [[DeferrableRead]]. */
sealed trait DeferrableReadStatus

/** Object containing the [[DeferrableReadStatus]] status values. */
object DeferrableReadStatus {
  /**
   * A [[DeferredRead]] instance for which [[DeferrableRead#load]] was not called yet for starting the load operation.
   */
  case object Unloaded extends DeferrableReadStatus

  /**
   * A [[DeferredRead]] instance for which [[DeferrableRead#load]] was called but the load operation was not
   * completed yet.
   */
  case object PendingLoad extends DeferrableReadStatus

  /**
   * A [[DeferrableRead]] that has its value available. It may be an [[AlreadyRead]] or a [[DeferredRead]] for which
   * [[DeferrableRead#load]] was called and the load operation was completed.
   */
  case object Loaded extends DeferrableReadStatus

  /**
   * A [[DeferredRead]] instance for which [[DeferrableRead#load]] was called and the load operation failed.
   *
   * @param exception the exception that cause the operation to fail
   */
  case class FailedLoad(exception: Throwable) extends DeferrableReadStatus
}

/**
 * Marks a JSON value representation to be potentially loaded later. The value may be read immediately, if it's
 * already present in the JSON, or a placeholder representation can be loaded first that can later be used to load
 * the actual value. The placeholder typically contains a reference / an import that allows loading the actual value
 * later by using a ''loader'' function (see [[DeferrableRead#load]]).
 *
 * To use a [[DeferrableRead]] object (if it's not an [[AlreadyRead]] and is a [[DeferredRead]]) you must first load
 * its value by calling [[DeferrableRead#load]]. Note that this is an asynchronous operation and the value will most
 * likely not be available immediately. To properly get the value you need to use one of the [[Future]]s for the
 * value returned by the class, either [[DeferrableRead#load]] or [[DeferrableRead#futureValue]]. You don't necessary
 * need to match between an [[AlreadyRead]] and a [[DeferredRead]] in order to know if the value needs to be loaded.
 * [[DeferrableRead#load]] is a no-op for [[AlreadyRead]].
 *
 * @tparam V type of the ''value'' whose reading may be deferred
 * @tparam P type of the ''placeholder'' that will be used later to load the actual value that typically contains a
 *           reference (e.g. a URI)
 */
sealed trait DeferrableRead[V, P] {
  /**
   * Asynchronously loads the value by using the given loader function.
   *
   * Note that this function does nothing for [[AlreadyRead]] and returns an already completed [[Future]].
   *
   * @param loader function that asynchronously loads the value by using the placeholder as input
   * @return a [[Future]] for the value
   */
  def load(loader: P => Future[V]): Future[V]

  /**
   * @return the loading status
   */
  def status: DeferrableReadStatus

  /**
   * @return a [[Future]] for the value
   */
  def futureValue: Future[V]

  /**
   * Convenience accessor that gets the current value if it's already available. Only call it if you are sure that
   * the value was successfully loaded: the [[Future]]s returned by [[DeferrableRead#load]] or
   * [[DeferrableRead#futureValue]] have successfully completed and [[DeferrableRead#status]] is
   * [[DeferrableReadStatus.Loaded]].
   *
   * @return the current value
   * @throws NoSuchElementException if the value was not loaded yet
   * @throws Throwable              any other exception caught while loading the value
   */
  def value: V
}

/**
 * A [[DeferrableRead]] for which its value loading was not deferred, already contains a value and does not require
 * loading via [[AlreadyRead#load]].
 *
 * [[AlreadyRead#value]] always returns a value, [[AlreadyRead#status]] always returns
 * [[DeferrableReadStatus.Loaded]] and [[AlreadyRead#futureValue]] always returns an already completed [[Future]] of
 * the value.
 *
 * @param value the already read value
 * @tparam V type of the ''value'' whose reading may be deferred
 * @tparam P type of the ''placeholder'' that will be used later to load the actual value that typically contains a
 *           reference (e.g. a URI)
 */
case class AlreadyRead[V, P](override val value: V) extends DeferrableRead[V, P] {
  override def load(loader: P => Future[V]): Future[V] = Future(value)

  override def status: DeferrableReadStatus = DeferrableReadStatus.Loaded

  override def futureValue: Future[V] = Future(value)
}

/**
 * A [[DeferrableRead]] for which its value loading was deferred and instead contains a [[DeferredRead#placeholder]]
 * value. To obtain its value, it must be loaded first by calling [[DeferredRead#load]], which does this
 * asynchronously by using the [[DeferredRead#placeholder]].
 *
 * @param placeholder the placeholder value used for loading the actual value which typically contains some kind of
 *                    reference to the value like an URI
 * @tparam V type of the ''value'' whose reading may be deferred
 * @tparam P type of the ''placeholder'' that will be used later to load the actual value that typically contains a
 *           reference (e.g. a URI)
 */
case class DeferredRead[V, P](placeholder: P) extends DeferrableRead[V, P] with LazyLogging {
  var _status: DeferrableReadStatus = DeferrableReadStatus.Unloaded
  var _futureValue: Option[Future[V]] = None
  var _value: Option[V] = None

  val lock: ReadWriteLock = new ReentrantReadWriteLock()

  override def load(loader: P => Future[V]): Future[V] = {
    // Read locks are cheaper, first try to read to see if it was not already loaded
    lock.readLock.lock()
    val valueRead = _futureValue
    lock.readLock.unlock()

    val loadedValue = valueRead match {
      case Some(v) =>
        logger.warn(s"The value $v was already loaded!")
        v
      case None =>
        lock.writeLock.lock()
        try {
          // We need to read the value again because it might have changed since we released the read lock above
          _futureValue match {
            case Some(v) =>
              logger.warn(s"The value $v was already concurrently loaded!")
              v
            case None =>
              _status = DeferrableReadStatus.PendingLoad
              val futureValue = loader(placeholder)
              futureValue.onComplete {
                case Success(v) =>
                  _value = Some(v)
                  _status = DeferrableReadStatus.Loaded
                case Failure(exception) =>
                  _status = DeferrableReadStatus.FailedLoad(exception)
              }
              _futureValue = Some(futureValue)
              futureValue
          }
        } catch {
          case throwable: Throwable =>
            _status = DeferrableReadStatus.FailedLoad(throwable)
            throw throwable
        } finally {
          // Make sure we release the write lock if the loader function above throws an exception
          lock.writeLock().unlock()
        }
    }

    loadedValue
  }

  override def status: DeferrableReadStatus = {
    lock.readLock.lock()
    val status = _status
    lock.readLock.unlock()

    status
  }

  override def futureValue: Future[V] = {
    lock.readLock.lock()
    val maybeFutureValue = _futureValue
    lock.readLock.unlock()

    maybeFutureValue
      .getOrElse(throw new NoSuchElementException("load was not called before getting the value in DeferredRead"))
  }

  override def value: V = {
    lock.readLock.lock()
    val maybeValue = _value
    val status = _status
    lock.readLock.unlock()

    maybeValue.getOrElse {
      status match {
        case DeferrableReadStatus.FailedLoad(exception) => throw exception
        case _ => throw new NoSuchElementException(s"value not loaded; status=$status")
      }
    }
  }
}

object DeferrableRead {
  /**
   * Creates a play-json [[Reads]] instance based on [[Reads]] instances for the value and the placeholder.
   *
   * @param valueReads       a [[Reads]] for the value
   * @param placeholderReads a [[Reads]] for the placeholder
   * @tparam V type of the ''value''
   * @tparam P type of the ''placeholder''
   * @return a [[Reads]] for the desired [[DeferrableRead]]
   */
  def reads[V, P](valueReads: Reads[V], placeholderReads: Reads[P]): Reads[DeferrableRead[V, P]] = {
    val alreadyReads: Reads[DeferrableRead[V, P]] = valueReads.map(AlreadyRead.apply)
    val deferredReads: Reads[DeferrableRead[V, P]] = placeholderReads.map(DeferredRead.apply)
    alreadyReads orElse deferredReads
  }

  /**
   * Creates a play-json [[Writes]] instance based on [[Writes]] instances for the value and the placeholder.
   *
   * @param valueWrites       a [[Writes]] for the value
   * @param placeholderWrites a [[Writes]] for the placeholder
   * @tparam V type of the ''value''
   * @tparam P type of the ''placeholder''
   * @return a [[Writes]] for the desired [[DeferrableRead]]
   */
  def writes[V, P](valueWrites: Writes[V], placeholderWrites: Writes[P]): Writes[DeferrableRead[V, P]] = Writes {
    case AlreadyRead(value) => valueWrites.writes(value)
    case DeferredRead(placeholder) => placeholderWrites.writes(placeholder)
  }

  /**
   * Creates a play-json [[Format]] instance based on [[Format]]s for the value and the placeholder.
   *
   * @param valueFormat       a [[Format]] for the value
   * @param placeholderFormat a [[Format]] for the placeholder
   * @tparam V type of the ''value''
   * @tparam P type of the ''placeholder''
   * @return a [[Format]] for the desired [[DeferrableRead]]
   */
  def format[V, P](valueFormat: Format[V], placeholderFormat: Format[P]): Format[DeferrableRead[V, P]] = Format(
    reads(valueFormat, placeholderFormat),
    writes(valueFormat, placeholderFormat)
  )
}

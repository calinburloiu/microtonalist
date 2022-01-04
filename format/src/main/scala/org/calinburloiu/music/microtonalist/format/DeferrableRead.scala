/*
 * Copyright 2021 Calin-Andrei Burloiu
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

import play.api.libs.functional.syntax._
import play.api.libs.json._

import java.net.URI
import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait DeferrableReadStatus
object DeferrableReadStatus {
  case object Unloaded extends DeferrableReadStatus
  case object PendingLoad extends DeferrableReadStatus
  case object Loaded extends DeferrableReadStatus
  case class FailedLoad(exception: Throwable) extends DeferrableReadStatus
}

/**
 * Marks a JSON value representation to be potentially loaded later. The value may be read immediately, if it's
 * already present in the JSON, or a placeholder representation can be loaded first that can be later be used to load
 * the actual value. The placeholder typically contains a reference / an import that allows loading the actual value
 * later by using a ''loader'' function (see [[DeferrableRead#load]]).
 *
 * @tparam V type of the ''value'' whose reading may be deferred
 * @tparam P type of the ''placeholder'' that will be used later to load the actual value that typically contains a
 *           reference (e.g. a URI)
 */
sealed trait DeferrableRead[V, P] {
  def load(loader: P => Future[V]): Future[V]

  def status: DeferrableReadStatus

  def futureValue: Future[V]

  /**
   * @return the value if it was already loaded
   * @throws NoSuchElementException if the value was not loaded yet
   * @throws Throwable any other exception caught while loading the value
   */
  def value: V
}

case class AlreadyRead[V, P](override val value: V) extends DeferrableRead[V, P] {
  override def load(loader: P => Future[V]): Future[V] = Future(value)

  override def status: DeferrableReadStatus = DeferrableReadStatus.Loaded

  override def futureValue: Future[V] = Future(value)
}

case class DeferredRead[V, P](placeholder: P) extends DeferrableRead[V, P] {
  var _futureValue: Option[Future[V]] = None
  var _value: Option[V] = None
  var _status: DeferrableReadStatus = DeferrableReadStatus.Unloaded
  val lock: ReadWriteLock = new ReentrantReadWriteLock()

  override def load(loader: P => Future[V]): Future[V] = {
    // Read locks are cheaper, first try to read to see if it was not already loaded
    lock.readLock.lock()
    val valueRead = _futureValue
    lock.readLock.unlock()

    val loadedValue = valueRead match {
      case Some(v) => v
      case None =>
        lock.writeLock.lock()
        try {
          // We need to read the value again because it might has changed since we released the read lock above
          _futureValue match {
            case Some(v) => v
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
          // Make sure we release the write lock if the loader function above might throw an exception
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
    val futureValueRead = _futureValue
    lock.readLock.unlock()

    futureValueRead
      .getOrElse(throw new NoSuchElementException("load was not called before getting the value in DeferredRead"))
  }

  override def value: V = {
    lock.readLock.lock()
    val valueRead = _value
    val status = _status
    lock.readLock.unlock()

    valueRead.getOrElse {
      status match {
        case DeferrableReadStatus.FailedLoad(exception) => throw exception
        case _ => throw new NoSuchElementException(s"value not loaded; status=$status")
      }
    }
  }
}

object DeferrableRead {
  def reads[V, P](valueReads: Reads[V], placeholderReads: Reads[P]): Reads[DeferrableRead[V, P]] = {
    val alreadyReads: Reads[DeferrableRead[V, P]] = valueReads.map(AlreadyRead.apply)
    val deferredReads: Reads[DeferrableRead[V, P]] = placeholderReads.map(DeferredRead.apply)
    alreadyReads orElse deferredReads
  }

  def writes[V, P](valueWrites: Writes[V], placeholderWrites: Writes[P]): Writes[DeferrableRead[V, P]] = Writes {
    case AlreadyRead(value) => valueWrites.writes(value)
    case DeferredRead(placeholder) => placeholderWrites.writes(placeholder)
  }

  def format[V, P](valueFormat: Format[V], placeholderFormat: Format[P]): Format[DeferrableRead[V, P]] = Format(
    reads(valueFormat, placeholderFormat),
    writes(valueFormat, placeholderFormat)
  )
}

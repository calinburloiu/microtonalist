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
import scala.util.Try

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
  // TODO #38 Return a Future instead
  def load(loader: P => V): V

  /**
   * @return the value if it was already loaded
   * @throws NoSuchElementException if the value was not loaded yet
   */
  def value: V
}

case class AlreadyRead[V, P](override val value: V) extends DeferrableRead[V, P] {
  override def load(loader: P => V): V = value
}

case class DeferredRead[V, P](placeholder: P) extends DeferrableRead[V, P] {
  var _value: Option[V] = None
  val lock: ReadWriteLock = new ReentrantReadWriteLock()

  override def load(loader: P => V): V = {
    // Read locks are cheaper, first try to read to see if it was not already loaded
    lock.readLock.lock()
    val valueRead = _value
    lock.readLock.unlock()

    val loadedValue = valueRead match {
      case Some(v) => v
      case None =>
        lock.writeLock.lock()
        try {
          // We need to read the value again because it might has changed since we released the read lock above
          _value match {
            case Some(v) => v
            case None =>
              val v = loader(placeholder)
              _value = Some(v)
              v
          }
        } finally {
          // TODO #38 Test this case when an exception occurs
          // Make sure we release the write lock if the loader function above might throw an exception
          lock.writeLock().unlock()
        }
    }

    loadedValue
  }

  override def value: V = {
    lock.readLock.lock()
    val valueRead = _value
    lock.readLock.unlock()

    Try(valueRead.get).recover {
      case exception: NoSuchElementException => throw new NoSuchElementException(
        "load was not called before getting the value in DeferredRead", exception)
    }.get
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

// TODO #38 Remove Ref hierarchy
@deprecated
sealed trait Ref[+A] {

  // TODO Make this an actual URI
  def uri: String

  def uriOption: Option[String]

  def value: A

  def valueOption: Option[A]

  def resolve[B >: A](refResolver: RefResolver[B], baseUri: Option[URI]): Ref[B]
}

@deprecated
case class NoRef[+A](override val value: A) extends Ref[A] {

  override def uri = throw new NoSuchElementException(getClass.getSimpleName)

  override def uriOption: Option[String] = None

  override def valueOption: Option[A] = Some(value)

  override def resolve[B >: A](refResolver: RefResolver[B], baseUri: Option[URI]): Ref[B] = this
}

@deprecated
case class UnresolvedRef[+A](override val uri: String) extends Ref[A] {

  override def uriOption: Option[String] = Some(uri)

  override def value: A = throw new NoSuchElementException(getClass.getSimpleName)

  override def valueOption: Option[A] = None

  override def resolve[B >: A](refResolver: RefResolver[B], baseUri: Option[URI]): Ref[B] = {
    val resolvedUri = baseUri.map(_.resolve(uri)).getOrElse(new URI(uri))
    ResolvedRef(uri, refResolver.read(resolvedUri))
  }
}

@deprecated
case class ResolvedRef[+A](override val uri: String, override val value: A) extends Ref[A] {

  override def uriOption: Option[String] = Some(uri)

  override def valueOption: Option[A] = Some(value)

  override def resolve[B >: A](refResolver: RefResolver[B], baseUri: Option[URI]): Ref[B] = this
}

@deprecated
object Ref {

  def refReads[A](implicit valueReads: Reads[A]): Reads[Ref[A]] = {
    val onlyRef: Reads[JsObject] = Reads.filter(
      JsonValidationError("only \"ref\" and/or value's fields are allowed")
    ) { jsObject: JsObject =>
      jsObject.keys == Set("ref")
    }
    val unresolvedRefReads: Reads[Ref[A]] = (__ \ "ref").read[String].map(UnresolvedRef.apply[A])
    val noRefReads: Reads[Ref[A]] = valueReads.map(NoRef.apply)
    //@formatter:off
    val resolvedRefReads: Reads[Ref[A]] = (
      (__ \ "ref").read[String] and
      __.read[A]
    ) { (uri, value) => ResolvedRef(uri, value) }
    //@formatter:on

    (onlyRef andThen unresolvedRefReads) orElse resolvedRefReads orElse noRefReads
  }
}

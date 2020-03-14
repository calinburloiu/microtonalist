/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.intonation.io

import play.api.libs.functional.syntax._
import play.api.libs.json._

// TODO How can I make it like a collection? (with map, foreach etc.)
sealed trait Ref[+A] {

  // TODO Make this an actual URI
  def uri: String

  def uriOption: Option[String]

  def value: A

  def valueOption: Option[A]

  def resolve[B >: A](implicit refResolver: RefResolver[B]): Ref[B]
}

case class NoRef[+A](value: A) extends Ref[A] {

  override def uri = throw new NoSuchElementException(getClass.getSimpleName)

  override def uriOption: Option[String] = None

  override def valueOption: Option[A] = Some(value)

  override def resolve[B >: A](implicit refResolver: RefResolver[B]): Ref[B] = this
}

case class UnresolvedRef[+A](uri: String) extends Ref[A] {

  override def uriOption: Option[String] = Some(uri)

  override def value: A = throw new NoSuchElementException(getClass.getSimpleName)

  override def valueOption: Option[A] = None

  override def resolve[B >: A](implicit refResolver: RefResolver[B]): Ref[B] =
    ResolvedRef(uri, refResolver.get(uri))
}

case class ResolvedRef[+A](uri: String, value: A) extends Ref[A] {

  override def uriOption: Option[String] = Some(uri)

  override def valueOption: Option[A] = Some(value)

  override def resolve[B >: A](implicit refResolver: RefResolver[B]): Ref[B] = this
}

object Ref {

  def refReads[A](implicit valueReads: Reads[A]): Reads[Ref[A]] = {
    val onlyRef: Reads[JsObject] = Reads.filter(
      JsonValidationError("only \"ref\" and/or value's fields are allowed")
    ) { jsObject: JsObject =>
      jsObject.keys == Set("ref")
    }
    val unresolvedRefReads: Reads[Ref[A]] = (__ \ "ref").read[String].map(UnresolvedRef.apply[A])
    val noRefReads: Reads[Ref[A]] = valueReads.map(NoRef.apply)
    val resolvedRefReads: Reads[Ref[A]] = (
      (__ \ "ref").read[String] and
      __.read[A]
    ) { (uri, value) => ResolvedRef(uri, value) }

    (onlyRef andThen unresolvedRefReads) orElse resolvedRefReads orElse noRefReads
  }
}

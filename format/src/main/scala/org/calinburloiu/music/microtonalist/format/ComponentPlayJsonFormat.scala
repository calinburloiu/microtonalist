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

/**
 * Trait extended for serialization/deserialization between a Scala base class or trait `A` and the classes that
 * extend it, on one side, and their JSON representation, on the other side.
 * The JSON representation could either be an object containing a `type` property, which is
 * used to identify the Scala children class or string representing that type if there are no parameters required.
 *
 * @tparam A base Scala class or trait
 */
trait ComponentPlayJsonFormat[A] extends Format[A] {

  import ComponentPlayJsonFormat._

  /**
   * Specification used for serialization/deserialization of the classes that extend `A`.
   *
   * @see [[ComponentPlayJsonFormat.SubComponentSpec]]
   */
  val subComponentSpecs: Seq[SubComponentSpec[_ <: A]]

  private lazy val subComponentSpecsByType: Map[String, SubComponentSpec[_ <: A]] = subComponentSpecs
    .map { spec => spec.typeName -> spec }.toMap
  private lazy val subComponentSpecsByClass: Map[Class[_ <: A], SubComponentSpec[_ <: A]] = subComponentSpecs
    .map { spec => spec.javaClass -> spec }.toMap

  override def writes(component: A): JsValue = {
    subComponentSpecsByClass.get(component.getClass) match {
      case Some(spec) =>
        spec.playJsonFormat
          .map { componentFormat =>
            //@formatter:off
            val writesWithType = (
              (__ \ SubComponentTypeFieldName).write[String] and
              __.write[A](componentFormat.asInstanceOf[Format[A]])
            ) ({ component: A => (spec.typeName, component) })
            //@formatter:on

            writesWithType.writes(component)
          }
          .getOrElse(JsString(spec.typeName))
      case None => throw new Error(s"Unregistered scale list sub-component class ${component.getClass.getName}")
    }
  }

  override def reads(json: JsValue): JsResult[A] = {
    val readsStrWithType = Reads.StringReads
      .map { typeName =>
        subComponentSpecsByType.get(typeName).map { spec =>
          spec.defaultFactory.map { factory => factory() }
        }
      }
      .filter(UnrecognizedTypeError) { foundByType => foundByType.nonEmpty }
      .map(_.get)
      .filter(MissingRequiredParams) { maybeDefaultFactory => maybeDefaultFactory.nonEmpty }
      .map(_.get)

    def objWithTypeResult = (json \ SubComponentTypeFieldName).asOpt[String] match {
      case Some(typeName) =>
        subComponentSpecsByType.get(typeName) match {
          case Some(spec) =>
            val maybeRead = spec.playJsonFormat.map(_.reads(json))
            spec.defaultFactory
              .map { factory => maybeRead.getOrElse(JsSuccess(factory())) }
              .getOrElse(JsError(Seq(JsPath -> Seq(MissingRequiredParams))))
          case None => JsError(Seq(JsPath -> Seq(UnrecognizedTypeError)))
        }
      case None => JsError(Seq(JsPath -> Seq(MissingTypeError)))
    }

    json match {
      case jsString: JsString => readsStrWithType.reads(jsString)
      case _: JsObject => objWithTypeResult
      case _ => JsError(Seq(JsPath -> Seq(InvalidError)))
    }
  }
}

object ComponentPlayJsonFormat {

  /** Field that identifies the class that extends type parameter `A` in companion class. */
  val SubComponentTypeFieldName = "type"

  val InvalidError: JsonValidationError = JsonValidationError("error.component.invalid")
  val UnrecognizedTypeError: JsonValidationError = JsonValidationError("error.component.type.unrecognized")
  val MissingTypeError: JsonValidationError = JsonValidationError("error.component.type.missing")
  val MissingRequiredParams: JsonValidationError = JsonValidationError("error.component.params.missing")

  /**
   * Specification of a class that extend type parameter `A` (from companion class) used for
   * serialization/deserialization.
   *
   * @param typeName       value used for `type` JSON field to identify the class that extends `A`
   * @param javaClass      exact class that extends `A`
   * @param playJsonFormat play-json library [[Format]] for reading/writing the class that extends `A`
   * @param defaultFactory factory function that creates an instance of the class that extends `A` without parameters.
   *                       If the parameters are required this parameter will be [[None]].
   * @tparam B a class that extends `A`
   */
  private[format] case class SubComponentSpec[B](typeName: String, javaClass: Class[B],
                                                 playJsonFormat: Option[Format[B]],
                                                 defaultFactory: Option[() => B])

}

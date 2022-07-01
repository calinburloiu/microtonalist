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

// TODO #38 It seems that playJsonFormat must not include the type property. This needs to be documented.
// TODO #38 Require one of format or default to be defined

/**
 * Specification of a class that extend type parameter `A` (from companion class) used for
 * serialization/deserialization.
 *
 * @tparam B a class that extends `A`
 */
trait ComponentFormatSpec[B] {
  /** Value used for `type` JSON field to identify the class that extends `A`. */
  val typeName: String

  /** Exact class that extends `A`. */
  val javaClass: Class[B]

  /** play-json library [[Format]] for reading/writing the class that extends `A`. */
  val format: Option[Format[B]]

  /** A default instance of the class that extends `A` used when no parameters are provided for the JSON component.
   * If the parameters are required this parameter will be [[None]].
   */
  val default: Option[B]
}

/**
 * Trait extended for serialization/deserialization between a Scala base class or trait `A` and the classes that
 * extend it, on one side, and their JSON representation, on the other side.
 * The JSON representation could either be an object containing a `type` property, which is
 * used to identify the Scala children class or string representing that type if there are no parameters required.
 *
 * @param specs specifications used for serialization/deserialization of the classes that extend `A`
 * @tparam A base Scala class or trait
 */
class ComponentFormat[A](val specs: Seq[ComponentFormatSpec[_ <: A]]) extends Format[A] {

  import ComponentFormat._

  private lazy val specsByType: Map[String, ComponentFormatSpec[_ <: A]] = specs
    .map { spec => spec.typeName -> spec }.toMap
  private lazy val specsByClass: Map[Class[_ <: A], ComponentFormatSpec[_ <: A]] = specs
    .map { spec => spec.javaClass -> spec }.toMap

  // TODO #38 Document that it only writes as JSON object
  override def writes(component: A): JsValue = {
    specsByClass.get(component.getClass) match {
      case Some(spec) =>
        spec.format
          .map { componentFormat =>
            //@formatter:off
            val writesWithType = (
              (__ \ TypeJsonProperty).write[String] and
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
        specsByType.get(typeName).map { spec =>
          spec.default
        }
      }
      // TODO #38 We need to test these error cases and this pipeline
      .filter(UnrecognizedTypeError) { foundByType => foundByType.nonEmpty }
      .map(_.get)
      .filter(MissingRequiredParams) { maybeDefaultFactory => maybeDefaultFactory.nonEmpty }
      .map(_.get)

    def objWithTypeResult = (json \ TypeJsonProperty).asOpt[String] match {
      case Some(typeName) =>
        specsByType.get(typeName) match {
          case Some(spec) =>
            val maybeRead = spec.format.map(_.reads(json))
            spec.default
              .map { default => maybeRead.getOrElse(JsSuccess(default)) }
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

object ComponentFormat {

  /** Field that identifies the class that extends type parameter `A` in companion class. */
  val TypeJsonProperty = "type"

  val InvalidError: JsonValidationError = JsonValidationError("error.component.invalid")
  val UnrecognizedTypeError: JsonValidationError = JsonValidationError("error.component.type.unrecognized")
  val MissingTypeError: JsonValidationError = JsonValidationError("error.component.type.missing")
  val MissingRequiredParams: JsonValidationError = JsonValidationError("error.component.params.missing")
}

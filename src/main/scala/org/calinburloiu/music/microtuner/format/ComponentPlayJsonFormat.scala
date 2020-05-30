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

package org.calinburloiu.music.microtuner.format

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.Option

trait ComponentPlayJsonFormat[A] extends Format[A] {

  import ComponentPlayJsonFormat._

  val SubComponentTypeFieldName = "type"

  val subComponentSpecs: Seq[SubComponentSpec[_ <: A]]
  lazy val subComponentSpecsByType: Map[String, SubComponentSpec[_ <: A]] = subComponentSpecs
    .map { spec => spec.typeName -> spec }.toMap
  lazy val subComponentSpecsByClass: Map[Class[_ <: A], SubComponentSpec[_ <: A]] = subComponentSpecs
    .map { spec => spec.javaClass -> spec }.toMap

  override def writes(component: A): JsValue = {
    subComponentSpecsByClass.get(component.getClass) match {
      case Some(spec) =>
        spec.playJsonFormat
          .map { componentFormat =>
            val writesWithType = (
              (__ \ SubComponentTypeFieldName).write[String] and
                __.write[A](componentFormat.asInstanceOf[Format[A]])
              ) ({ component: A => (spec.typeName, component) })

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
  val InvalidError: JsonValidationError = JsonValidationError("error.component.invalid")
  val UnrecognizedTypeError: JsonValidationError = JsonValidationError("error.component.type.unrecognized")
  val MissingTypeError: JsonValidationError = JsonValidationError("error.component.type.missing")
  val MissingRequiredParams: JsonValidationError = JsonValidationError("error.component.params.missing")

  private[format] case class SubComponentSpec[A](typeName: String, javaClass: Class[A],
                                                 playJsonFormat: Option[Format[A]],
                                                 defaultFactory: Option[() => A])

}

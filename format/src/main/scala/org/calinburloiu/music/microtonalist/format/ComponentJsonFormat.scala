/*
 * Copyright 2024 Calin-Andrei Burloiu
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

import play.api.libs.json.{
  Format, JsError, JsObject, JsPath, JsResult, JsString, JsSuccess, JsValue, Json,
  JsonValidationError, OWrites, Writes, __
}
import play.api.libs.functional.syntax._

/**
 * Instances of this class are used for parsing a _family_ of JSON component objects by using Play JSON Format.
 *
 * A _JSON component_ is an object with a particular _type_ that is part of a _family_. The family is used in a
 * certain context (e.g. tuning mapper, tuning reduces, scale etc.). For each family there can be one or more types.
 * The type is identified in the JSON object by the `type` property (which might be implicit for a default type).
 * Each type may have its own specific properties called _settings_.
 *
 * One should create separate instances of this class for each family and configure it with a sequence of specs, each
 * for each type. Each type may have default values, in `defaultSettings`, for certain settings to allow users to
 * omit them. Settings values per family/type can also be set globally by the user per composition file in the
 * settings section and the class has a setter for setting the root of the settings JSON after the JSON file was parsed.
 */
class ComponentJsonFormat[F](val familyName: String,
                             specs: ComponentJsonFormat.SpecsSeqType[F],
                             defaultTypeName: Option[String] = None) extends Format[F] {

  import ComponentJsonFormat._

  private var _rootGlobalSettings: JsObject = Json.obj()

  private val componentSpecsByType: Map[String, TypeSpec[_ <: F]] = specs
    .map { spec => spec.typeName -> spec }.toMap
  private val componentSpecsByClass: Map[Class[_ <: F], TypeSpec[_ <: F]] = specs
    .map { spec => spec.javaClass -> spec }.toMap

  def rootGlobalSettings: JsObject = _rootGlobalSettings

  def rootGlobalSettings_=(newValue: JsObject): Unit = {
    _rootGlobalSettings = newValue
  }

  override def reads(componentJson: JsValue): JsResult[F] = {
    def doRead(typeNameOpt: Option[String], localSettings: JsObject): JsResult[F] = {
      typeNameOpt match {
        case Some(typeName) =>
          componentSpecsByType.get(typeName) match {
            case Some(spec) if spec.formatOrComponentValue.isLeft =>
              val mergedSettings = spec.defaultSettings ++ globalSettingsOf(typeName) ++ localSettings
              spec.format.reads(mergedSettings)
            case Some(spec) if spec.formatOrComponentValue.isRight => JsSuccess(spec.componentValue)
            case None => JsError(Seq(JsPath -> Seq(UnrecognizedTypeError)))
          }
        case None => JsError(Seq(JsPath -> Seq(MissingTypeError)))
      }
    }

    componentJson match {
      case typeName: JsString => doRead(Some(typeName.value), Json.obj())
      case componentObj: JsObject =>
        val typeNameOpt = (componentObj \ PropertyNameType).asOpt[String].orElse(defaultTypeName)
        doRead(typeNameOpt, componentObj - PropertyNameType)
      case _ => JsError(Seq(JsPath -> Seq(InvalidError)))
    }
  }

  private def globalSettingsOf(typeName: String): JsObject = (_rootGlobalSettings \ familyName \ typeName)
    // Non-object entries are ignored, i.e. replaced with an empty object
    .validate[JsObject].getOrElse(Json.obj())

  override def writes(component: F): JsValue = componentSpecsByClass.get(component.getClass) match {
    case Some(spec) if spec.formatOrComponentValue.isLeft =>
      writesWithTypeFor(spec.format.asInstanceOf[Format[F]], spec.typeName).writes(component)
    case Some(spec) if spec.formatOrComponentValue.isRight => JsString(spec.typeName)
    case None => throw new Error(s"Unregistered composition sub-component class ${component.getClass.getName}")
  }
}

object ComponentJsonFormat {
  type SpecsSeqType[F] = Seq[ComponentJsonFormat.TypeSpec[_ <: F]]

  private[format] val InvalidError: JsonValidationError = JsonValidationError("error.component.invalid")
  private[format] val MissingTypeError: JsonValidationError = JsonValidationError("error.component.type.missing")
  private[format] val UnrecognizedTypeError: JsonValidationError = JsonValidationError("error.component.type.unrecognized")

  private val PropertyNameType = "type"

  /**
   * Specification object for serializing/deserializing a component a given type.
   *
   * @param typeName               The given component type name.
   * @param javaClass              The Java [[Class]] used for the deserialized component that is used when doing
   *                               serialization
   *                               for
   *                               identification.
   * @param formatOrComponentValue Either a [[Left]] with [[Format]] used for serializing/deserializing the
   *                               component type settings
   *                               in/from JSON, or a [[Right]] with the singleton component type value when the
   *                               component has no settings and is only defined by its type name.
   * @param defaultSettings        Default settings values in JSON object format that should be used when not
   *                               provided in a
   *                               serialized JSON.
   * @tparam T Scala type used for the component type.
   */
  case class TypeSpec[T](typeName: String,
                         formatOrComponentValue: Either[Format[T], T],
                         javaClass: Class[T],
                         defaultSettings: JsObject = Json.obj()) {
    def format: Format[T] = formatOrComponentValue.swap.getOrElse(
      throw new IllegalArgumentException("TypeSpec that not contain a format!"))

    def componentValue: T = formatOrComponentValue.getOrElse(
      throw new IllegalArgumentException("TypeSpec that not contain a component value!")
    )
  }

  object TypeSpec {
    /**
     * Factory method for a component type that has one or more settings.
     */
    def withSettings[T](typeName: String,
                        format: Format[T],
                        javaClass: Class[T],
                        defaultSettings: JsObject = Json.obj()): TypeSpec[T] =
      TypeSpec[T](typeName, Left(format), javaClass, defaultSettings)

    /**
     * Factory method for a component type that has no settings and is only defined by its type name.
     *
     * Note that in this case the [[TypeSpec]] does not need a [[Format]].
     */
    def withoutSettings[T <: Object](typeName: String, componentValue: T): TypeSpec[T] = {
      TypeSpec[T](typeName, Right(componentValue), componentValue.getClass.asInstanceOf[Class[T]])
    }
  }

  private def writesWithTypeFor[A](writes: Writes[A], typeName: String): OWrites[A] = {
    //@formatter:off
    (
      (__ \ PropertyNameType).write[String] and
      __.write[A](writes)
    ) ({ component: A => (typeName, component) })
    //@formatter:on
  }
}

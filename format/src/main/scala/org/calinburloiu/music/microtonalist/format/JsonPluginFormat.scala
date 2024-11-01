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

import org.calinburloiu.music.microtonalist.core.Plugin
import play.api.libs.functional.syntax._
import play.api.libs.json._

/**
 * Instances of this trait are used for parsing a _family_ of plugins represented as JSON by using Play JSON Format.
 *
 * A _JSON plugin_ is an object with a particular _type_ that is part of a _family_. The family is used in a
 * certain context (e.g. tuning mapper, tuning reduces, scale etc.). For each family there can be one or more types.
 * The type is identified in the JSON object by the `type` property (which might be implicit for a default type).
 * Each type may have its own specific properties called _settings_.
 *
 * One should create separate instances of this class for each family and configure it with a sequence of specs, each
 * for each type. Each type may have default values, in `defaultSettings`, for certain settings to allow users to
 * omit them. Settings values per family/type can also be set globally by the user per composition file in the
 * settings section and the class has a setter for setting the root of the settings JSON after the JSON file was parsed.
 */
trait JsonPluginFormat[P] {

  import JsonPluginFormat._

  /**
   * Plugin family name.
   *
   * @see [[org.calinburloiu.music.microtonalist.core.Plugin]]
   */
  val familyName: String

  /**
   * Sequence of specifications, one for each type of the plugin, that allows the (de)serialization.
   */
  val specs: JsonPluginFormat.TypeSpecs[P]

  /**
   * Implicit type name to be used for a plugin which allows:
   *
   *  - Omitting the `type` property in the JSON representation.
   *  - Having an implicit plugin instance without declaring it.
   */
  val defaultTypeName: Option[String] = None

  private lazy val specsByType: Map[String, TypeSpec[_ <: P]] = specs.map { spec => spec.typeName -> spec }.toMap
  private lazy val specsByClass: Map[Class[_ <: P], TypeSpec[_ <: P]] = specs.map { spec => spec.javaClass -> spec }
    .toMap

  /**
   * Returns a JSON deserializer that takes the global settings from a composition file into account.
   *
   * @param rootGlobalSettings The JSON of the `settings` property branch from a composition file.
   * @return a [[Reads]] instance for deserializing the plugin.
   */
  def readsWithRootGlobalSettings(rootGlobalSettings: JsObject): Reads[P] = {
    def doRead(typeNameOpt: Option[String], localSettings: JsObject): JsResult[P] = {
      typeNameOpt match {
        case Some(typeName) =>
          specsByType.get(typeName) match {
            case Some(spec) if spec.formatOrPlugin.isLeft =>
              val globalSettings = globalSettingsOf(rootGlobalSettings, typeName)
              val mergedSettings = spec.defaultSettings ++ globalSettings ++ localSettings
              spec.format.reads(mergedSettings)
            case Some(spec) if spec.formatOrPlugin.isRight => JsSuccess(spec.plugin)
            case Some(_) => throw new IllegalStateException("Unreachable!")
            case None => JsError(Seq(JsPath -> Seq(UnrecognizedTypeError)))
          }
        case None => JsError(Seq(JsPath -> Seq(MissingTypeError)))
      }
    }

    Reads {
      case typeName: JsString => doRead(Some(typeName.value), Json.obj())
      case pluginObj: JsObject =>
        val typeNameOpt = (pluginObj \ PropertyNameType).asOpt[String].orElse(defaultTypeName)
        doRead(typeNameOpt, pluginObj - PropertyNameType)
      case _ => JsError(Seq(JsPath -> Seq(InvalidError)))
    }
  }

  /**
   * Returns a JSON deserializer that does not use the global settings from a composition file.
   */
  lazy val reads: Reads[P] = readsWithRootGlobalSettings(Json.obj())

  /**
   * Returns a JSON serializer.
   */
  lazy val writes: Writes[P] = Writes { plugin =>
    specsByClass.get(plugin.getClass) match {
      case Some(spec) if spec.formatOrPlugin.isLeft =>
        writesWithTypeFor(spec.format.asInstanceOf[Format[P]], spec.typeName).writes(plugin)
      case Some(spec) if spec.formatOrPlugin.isRight => JsString(spec.typeName)
      case Some(_) => throw new IllegalStateException("Unreachable!")
      case None => throw new Error(s"Unregistered plugin type class ${plugin.getClass.getName}")
    }
  }

  /**
   * Returns a JSON (de)serializer that takes the global settings from a composition file into account.
   *
   * @param rootGlobalSettings The JSON of the `settings` property branch from a composition file.
   * @return a [[Format]] instance for (de)serializing the plugin.
   */
  def formatWithRootGlobalSettings(rootGlobalSettings: JsObject): Format[P] = Format(
    readsWithRootGlobalSettings(rootGlobalSettings),
    writes
  )

  /**
   * Returns a JSON (de)serializer that does not use the global settings from a composition file.
   */
  lazy val format: Format[P] = formatWithRootGlobalSettings(Json.obj())

  /**
   * Attempts to read a plugin from the global settings of a composition file.
   *
   * @param rootGlobalSettings The JSON of the `settings` property branch from a composition file.
   * @return [[Some]] plugin instance if the deserialization from global settings was successful, or [[None]] if
   *         there are mandatory settings that are missing.
   */
  def readDefaultPlugin(rootGlobalSettings: JsObject): Option[P] = {
    for (
      defaultTypeNameValue <- defaultTypeName;
      localSettings <- (rootGlobalSettings \ familyName \ defaultTypeNameValue).asOpt[JsObject];
      defaultPlugin <- localSettings.asOpt(reads)
    ) yield defaultPlugin
  }

  /**
   * @param rootGlobalSettings The JSON of the settings property branch from a composition file.
   * @return true if there are global settings defined for the default plugin, or false otherwise.
   */
  def hasGlobalSettingsForDefaultType(rootGlobalSettings: JsObject): Boolean = defaultTypeName match {
    case Some(defaultTypeNameValue) => (rootGlobalSettings \ familyName \ defaultTypeNameValue).isDefined
    case None => false
  }


  private def globalSettingsOf(rootGlobalSettings: JsObject, typeName: String): JsObject =
    (rootGlobalSettings \ familyName \ typeName)
      // Non-object entries are ignored, i.e. replaced with an empty object
      .validate[JsObject].getOrElse(Json.obj())
}

object JsonPluginFormat {
  type TypeSpecs[P] = Seq[JsonPluginFormat.TypeSpec[_ <: P]]

  /**
   * Factory method for instantiating a [[JsonPluginFormat]] without extending it in a class.
   */
  def apply[P](_familyName: String,
               _specs: TypeSpecs[P],
               _defaultTypeName: Option[String] = None): JsonPluginFormat[P] = new JsonPluginFormat[P] {
    override val familyName: String = _familyName
    override val specs: TypeSpecs[P] = _specs
    override val defaultTypeName: Option[String] = _defaultTypeName
  }

  private[format] val InvalidError: JsonValidationError = JsonValidationError("error.plugin.invalid")
  private[format] val MissingTypeError: JsonValidationError = JsonValidationError("error.plugin.type.missing")
  private[format] val UnrecognizedTypeError: JsonValidationError = JsonValidationError(
    "error.plugin.type.unrecognized")

  private val PropertyNameType = "type"

  /**
   * Specification object for serializing/deserializing a plugin with a given type.
   *
   * @param typeName        The given plugin type name.
   * @param javaClass       The Java [[Class]] used for the deserialized plugin that is used when doing
   *                        serialization for identification.
   * @param formatOrPlugin  Either a [[Left]] with [[Format]] used for serializing/deserializing the
   *                        plugin type settings in/from JSON, or a [[Right]] with the singleton plugin type value
   *                        when the plugin has no settings and is only defined by its type name.
   * @param defaultSettings Default settings values in JSON object format that should be used when not
   *                        provided in a serialized JSON.
   * @tparam P Scala type used for the plugin type.
   */
  case class TypeSpec[P](typeName: String,
                         formatOrPlugin: Either[Format[P], P],
                         javaClass: Class[P],
                         defaultSettings: JsObject = Json.obj()) {
    def format: Format[P] = formatOrPlugin.swap.getOrElse(
      throw new IllegalArgumentException("TypeSpec that not contain a format!"))

    def plugin: P = formatOrPlugin.getOrElse(
      throw new IllegalArgumentException("TypeSpec that not contain a plugin value!")
    )
  }

  object TypeSpec {
    /**
     * Factory method for a plugin type that has one or more settings.
     */
    def withSettings[P](typeName: String,
                        format: Format[P],
                        javaClass: Class[P],
                        defaultSettings: JsObject = Json.obj()): TypeSpec[P] =
      TypeSpec[P](typeName, Left(format), javaClass, defaultSettings)

    /**
     * Factory method for a plugin type that has no settings and is only defined by its type name.
     *
     * This method should be typically used for plugin that do not implement [[Plugin]]. See the overloaded method if
     * it does.
     *
     * Note that in this case the [[TypeSpec]] does not need a [[Format]].
     */
    def withoutSettings[P <: Object](typeName: String, plugin: P): TypeSpec[P] =
      TypeSpec[P](typeName, Right(plugin), plugin.getClass.asInstanceOf[Class[P]])

    /**
     * Factory method for a plugin type that implements [[Plugin]], has no settings and is only defined by its type
     * name.
     *
     * Note that in this case the [[TypeSpec]] does not need a [[Format]].
     */
    def withoutSettings[P <: Plugin](plugin: P): TypeSpec[P] = withoutSettings(plugin.typeName, plugin)
  }

  private def writesWithTypeFor[P](writes: Writes[P], typeName: String): OWrites[P] = {
    //@formatter:off
    (
      (__ \ PropertyNameType).write[String] and
      __.write[P](writes)
    ) ({ plugin: P => (typeName, plugin) })
    //@formatter:on
  }
}

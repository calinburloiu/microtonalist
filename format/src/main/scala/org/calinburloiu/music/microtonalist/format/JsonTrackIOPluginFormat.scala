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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{PropertyNameType, TypeSpec, TypeSpecs}
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.MidiDeviceId
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*

/**
 * Abstract class extending [[JsonPluginFormat]] to handle JSON serialization and deserialization
 * for plugins related to MIDI track input/output.
 *
 * This class provides utility methods to create `TypeSpec` definitions for devices and inter-track
 * configurations, facilitating (de)serialization of associated plugin data.
 *
 * @tparam P A type parameter bounded by [[TrackIOSupport]] to specify the supported plugin type.
 */
abstract class JsonTrackIOPluginFormat[P <: TrackIOSupport] extends JsonPluginFormat[P] {

  import JsonTrackIOPluginFormat.*
  import org.calinburloiu.music.microtonalist.format.JsonCommonMidiFormat.{channelFormat, midiDeviceIdFormat}

  //@formatter:off
  protected implicit val deviceFormat: Format[DeviceParamsTuple] = (
    (__ \ "midiDeviceId").format[MidiDeviceId](midiDeviceIdFormat) and
    (__ \ "channel").formatNullable[Int](channelFormat)
  )(Tuple2.apply, identity)
  protected implicit val interTrackFormat: Format[InterTrackParamsTuple] = (
    (__ \ "trackId").format[TrackSpec.Id] and
    (__ \ "channel").formatNullable[Int](channelFormat)
  )(Tuple2.apply, identity)
  //@formatter:on

  /**
   * Creates a TypeSpec instance for track devices with serialization and deserialization settings.
   */
  protected def makeDeviceTypeSpec[T <: P](typeName: String,
                                           javaClass: Class[T],
                                           apply: DeviceParamsTuple => T,
                                           unapply: T => DeviceParamsTuple): TypeSpec[T] = {
    val reads: Reads[T] = deviceFormat.map(apply)
    val writes: Writes[T] = Writes { device =>
      Json.obj(PropertyNameType -> typeName) ++ deviceFormat.writes(unapply(device)).as[JsObject]
    }

    TypeSpec.withSettings[T](
      typeName = typeName,
      format = Format(reads, writes),
      javaClass = javaClass,
      defaultSettings = Json.obj()
    )
  }

  /**
   * Creates a TypeSpec instance for inter-track I/O operations with serialization and deserialization settings.
   */
  protected def makeInterTrackTypeSpec[T <: P](typeName: String,
                                               javaClass: Class[T],
                                               apply: InterTrackParamsTuple => T,
                                               unapply: T => InterTrackParamsTuple): TypeSpec[T] = {
    val reads: Reads[T] = interTrackFormat.map(apply)
    val writes: Writes[T] = Writes { device =>
      Json.obj(PropertyNameType -> typeName) ++ interTrackFormat.writes(unapply(device)).as[JsObject]
    }

    TypeSpec.withSettings[T](
      typeName = typeName,
      format = Format(reads, writes),
      javaClass = javaClass,
      defaultSettings = Json.obj()
    )
  }
}

object JsonTrackIOPluginFormat {
  protected type DeviceParamsTuple = (MidiDeviceId, Option[Int])
  protected type InterTrackParamsTuple = (TrackSpec.Id, Option[Int])
}

/**
 * JSON format handler for [[TrackInputSpec]] plugins, providing serialization and deserialization support
 * for various track input specifications.
 */
object JsonTrackInputSpecPluginFormat extends JsonTrackIOPluginFormat[TrackInputSpec] {
  override val familyName: String = TrackInputSpec.FamilyName

  override val defaultTypeName: Option[String] = Some(DeviceTrackInputSpec.TypeName)

  override val specs: TypeSpecs[TrackInputSpec] = Seq(
    makeDeviceTypeSpec[DeviceTrackInputSpec](
      typeName = DeviceTrackInputSpec.TypeName,
      javaClass = classOf[DeviceTrackInputSpec],
      apply = DeviceTrackInputSpec.apply.tupled,
      unapply = Tuple.fromProductTyped
    ),
    makeInterTrackTypeSpec[FromTrackInputSpec](
      typeName = FromTrackInputSpec.TypeName,
      javaClass = classOf[FromTrackInputSpec],
      apply = FromTrackInputSpec.apply.tupled,
      unapply = Tuple.fromProductTyped
    )
  )
}

/**
 * JSON format handler for [[TrackOutputSpec]] plugins, providing serialization and deserialization support
 * for various track output specifications.
 */
object JsonTrackOutputSpecPluginFormat extends JsonTrackIOPluginFormat[TrackOutputSpec] {
  override val familyName: String = TrackOutputSpec.FamilyName

  override val defaultTypeName: Option[String] = Some(DeviceTrackOutputSpec.TypeName)

  override val specs: TypeSpecs[TrackOutputSpec] = Seq(
    makeDeviceTypeSpec[DeviceTrackOutputSpec](
      typeName = DeviceTrackOutputSpec.TypeName,
      javaClass = classOf[DeviceTrackOutputSpec],
      apply = DeviceTrackOutputSpec.apply.tupled,
      unapply = Tuple.fromProductTyped
    ),
    makeInterTrackTypeSpec[ToTrackOutputSpec](
      typeName = ToTrackOutputSpec.TypeName,
      javaClass = classOf[ToTrackOutputSpec],
      apply = ToTrackOutputSpec.apply.tupled,
      unapply = Tuple.fromProductTyped
    )
  )
}

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

import java.io.InputStream

import org.calinburloiu.music.intonation.format.{JsonScaleFormat, Ref, ScaleLibrary}
import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.microtuner.{Modulation, OriginOld, ScaleList, ScaleMapping}
import org.calinburloiu.music.plugin.{Plugin, PluginConfig, PluginConfigIO, PluginRegistry}
import org.calinburloiu.music.tuning._
import play.api.libs.json._

class JsonScaleListFormat(scaleLibrary: ScaleLibrary,
                          tuningMapperRegistry: TuningMapperRegistry,
                          tuningReducerRegistry: TuningReducerRegistry) extends ScaleListFormat {

  private[this] implicit val scaleLibraryImpl: ScaleLibrary = scaleLibrary

  private lazy val defaultTuningListReducer =
    JsonScaleListFormat.createPlugin(tuningReducerRegistry, DirectTuningReducer.pluginId)

  override def read(inputStream: InputStream): ScaleList = {
    val repr = readRepr(inputStream).resolve

    fromReprToDomain(repr)
  }

  def readRepr(inputStream: InputStream): ScaleListRepr = {
    import JsonScaleListFormat._

    val json = Json.parse(inputStream)

    json.validate[ScaleListRepr] match {
      case JsSuccess(scaleList, _) => scaleList
      case error: JsError => throw new InvalidScaleListFileException(JsError.toJson(error).toString)
    }
  }

  def fromReprToDomain(scaleListRepr: ScaleListRepr): ScaleList = {
    val mapQuarterTonesLow = scaleListRepr.config
      .getOrElse(ScaleListConfigRepr.DEFAULT).mapQuarterTonesLow
    val defaultTuningMapperConfig = AutoTuningMapperConfig(mapQuarterTonesLow)
    val defaultTuningMapper = tuningMapperRegistry.get(AutoTuningMapper.pluginId)
      .create(Some(defaultTuningMapperConfig))

    val name = scaleListRepr.name.getOrElse("")
    val origin = OriginOld(scaleListRepr.origin.basePitchClass)

    val modulations = scaleListRepr.modulations.map { modulationRepr =>
      val transposition = modulationRepr.transposition.getOrElse(Interval.UNISON)

      val tuningMapper = modulationRepr.tuningMapper.map(createTuningMapper)
        .getOrElse(defaultTuningMapper)
      val scaleMapping = ScaleMapping(modulationRepr.scale.value, tuningMapper)

      val extension = modulationRepr.extension.map { extensionScaleRef =>
        ScaleMapping(extensionScaleRef.value, defaultTuningMapper)
      }

      val fill = modulationRepr.fill.map { fillScaleRef =>
        val fillTuningMapper = modulationRepr.fillTuningMapper.map(createTuningMapper)
          .getOrElse(defaultTuningMapper)
        ScaleMapping(fillScaleRef.value, fillTuningMapper)
      }

      Modulation(transposition, scaleMapping, extension, fill)
    }

    val tuningListReducer = scaleListRepr.tuningReducer.map(createTuningListReducer)
      .getOrElse(defaultTuningListReducer)

    val globalFillTuningMapper = scaleListRepr.globalFillTuningMapper.map(createTuningMapper)
      .getOrElse(defaultTuningMapper)
    val globalFill = ScaleMapping(scaleListRepr.globalFill.value, globalFillTuningMapper)

    ScaleList(name, origin, modulations, tuningListReducer, globalFill)
  }

  private[this] def createTuningMapper(spec: PluginSpecRepr): TuningMapper =
    JsonScaleListFormat.createPlugin(tuningMapperRegistry, spec.id, spec.config)

  private[this] def createTuningListReducer(spec: PluginSpecRepr): TuningReducer =
    JsonScaleListFormat.createPlugin(tuningReducerRegistry, spec.id, spec.config)
}

object JsonScaleListFormat {

  private[format] implicit val pluginSpecReprReads: Reads[PluginSpecRepr] =
    Json.using[Json.WithDefaultValues].reads[PluginSpecRepr].orElse {
      Reads.StringReads.map { id =>
        PluginSpecRepr(id)
      }
    }

  private[JsonScaleListFormat] implicit val intervalReads: Reads[Interval] = JsonScaleFormat.intervalReads

  private[JsonScaleListFormat] implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleFormat.jsonScaleReads

  private[format] implicit val scaleRefReads: Reads[Ref[Scale[Interval]]] = Ref.refReads[Scale[Interval]]

  private[format] implicit val scaleListBaseReprReads: Reads[OriginRepr] = Json.reads[OriginRepr]

  private[format] implicit val scaleListConfigReprReads: Reads[ScaleListConfigRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListConfigRepr]

  private[format] implicit val modulationReprReads: Reads[ModulationRepr] =
    Json.using[Json.WithDefaultValues].reads[ModulationRepr]

  private[format] implicit val scaleListReprReads: Reads[ScaleListRepr] =
    Json.using[Json.WithDefaultValues].reads[ScaleListRepr]

  private[JsonScaleListFormat] def createPlugin[P <: Plugin](registry: PluginRegistry[P],
                                                             id: String,
                                                             jsValueConfig: JsValue = JsNull): P = {
    val factory = registry.get(id)
    val config: Option[PluginConfig] = PluginConfigIO.fromPlayJsValue(jsValueConfig, factory.configClass)
    factory.create(config)
  }
}

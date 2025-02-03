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

import org.calinburloiu.music.microtonalist.composition._
import play.api.libs.json._

object JsonTuningMapperPluginFormat extends JsonPluginFormat[TuningMapper] {

  override val familyName: String = TuningMapper.FamilyName

  val AutoTypeName: String = AutoTuningMapper.typeName
  val ManualTypeName: String = ManualTuningMapper.typeName

  override val defaultTypeName: Option[String] = Some(AutoTypeName)

  private implicit val keyboardMappingFormat: Format[KeyboardMapping] = KeyboardMappingFormat.format

  private val manualTuningMapperReprFormat: Format[ManualTuningMapperRepr] = Json.format[ManualTuningMapperRepr]
  private val manualTuningMapperFormat: Format[ManualTuningMapper] = Format(
    manualTuningMapperReprFormat.map { repr => ManualTuningMapper(repr.keyboardMapping) },
    Writes { manualTuningMapper =>
      val repr = ManualTuningMapperRepr(manualTuningMapper.keyboardMapping)
      manualTuningMapperReprFormat.writes(repr)
    }
  )

  private implicit val softChromaticGenusMappingFormat: Format[SoftChromaticGenusMapping] =
    JsonSoftChromaticGenusMappingPluginFormat.format
  private val autoTuningMapperReprFormat: Format[AutoTuningMapperRepr] = Json.using[Json.WithDefaultValues]
    .format[AutoTuningMapperRepr]
  private val autoTuningMapperFormat: Format[AutoTuningMapper] = Format(
    autoTuningMapperReprFormat.map { repr =>
      AutoTuningMapper(
        shouldMapQuarterTonesLow = repr.shouldMapQuarterTonesLow,
        quarterToneTolerance = repr.quarterToneTolerance.getOrElse(DefaultQuarterToneTolerance),
        softChromaticGenusMapping = repr.softChromaticGenusMapping,
        overrideKeyboardMapping = repr.overrideKeyboardMapping.getOrElse(KeyboardMapping.empty)
      )
    },
    Writes { autoTuningMapper: AutoTuningMapper =>
      val repr = AutoTuningMapperRepr(
        shouldMapQuarterTonesLow = autoTuningMapper.shouldMapQuarterTonesLow,
        quarterToneTolerance = Some(autoTuningMapper.quarterToneTolerance),
        softChromaticGenusMapping = autoTuningMapper.softChromaticGenusMapping,
        overrideKeyboardMapping = Some(autoTuningMapper.overrideKeyboardMapping)
      )
      autoTuningMapperReprFormat.writes(repr)
    }
  )

  override val specs: JsonPluginFormat.TypeSpecs[TuningMapper] = Seq(
    JsonPluginFormat.TypeSpec.withSettings[ManualTuningMapper](
      typeName = ManualTypeName,
      format = manualTuningMapperFormat,
      javaClass = classOf[ManualTuningMapper]
    ),
    JsonPluginFormat.TypeSpec.withSettings[AutoTuningMapper](
      typeName = AutoTypeName,
      format = autoTuningMapperFormat,
      javaClass = classOf[AutoTuningMapper],
      defaultSettings = Json.obj(
        "shouldMapQuarterTonesLow" -> false,
        "quarterToneTolerance" -> DefaultQuarterToneTolerance,
        "softChromaticGenusMapping" -> SoftChromaticGenusMapping.Off.typeName
      )
    )
  )
}

private case class ManualTuningMapperRepr(keyboardMapping: KeyboardMapping)

private case class AutoTuningMapperRepr(shouldMapQuarterTonesLow: Boolean = false,
                                        quarterToneTolerance: Option[Double] = None,
                                        softChromaticGenusMapping: SoftChromaticGenusMapping =
                                        SoftChromaticGenusMapping.Off,
                                        overrideKeyboardMapping: Option[KeyboardMapping] = None)

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

import org.calinburloiu.music.microtonalist.core._
import play.api.libs.json._

object TuningMapperFormatComponent extends JsonFormatComponentFactory[TuningMapper] {

  override val familyName: String = "tuningMapper"

  val AutoTypeName: String = "auto"
  val ManualTypeName: String = "manual"

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

  private val autoTuningMapperReprFormat: Format[AutoTuningMapperRepr] = Json.using[Json.WithDefaultValues]
    .format[AutoTuningMapperRepr]
  private val autoTuningMapperFormat: Format[AutoTuningMapper] = Format(
    autoTuningMapperReprFormat.map { repr =>
      AutoTuningMapper(
        shouldMapQuarterTonesLow = repr.shouldMapQuarterTonesLow,
        quarterToneTolerance = repr.quarterToneTolerance.getOrElse(DefaultQuarterToneTolerance),
        softChromaticGenusMapping = SoftChromaticGenusMapping.withName(repr.softChromaticGenusMapping),
        overrideKeyboardMapping = repr.overrideKeyboardMapping.getOrElse(KeyboardMapping.empty)
      )
    },
    Writes { autoTuningMapper: AutoTuningMapper =>
      val repr = AutoTuningMapperRepr(
        shouldMapQuarterTonesLow = autoTuningMapper.shouldMapQuarterTonesLow,
        quarterToneTolerance = Some(autoTuningMapper.quarterToneTolerance),
        softChromaticGenusMapping = autoTuningMapper.softChromaticGenusMapping.entryName,
        overrideKeyboardMapping = Some(autoTuningMapper.overrideKeyboardMapping)
      )
      autoTuningMapperReprFormat.writes(repr)
    }
  )

  override val specs: JsonFormatComponent.TypeSpecs[TuningMapper] = Seq(
    JsonFormatComponent.TypeSpec.withSettings[ManualTuningMapper](
      typeName = ManualTypeName,
      format = manualTuningMapperFormat,
      javaClass = classOf[ManualTuningMapper]
    ),
    JsonFormatComponent.TypeSpec.withSettings[AutoTuningMapper](
      typeName = AutoTypeName,
      format = autoTuningMapperFormat,
      javaClass = classOf[AutoTuningMapper]
    )
  )
}

private case class ManualTuningMapperRepr(keyboardMapping: KeyboardMapping)

private case class AutoTuningMapperRepr(shouldMapQuarterTonesLow: Boolean = false,
                                        quarterToneTolerance: Option[Double] = None,
                                        softChromaticGenusMapping: String = SoftChromaticGenusMapping.Off.entryName,
                                        overrideKeyboardMapping: Option[KeyboardMapping] = None)

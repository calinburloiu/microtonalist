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
import org.calinburloiu.music.scmidi.PitchClass
import play.api.libs.json._

object TuningMapperComponentFormat extends ComponentJsonFormatFactory[TuningMapper] {

  override val familyName: String = "tuningMapper"

  val AutoTypeName: String = "auto"
  val ManualTypeName: String = "manual"

  override def componentJsonFormat: ComponentJsonFormat[TuningMapper] = new ComponentJsonFormat[TuningMapper](
    familyName = familyName,
    specs = specs,
    defaultTypeName = Some(AutoTypeName)
  )

  private[format] val InvalidPitchClassError: String = "error.tuningMapper.pitchClass.invalid"
  private[format] val InvalidKeyboardMapping: String = "error.tuningMapper.keyboardMapping.invalid"

  private val denseKeyboardMappingReads: Reads[KeyboardMapping] = {
    Reads.seq[Option[Int]](Reads.optionWithNull[Int]).map { seq =>
      KeyboardMapping(seq)
    }
  }
  private val sparseKeyboardMappingReads: Reads[KeyboardMapping] =
    Reads.map[Int].flatMapResult { mappingRepr =>
      val parsedMapping = mappingRepr.map {
        case (pitchClassString, scalePitchIndex) => (PitchClass.parse(pitchClassString), scalePitchIndex)
      }
      if (parsedMapping.forall(_._1.isDefined)) {
        val sparseMapping: Map[Int, Option[Int]] = parsedMapping.map {
          case (maybePitchClass, scalePitchIndex) => (maybePitchClass.get.number, Some(scalePitchIndex))
        }.withDefault(_ => None)
        val indexesInScale = (0 until 12).map { pitchClassNumber => sparseMapping(pitchClassNumber) }
        JsSuccess(KeyboardMapping(indexesInScale))
      } else {
        JsError(InvalidPitchClassError)
      }
    }
  private val keyboardMappingReads: Reads[KeyboardMapping] =
    denseKeyboardMappingReads orElse sparseKeyboardMappingReads orElse Reads.failed(InvalidKeyboardMapping)
  private val keyboardMappingWrites: Writes[KeyboardMapping] = Writes { keyboardMapping: KeyboardMapping =>
    Writes.seq[Option[Int]](Writes.optionWithNull[Int]).writes(keyboardMapping.indexesInScale)
  }
  private implicit val keyboardMappingFormat: Format[KeyboardMapping] = Format(keyboardMappingReads,
    keyboardMappingWrites)
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

  private val specs: ComponentJsonFormat.SpecsSeqType[TuningMapper] = Seq(
    ComponentJsonFormat.TypeSpec.withSettings[ManualTuningMapper](
      typeName = ManualTypeName,
      format = manualTuningMapperFormat,
      javaClass = classOf[ManualTuningMapper]
    ),
    ComponentJsonFormat.TypeSpec.withSettings[AutoTuningMapper](
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

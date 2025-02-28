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

import org.calinburloiu.music.microtonalist.composition.KeyboardMapping
import org.calinburloiu.music.microtonalist.format.PitchClassFormat.InvalidPitchClassError
import org.calinburloiu.music.scmidi.PitchClass
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

object KeyboardMappingFormat {

  private[format] val InvalidKeyboardMapping: String = "error.tuningMapper.keyboardMapping.invalid"

  private val denseKeyboardMappingReads: Reads[KeyboardMapping] = {
    Reads.seq[Option[Int]](Reads.optionWithNull[Int]).flatMapResult { seq =>
      Try {
        KeyboardMapping(seq)
      } match {
        case Success(keyboardMapping) => JsSuccess(keyboardMapping)
        case Failure(_) => JsError(InvalidKeyboardMapping)
      }
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

        Try {
          KeyboardMapping(indexesInScale)
        } match {
          case Success(keyboardMapping) => JsSuccess(keyboardMapping)
          case Failure(_) => JsError(InvalidKeyboardMapping)
        }
      } else {
        JsError(InvalidPitchClassError)
      }
    }

  val reads: Reads[KeyboardMapping] =
    denseKeyboardMappingReads orElse sparseKeyboardMappingReads orElse Reads.failed(InvalidKeyboardMapping)

  val writes: Writes[KeyboardMapping] = Writes { (keyboardMapping: KeyboardMapping) =>
    Writes.seq[Option[Int]](Writes.optionWithNull[Int]).writes(keyboardMapping.indexesInScale)
  }

  val format: Format[KeyboardMapping] = Format(reads, writes)
}

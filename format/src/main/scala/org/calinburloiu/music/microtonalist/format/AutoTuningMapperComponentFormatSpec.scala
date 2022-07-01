/*
 * Copyright 2022 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.core.{AutoTuningMapper, CentsTolerance}
import play.api.libs.json.{Format, Json, Writes}

object AutoTuningMapperComponentFormatSpec extends ComponentFormatSpec[AutoTuningMapper] {
  override val typeName: String = "auto"

  override val javaClass: Class[AutoTuningMapper] = classOf[AutoTuningMapper]

  private val reprFormat: Format[AutoTuningMapperRepr] = Json
    .using[Json.WithDefaultValues]
    .format[AutoTuningMapperRepr]
  override val format: Option[Format[AutoTuningMapper]] = Some(
    Format(
      reprFormat.map { repr =>
        AutoTuningMapper(mapQuarterTonesLow = repr.mapQuarterTonesLow,
          halfTolerance = repr.halfTolerance.getOrElse(CentsTolerance))
      },
      Writes { mapper: AutoTuningMapper =>
        val repr = AutoTuningMapperRepr(mapper.mapQuarterTonesLow, halfTolerance = Some(mapper.halfTolerance))
        reprFormat.writes(repr)
      }
    )
  )

  override lazy val default: Option[AutoTuningMapper] = Some(AutoTuningMapper(mapQuarterTonesLow = false))
}

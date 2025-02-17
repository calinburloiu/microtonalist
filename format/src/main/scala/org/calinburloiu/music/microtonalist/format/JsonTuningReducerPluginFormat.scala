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

import org.calinburloiu.music.microtonalist.composition.{DirectTuningReducer, MergeTuningReducer, TuningReducer}
import org.calinburloiu.music.microtonalist.tuner.DefaultCentsTolerance
import play.api.libs.functional.syntax.toApplicativeOps
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json.{Format, Json, Writes, __}

object JsonTuningReducerPluginFormat extends JsonPluginFormat[TuningReducer] {

  override val familyName: String = TuningReducer.FamilyName

  val DirectTypeName: String = DirectTuningReducer.typeName
  val MergeTypeName: String = MergeTuningReducer.typeName

  private val mergeTypeFormat: Format[MergeTuningReducer] = {
    val path = __ \ "equalityTolerance"
    val reads = path.read[Double](min(-50.0) keepAnd max(50.0)).map { equalityTolerance =>
      MergeTuningReducer(equalityTolerance)
    }
    val writes = Writes[MergeTuningReducer] { mergeTuningReducer =>
      path.write[Double].writes(mergeTuningReducer.equalityTolerance)
    }

    Format(reads, writes)
  }

  override val specs: JsonPluginFormat.TypeSpecs[TuningReducer] = Seq(
    JsonPluginFormat.TypeSpec.withoutSettings(DirectTuningReducer),
    JsonPluginFormat.TypeSpec.withSettings(
      MergeTypeName,
      mergeTypeFormat,
      classOf[MergeTuningReducer],
      defaultSettings = Json.obj(
        "equalityTolerance" -> DefaultCentsTolerance
      )
    )
  )

  override val defaultTypeName: Option[String] = Some(MergeTypeName)
}

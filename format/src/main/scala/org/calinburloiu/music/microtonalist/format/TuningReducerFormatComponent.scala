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

import org.calinburloiu.music.microtonalist.core.{DefaultCentsTolerance, DirectTuningReducer, MergeTuningReducer, TuningReducer}
import play.api.libs.functional.syntax.toApplicativeOps
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json.{Format, Writes, __}

object TuningReducerFormatComponent extends JsonFormatComponentFactory[TuningReducer] {

  override val familyName: String = "tuningReducer"

  val DirectTypeName: String = "direct"
  val MergeTypeName: String = "merge"

  private val mergeTypeFormat: Format[MergeTuningReducer] = {
    val path = __ \ "equalityTolerance"
    val reads =
      path.readWithDefault[Double](DefaultCentsTolerance)(min(-50.0) keepAnd max(50.0))
        .map { equalityTolerance => MergeTuningReducer(equalityTolerance) }
    val writes = Writes[MergeTuningReducer] { mergeTuningReducer =>
      path.write[Double].writes(mergeTuningReducer.equalityTolerance)
    }

    Format(reads, writes)
  }

  override val specs: JsonFormatComponent.TypeSpecs[TuningReducer] = Seq(
    JsonFormatComponent.TypeSpec.withoutSettings(DirectTypeName, DirectTuningReducer),
    JsonFormatComponent.TypeSpec.withSettings(MergeTypeName, mergeTypeFormat, classOf[MergeTuningReducer])
  )

  override val defaultTypeName: Option[String] = Some(MergeTypeName)
}

/*
 * Copyright 2026 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.TypeSpec
import org.calinburloiu.music.microtonalist.tuner.MonophonicPitchBendTuner
import org.calinburloiu.music.scmidi.PitchBendSensitivity
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{Format, Json, __}

/**
 * JSON format utilities for [[MonophonicPitchBendTuner]].
 */
object JsonMonophonicPitchBendTunerPluginFormating {

  private implicit val pitchBendSensitivityFormat: Format[PitchBendSensitivity] =
    JsonCommonMidiFormat.pitchBendSensitivityFormat

  //@formatter:off
  private val monophonicPitchBendTunerFormat: Format[MonophonicPitchBendTuner] = (
    (__ \ "outputChannel").format[Int](JsonCommonMidiFormat.channelFormat) and
    (__ \ "pitchBendSensitivity").format[PitchBendSensitivity]
  )(MonophonicPitchBendTuner.apply, Tuple.fromProductTyped)
  //@formatter:on

  /**
   * JSON format specification for [[MonophonicPitchBendTuner]].
   *
   * @see [[JsonTunerPluginFormat]] where it is used.
   */
  val spec: TypeSpec[MonophonicPitchBendTuner] = TypeSpec.withSettings[MonophonicPitchBendTuner](
    typeName = MonophonicPitchBendTuner.TypeName,
    format = monophonicPitchBendTunerFormat,
    javaClass = classOf[MonophonicPitchBendTuner],
    defaultSettings = Json.obj(
      "outputChannel" -> 1,
      "pitchBendSensitivity" -> Json.obj(
        "semitoneCount" -> 2,
        "centCount" -> 0
      )
    )
  )
}

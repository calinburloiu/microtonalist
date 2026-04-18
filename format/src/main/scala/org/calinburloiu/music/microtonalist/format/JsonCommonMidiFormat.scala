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

import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps}
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json.*

object JsonCommonMidiFormat {
  val midiDeviceIdFormat: Format[MidiDeviceId] = Json.format[MidiDeviceId]

  val channelFormat: Format[Int] = Format(
    (min(1) keepAnd max(16)).map(_ - 1),
    Writes { (channel: Int) => JsNumber(channel + 1) }
  )

  val pitchBendSensitivityFormat: Format[PitchBendSensitivity] = (
    (__ \ "semitoneCount").format[Int](uint7Format) and
      (__ \ "centCount").formatWithDefault[Int](0)(uint7Format)
    )(PitchBendSensitivity.apply, Tuple.fromProductTyped)
  //@formatter:on
}

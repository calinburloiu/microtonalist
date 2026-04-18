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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{PropertyNameType, TypeSpec}
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.MidiDeviceId
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*

/**
 * JSON format utilities for [[MtsTuner]] plugins.
 */
object JsonMtsTunerPluginFormating {

  import org.calinburloiu.music.microtonalist.format.JsonCommonMidiFormat.*

  private type MtsTunerParamsTuple = (Boolean, Option[MidiDeviceId])

  //@formatter:off
  private val mtsTunerCommonFormat: Format[MtsTunerParamsTuple] = (
    (__ \ "thru").format[Boolean] and
    (__ \ "altTuningOutput").formatNullable[MidiDeviceId](midiDeviceIdFormat)
  )(Tuple2.apply, identity)
  //@formatter:on

  private def makeMtsTunerTypeSpec[T <: MtsTuner](typeName: String,
                                                  javaClass: Class[T],
                                                  apply: MtsTunerParamsTuple => T,
                                                  unapply: T => MtsTunerParamsTuple): TypeSpec[T] = {
    val reads: Reads[T] = mtsTunerCommonFormat.map(apply)
    val writes: Writes[T] = Writes { tuner =>
      Json.obj(PropertyNameType -> typeName) ++ mtsTunerCommonFormat.writes(unapply(tuner)).as[JsObject]
    }

    TypeSpec.withSettings[T](
      typeName = typeName,
      format = Format(reads, writes),
      javaClass = javaClass,
      defaultSettings = Json.obj(
        "thru" -> false
      )
    )
  }

  /**
   * JSON format specifications for all [[MtsTuner]] plugins.
   *
   * @see [[JsonTunerPluginFormat]] where it is used.
   */
  val specs: Seq[TypeSpec[? <: MtsTuner]] = Seq(
    makeMtsTunerTypeSpec[MtsOctave1ByteNonRealTimeTuner](
      typeName = MtsTuner.MtsOctave1ByteNonRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave1ByteNonRealTimeTuner],
      apply = MtsOctave1ByteNonRealTimeTuner.apply.tupled,
      unapply = Tuple.fromProductTyped
    ),
    makeMtsTunerTypeSpec[MtsOctave2ByteNonRealTimeTuner](
      typeName = MtsTuner.MtsOctave2ByteNonRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave2ByteNonRealTimeTuner],
      apply = MtsOctave2ByteNonRealTimeTuner.apply.tupled,
      unapply = Tuple.fromProductTyped
    ),
    makeMtsTunerTypeSpec[MtsOctave1ByteRealTimeTuner](
      typeName = MtsTuner.MtsOctave1ByteRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave1ByteRealTimeTuner],
      apply = MtsOctave1ByteRealTimeTuner.apply.tupled,
      unapply = Tuple.fromProductTyped
    ),
    makeMtsTunerTypeSpec[MtsOctave2ByteRealTimeTuner](
      typeName = MtsTuner.MtsOctave2ByteRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave2ByteRealTimeTuner],
      apply = MtsOctave2ByteRealTimeTuner.apply.tupled,
      unapply = Tuple.fromProductTyped
    )
  )
}

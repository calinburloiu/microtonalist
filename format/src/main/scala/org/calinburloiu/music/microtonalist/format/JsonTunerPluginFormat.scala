/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{PropertyNameType, TypeSpec, TypeSpecs}
import org.calinburloiu.music.microtonalist.tuner._
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json._

object JsonTunerPluginFormat extends JsonPluginFormat[Tuner] {

  import org.calinburloiu.music.microtonalist.format.JsonCommonMidiFormat._

  override val familyName: String = Tuner.FamilyName

  private type MtsTunerParamsTuple = (Boolean, Option[MidiDeviceId])

  //@formatter:off
  private implicit val pitchBendSensitivityFormat: Format[PitchBendSensitivity] = (
    (__ \ "semitoneCount").format[Int](uint7Format) and
    (__ \ "centCount").format[Int](uint7Format)
  )(PitchBendSensitivity.apply, unlift(PitchBendSensitivity.unapply))

  private val monophonicPitchBendTunerFormat: Format[MonophonicPitchBendTuner] = (
    (__ \ "outputChannel").format[Int](channelFormat) and
    (__ \ "pitchBendSensitivity").format[PitchBendSensitivity]
  )(MonophonicPitchBendTuner.apply, unlift(MonophonicPitchBendTuner.unapply))

  private val mtsTunerCommonFormat: Format[MtsTunerParamsTuple] = (
    (__ \ "thru").format[Boolean] and
    (__ \ "altTuningOutput").formatNullable[MidiDeviceId](midiDeviceIdFormat)
  )(Tuple2.apply, identity)
  //@formatter:on

  override val specs: TypeSpecs[Tuner] = Seq(
    TypeSpec.withSettings[MonophonicPitchBendTuner](
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
    ),
    makeMtsTunerTypeSpec[MtsOctave1ByteNonRealTimeTuner](
      typeName = MtsTuner.MtsOctave1ByteNonRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave1ByteNonRealTimeTuner],
      apply = (MtsOctave1ByteNonRealTimeTuner.apply _).tupled,
      unapply = unlift(MtsOctave1ByteNonRealTimeTuner.unapply)
    ),
    makeMtsTunerTypeSpec[MtsOctave2ByteNonRealTimeTuner](
      typeName = MtsTuner.MtsOctave2ByteNonRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave2ByteNonRealTimeTuner],
      apply = (MtsOctave2ByteNonRealTimeTuner.apply _).tupled,
      unapply = unlift(MtsOctave2ByteNonRealTimeTuner.unapply)
    ),
    makeMtsTunerTypeSpec[MtsOctave1ByteRealTimeTuner](
      typeName = MtsTuner.MtsOctave1ByteRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave1ByteRealTimeTuner],
      apply = (MtsOctave1ByteRealTimeTuner.apply _).tupled,
      unapply = unlift(MtsOctave1ByteRealTimeTuner.unapply)
    ),
    makeMtsTunerTypeSpec[MtsOctave2ByteRealTimeTuner](
      typeName = MtsTuner.MtsOctave2ByteRealTimeTunerTypeName,
      javaClass = classOf[MtsOctave2ByteRealTimeTuner],
      apply = (MtsOctave2ByteRealTimeTuner.apply _).tupled,
      unapply = unlift(MtsOctave2ByteRealTimeTuner.unapply)
    )
  )

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
}

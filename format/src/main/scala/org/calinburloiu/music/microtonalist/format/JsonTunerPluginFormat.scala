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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{PropertyNameType, TypeSpec, TypeSpecs}
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps}
import play.api.libs.json.*
import play.api.libs.json.Reads.{max, min}

object JsonTunerPluginFormat extends JsonPluginFormat[Tuner] {

  import org.calinburloiu.music.microtonalist.format.JsonCommonMidiFormat.*

  override val familyName: String = Tuner.FamilyName

  private type MtsTunerParamsTuple = (Boolean, Option[MidiDeviceId])

  //@formatter:off
  private implicit val pitchBendSensitivityFormat: Format[PitchBendSensitivity] = (
    (__ \ "semitoneCount").format[Int](uint7Format) and
    (__ \ "centCount").formatWithDefault[Int](0)(uint7Format)
  )(PitchBendSensitivity.apply, Tuple.fromProductTyped)

  private val monophonicPitchBendTunerFormat: Format[MonophonicPitchBendTuner] = (
    (__ \ "outputChannel").format[Int](channelFormat) and
    (__ \ "pitchBendSensitivity").format[PitchBendSensitivity]
  )(MonophonicPitchBendTuner.apply, Tuple.fromProductTyped)

  private val mtsTunerCommonFormat: Format[MtsTunerParamsTuple] = (
    (__ \ "thru").format[Boolean] and
    (__ \ "altTuningOutput").formatNullable[MidiDeviceId](midiDeviceIdFormat)
  )(Tuple2.apply, identity)
  //@formatter:on

  private val defaultMasterPbs = MpeZone.DefaultMasterPitchBendSensitivity
  private val defaultMemberPbs = MpeZone.DefaultMemberPitchBendSensitivity

  private val memberCountFormat: Format[Int] = {
    val reads = __.read[Int](min(0) keepAnd max(15))
    Format(reads, Writes.IntWrites)
  }

  private implicit val inputModeFormat: Format[MpeInputMode] = Format(
    Reads[MpeInputMode] {
      case JsString("nonMpe") => JsSuccess(MpeInputMode.NonMpe)
      case JsString("mpe") => JsSuccess(MpeInputMode.Mpe)
      case JsString(_) => JsError("error.expected.inputMode")
      case _ => JsError("error.expected.jsstring")
    },
    Writes[MpeInputMode] {
      case MpeInputMode.NonMpe => JsString("nonMpe")
      case MpeInputMode.Mpe => JsString("mpe")
    }
  )

  //@formatter:off
  private val mpeZoneFormat: Format[(Int, PitchBendSensitivity, PitchBendSensitivity)] = (
    (__ \ "memberCount").format[Int](memberCountFormat) and
    (__ \ "masterPitchBendSensitivity").formatWithDefault[PitchBendSensitivity](defaultMasterPbs) and
    (__ \ "memberPitchBendSensitivity").formatWithDefault[PitchBendSensitivity](defaultMemberPbs)
  )(Tuple3.apply, identity)
  //@formatter:on

  private val mpeTunerReads: Reads[MpeTuner] = {
    val zonesReads: Reads[(MpeZone, MpeZone)] = (__ \ "zones").readNullable[JsObject].flatMap {
      case None =>
        Reads.pure((MpeZone(MpeZoneType.Lower, 15), MpeZone(MpeZoneType.Upper, 0)))
      case Some(_) =>
        val lowerReads = (__ \ "zones" \ "lower").readNullable[(Int, PitchBendSensitivity, PitchBendSensitivity)](
          mpeZoneFormat).map {
          case Some((mc, masterPbs, memberPbs)) => MpeZone(MpeZoneType.Lower, mc, masterPbs, memberPbs)
          case None => MpeZone(MpeZoneType.Lower, 15)
        }
        val upperReads = (__ \ "zones" \ "upper").readNullable[(Int, PitchBendSensitivity, PitchBendSensitivity)](
          mpeZoneFormat).map {
          case Some((mc, masterPbs, memberPbs)) => MpeZone(MpeZoneType.Upper, mc, masterPbs, memberPbs)
          case None => MpeZone(MpeZoneType.Upper, 0)
        }
        for {
          lower <- lowerReads
          upper <- upperReads
          _ <- Reads[JsValue] { _ =>
            if (lower.isEnabled && upper.isEnabled &&
              lower.memberChannels.toSet.intersect(upper.memberChannels.toSet).nonEmpty) {
              JsError(__ \ "zones", "error.zones.overlap")
            } else {
              JsSuccess(JsNull)
            }
          }
        } yield (lower, upper)
    }

    for {
      inputMode <- (__ \ "inputMode").readWithDefault[MpeInputMode](MpeInputMode.NonMpe)
      zones <- zonesReads
    } yield new MpeTuner(zones, inputMode)
  }

  private val mpeTunerWrites: Writes[MpeTuner] = Writes[MpeTuner] { tuner =>
    val (lower, upper) = tuner.zones
    val zonesObj = Json.obj(
      "lower" -> mpeZoneFormat.writes((lower.memberCount, lower.masterPitchBendSensitivity,
        lower.memberPitchBendSensitivity)),
      "upper" -> mpeZoneFormat.writes((upper.memberCount, upper.masterPitchBendSensitivity,
        upper.memberPitchBendSensitivity))
    )
    Json.obj(
      PropertyNameType -> MpeTuner.TypeName,
      "inputMode" -> inputModeFormat.writes(tuner.inputMode),
      "zones" -> zonesObj
    )
  }

  private val mpeTunerFormat: Format[MpeTuner] = Format(mpeTunerReads, mpeTunerWrites)

  override val specs: TypeSpecs[Tuner] = Seq(
    TypeSpec.withSettings[MpeTuner](
      typeName = MpeTuner.TypeName,
      format = mpeTunerFormat,
      javaClass = classOf[MpeTuner]
    ),
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

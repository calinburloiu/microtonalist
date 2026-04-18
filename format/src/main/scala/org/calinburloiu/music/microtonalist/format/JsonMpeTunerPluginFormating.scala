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
import org.calinburloiu.music.microtonalist.tuner.{MpeInputMode, MpeTuner, MpeZone, MpeZoneType, MpeZones}
import org.calinburloiu.music.scmidi.PitchBendSensitivity
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps}
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json.*

/**
 * JSON format utilities for [[MpeTuner]].
 */
object JsonMpeTunerPluginFormating {

  private implicit val pitchBendSensitivityFormat: Format[PitchBendSensitivity] =
    JsonCommonMidiFormat.pitchBendSensitivityFormat

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
    val zonesReads: Reads[MpeZones] = (__ \ "zones").readNullable[JsObject].flatMap {
      case None =>
        Reads.pure(MpeZones.DefaultZones)
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
            if (MpeZones.wouldOverlap(lower, upper)) {
              JsError(__ \ "zones", "error.zones.overlap")
            } else {
              JsSuccess(JsNull)
            }
          }
        } yield MpeZones(lower, upper)
    }

    for {
      inputMode <- (__ \ "inputMode").readWithDefault[MpeInputMode](MpeInputMode.NonMpe)
      zones <- zonesReads
    } yield MpeTuner(zones, inputMode)
  }

  private val mpeTunerWrites: Writes[MpeTuner] = Writes[MpeTuner] { tuner =>
    val lower = tuner.zones.lower
    val upper = tuner.zones.upper
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

  /**
   * JSON format specification for [[MpeTuner]].
   *
   * @see [[JsonTunerPluginFormat]] where it is used.
   */
  val spec: TypeSpec[MpeTuner] = TypeSpec.withSettings[MpeTuner](
    typeName = MpeTuner.TypeName,
    format = mpeTunerFormat,
    javaClass = classOf[MpeTuner]
  )
}

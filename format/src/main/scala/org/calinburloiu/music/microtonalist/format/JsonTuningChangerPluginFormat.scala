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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{TypeSpec, TypeSpecs}
import org.calinburloiu.music.microtonalist.tuner.PedalTuningChanger.Cc
import org.calinburloiu.music.microtonalist.tuner.{PedalTuningChanger, TuningChangeTriggers, TuningChanger}
import org.calinburloiu.music.scmidi.ScCcMidiMessage
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps, unlift}
import play.api.libs.json.Reads.{max, min}
import play.api.libs.json._

import scala.util.Try

object JsonTuningChangerPluginFormat extends JsonPluginFormat[TuningChanger] {

  override val familyName: String = TuningChanger.FamilyName

  // # index triggers
  private val tuningIndexKeyReads: String => JsResult[Int] = { str =>
    Try(Integer.parseInt(str)).toOption match {
      case Some(tuningIndex) if tuningIndex >= 0 => JsSuccess(tuningIndex)
      case _ => JsError("error.expected.integer.positive")
    }
  }
  private val indexTriggersReads: Reads[Map[Int, Int]] = Reads.mapReads[Int, Cc](tuningIndexKeyReads)(uint7Format)
  private val indexTriggersWrites: Writes[Map[Int, Int]] = Writes { map =>
    val convertedMap = map.map { case (k, v) => k.toString -> v }
    Json.toJson(convertedMap)
  }

  //@formatter:off
  val ccTriggersFormat: Format[TuningChangeTriggers[Cc]] = (
    (__ \ "previous").formatNullable[Cc](uint7Format) and
    (__ \ "next").formatNullable[Cc](uint7Format) and
    (__ \ "index").formatWithDefault[Map[Int, Cc]](Map.empty)(Format(indexTriggersReads, indexTriggersWrites))
  )(TuningChangeTriggers.apply, Tuple.fromProductTyped)

  implicit val pedalTuningChangerFormat: Format[PedalTuningChanger] = (
    (__ \ "triggers").format[TuningChangeTriggers[Cc]](ccTriggersFormat) and
    (__ \ "threshold").format[Int](min(0) keepAnd max(126)) and
    (__ \ "triggersThru").format[Boolean]
  )(PedalTuningChanger.apply, Tuple.fromProductTyped)
  //@formatter:on

  override val specs: TypeSpecs[TuningChanger] = Seq(
    TypeSpec.withSettings[PedalTuningChanger](
      typeName = PedalTuningChanger.TypeName,
      format = pedalTuningChangerFormat,
      javaClass = classOf[PedalTuningChanger],
      defaultSettings = Json.obj(
        "triggers" -> Json.obj(
          "previous" -> ScCcMidiMessage.SoftPedal,
          "next" -> ScCcMidiMessage.SostenutoPedal
        ),
        "threshold" -> 0,
        "triggersThru" -> false
      )
    )
  )
}

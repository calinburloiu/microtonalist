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

import org.calinburloiu.music.intonation.{Interval, IntonationStandard}
import org.calinburloiu.music.microtonalist.composition.{ConcertPitchTuningReference, StandardTuningReference, TuningReference}
import org.calinburloiu.music.microtonalist.format.JsonConstraints.exclusiveMin
import org.calinburloiu.music.scmidi.{DefaultConcertPitchFreq, MidiNote, PitchClass}
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps, unlift}
import play.api.libs.json.*
import play.api.libs.json.Reads.{max, min}

case class JsonTuningReferencePluginFormat(intonationStandard: IntonationStandard) extends
  JsonPluginFormat[TuningReference] {

  override val familyName: String = TuningReference.FamilyName

  val StandardTypeName: String = StandardTuningReference.typeName
  val ConcertPitchTypeName: String = ConcertPitchTuningReference.typeName

  override val defaultTypeName: Option[String] = Some(StandardTypeName)

  private implicit val intervalFormat: Format[Interval] = JsonIntervalFormat.formatFor(intonationStandard)

  //@formatter:off
  private val standardTypeFormat: Format[StandardTuningReference] = (
    (__ \ "basePitchClass").format[PitchClass](PitchClassFormat) and
    (__ \ "baseOffset").format[Double](min(-50.0) keepAnd max(50.0))
  )(StandardTuningReference.apply, Tuple.fromProductTyped)
  private val concertPitchTypeFormat: Format[ConcertPitchTuningReference] = (
    (__ \ "concertPitchToBaseInterval").format[Interval] and
    (__ \ "baseMidiNote").format[MidiNote](MidiNoteFormat) and
    (__ \ "concertPitchFrequency").format[Double](exclusiveMin(0.0) keepAnd max(20000.0))
  )(ConcertPitchTuningReference.apply, Tuple.fromProductTyped)
  //@formatter:on

  override val specs: JsonPluginFormat.TypeSpecs[TuningReference] = Seq(
    JsonPluginFormat.TypeSpec.withSettings[StandardTuningReference](
      typeName = StandardTypeName,
      format = standardTypeFormat,
      javaClass = classOf[StandardTuningReference],
      defaultSettings = Json.obj(
        "baseOffset" -> 0.0
      )
    ),
    JsonPluginFormat.TypeSpec.withSettings[ConcertPitchTuningReference](
      typeName = ConcertPitchTypeName,
      format = concertPitchTypeFormat,
      javaClass = classOf[ConcertPitchTuningReference],
      defaultSettings = Json.obj(
        "concertPitchFrequency" -> DefaultConcertPitchFreq
      )
    )
  )
}

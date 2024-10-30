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

import org.calinburloiu.music.intonation.{Interval, IntonationStandard}
import org.calinburloiu.music.microtonalist.core.{ConcertPitchTuningReference, StandardTuningReference, TuningReference}
import org.calinburloiu.music.scmidi.{DefaultConcertPitchFreq, MidiNote, PitchClass}
import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json._

case class TuningReferenceFormatComponent(intonationStandard: IntonationStandard) extends
  JsonFormatComponentFactory[TuningReference] {

  override val familyName: String = "tuningReference"

  val StandardTypeName: String = "standard"
  val ConcertPitchTypeName: String = "concertPitch"

  override val defaultTypeName: Option[String] = Some(StandardTypeName)

  private[format] val ConcertPitchFreqRangeError = "error.concertPitchFrequency.range"

  private implicit val intervalFormat: Format[Interval] = JsonIntervalFormat.formatFor(intonationStandard)

  //@formatter:off
  private val standardTypeFormat: Format[StandardTuningReference] = (
    (__ \ "basePitchClass").format[PitchClass](PitchClassFormat) and
    (__ \ "baseDeviation").formatWithDefault[Double](0.0)
  )(StandardTuningReference.apply, unlift(StandardTuningReference.unapply))

  private val concertPitchTypeReads: Reads[ConcertPitchTuningReference] = (
    (__ \ "concertPitchToBaseInterval").read[Interval] and
    (__ \ "baseMidiNote").read[MidiNote](MidiNoteFormat) and
    (__ \ "concertPitchFrequency").readWithDefault[Double](DefaultConcertPitchFreq)(
      Reads.filter(JsonValidationError(ConcertPitchFreqRangeError)) { v: Double => v > 0.0 && v <= 20000 })
  )(ConcertPitchTuningReference.apply _)
  private val concertPitchTypeWrites: Writes[ConcertPitchTuningReference] = (
    (__ \ "concertPitchToBaseInterval").write[Interval] and
    (__ \ "baseMidiNote").write[MidiNote](MidiNoteFormat) and
    (__ \ "concertPitchFrequency").write[Double]
  )(unlift(ConcertPitchTuningReference.unapply))
  private val concertPitchTypeFormat: Format[ConcertPitchTuningReference] = Format(
    concertPitchTypeReads, concertPitchTypeWrites)
  //@formatter:on

  override val specs: JsonFormatComponent.TypeSpecs[TuningReference] = Seq(
    JsonFormatComponent.TypeSpec.withSettings[StandardTuningReference](
      typeName = StandardTypeName,
      format = standardTypeFormat,
      javaClass = classOf[StandardTuningReference]
    ),
    JsonFormatComponent.TypeSpec.withSettings[ConcertPitchTuningReference](
      typeName = ConcertPitchTypeName,
      format = concertPitchTypeFormat,
      javaClass = classOf[ConcertPitchTuningReference]
    )
  )
}

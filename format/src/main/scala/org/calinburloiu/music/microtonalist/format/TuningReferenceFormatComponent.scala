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
import org.calinburloiu.music.microtonalist.format.JsonConstraints.exclusiveMin
import org.calinburloiu.music.scmidi.{DefaultConcertPitchFreq, MidiNote, PitchClass}
import play.api.libs.functional.syntax.{toApplicativeOps, toFunctionalBuilderOps, unlift}
import play.api.libs.json.Reads.{min, max}
import play.api.libs.json._

case class TuningReferenceFormatComponent(intonationStandard: IntonationStandard) extends
  JsonFormatComponentFactory[TuningReference] {

  override val familyName: String = "tuningReference"

  val StandardTypeName: String = "standard"
  val ConcertPitchTypeName: String = "concertPitch"

  override val defaultTypeName: Option[String] = Some(StandardTypeName)

  private implicit val intervalFormat: Format[Interval] = JsonIntervalFormat.formatFor(intonationStandard)

  //@formatter:off
  private val standardTypeFormat: Format[StandardTuningReference] = (
    (__ \ "basePitchClass").format[PitchClass](PitchClassFormat) and
    (__ \ "baseDeviation").formatWithDefault[Double](0.0)(
      Format(min(-50.0) keepAnd max(50.0), Writes.DoubleWrites))
  )(StandardTuningReference.apply, unlift(StandardTuningReference.unapply))
  private val concertPitchTypeFormat: Format[ConcertPitchTuningReference] = (
    (__ \ "concertPitchToBaseInterval").format[Interval] and
    (__ \ "baseMidiNote").format[MidiNote](MidiNoteFormat) and
    (__ \ "concertPitchFrequency").formatWithDefault[Double](DefaultConcertPitchFreq)(
      Format(exclusiveMin(0.0) keepAnd max(20000.0), Writes.DoubleWrites))
  )(ConcertPitchTuningReference.apply, unlift(ConcertPitchTuningReference.unapply))
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

object TuningReferenceFormatComponent {
  private[format] val ConcertPitchFreqRangeError = "error.concertPitchFrequency.range"
}

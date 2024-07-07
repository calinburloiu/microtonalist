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

import org.calinburloiu.music.intonation.{CentsInterval, CentsIntonationStandard, EdoInterval, EdoIntonationStandard, Interval, IntonationStandard, JustIntonationStandard, RatioInterval}
import play.api.libs.json.{Format, JsError, JsNumber, JsString, JsSuccess, Json, JsonValidationError, Reads, Writes}

/**
 * Object that contains format utilities for reading intervals in JSON format.
 *
 * Note that how an interval is read in JSON format depends on the [[IntonationStandard]].
 */
private[format] object JsonIntervalFormat {
  val ErrorExpectingIntervalFor: Map[String, String] = Map(
    CentsIntonationStandard.typeName -> "error.expecting.intervalForCentsIntonationStandard",
    JustIntonationStandard.typeName -> "error.expecting.intervalForJustIntonationStandard",
    EdoIntonationStandard.typeName -> "error.expecting.intervalForEdoIntonationStandard"
  )

  def readsFor(intonationStandard: IntonationStandard): Reads[Interval] = {
    lazy val centsReads: Reads[Interval] = Reads.JsNumberReads.map { jsNumber =>
      CentsInterval(jsNumber.value.doubleValue)
    }
    lazy val ratioReads: Reads[Interval] = Reads.StringReads
      .collect(JsonValidationError(ErrorExpectingIntervalFor(JustIntonationStandard.typeName)))(
        Function.unlift(Interval.fromRatioString)
      )

    intonationStandard match {
      case CentsIntonationStandard => centsReads orElse ratioReads orElse
        Reads.failed(ErrorExpectingIntervalFor(CentsIntonationStandard.typeName))
      case JustIntonationStandard => ratioReads orElse
        Reads.failed(ErrorExpectingIntervalFor(JustIntonationStandard.typeName))
      case EdoIntonationStandard(countPerOctave) => Reads.JsNumberReads.map { jsNumber =>
        EdoInterval(countPerOctave, jsNumber.value.intValue).asInstanceOf[Interval]
      } orElse Reads.JsArrayReads.flatMapResult { jsArr =>
        if (jsArr.value.size == 2) {
          val semitones = jsArr.value.head.asOpt[Int]
          val deviation = jsArr.value(1).asOpt[Int]
          if (semitones.isDefined && deviation.isDefined) {
            JsSuccess(EdoInterval(countPerOctave, (semitones.get, deviation.get)).asInstanceOf[Interval])
          } else {
            JsError()
          }
        } else {
          JsError()
        }
      } orElse ratioReads orElse Reads.failed(ErrorExpectingIntervalFor(EdoIntonationStandard.typeName))
    }
  }

  val writes: Writes[Interval] = Writes {
    case CentsInterval(centsValue) => JsNumber(centsValue)
    case RatioInterval(numerator, denominator) => JsString(s"$numerator/$denominator")
    case edoInterval: EdoInterval if edoInterval.edo % 12 == 0 =>
      val (semitones, deviation) = edoInterval.countRelativeToStandard
      Json.arr(JsNumber(semitones), JsNumber(deviation))
    case EdoInterval(_, count) => JsNumber(count)
    case interval: Interval => JsNumber(interval.cents)
  }

  def formatFor(intonationStandard: IntonationStandard): Format[Interval] = Format(
    readsFor(intonationStandard),
    writes
  )

  // TODO #4 Remove
  @deprecated
  private val legacyIntervalReads: Reads[Interval] = Reads.StringReads.collect(
    JsonValidationError("error.expecting.HuygensFokkerScalaScalePitch")
  )(
    Function.unlift(Interval.fromScalaTuningInterval)
  ).orElse {
    Reads.JsNumberReads.map { jsNumber =>
      CentsInterval(jsNumber.value.doubleValue)
    }
  }

  // TODO #4 Remove
  @deprecated
  private[format] implicit val legacyIntervalFormat: Format[Interval] = Format(legacyIntervalReads, writes)
}

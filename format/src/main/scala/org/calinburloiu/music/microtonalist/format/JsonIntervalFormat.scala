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

import org.calinburloiu.music.intonation.*
import play.api.libs.json.*

/**
 * Object that contains format utilities for reading intervals in JSON format.
 *
 * Note that how an interval is read in JSON format depends on the [[IntonationStandard]]. For a given intonation
 * standard, intervals specific to other intonation standards are allowed if those are convertible to the former and
 * if there are no ambiguities (e.g. for [[EdoIntonationStandard]] JSON numbers are values in divisions and cannot
 * be interpreted as cents because for them the same JSON number type is used).
 */
object JsonIntervalFormat {
  private[format] val ErrorExpectedIntervalFor: Map[String, String] = Map(
    CentsIntonationStandard.typeName -> "error.expected.intervalForCentsIntonationStandard",
    JustIntonationStandard.typeName -> "error.expected.intervalForJustIntonationStandard",
    EdoIntonationStandard.typeName -> "error.expected.intervalForEdoIntonationStandard"
  )

  private lazy val centsReads: Reads[Interval] = Reads.JsNumberReads.map { jsNumber =>
    CentsInterval(jsNumber.value.doubleValue)
  }
  private lazy val ratioReads: Reads[Interval] = Reads.StringReads
    .collect(JsonValidationError(ErrorExpectedIntervalFor(JustIntonationStandard.typeName)))(
      Function.unlift(Interval.fromRatioString)
    )

  private def edoReadsFor(countPerOctave: Int) = {
    Reads.JsNumberReads.map { jsNumber =>
      EdoInterval(countPerOctave, jsNumber.value.intValue).asInstanceOf[Interval]
    } orElse Reads.JsArrayReads.flatMapResult { jsArr =>
      if (jsArr.value.size == 2) {
        val semitones = jsArr.value.head.asOpt[Int]
        val offset = jsArr.value(1).asOpt[Int]
        if (semitones.isDefined && offset.isDefined) {
          JsSuccess(EdoInterval(countPerOctave, (semitones.get, offset.get)).asInstanceOf[Interval])
        } else {
          // We don't care about the message of the error here because the fallback below will override the error
          // anyway.
          JsError()
        }
      } else {
        // See the comment above for the other JsError.
        JsError()
      }
    }
  }

  def readsFor(intonationStandard: IntonationStandard): Reads[Interval] = intonationStandard match {
    case CentsIntonationStandard =>
      (centsReads orElse ratioReads orElse Reads.failed(ErrorExpectedIntervalFor(CentsIntonationStandard.typeName)))
        .map(_.toCentsInterval)
    case JustIntonationStandard =>
      ratioReads orElse Reads.failed(ErrorExpectedIntervalFor(JustIntonationStandard.typeName))
    case EdoIntonationStandard(countPerOctave) =>
      val edoReads = edoReadsFor(countPerOctave)
      (edoReads orElse ratioReads orElse Reads.failed(ErrorExpectedIntervalFor(EdoIntonationStandard.typeName)))
        .map(_.toEdoInterval(countPerOctave))
  }

  val writes: Writes[Interval] = Writes {
    case CentsInterval(centsValue) => JsNumber(centsValue)
    case RatioInterval(numerator, denominator) => JsString(s"$numerator/$denominator")
    case edoInterval: EdoInterval if edoInterval.edo % 12 == 0 =>
      val (semitones, offset) = edoInterval.countRelativeToStandard
      Json.arr(JsNumber(semitones), JsNumber(offset))
    case EdoInterval(_, count) => JsNumber(count)
    case interval: Interval => JsNumber(interval.cents)
  }

  def formatFor(intonationStandard: IntonationStandard): Format[Interval] = Format(
    readsFor(intonationStandard),
    writes
  )
}

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

import org.calinburloiu.music.intonation.{CentsIntonationStandard, RatioInterval}
import org.calinburloiu.music.microtonalist.composition.{ConcertPitchTuningReference, StandardTuningReference, TuningReference}
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import play.api.libs.json.*

class JsonTuningReferencePluginFormatTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils.*

  private val jsonPluginFormat = JsonTuningReferencePluginFormat(CentsIntonationStandard)
  private val reads: Reads[TuningReference] = jsonPluginFormat.reads

  behavior of "StandardTuningReference JSON plugin format"

  private val standardTypeJson = Json.obj(
    "basePitchClass" -> "Bb",
    "baseOffset" -> -5.0
  )
  private val standardType = StandardTuningReference(PitchClass.BFlat, -5)

  private val standardTypeFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),
    (__ \ "basePitchClass", AllowedTypes(JsonNumberType, JsonStringType), "error.pitchClass.invalid"),
    (__ \ "basePitchClass", DisallowedValues(JsString("bla"), JsString("H#")), "error.pitchClass.invalid"),
    (__ \ "basePitchClass", DisallowedValues(JsNumber(12)), "error.max"),
    (__ \ "basePitchClass", DisallowedValues(JsNumber(-1)), "error.min"),

    (__ \ "baseOffset", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.jsnumber"),
    (__ \ "baseOffset", DisallowedValues(JsNumber(-51)), "error.min"),
    (__ \ "baseOffset", DisallowedValues(JsNumber(51)), "error.max"),
  )

  it should "deserialize a StandardTuningReference" in {
    assertReads(reads, standardTypeJson, standardType)
  }

  it should "fail to deserialize a StandardTuningReference without mandatory settings" in {
    assertReadsFailure(reads, JsString("standard"), __ \ "basePitchClass", "error.path.missing")
  }

  it should "fail to deserialize a StandardTuningReference from invalid JSON" in {
    assertReadsFailureTable(reads, standardTypeJson, standardTypeFailureTable)
  }

  it should "serialize a StandardTuningReference" in {
    jsonPluginFormat.writes.writes(standardType) shouldEqual Json.obj(
      "type" -> "standard",
      "basePitchClass" -> 10,
      "baseOffset" -> -5.0
    )
  }

  behavior of "ConcertPitchTuningReference JSON plugin format"

  private val concertPitchTypeJson = Json.obj(
    "type" -> "concertPitch",
    "concertPitchToBaseInterval" -> "2/3",
    "baseMidiNote" -> 60,
    "concertPitchFrequency" -> 440.0
  )
  private val concertPitchType = ConcertPitchTuningReference(RatioInterval(2, 3), MidiNote(60), 440.0)

  private val concertPitchTypeFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),
    (__ \ "concertPitchToBaseInterval", AllowedTypes(JsonNumberType, JsonStringType),
      "error.expected.intervalForCentsIntonationStandard"),
    (__ \ "concertPitchToBaseInterval", DisallowedValues(JsString("bla")),
      "error.expected.intervalForCentsIntonationStandard"),

    (__ \ "baseMidiNote", AllowedTypes(JsonNumberType), "error.expected.jsnumber"),
    (__ \ "baseMidiNote", DisallowedValues(JsNumber(-1)), "error.min"),
    (__ \ "baseMidiNote", DisallowedValues(JsNumber(128)), "error.max"),

    (__ \ "concertPitchFrequency", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.jsnumber"),
    (__ \ "concertPitchFrequency", DisallowedValues(JsNumber(-10.0), JsNumber(0.0)), "error.exclusiveMin"),
    (__ \ "concertPitchFrequency", DisallowedValues(JsNumber(20000.1)), "error.max")
  )

  it should "deserialize a ConcertPitchTuningReference" in {
    val concertPitchTypeInCents = concertPitchType.copy(
      concertPitchToBaseInterval = concertPitchType.concertPitchToBaseInterval.toCentsInterval)
    assertReads(reads, concertPitchTypeJson, concertPitchTypeInCents)
  }

  it should "fail to deserialize a ConcertPitchTuningReference without mandatory settings" in {
    assertReadsFailure(reads, JsString("concertPitch"), __ \ "concertPitchToBaseInterval", "error.path.missing")
  }

  it should "fail to deserialize a ConcertPitchTuningReference from invalid JSON" in {
    assertReadsFailureTable(reads, concertPitchTypeJson, concertPitchTypeFailureTable)
  }

  it should "serialize a ConcertPitchTuningReference" in {
    jsonPluginFormat.writes.writes(concertPitchType) shouldEqual concertPitchTypeJson
  }
}

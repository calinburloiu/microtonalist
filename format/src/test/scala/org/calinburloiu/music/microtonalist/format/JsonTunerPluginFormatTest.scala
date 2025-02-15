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

import org.calinburloiu.music.microtonalist.tuner._
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import play.api.libs.json._

class JsonTunerPluginFormatTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils._

  private val jsonPluginFormat = JsonTunerPluginFormat
  private val reads: Reads[Tuner] = jsonPluginFormat.reads

  behavior of "MonophonicPitchBendTuner JSON plugin format"

  private val pitchBendSensitivity = PitchBendSensitivity(semitones = 3, cents = 34)
  private val monophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel = 3, pitchBendSensitivity)
  private val monophonicPitchBendTunerJson = Json.obj(
    "type" -> "monophonicPitchBend",
    "outputChannel" -> 3,
    "pitchBendSensitivity" -> Json.obj(
      "semitoneCount" -> 3,
      "centCount" -> 34
    )
  )

  private val monophonicPitchBendTunerFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),

    (__ \ "outputChannel", AllowedTypes(JsonNumberType), "error.expected.jsnumber"),
    (__ \ "outputChannel", DisallowedValues(JsNumber(-1)), "error.min"),
    (__ \ "outputChannel", DisallowedValues(JsNumber(16)), "error.max"),

    (__ \ "pitchBendSensitivity" \ "semitoneCount", AllowedTypes(JsonNumberType), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "semitoneCount", DisallowedValues(JsNumber(-1)), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "semitoneCount", DisallowedValues(JsNumber(128)), "error.expected.uint7"),

    (__ \ "pitchBendSensitivity" \ "centCount", AllowedTypes(JsonNumberType), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "centCount", DisallowedValues(JsNumber(-1)), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "centCount", DisallowedValues(JsNumber(128)), "error.expected.uint7")
  )

  it should "deserialize with default value" in {
    val defaultMonophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel = 0)
    assertReads(reads, JsString("monophonicPitchBend"), defaultMonophonicPitchBendTuner)
    assertReads(reads, Json.obj("type" -> "monophonicPitchBend"), defaultMonophonicPitchBendTuner)
  }

  it should "deserialize" in {
    assertReads(reads, monophonicPitchBendTunerJson, monophonicPitchBendTuner)
  }

  it should "fail to deserialize from invalid JSON" in {
    assertReadsFailureTable(reads, monophonicPitchBendTunerJson, monophonicPitchBendTunerFailureTable)
  }

  it should "serialize" in {
    jsonPluginFormat.writes.writes(monophonicPitchBendTuner) shouldEqual monophonicPitchBendTunerJson
  }

  private val altTuningOutput = MidiDeviceId(name = "FP-90", vendor = "Roland")
  private val mtsTunersTestTable = Seq(
    ("mtsOctave1ByteNonRealTime", MtsOctave1ByteNonRealTimeTuner(thru = true, Some(altTuningOutput)),
      MtsOctave1ByteNonRealTimeTuner()),
    ("mtsOctave2ByteNonRealTime", MtsOctave2ByteNonRealTimeTuner(thru = true, Some(altTuningOutput)),
      MtsOctave2ByteNonRealTimeTuner()),
    ("mtsOctave1ByteRealTime", MtsOctave1ByteRealTimeTuner(thru = true, Some(altTuningOutput)),
      MtsOctave1ByteRealTimeTuner()),
    ("mtsOctave2ByteRealTime", MtsOctave2ByteRealTimeTuner(thru = true, Some(altTuningOutput)),
      MtsOctave2ByteRealTimeTuner())
  )
  private val mtsTunerCommonJson = Json.obj(
    "thru" -> true,
    "altTuningOutput" -> Json.obj(
      "name" -> "FP-90",
      "vendor" -> "Roland"
    )
  )

  private val mtsTunerFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),

    (__ \ "thru", AllowedTypes(JsonBooleanType), "error.expected.jsboolean"),

    (__ \ "altTuningOutput" \ "name", AllowedTypes(JsonStringType), "error.expected.jsstring"),
    (__ \ "altTuningOutput" \ "vendor", AllowedTypes(JsonStringType), "error.expected.jsstring")
  )

  for ((typeName, tuner, defaultTuner) <- mtsTunersTestTable) {
    val tunerClassName = tuner.getClass.getSimpleName
    val mtsTunerJson = Json.obj("type" -> typeName) ++ mtsTunerCommonJson

    behavior of s"$tunerClassName JSON plugin format"

    it should s"deserialize with default value" in {
      assertReads(reads, JsString(typeName), defaultTuner)
      assertReads(reads, Json.obj("type" -> typeName), defaultTuner)
    }

    it should s"deserialize" in {
      assertReads(reads, mtsTunerJson, tuner)
    }

    it should s"fail to deserialize from invalid JSON" in {
      assertReadsFailureTable(reads, mtsTunerJson, mtsTunerFailureTable)
    }

    it should s"serialize" in {
      jsonPluginFormat.writes.writes(tuner) shouldEqual mtsTunerJson
    }
  }
}

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

import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import play.api.libs.json.*

class JsonTunerPluginFormatTest extends JsonFormatTestUtils {

  import JsonFormatTestUtils.*

  private val jsonPluginFormat = JsonTunerPluginFormat
  private val reads: Reads[Tuner] = jsonPluginFormat.reads

  behavior of "MonophonicPitchBendTuner JSON plugin format"

  private val pitchBendSensitivity = PitchBendSensitivity(semitones = 3, cents = 34)
  private val monophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel = 3, pitchBendSensitivity)
  private val monophonicPitchBendTunerJson = Json.obj(
    "type" -> "monophonicPitchBend",
    "outputChannel" -> 4,
    "pitchBendSensitivity" -> Json.obj(
      "semitoneCount" -> 3,
      "centCount" -> 34
    )
  )

  private val monophonicPitchBendTunerFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("path", "check", "expected JsonValidationError"),

    (__ \ "outputChannel", AllowedTypes(JsonNumberType), "error.expected.jsnumber"),
    (__ \ "outputChannel", DisallowedValues(JsNumber(0)), "error.min"),
    (__ \ "outputChannel", DisallowedValues(JsNumber(17)), "error.max"),

    (__ \ "pitchBendSensitivity" \ "semitoneCount", AllowedTypes(JsonNumberType), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "semitoneCount", DisallowedValues(JsNumber(-1)), "error.expected.uint7"),
    (__ \ "pitchBendSensitivity" \ "semitoneCount", DisallowedValues(JsNumber(128)), "error.expected.uint7"),

    (__ \ "pitchBendSensitivity" \ "centCount", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.uint7"),
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

  // --- MpeTuner JSON plugin format ---

  behavior of "MpeTuner JSON plugin format"

  private val mpeTunerFullJson = Json.obj(
    "type" -> "mpe",
    "inputMode" -> "nonMpe",
    "zones" -> Json.obj(
      "lower" -> Json.obj(
        "memberCount" -> 7,
        "masterPitchBendSensitivity" -> Json.obj(
          "semitoneCount" -> 2,
          "centCount" -> 0
        ),
        "memberPitchBendSensitivity" -> Json.obj(
          "semitoneCount" -> 48,
          "centCount" -> 0
        )
      ),
      "upper" -> Json.obj(
        "memberCount" -> 7,
        "masterPitchBendSensitivity" -> Json.obj(
          "semitoneCount" -> 1,
          "centCount" -> 0
        ),
        "memberPitchBendSensitivity" -> Json.obj(
          "semitoneCount" -> 12,
          "centCount" -> 0
        )
      )
    )
  )

  private def assertMpeTuner(tuner: Tuner, expectedInputMode: MpeInputMode,
                             expectedLowerMemberCount: Int, expectedUpperMemberCount: Int,
                             expectedLowerMasterPbs: PitchBendSensitivity = PitchBendSensitivity(2),
                             expectedLowerMemberPbs: PitchBendSensitivity = PitchBendSensitivity(48),
                             expectedUpperMasterPbs: PitchBendSensitivity = PitchBendSensitivity(2),
                             expectedUpperMemberPbs: PitchBendSensitivity = PitchBendSensitivity(48)): Unit = {
    tuner shouldBe a[MpeTuner]
    val mpe = tuner.asInstanceOf[MpeTuner]
    mpe.inputMode shouldBe expectedInputMode
    val (lower, upper) = mpe.zones
    lower.memberCount shouldBe expectedLowerMemberCount
    upper.memberCount shouldBe expectedUpperMemberCount
    lower.masterPitchBendSensitivity shouldBe expectedLowerMasterPbs
    lower.memberPitchBendSensitivity shouldBe expectedLowerMemberPbs
    upper.masterPitchBendSensitivity shouldBe expectedUpperMasterPbs
    upper.memberPitchBendSensitivity shouldBe expectedUpperMemberPbs
  }

  it should "deserialize with all fields specified" in {
    matchReads(reads, mpeTunerFullJson, { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 7, 7,
        expectedUpperMasterPbs = PitchBendSensitivity(1),
        expectedUpperMemberPbs = PitchBendSensitivity(12))
    })
  }

  it should "deserialize with default values (minimal JSON)" in {
    matchReads(reads, Json.obj("type" -> "mpe"), { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 15, 0)
    })
    matchReads(reads, JsString("mpe"), { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 15, 0)
    })
  }

  it should "deserialize with only lower zone specified" in {
    val json = Json.obj(
      "type" -> "mpe",
      "zones" -> Json.obj(
        "lower" -> Json.obj("memberCount" -> 10)
      )
    )
    matchReads(reads, json, { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 10, 0)
    })
  }

  it should "deserialize with only upper zone specified and lower defaults to 15" in {
    // When only upper is specified with memberCount that doesn't overlap with default lower (15),
    // we need upper memberCount=0 or the zones must not overlap.
    // With lower=15 (channels 1..15) and upper=5 (channels 10..14), they overlap -> error.
    // So test with upper disabled (memberCount=0) or a non-overlapping config.
    val json = Json.obj(
      "type" -> "mpe",
      "zones" -> Json.obj(
        "upper" -> Json.obj("memberCount" -> 0)
      )
    )
    matchReads(reads, json, { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 15, 0)
    })
  }

  it should "deserialize with zones but omitting pitch bend sensitivities" in {
    val json = Json.obj(
      "type" -> "mpe",
      "zones" -> Json.obj(
        "lower" -> Json.obj("memberCount" -> 7),
        "upper" -> Json.obj("memberCount" -> 7)
      )
    )
    matchReads(reads, json, { tuner =>
      assertMpeTuner(tuner, MpeInputMode.NonMpe, 7, 7)
    })
  }

  it should "serialize" in {
    val tuner = new MpeTuner(
      zones = (
        MpeZone(MpeZoneType.Lower, 7),
        MpeZone(MpeZoneType.Upper, 7, PitchBendSensitivity(1), PitchBendSensitivity(12))
      ),
      inputMode = MpeInputMode.NonMpe
    )
    jsonPluginFormat.writes.writes(tuner) shouldEqual mpeTunerFullJson
  }

  it should "round-trip serialize then deserialize" in {
    val original = new MpeTuner(
      zones = (MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 5, PitchBendSensitivity(3))),
      inputMode = MpeInputMode.Mpe
    )
    val json = jsonPluginFormat.writes.writes(original)
    matchReads(reads, json, { tuner =>
      assertMpeTuner(tuner, MpeInputMode.Mpe, 7, 5,
        expectedUpperMasterPbs = PitchBendSensitivity(3))
    })
  }

  it should "fail to deserialize with invalid inputMode" in {
    val json = Json.obj("type" -> "mpe", "inputMode" -> "invalid")
    reads.reads(json) shouldBe a[JsError]
  }

  it should "fail to deserialize with memberCount out of range" in {
    val json16 = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> 16)))
    reads.reads(json16) shouldBe a[JsError]

    val jsonNeg = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> -1)))
    reads.reads(jsonNeg) shouldBe a[JsError]
  }

  it should "fail to deserialize with memberCount of wrong type" in {
    val json = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> "seven")))
    reads.reads(json) shouldBe a[JsError]
  }

  it should "fail to deserialize with PBS semitoneCount out of uint7 range" in {
    val json = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> 7,
        "masterPitchBendSensitivity" -> Json.obj("semitoneCount" -> 128))))
    reads.reads(json) shouldBe a[JsError]
  }

  it should "fail to deserialize with PBS centCount out of uint7 range" in {
    val json = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> 7,
        "memberPitchBendSensitivity" -> Json.obj("semitoneCount" -> 48, "centCount" -> 128))))
    reads.reads(json) shouldBe a[JsError]
  }

  it should "fail to deserialize with overlapping zone channel ranges" in {
    val json = Json.obj("type" -> "mpe", "zones" -> Json.obj(
      "lower" -> Json.obj("memberCount" -> 10),
      "upper" -> Json.obj("memberCount" -> 10)))
    reads.reads(json) shouldBe a[JsError]
  }

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

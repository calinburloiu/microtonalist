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

import org.calinburloiu.music.microtonalist.format.JsonFormatTestUtils.{AllowedTypes, JsonFailureCheck, JsonStringType}
import org.calinburloiu.music.microtonalist.tuner._
import org.calinburloiu.music.scmidi.MidiDeviceId
import play.api.libs.json._

object JsonTrackIOPluginFormatTest {

  import JsonFormatTestUtils._

  val midiDeviceIdFailureTableRows: Seq[(JsPath, JsonFailureCheck, String)] = Seq(
    (__ \ "midiDeviceId" \ "name", AllowedTypes(JsonStringType), "error.expected.jsstring"),
    (__ \ "midiDeviceId" \ "vendor", AllowedTypes(JsonStringType), "error.expected.jsstring")
  )
  val channelFailureTableRows: Seq[(JsPath, JsonFailureCheck, String)] = Seq(
    (__ \ "channel", AllowedTypes(JsonNumberType, JsonNullType), "error.expected.jsnumber"),
    (__ \ "channel", DisallowedValues(JsNumber(0)), "error.min"),
    (__ \ "channel", DisallowedValues(JsNumber(17)), "error.max"),
  )
}

class JsonTrackInputSpecPluginFormatTest extends JsonFormatTestUtils {

  import JsonTrackIOPluginFormatTest._

  private val jsonPluginFormat = JsonTrackInputSpecPluginFormat
  private val reads: Reads[TrackInputSpec] = jsonPluginFormat.reads

  behavior of "DeviceTrackInput JSON plugin format"

  private val deviceTrackInput = DeviceTrackInputSpec(MidiDeviceId("Fp-90", "Roland"), Some(9))
  private val deviceTrackInputJson = Json.obj(
    "type" -> "device",
    "midiDeviceId" -> Json.obj("name" -> "Fp-90", "vendor" -> "Roland"),
    "channel" -> 10
  )

  private val deviceFailureTableRows = midiDeviceIdFailureTableRows ++ channelFailureTableRows
  private val deviceFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("jsonPath", "failureCheck", "errorKey"),
    deviceFailureTableRows: _*
  )

  it should "deserialize" in {
    assertReads(reads, deviceTrackInputJson, deviceTrackInput)
  }

  it should "fail to deserialize from invalid JSON" in {
    assertReadsFailureTable(reads, deviceTrackInputJson, deviceFailureTable)
  }

  it should "serialize" in {
    jsonPluginFormat.writes.writes(deviceTrackInput) shouldEqual deviceTrackInputJson
  }

  behavior of "FromTrackInput JSON plugin format"

  private val fromTrackInput = FromTrackInputSpec("Piano", Some(0))
  private val fromTrackInputJson = Json.obj(
    "type" -> "track",
    "trackId" -> "Piano",
    "channel" -> 1
  )

  private val fromTrackInputFailureTableRows = Seq(
    (__ \ "trackId", AllowedTypes(JsonStringType), "error.expected.jsstring")
  ) ++ channelFailureTableRows
  private val fromTrackInputFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("jsonPath", "failureCheck", "errorKey"),
    fromTrackInputFailureTableRows: _*
  )

  it should "deserialize" in {
    assertReads(reads, fromTrackInputJson, fromTrackInput)
  }

  it should "fail to deserialize from invalid JSON" in {
    assertReadsFailureTable(reads, fromTrackInputJson, fromTrackInputFailureTable)
  }

  it should "serialize" in {
    jsonPluginFormat.writes.writes(fromTrackInput) shouldEqual fromTrackInputJson
  }
}

class JsonTrackOutputSpecPluginFormatTest extends JsonFormatTestUtils {

  import JsonTrackIOPluginFormatTest._

  private val jsonPluginFormat = JsonTrackOutputSpecPluginFormat
  private val reads: Reads[TrackOutputSpec] = jsonPluginFormat.reads

  behavior of "DeviceTrackOutput JSON plugin format"

  private val deviceTrackOutput = DeviceTrackOutputSpec(MidiDeviceId("Fp-90", "Roland"), Some(9))
  private val deviceTrackOutputJson = Json.obj(
    "type" -> "device",
    "midiDeviceId" -> Json.obj("name" -> "Fp-90", "vendor" -> "Roland"),
    "channel" -> 10
  )

  private val deviceFailureTableRows = midiDeviceIdFailureTableRows ++ channelFailureTableRows
  private val deviceFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("jsonPath", "failureCheck", "errorKey"),
    deviceFailureTableRows: _*
  )

  it should "deserialize" in {
    assertReads(reads, deviceTrackOutputJson, deviceTrackOutput)
  }

  it should "fail to deserialize from invalid JSON" in {
    assertReadsFailureTable(reads, deviceTrackOutputJson, deviceFailureTable)
  }

  it should "serialize" in {
    jsonPluginFormat.writes.writes(deviceTrackOutput) shouldEqual deviceTrackOutputJson
  }

  behavior of "FromTrackOutput JSON plugin format"

  private val toTrackOutput = ToTrackOutputSpec("Piano", Some(0))
  private val toTrackOutputJson = Json.obj(
    "type" -> "track",
    "trackId" -> "Piano",
    "channel" -> 1
  )

  private val toTrackOutputFailureTableRows = Seq(
    (__ \ "trackId", AllowedTypes(JsonStringType), "error.expected.jsstring")
  ) ++ channelFailureTableRows
  private val toTrackOutputFailureTable = Table[JsPath, JsonFailureCheck, String](
    ("jsonPath", "failureCheck", "errorKey"),
    toTrackOutputFailureTableRows: _*
  )

  it should "deserialize" in {
    assertReads(reads, toTrackOutputJson, toTrackOutput)
  }

  it should "fail to deserialize from invalid JSON" in {
    assertReadsFailureTable(reads, toTrackOutputJson, toTrackOutputFailureTable)
  }

  it should "serialize" in {
    jsonPluginFormat.writes.writes(toTrackOutput) shouldEqual toTrackOutputJson
  }
}

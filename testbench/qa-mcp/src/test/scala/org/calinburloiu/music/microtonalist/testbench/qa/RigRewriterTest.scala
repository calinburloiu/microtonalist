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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.calinburloiu.music.microtonalist.testbench.qa.RigRewriter.rewrite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, JsValue, Json}

class RigRewriterTest extends AnyWordSpec with Matchers {

  private val runId: String = "qa-3f9c"

  /** The design's fixture rig, which the `gui-qa` skill's agent copies. */
  private val fixtureRig: JsValue = Json.parse(
    """{
      |  "tracks": [
      |    {
      |      "id": "qa",
      |      "name": "QA",
      |      "input": { "midiDeviceId": { "name": "qa:keys" } },
      |      "tuningChangers": ["pedal"],
      |      "tuner": "mtsOctave1ByteNonRealTime",
      |      "output": { "midiDeviceId": { "name": "qa:synth" } }
      |    }
      |  ]
      |}""".stripMargin)

  private def deviceId(name: String): JsObject = Json.obj("name" -> name)

  private def runPort(device: String): JsObject =
    Json.obj("name" -> s"CoreMIDI4J - $runId-$device", "vendor" -> "<Unknown Manufacturer>")

  /** A rig of one track whose input is `device` and whose output is the QA device `synth`. */
  private def rigWith(device: JsValue): JsValue = Json.obj("tracks" -> Json.arr(Json.obj(
    "input" -> Json.obj("midiDeviceId" -> device),
    "output" -> Json.obj("midiDeviceId" -> deviceId("qa:synth"))
  )))

  "RigRewriter.rewrite" should {
    "turn the QA devices into the run's ports" in {
      // When
      val Right(rewritten) = rewrite(fixtureRig, runId): @unchecked

      // Then
      rewritten.devices shouldBe Seq(QaDevice("keys", input = true, output = false),
        QaDevice("synth", input = false, output = true))
      (rewritten.json \ "tracks" \ 0 \ "input" \ "midiDeviceId").get shouldBe runPort("keys")
      (rewritten.json \ "tracks" \ 0 \ "output" \ "midiDeviceId").get shouldBe runPort("synth")
    }

    "keep the rest of the rig unchanged" in {
      // Given
      val rig = Json.obj(
        "$schema" -> "https://example.com/tracks.schema.json",
        "tracks" -> Json.arr(Json.obj(
            "id" -> "a",
            "input" -> Json.obj("midiDeviceId" -> deviceId("qa:keys"), "channel" -> 3),
            "tuner" -> "nope"
          ), 42, Json.arr("x", true))
      )

      // When
      val result = rewrite(rig, runId)

      // Then
      val expected = Json.obj(
        "$schema" -> "https://example.com/tracks.schema.json",
        "tracks" -> Json.arr(Json.obj(
            "id" -> "a",
            "input" -> Json.obj("midiDeviceId" -> runPort("keys"), "channel" -> 3),
            "tuner" -> "nope"
          ), 42, Json.arr("x", true))
      )
      result shouldBe Right(RewrittenRig(expected, Seq(QaDevice("keys", input = true, output = false))))
    }

    "count a device used as an input and as an output once" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(
        Json.obj("id" -> "a", "input" -> Json.obj("midiDeviceId" -> deviceId("qa:dev"))),
        Json.obj("id" -> "b", "output" -> Json.obj("midiDeviceId" -> deviceId("qa:dev")))
      ))

      // When
      val Right(rewritten) = rewrite(rig, runId): @unchecked

      // Then
      rewritten.devices shouldBe Seq(QaDevice("dev", input = true, output = true))
    }

    "turn a tuner's QA altTuningOutput into the run's port, as an output" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(Json.obj(
        "input" -> Json.obj("midiDeviceId" -> deviceId("qa:keys")),
        "tuner" -> Json.obj("type" -> "mtsOctave1ByteNonRealTime", "altTuningOutput" -> deviceId("qa:tuning"))
      )))

      // When
      val Right(rewritten) = rewrite(rig, runId): @unchecked

      // Then
      rewritten.devices shouldBe Seq(QaDevice("keys", input = true, output = false),
        QaDevice("tuning", input = false, output = true))
      (rewritten.json \ "tracks" \ 0 \ "tuner" \ "altTuningOutput").get shouldBe runPort("tuning")
      (rewritten.json \ "tracks" \ 0 \ "tuner" \ "type").as[String] shouldBe "mtsOctave1ByteNonRealTime"
    }

    "accept the names it wrote, on a second start of the run" in {
      // Given
      val Right(rewritten) = rewrite(fixtureRig, runId): @unchecked

      // When / Then
      rewrite(rewritten.json, runId) shouldBe Right(rewritten)
    }

    "refuse a device that isn't a QA device" in {
      // Given
      val iac = Json.obj("name" -> "IAC Driver Bus 1", "vendor" -> "Apple")

      // When / Then
      rewrite(rigWith(iac), runId).isLeft shouldBe true
      rewrite(rigWith(Json.obj("vendor" -> "Apple")), runId).isLeft shouldBe true
      rewrite(rigWith(Json.toJson("qa:keys")), runId).isLeft shouldBe true
    }

    "refuse a tuner's altTuningOutput that isn't a QA device" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(Json.obj(
        "input" -> Json.obj("midiDeviceId" -> deviceId("qa:keys")),
        "tuner" -> Json.obj("type" -> "mtsOctave1ByteNonRealTime",
          "altTuningOutput" -> Json.obj("name" -> "FP-90", "vendor" -> "Roland"))
      )))

      // When
      val result = rewrite(rig, runId)

      // Then
      result.left.toOption.get should include("/tracks(0)/tuner/altTuningOutput")
    }

    "refuse a QA altTuningOutput outside a track's tuner" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(Json.obj(
        "input" -> Json.obj("altTuningOutput" -> deviceId("qa:keys"))
      )))

      // When
      val result = rewrite(rig, runId)

      // Then
      result.left.toOption.get should include("/tracks(0)/input/altTuningOutput")
    }

    "refuse another run's port" in {
      // When / Then
      rewrite(rigWith(deviceId("CoreMIDI4J - qa-0000-keys")), runId).isLeft shouldBe true
      rewrite(rigWith(deviceId(s"CoreMIDI4J - $runId-../x")), runId).isLeft shouldBe true
    }

    "refuse an invalid device name" in {
      // When / Then
      rewrite(rigWith(deviceId("qa:../x")), runId).isLeft shouldBe true
      rewrite(rigWith(deviceId("qa:")), runId).isLeft shouldBe true
      rewrite(rigWith(deviceId("qa:" + "k" * 33)), runId).isLeft shouldBe true
    }

    "refuse a QA device outside a track's input or output" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(Json.obj(
        "tuner" -> Json.obj("midiDeviceId" -> deviceId("qa:keys"))
      )))

      // When
      val result = rewrite(rig, runId)

      // Then
      result.left.toOption.get should include("/tracks(0)/tuner/midiDeviceId")
      rewrite(Json.obj("midiDeviceId" -> deviceId("qa:keys")), runId).isLeft shouldBe true
    }

    "refuse a $ref" in {
      // Given
      val rig = Json.obj("tracks" -> Json.arr(Json.obj(
        "input" -> Json.obj("$ref" -> "other.json")
      )))

      // When
      val result = rewrite(rig, runId)

      // Then
      result.left.toOption.get should include("/tracks(0)/input")
      rewrite(rigWith(Json.obj("name" -> "qa:keys", "$ref" -> "other.json")), runId).isLeft shouldBe true
    }
  }

  "QaDevice.port" should {
    "name the run's virtual port of the device" in {
      // When / Then
      QaDevice("keys", input = true, output = false).port(runId) shouldBe "qa-3f9c-keys"
    }
  }
}

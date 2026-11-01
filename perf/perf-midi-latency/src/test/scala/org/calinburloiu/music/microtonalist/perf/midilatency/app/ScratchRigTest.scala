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

package org.calinburloiu.music.microtonalist.perf.midilatency.app

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDevices
import org.calinburloiu.music.scmidi.MidiDeviceId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

import java.nio.file.Files

class ScratchRigTest extends AnyWordSpec with Matchers {

  private val devices = PerfDevices(
    input = MidiDeviceId("CoreMIDI4J - IAC 1", "Apple Inc."),
    output = MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc.")
  )

  private val template =
    """{"input": {"name": "{{input.name}}", "vendor": "{{input.vendor}}"},
      | "output": {"name": "{{output.name}}", "vendor": "{{output.vendor}}"}}""".stripMargin

  "ScratchRig" should {
    "replace the device variables" in {
      // When
      val rendered = Json.parse(ScratchRig.render(template, devices))

      // Then
      (rendered \ "input" \ "name").as[String] shouldEqual "CoreMIDI4J - IAC 1"
      (rendered \ "input" \ "vendor").as[String] shouldEqual "Apple Inc."
      (rendered \ "output" \ "name").as[String] shouldEqual "CoreMIDI4J - IAC 2"
      (rendered \ "output" \ "vendor").as[String] shouldEqual "Apple Inc."
    }

    "escape JSON-special characters in device names" in {
      // Given
      val name = """Bus "A" \ 1"""
      val quoted = devices.copy(input = MidiDeviceId(name, "Acme"))

      // When
      val rendered = Json.parse(ScratchRig.render(template, quoted))

      // Then
      (rendered \ "input" \ "name").as[String] shouldEqual name
    }

    "reject an unknown variable" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy ScratchRig.render("{{input.port}}", devices)

      // Then
      exception.getMessage should include("{{input.port}}")
    }

    "write the composition, its tracks file next to it and a minimal app config" in {
      // Given
      val dir = Files.createTempDirectory("scratch-rig")

      // When
      val rig = ScratchRig.prepare(dir, "two.mtlist", "{}", template, appConfig = None, devices)

      // Then
      rig.composition shouldEqual dir.resolve("two.mtlist")
      rig.tracks shouldEqual dir.resolve("two.mtlist.tracks")
      Files.readString(rig.composition) shouldEqual "{}"
      Files.readString(rig.tracks) should include("CoreMIDI4J - IAC 2")
      // The app needs a `core` section, whose settings all have defaults
      Files.readString(rig.appConfig) shouldEqual "core {}\n"
    }

    "write the given app config" in {
      // Given
      val dir = Files.createTempDirectory("scratch-rig")

      // When
      val rig = ScratchRig.prepare(dir, "two.mtlist", "{}", template, appConfig = Some("core {}"), devices)

      // Then
      Files.readString(rig.appConfig) shouldEqual "core {}"
    }
  }
}

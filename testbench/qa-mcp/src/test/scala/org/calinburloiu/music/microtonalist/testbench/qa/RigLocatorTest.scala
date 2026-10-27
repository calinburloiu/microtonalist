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

import org.calinburloiu.music.microtonalist.testbench.qa.RigLocator.locate
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class RigLocatorTest extends AnyWordSpec with Matchers {

  private trait Fixture {
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"))
    val composition: Path = run.dir.resolve("qa.mtlist")

    /** Writes the composition, with the given `tracksUrl` JSON value, or without one. */
    def writeComposition(tracksUrl: Option[String] = None): Path = {
      val field = tracksUrl.map(value => s""", "tracksUrl": $value""").getOrElse("")
      Files.writeString(composition, s"""{ "tunings": []$field }""")
    }

    def writeCompositionWithUrl(url: String): Path = writeComposition(Some(s"\"$url\""))
  }

  "RigLocator.locate" should {
    "default to the .tracks file next to the composition" in new Fixture {
      // Given
      writeComposition()

      // When / Then
      locate(run.dir.resolve("qa.mtlist"), run) shouldBe Right(run.dir.resolve("qa.mtlist.tracks"))
    }

    "default to the .tracks file when tracksUrl is null" in new Fixture {
      // Given
      writeComposition(Some("null"))

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("qa.mtlist.tracks"))
    }

    "follow a relative tracksUrl" in new Fixture {
      // Given
      writeCompositionWithUrl("rigs/a.tracks")

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("rigs").resolve("a.tracks"))
    }

    "follow a file: tracksUrl inside the run" in new Fixture {
      // Given
      writeCompositionWithUrl(s"file:${run.dir}/b.tracks")

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("b.tracks"))
    }

    "refuse a tracksUrl out of the run, or on another scheme" in new Fixture {
      // When / Then
      for (url <- Seq("../x.tracks", "https://x/y.tracks", "microtonalist:///y.tracks", "file://host/y.tracks")) {
        writeCompositionWithUrl(url)
        withClue(url) {
          locate(composition, run).isLeft shouldBe true
        }
      }
    }

    "refuse an invalid tracksUrl" in new Fixture {
      // When / Then
      for (tracksUrl <- Seq("\"a b|c\"", "3")) {
        writeComposition(Some(tracksUrl))
        withClue(tracksUrl) {
          locate(composition, run).isLeft shouldBe true
        }
      }
    }

    "refuse a default .tracks file out of the run" in new Fixture {
      // Given
      val outside: Path = Files.createTempDirectory("qa-rig-locator-test-outside").resolve("qa.mtlist")
      Files.writeString(outside, "{}")

      // When / Then
      locate(outside, run).isLeft shouldBe true
    }

    "refuse a composition that it can't read as JSON" in new Fixture {
      // When / Then
      locate(composition, run).isLeft shouldBe true

      // Given
      Files.writeString(composition, "{ not JSON")

      // When / Then
      locate(composition, run).isLeft shouldBe true
    }
  }
}

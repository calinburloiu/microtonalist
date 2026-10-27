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

    /** Writes the composition with a `baseUrl` and, if given, a `tracksUrl`, both JSON strings. */
    def writeCompositionWithBaseUrl(baseUrl: String, tracksUrl: Option[String]): Path = {
      val tracksUrlField = tracksUrl.map(url => s""", "tracksUrl": "$url"""").getOrElse("")
      Files.writeString(composition, s"""{ "baseUrl": "$baseUrl", "tunings": []$tracksUrlField }""")
    }
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

    "resolve tracksUrl against a relative baseUrl, as the app does" in new Fixture {
      // Given
      writeCompositionWithBaseUrl("sub/", Some("a.tracks"))

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("sub").resolve("a.tracks"))
    }

    "resolve tracksUrl against a file: baseUrl inside the run" in new Fixture {
      // Given
      writeCompositionWithBaseUrl(s"file:${run.dir}/sub/", Some("b.tracks"))

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("sub").resolve("b.tracks"))
    }

    "refuse a tracksUrl that a baseUrl takes out of the run" in new Fixture {
      // Given
      val outside: Path = Files.createTempDirectory("qa-rig-locator-test-outside")

      // When / Then
      for (baseUrl <- Seq(s"file:$outside/", "../")) {
        writeCompositionWithBaseUrl(baseUrl, Some("live.mtlist.tracks"))
        withClue(baseUrl) {
          locate(composition, run).isLeft shouldBe true
        }
      }
    }

    "keep the default .tracks file next to the composition, whatever the baseUrl" in new Fixture {
      // Given
      writeCompositionWithBaseUrl("sub/", None)

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("qa.mtlist.tracks"))
    }

    "resolve tracksUrl against the composition when baseUrl is null" in new Fixture {
      // Given
      Files.writeString(composition, """{ "baseUrl": null, "tracksUrl": "a.tracks" }""")

      // When / Then
      locate(composition, run) shouldBe Right(run.dir.resolve("a.tracks"))
    }

    "refuse an invalid baseUrl" in new Fixture {
      // When / Then
      for (
        json <- Seq("""{ "baseUrl": 3, "tracksUrl": "a.tracks" }""",
          """{ "baseUrl": "a b|c", "tracksUrl": "a.tracks" }""")
      ) {
        Files.writeString(composition, json)
        withClue(json) {
          locate(composition, run).isLeft shouldBe true
        }
      }
    }

    "refuse a composition with a $ref at its root" in new Fixture {
      // Given
      Files.writeString(composition, """{ "$ref": "/Users/me/x.json" }""")

      // When
      val result: Either[String, Path] = locate(composition, run)

      // Then
      result.left.toOption.get should include("$ref")
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

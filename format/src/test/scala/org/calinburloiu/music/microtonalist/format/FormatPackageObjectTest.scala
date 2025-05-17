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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsError, JsNumber, JsSuccess}

import java.net.URI
import java.nio.file.Paths

class FormatPackageObjectTest extends AnyFlatSpec with Matchers {

  "filePathOf" should "convert an absolute URI to a file system path" in {
    filePathOf(new URI("file:///Users/john/Music/phrygian.scl")) shouldEqual Paths
      .get("/", "Users", "john", "Music", "phrygian.scl")
  }

  it should "convert a relative URI to file system path" in {
    filePathOf(new URI("Music/phrygian.scl")) shouldEqual Paths
      .get("Music", "phrygian.scl")
  }

  it should "fail for a non file URI" in {
    assertThrows[IllegalArgumentException] {
      filePathOf(new URI("https://example.org/path/to/file.scl"))
    }
  }

  it should "resolve an override base URI against an initial base URI" in {
    def uri(str: String) = Some(new URI(str))

    resolveBaseUriWithOverride(
      uri("http://example.org/compositions/semai.mtlist"),
      uri("scales/")
    ) shouldEqual uri("http://example.org/compositions/scales/")

    resolveBaseUriWithOverride(
      uri("http://example.org/compositions/semai.mtlist"),
      uri("files:///Users/john/Scales/")
    ) shouldEqual uri("files:///Users/john/Scales/")

    resolveBaseUriWithOverride(
      uri("http://example.org/compositions/semai.mtlist"),
      None
    ) shouldEqual uri("http://example.org/compositions/semai.mtlist")

    resolveBaseUriWithOverride(None, uri("scales/")) shouldEqual uri("scales/")

    resolveBaseUriWithOverride(None, None) shouldEqual None
  }

  "uint7Format" should "read an unsigned integer of 7 bits (between 0 and 127)" in {
    uint7Format.reads(JsNumber(0)) shouldEqual JsSuccess(0)
    uint7Format.reads(JsNumber(19)) shouldEqual JsSuccess(19)
    uint7Format.reads(JsNumber(127)) shouldEqual JsSuccess(127)
    uint7Format.reads(JsNumber(128)) shouldEqual JsError("error.expected.uint7")
    uint7Format.reads(JsNumber(-1)) shouldEqual JsError("error.expected.uint7")
  }

  it should "write an integer" in {
    uint7Format.writes(0) shouldEqual JsNumber(0)
    uint7Format.writes(19) shouldEqual JsNumber(19)
    uint7Format.writes(127) shouldEqual JsNumber(127)
    // No validation on write
  }

  "resolveLibraryUrl" should "resolve an URI with microtonalist scheme" in {
    val uri = URI("microtonalist:///scales/dorian.scl")

    resolveLibraryUrl(uri, URI("file:///Users/grey/Music/Library/")) shouldEqual URI(
      "file:///Users/grey/Music/Library/scales/dorian.scl")
    resolveLibraryUrl(uri, URI("https://microtonalist.org/library/grey/")) shouldEqual URI(
      "https://microtonalist.org/library/grey/scales/dorian.scl")
  }

  it should "fail for a relative URI" in {
    val uri = URI("scales/dorian.scl")

    assertThrows[IllegalArgumentException] {
      resolveLibraryUrl(uri, URI("file:///Users/grey/Music/Library/")) shouldEqual uri
    }
  }

  it should "fail for URIs that don't have a microtonalist scheme" in {
    val libraryBaseUrl = URI("file:///Users/grey/Music/Library/")

    assertThrows[IllegalArgumentException] {
      resolveLibraryUrl(URI("file:///Users/grey/Music/Library/scales/dorian.scl"), libraryBaseUrl)
    }
    assertThrows[IllegalArgumentException] {
      resolveLibraryUrl(URI("https://microtonalist.org/library/grey/scales/dorian.scl"), libraryBaseUrl)
    }
  }
}

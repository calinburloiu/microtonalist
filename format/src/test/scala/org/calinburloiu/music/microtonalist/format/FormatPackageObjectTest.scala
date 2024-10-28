/*
 * Copyright 2021 Calin-Andrei Burloiu
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
}

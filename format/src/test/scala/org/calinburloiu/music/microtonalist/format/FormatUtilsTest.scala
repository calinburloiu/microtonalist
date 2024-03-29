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

class FormatUtilsTest extends AnyFlatSpec with Matchers {

  "baseUriOf" should "only strip the part after last slash in a URI path" in {
    baseUriOf(new URI("https://example.org/path/to/file.json")) shouldEqual
      new URI("https://example.org/path/to/")
  }

  it should "only affect path and leave other URI components unchanged" in {
    baseUriOf(new URI("http://john@example.org:8080/path/to/file.txt?a=2&b=3#contents")) shouldEqual
      new URI("http://john@example.org:8080/path/to/?a=2&b=3#contents")
  }

  it should "not strip a URI path if it ends in slash" in {
    val uri = new URI("https://example.org/path/to/")
    val baseUri = baseUriOf(uri)

    baseUri shouldEqual uri
    baseUri shouldBe theSameInstanceAs (uri)
  }

  it should "not strip a root URI path" in {
    val uri = new URI("https://example.org/")
    val baseUri = baseUriOf(uri)

    baseUri shouldEqual uri
    baseUri shouldBe theSameInstanceAs (uri)
  }

  it should "return a URI with root path for a URI without path" in {
    baseUriOf(new URI("https://example.org")) shouldEqual new URI("https://example.org/")
  }

  "pathOf" should "convert an absolute URI to a file system path" in {
    pathOf(new URI("file:///Users/john/Music/phrygian.scl")) shouldEqual
      Paths.get("/" , "Users", "john", "Music", "phrygian.scl")
  }

  it should "convert a relative URI to file system path" in {
    pathOf(new URI("Music/phrygian.scl")) shouldEqual
      Paths.get("Music", "phrygian.scl")
  }

  it should "fail for a non file URI" in {
    assertThrows[IllegalArgumentException] { pathOf(new URI("https://example.org/path/to/file.scl")) }
  }

  "parseUri" should "parse an absolute URI" in {
    parseBaseUri("https://example.org/path/to/") should contain (new URI("https://example.org/path/to/"))
    parseBaseUri("https://example.org/path/to/file.json") should contain
      new URI("https://example.org/path/to/file.json")

    parseBaseUri("file:///path/to/") should contain (new URI("file:///path/to/"))
    parseBaseUri("file:///path/to/file.json") should contain
      new URI("file:///path/to/file.json")
  }

  "parseUri" should "parse a UNIX path" in {
    parseBaseUri("/Users/johnny/Music/microtonalist/lib/scales/") should contain
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales/")
    parseBaseUri("/Users/johnny/Music/microtonalist/lib/scales") should contain
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales")
  }

  "parseBaseUri" should "parse an absolute URI" in {
    parseBaseUri("https://example.org/path/to/") should contain (new URI("https://example.org/path/to/"))
    parseBaseUri("https://example.org/path/to/file.json") should contain
      new URI("https://example.org/path/to/file.json/")

    parseBaseUri("file:///path/to/") should contain (new URI("file:///path/to/"))
    parseBaseUri("file:///path/to/file.json") should contain
      new URI("file:///path/to/file.json/")
  }

  it should "parse a UNIX path" in {
    parseBaseUri("/Users/johnny/Music/microtonalist/lib/scales/") should contain
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales/")
    parseBaseUri("/Users/johnny/Music/microtonalist/lib/scales") should contain
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales/")
  }
}

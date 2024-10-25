/*
 * Copyright 2024 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist

import org.calinburloiu.music.microtonalist.format.FormatTestUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.Paths

class MicrotonalistPackageObjectTest extends AnyFlatSpec with Matchers {

  "parseUri" should "parse an absolute URI" in {
    parseUri("https://example.org/path/to/file.json") should contain(new URI("https://example.org/path/to/file.json"))
    parseUri("https://example.org/path/to/") should contain(new URI("https://example.org/path/to/"))

    parseUri("file:///path/to/file.json") should contain(new URI("file:///path/to/file.json"))
    parseUri("file:///path/to/") should contain(new URI("file:///path/to/"))
  }

  it should "parse a UNIX path" in {
    var uri = FormatTestUtils.uriOfResource("app/")
    parseUri(uri.getPath) should contain(uri)
    uri = FormatTestUtils.uriOfResource("app/minor-major.mtlist")
    parseUri(uri.getPath) should contain(uri)

    parseUri("/Users/johnny/Music/microtonalist/lib/scales/rast.jscl") should contain(
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales/rast.jscl"))
    parseUri("/Users/johnny/Music/microtonalist/lib/scales/") should contain(
      new URI("file:///Users/johnny/Music/microtonalist/lib/scales/"))

    // Current working directory
    val cwd = Paths.get("").toAbsolutePath.toString
    parseUri("scales/rast.scl") should contain(new URI(s"file://$cwd/scales/rast.scl"))
    parseUri("scales/") should contain(new URI(s"file://$cwd/scales/"))
  }
}

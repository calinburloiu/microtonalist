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

import com.google.common.net.MediaType
import org.calinburloiu.businessync.Businessync
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class ScaleFormatRegistryTest extends AnyFlatSpec, Matchers, Stubs {
  val businessyncStub: Stub[Businessync] = stub[Businessync]
  val huygensFokkerScalaScaleFormat: ScaleFormat = new HuygensFokkerScalaScaleFormat
  val jsonScaleFormat: ScaleFormat = new JsonScaleFormat(NoJsonPreprocessor, businessyncStub)
  val registry: ScaleFormatRegistry = new ScaleFormatRegistry(Seq(huygensFokkerScalaScaleFormat, jsonScaleFormat))

  def assertResult(actualResult: Option[ScaleFormat], expectedResult: ScaleFormat): Unit = {
    actualResult should not be empty
    actualResult.get shouldBe theSameInstanceAs(expectedResult)
  }

  "getByExtension" should "return the ScaleFormat by file extension" in {
    assertResult(registry.getByExtension("scl"), huygensFokkerScalaScaleFormat)
    assertResult(registry.getByExtension("jscl"), jsonScaleFormat)
    assertResult(registry.getByExtension("json"), jsonScaleFormat)

    registry.getByExtension("txt") shouldBe empty
  }

  "getByMediaType" should "return the ScaleFormat by media type" in {
    assertResult(registry.getByMediaType(JsonScaleFormat.JsonScaleMediaType), jsonScaleFormat)
    assertResult(registry.getByMediaType(MediaType.JSON_UTF_8), jsonScaleFormat)

    registry.getByMediaType(MediaType.parse("text/html")) shouldBe empty
    registry.getByMediaType(MediaType.create("application", "*")) shouldBe empty
  }

  "get" should "return the ScaleFormat by URI and media type" in {
    assertResult(registry.get(new URI("https://example.org/dorian.scl"), None), huygensFokkerScalaScaleFormat)
    assertResult(registry.get(new URI("https://example.org/dorian.jscl"), None), jsonScaleFormat)
    assertResult(registry.get(new URI("https://example.org/dorian.json"), None), jsonScaleFormat)
    registry.get(new URI("https://example.org/dorian"), None) shouldBe empty

    assertResult(registry.get(new URI("https://example.org/dorian"), Some(JsonScaleFormat.JsonScaleMediaType)),
      jsonScaleFormat)
    assertResult(registry.get(new URI("https://example.org/dorian"), Some(MediaType.JSON_UTF_8)),
      jsonScaleFormat)
    registry.get(new URI("https://example.org/dorian"), Some(MediaType.parse("text/html"))) shouldBe empty

    // Media type takes precedence
    assertResult(registry.get(new URI("https://example.org/dorian.scl"), Some(JsonScaleFormat.JsonScaleMediaType)),
      jsonScaleFormat)
  }
}

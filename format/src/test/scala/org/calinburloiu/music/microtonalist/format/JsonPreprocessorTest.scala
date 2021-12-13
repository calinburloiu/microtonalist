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

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsObject, JsPath, Json, __}

import java.net.URI

class JsonPreprocessorTest extends AnyFlatSpec with Matchers with MockFactory {
  type RefLoaders = Seq[JsonRefLoader]

  behavior of classOf[JsonPreprocessor].getSimpleName

  // TODO #38 Is it used more than once?
  private def assertPreprocessing(input: JsObject, loaders: RefLoaders, expectedOutput: JsObject): Unit = {
    val preprocessor = new JsonPreprocessor(loaders)
    val output = preprocessor.preprocess(input)

    output shouldEqual expectedOutput
  }

  it should "replace references in a JSON based on URI" in {
    val dogUri = "https://dogs.org/pamela"
    val catUri = "https://cats.org/mitzy"
    val input = Json.obj(
      "name" -> "John Doe",
      "age" -> 30,
      "pets" -> Json.arr(
        Json.obj(
          "animal" -> "cat",
          "name" -> "Felix"
        ),
        Json.obj(
          "$ref" -> dogUri,
          "name" -> "Pammy",
          "location" -> "countryside"
        ),
        Json.obj(
          "animal" -> "cat",
          "name" -> "Florica"
        ),
        Json.obj(
          "$ref" -> catUri
        )
      )
    )
    val loaders: RefLoaders = Seq(
      (uri, _) => {
        if (uri.toString == catUri) {
          Some(Json.obj(
            "animal" -> "dog",
            "name" -> "Pamela"
          ))
        } else {
          None
        }
      },
      (_, _) => None,
      (uri, _) => {
        if (uri.toString == dogUri) {
          Some(Json.obj(
            "animal" -> "dog",
            "name" -> "Pamela"
          ))
        } else {
          None
        }
      }
    )
    val expectedOutput = Json.obj(
      "name" -> "John Doe",
      "age" -> 30,
      "pets" -> Json.arr(
        Json.obj(
          "animal" -> "cat",
          "name" -> "Felix"
        ),
        Json.obj(
          "animal" -> "dog",
          "name" -> "Pamela",
          "location" -> "countryside"
        ),
        Json.obj(
          "animal" -> "cat",
          "name" -> "Florica"
        ),
        Json.obj(
          "animal" -> "dog",
          "name" -> "Pamela"
        )
      )
    )

    assertPreprocessing(input, loaders, expectedOutput)
  }

  it should "pass the correct JsonPath context to JsonRefLoaders" in {
    // Given
    val input = Json.obj(
      "$ref" -> "https://example.org/1",
      "foo" -> Json.obj(
        "$ref" -> "https://example.org/2",
        "bar" -> Json.obj(
          "$ref" -> "https://example.org/3",
          "items" -> Json.arr(
            Json.obj(),
            Json.obj(
              "$ref" -> "https://example.org/4",
              "detail" -> Json.obj(
                "$ref" -> "https://example.org/5"
              )
            ),
            Json.obj()
          )
        )
      )
    )
    val mockLoader = stub[JsonRefLoader]
    mockLoader.load _ when(*, *) returns Some(Json.obj())
    val loaders: RefLoaders = Seq(mockLoader)
    val preprocessor = new JsonPreprocessor(loaders)
    // When
    val output = preprocessor.preprocess(input)
    // Then
    mockLoader.load _ verify (new URI("https://example.org/1"), __)
    mockLoader.load _ verify (new URI("https://example.org/2"), __ \ "foo")
    mockLoader.load _ verify (new URI("https://example.org/3"), __ \ "foo" \ "bar")
    mockLoader.load _ verify (new URI("https://example.org/4"), __ \ "foo" \ "bar" \ "items" \ 1)
    mockLoader.load _ verify (new URI("https://example.org/5"), __ \ "foo" \ "bar" \ "items" \ 1 \ "detail")
  }

  it should "leave a JSON as it is if it does not have references" in {
    // Given
    val input = Json.obj("name" -> "John Doe", "age" -> 30)
    val preprocessor = new JsonPreprocessor(Seq.empty)
    // When
    val output = preprocessor.preprocess(input)
    // Then
    output shouldEqual input
    output shouldBe theSameInstanceAs (input)
  }

  it should "fail if a reference can't be loaded by any JsonRefLoader" in {
    // Given
    val uri = "https://example.org/1"
    val input = Json.obj("$ref" -> uri)
    val loaders: RefLoaders = Seq((_, _) => None, (_, _) => None)
    val preprocessor = new JsonPreprocessor(loaders)
    // Then
    val exception = intercept[JsonRefLoadException] {
      preprocessor.preprocess(input)
    }
    exception.uri shouldEqual new URI(uri)
    exception.pathContext shouldEqual JsPath()
    exception.getMessage shouldEqual "No loaded matched the reference!"
  }

  it should "fail if an error occurs while loading a reference" in {
    // Given
    val input = Json.obj("$ref" -> "https://example.org/1")
    val loaders: RefLoaders = Seq((_, _) => None, (uri, path) => throw new JsonRefLoadException(uri, path, "Boom!"))
    val preprocessor = new JsonPreprocessor(loaders)
    // Then
    assertThrows[JsonRefLoadException] {
      preprocessor.preprocess(input)
    }
  }
}

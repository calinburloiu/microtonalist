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
import play.api.libs.json.{JsPath, Json, __}

import java.net.URI
import scala.collection.mutable

class JsonPreprocessorTest extends AnyFlatSpec with Matchers with MockFactory {
  type RefLoaders = Seq[JsonPreprocessorRefLoader]

  behavior of classOf[JsonPreprocessor].getSimpleName

  it should "replace references in a JSON based on URI" in {
    // Given
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
            "animal" -> "cat",
            "name" -> "Mitzy"
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
          "animal" -> "cat",
          "name" -> "Mitzy"
        )
      )
    )
    val preprocessor = new JsonPreprocessor(loaders)

    // When
    val output = preprocessor.preprocess(input, None)
    // Then
    output shouldEqual expectedOutput
  }
  
  it should "pass resolved URIs to JsonPreprocessorRefLoaders" in {
    // Given
    val input = Json.obj("$ref" -> "company/employees/john.json")
    val mockLoader = stub[JsonPreprocessorRefLoader]
    mockLoader.load _ when(*, *) returns Some(Json.obj())
    val loaders: RefLoaders = Seq(mockLoader)
    val preprocessor = new JsonPreprocessor(loaders)
    // When
    preprocessor.preprocess(input, Some(new URI("https://example.org/")))
    // Then
    mockLoader.load _ verify (new URI("https://example.org/company/employees/john.json"), __)
  }

  it should "pass the correct JsonPath context to JsonPreprocessorRefLoaders" in {
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
    val mockLoader = stub[JsonPreprocessorRefLoader]
    mockLoader.load _ when(*, *) returns Some(Json.obj())
    val loaders: RefLoaders = Seq(mockLoader)
    val preprocessor = new JsonPreprocessor(loaders)
    // When
    preprocessor.preprocess(input, None)
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
    val preprocessor = new JsonPreprocessor(Seq(mock[JsonPreprocessorRefLoader]))
    // When
    val output = preprocessor.preprocess(input, None)
    // Then
    output shouldEqual input
    output shouldBe theSameInstanceAs (input)
  }

  it should "leave a JSON as it is there are no reference loaders" in {
    // Given
    val input = Json.obj("name" -> "John Doe", "age" -> 30, "$ref" -> "https://example.org/1")
    val preprocessor = new JsonPreprocessor(Seq.empty)
    // When
    val output = preprocessor.preprocess(input, None)
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
    val exception = intercept[JsonPreprocessorRefLoadException] {
      preprocessor.preprocess(input, None)
    }
    exception.uri shouldEqual new URI(uri)
    exception.pathContext shouldEqual JsPath()
    exception.getMessage shouldEqual "No loader matched the reference!"
  }

  it should "fail if an error occurs while loading a reference" in {
    // Given
    val input = Json.obj("$ref" -> "https://example.org/1")
    val loaders: RefLoaders = Seq((_, _) => None, (uri, path) => throw new JsonPreprocessorRefLoadException(uri, path, "Boom!"))
    val preprocessor = new JsonPreprocessor(loaders)
    // Then
    assertThrows[JsonPreprocessorRefLoadException] {
      preprocessor.preprocess(input, None)
    }
  }

  it should "replace nested references" in {
    // Given
    val input = Json.obj(
      "$ref" -> "https://example.org/1"
    )
    val loaders: RefLoaders = Seq(
      (uri, _) => uri.toString match {
        case "https://example.org/1" => Some(Json.obj(
          "name" -> "John",
          "$ref" -> "https://example.org/2"
        ))
        case "https://example.org/2" => Some(Json.obj(
          "age" -> 25
        ))
        case _ => None
      }
    )
    val expectedOutput = Json.obj(
      "name" -> "John",
      "age" -> 25
    )
    val preprocessor = new JsonPreprocessor(loaders)

    // When
    val output = preprocessor.preprocess(input, None)
    // Then
    output shouldEqual expectedOutput
  }

  it should "continue to resolve URI and append to path context with nested references" in {
    // Given
    val input = Json.obj(
      "name" -> "John Doe",
      "$ref" -> "people/john-details.json",
      "pets" -> Json.arr(
        Json.obj(
          "animal" -> "cat",
          "name" -> "Felix"
        ),
        Json.obj(
          "name" -> "Pamela",
          "$ref" -> "pets/pamela.json"
        )
      )
    )
    val refs = mutable.ArrayBuffer[String]()
    val paths = mutable.ArrayBuffer[JsPath]()
    val loaders: RefLoaders = Seq(
      (uri, path) => {
        val uriString = uri.toString

        refs += uriString
        paths += path

        uriString match {
          case "https://example.org/data/people/john-details.json" => Some(Json.obj(
            "age" -> 25,
          ))
          case "https://example.org/data/pets/pamela.json" => Some(Json.obj(
            "animal" -> "dog",
            "details" -> Json.obj(
            "$ref" -> "details/countryside.json"
            )
          ))
          case "https://example.org/data/pets/details/countryside.json" => Some(Json.obj(
            "location" -> "countryside"
          ))
          case _ => None
        }
      }
    )
    val expectedOutput = Json.obj(
      "name" -> "John Doe",
      "age" -> 25,
      "pets" -> Json.arr(
        Json.obj(
          "animal" -> "cat",
          "name" -> "Felix"
        ),
        Json.obj(
          "name" -> "Pamela",
          "animal" -> "dog",
          "details" -> Json.obj(
            "location" -> "countryside"
          )
        )
      )
    )
    val preprocessor = new JsonPreprocessor(loaders)

    // When
    val output = preprocessor.preprocess(input, Some(new URI("https://example.org/data/")))
    // Then
    output shouldEqual expectedOutput
    refs.toSet shouldEqual Set(
      "https://example.org/data/people/john-details.json",
      "https://example.org/data/pets/pamela.json",
      "https://example.org/data/pets/details/countryside.json"
    )
    paths.toSet shouldEqual Set(
      __,
      __ \ "pets" \ 1,
      __ \ "pets" \ 1 \ "details"
    )
  }

  it should "fail gracefully with cyclic references" in {
    // Given
    val input = Json.obj(
      "$ref" -> "https://example.org/1"
    )
    val loaders: RefLoaders = Seq(
      (uri, _) => uri.toString match {
        case "https://example.org/1" => Some(Json.obj(
          "$ref" -> "https://example.org/2"
        ))
        case "https://example.org/2" => Some(Json.obj(
          "$ref" -> "https://example.org/1"
        ))
        case _ => None
      }
    )
    val preprocessor = new JsonPreprocessor(loaders)

    // Then
    assertThrows[JsonPreprocessorRefLoadException] {
      preprocessor.preprocess(input, None)
    }
  }

  it should "fail gracefully with nested references that exceed the depth threshold" in {
    // Given
    val input = Json.obj(
      "$ref" -> "https://example.org/foo"
    )
    var i = 0
    val loaders: RefLoaders = Seq(
      (uri, path) => uri.toString match {
        case _ =>
          i += 1
          Some(Json.obj(
            s"a$i" -> Json.obj(
              "$ref" -> s"https://example.org/$i"
            )
          ))
      }
    )
    val preprocessor = new JsonPreprocessor(loaders)

    // Then
    assertThrows[JsonPreprocessorRefLoadException] {
      preprocessor.preprocess(input, None)
    }
    i shouldEqual JsonPreprocessor.MaxNestedRefDepth
  }
}

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

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor3}
import play.api.libs.json._

/**
 * Convenience trait that can be mixed in tests for various utilities like easily asserting reading JSON with the
 * play-json library.
 */
trait JsonFormatTestUtils extends AnyFlatSpec with Matchers with Inside with TableDrivenPropertyChecks {

  import JsonFormatTestUtils._

  def assertReads[A](reads: Reads[A], json: JsValue, expectedResult: A): Unit = {
    val actualResult = reads.reads(json)
    actualResult match {
      case JsSuccess(`expectedResult`, _) => // succeed
      case _ => fail(s"Reading JSON `$json` should give JsSuccess($expectedResult), but got $actualResult.")
    }
  }

  /** Asserts that the given error is the only error to be matched. */
  def assertReadsSingleFailure[A](reads: Reads[A],
                                  json: JsValue,
                                  error: JsonValidationError): Unit =
    reads.reads(json) should matchPattern { case JsError(Seq((_, Seq(`error`)))) => }

  /** Asserts that the given error is the only error to be matched. */
  def assertReadsSingleFailure[A](reads: Reads[A],
                                  json: JsValue,
                                  errorMessage: String): Unit = {
    assertReadsSingleFailure(reads, json, JsonValidationError(errorMessage))
  }

  /** Assert that the given error is matched at list once. */
  def assertReadsFailure[A](reads: Reads[A],
                            json: JsValue,
                            path: JsPath,
                            jsonValidationError: JsonValidationError): Unit = {
    reads.reads(json) match {
      case jsError: JsError =>
        withClue(s"($path, $jsonValidationError) should be in $jsError") {
          jsError.errors.exists {
            case (currPath, currJsonValidationErrors) =>
              currPath == path && currJsonValidationErrors.contains(jsonValidationError)
          } shouldBe true
        }
      case jsSuccess: JsSuccess[_] => fail(s"($path, $jsonValidationError) does not match ($json, $jsSuccess)")
    }
  }

  /** Assert that the given error is matched at list once. */
  def assertReadsFailure[A](reads: Reads[A],
                            json: JsValue,
                            path: JsPath,
                            errorMessage: String): Unit = {
    reads.reads(json) match {
      case jsError: JsError =>
        withClue(s"($path, $errorMessage) should be in $jsError") {
          jsError.errors.exists {
            case (currPath, currJsonValidationErrors) =>
              currPath == path && currJsonValidationErrors.exists(_.messages.contains(errorMessage))
            case _ => false
          } shouldBe true
        }
      case jsSuccess: JsSuccess[_] => fail(s"($path, $errorMessage) does not match ($json, $jsSuccess)")
    }
  }

  /**
   * Method that can be used for testing error handling at property level by using a table to check errors returned
   * when invalid values are passed to various properties.
   *
   * @param reads        JSON deserializer.
   * @param baselineJson A JSON that should be successfully deserialized as it is which will be modified according to
   *                     the second column of the table at the [[JsPath]] of the first column.
   * @param table        A test table with the following columns:
   *               - Path where `baselineJson` will be modified to be invalid.
   *               - Check to be performed which will produce various failures at the given path.
   *               - Expected error message.
   * @tparam A Type to be deserialized.
   */
  def assertReadsFailureTable[A](reads: Reads[A],
                                 baselineJson: JsObject,
                                 table: TableFor3[JsPath, JsonFailureCheck, String]): Unit = {
    withClue("Precondition: baselineJson deserialization should be successful") {
      baselineJson.validate[A](reads).isSuccess shouldBe true
    }

    def updateJson(path: JsPath, value: JsValue): JsValue = {
      val update = __.json.update(path.json.put(value))
      baselineJson.transform(update).get
    }

    def assertRow(path: JsPath, value: JsValue, expectedErrorMessage: String): Unit = {
      val updatedJson = updateJson(path, value)
      assertReadsFailure(reads, updatedJson, path, expectedErrorMessage)
    }

    forAll(table) { (path, check, expectedErrorMessage) =>
      check match {
        case DisallowedValues(values@_*) =>
          for (value <- values) {
            assertRow(path, value, expectedErrorMessage)
          }

        case AllowedTypes(allowedJsonTypes@_*) =>
          for (jsonType <- AllJsonTypes if !allowedJsonTypes.contains(jsonType)) {
            assertRow(path, jsonType.sample, expectedErrorMessage)
          }
      }
    }
  }
}

object JsonFormatTestUtils {

  /**
   * Base class for identifiers for JSON types, each with a sample value, that will be used by
   * [[JsonFormatTestUtils#assertReadsFailureTable]].
   */
  sealed abstract class JsonType(val sample: JsValue)

  case object JsonNullType extends JsonType(JsNull)

  case object JsonNumberType extends JsonType(JsNumber(1))

  case object JsonBooleanType extends JsonType(JsTrue)

  case object JsonStringType extends JsonType(JsString("foo"))

  case object JsonArrayType extends JsonType(Json.arr(2, 10))

  case object JsonObjectType extends JsonType(Json.obj("bar" -> "baz"))

  val AllJsonTypes: Seq[JsonType] =
    Seq(JsonNullType, JsonNumberType, JsonBooleanType, JsonStringType, JsonArrayType, JsonObjectType)

  /**
   * Trait for checks to be performed which will produce various failures at the given path by the
   * [[JsonFormatTestUtils#assertReadsFailureTable]] method.
   */
  sealed trait JsonFailureCheck

  /**
   * Invalid values at a given path in a JSON that are expected to produce an error when deserializing with [[Reads]].
   *
   * @param values A sequence of invalid value for the given path.
   */
  case class DisallowedValues(values: JsValue*) extends JsonFailureCheck

  /**
   * All JSON types not listed here will be used as invalid values at a given path.
   *
   * @param jsonTypes A sequence of acceptable types that be avoided to produce a failure.
   */
  case class AllowedTypes(jsonTypes: JsonType*) extends JsonFailureCheck
}

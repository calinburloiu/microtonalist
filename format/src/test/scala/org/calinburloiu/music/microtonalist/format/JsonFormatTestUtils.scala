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

package org.calinburloiu.music.microtonalist.format

import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json._

trait JsonFormatTestUtils extends AnyFlatSpec with Matchers with Inside {

  def assertReads[A](reads: Reads[A], json: JsValue, result: A): Unit =
    reads.reads(json) should matchPattern { case JsSuccess(`result`, _) => }

  def assertReadsFailure[A](reads: Reads[A],
                            json: JsValue,
                            error: JsonValidationError): Unit =
    reads.reads(json) should matchPattern { case JsError(Seq((_, Seq(`error`)))) => }

  def assertReadsFailure[A](reads: Reads[A],
                            json: JsValue,
                            errorMessage: String): Unit = {
    assertReadsFailure(reads, json, JsonValidationError(errorMessage))
  }
}

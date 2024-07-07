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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import org.calinburloiu.music.microtonalist.format.JsonIntervalFormat.ErrorExpectingIntervalFor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Format, JsNumber, JsString, Json}

class JsonIntervalFormatTest extends AnyFlatSpec with Matchers {
  "formatFor" should "read cents and ratio intervals in CentsIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(CentsIntonationStandard)

      assertReads(JsNumber(203.91), CentsInterval(203.91))
      assertReads(JsNumber(-333.33), CentsInterval(-333.33))
      assertReads(JsNumber(0), CentsInterval(0))

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
    }

  it should "not read invalid data in CentsIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(CentsIntonationStandard)
      val error: String = ErrorExpectingIntervalFor(CentsIntonationStandard.typeName)

      assertReadsFailure(JsString("blah"), error)
      assertReadsFailure(JsString("0/4"), error)
      assertReadsFailure(JsString("4/0"), error)

      assertReadsFailure(Json.arr(1, 2), error)
      assertReadsFailure(Json.obj("foo" -> "bar"), error)
    }

  it should "read ratio intervals in JustIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(JustIntonationStandard)

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
    }

  it should "not read invalid data in JustIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(JustIntonationStandard)
      val error: String = ErrorExpectingIntervalFor(JustIntonationStandard.typeName)

      assertReadsFailure(JsString("blah"), error)
      assertReadsFailure(JsString("0/4"), error)
      assertReadsFailure(JsString("4/0"), error)

      assertReadsFailure(JsNumber(1), error)
      assertReadsFailure(Json.arr(1, 2), error)
      assertReadsFailure(Json.obj("foo" -> "bar"), error)
    }

  it should "read EDO and ratio intervals in EdoIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(EdoIntonationStandard(72))

      assertReads(JsNumber(30), EdoInterval(72, 30))
      assertReads(JsNumber(23), EdoInterval(72, 23))
      assertReads(JsNumber(19), EdoInterval(72, 19))

      assertReads(Json.arr(5, 0), EdoInterval(72, 30))
      assertReads(Json.arr(4, -1), EdoInterval(72, 23))
      assertReads(Json.arr(3, +1), EdoInterval(72, 19))

      assertReads(JsString("5/4"), RatioInterval(5, 4))
      assertReads(JsString("8/9"), RatioInterval(8, 9))
    }

  it should "not read invalid data in EdoIntonationStandard" in
    new JsonFormatTestUtils[Format[Interval]] {
      val format: Format[Interval] = JsonIntervalFormat.formatFor(EdoIntonationStandard(72))
      val error: String = ErrorExpectingIntervalFor(EdoIntonationStandard.typeName)

      assertReadsFailure(JsString("blah"), error)
      assertReadsFailure(JsString("0/4"), error)
      assertReadsFailure(JsString("4/0"), error)

      assertReadsFailure(Json.arr(), error)
      assertReadsFailure(Json.arr(1), error)
      assertReadsFailure(Json.arr(1, 2, 3), error)
      assertReadsFailure(Json.arr("1", "2"), error)
      assertReadsFailure(Json.obj("foo" -> "bar"), error)
    }

  it should "write a cents interval" in {
    val format = JsonIntervalFormat.formatFor(CentsIntonationStandard)
    format.writes(CentsInterval(203.91)) shouldEqual JsNumber(203.91)
  }

  it should "write a ratio interval" in {
    val format = JsonIntervalFormat.formatFor(CentsIntonationStandard)
    format.writes(5 /: 4) shouldEqual JsString("5/4")
  }

  it should "write EDO intervals" in {
    val format = JsonIntervalFormat.formatFor(CentsIntonationStandard)
    format.writes(EdoInterval(31, 10)) shouldEqual JsNumber(10)
    format.writes(EdoInterval(72, 23)) shouldEqual Json.arr(4, -1)
  }
}

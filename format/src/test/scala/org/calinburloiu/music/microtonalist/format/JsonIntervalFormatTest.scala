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

import org.calinburloiu.music.intonation.*
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.microtonalist.format.JsonIntervalFormat.ErrorExpectedIntervalFor
import org.scalactic.{Equality, TolerantNumerics}
import play.api.libs.json.*

class JsonIntervalFormatTest extends JsonFormatTestUtils {
  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  "formatFor" should "read cents and ratio intervals in CentsIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(CentsIntonationStandard)

    assertReads(format, JsNumber(203.91), CentsInterval(203.91))
    assertReads(format, JsNumber(-333.33), CentsInterval(-333.33))
    assertReads(format, JsNumber(0), CentsInterval(0))

    matchReads(format, JsString("5/4"), { (result: Interval) =>
      result shouldBe a[CentsInterval]
      result.cents shouldEqual 386.31
    })
    matchReads(format, JsString("8/9"), { (result: Interval) =>
      result shouldBe a[CentsInterval]
      result.cents shouldEqual -203.91
    })
  }

  it should "not read invalid data in CentsIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(CentsIntonationStandard)
    val error: String = ErrorExpectedIntervalFor(CentsIntonationStandard.typeName)

    assertReadsSingleFailure(format, JsString("blah"), error)
    assertReadsSingleFailure(format, JsString("0/4"), error)
    assertReadsSingleFailure(format, JsString("4/0"), error)

    assertReadsSingleFailure(format, Json.arr(1, 2), error)
    assertReadsSingleFailure(format, Json.obj("foo" -> "bar"), error)
  }

  it should "read ratio intervals in JustIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(JustIntonationStandard)

    assertReads(format, JsString("5/4"), RatioInterval(5, 4))
    assertReads(format, JsString("8/9"), RatioInterval(8, 9))
  }

  it should "not read invalid data in JustIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(JustIntonationStandard)
    val error: String = ErrorExpectedIntervalFor(JustIntonationStandard.typeName)

    assertReadsSingleFailure(format, JsString("blah"), error)
    assertReadsSingleFailure(format, JsString("0/4"), error)
    assertReadsSingleFailure(format, JsString("4/0"), error)

    assertReadsSingleFailure(format, JsNumber(1), error)
    assertReadsSingleFailure(format, Json.arr(1, 2), error)
    assertReadsSingleFailure(format, Json.obj("foo" -> "bar"), error)
  }

  it should "read EDO and ratio intervals in EdoIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(EdoIntonationStandard(72))

    assertReads(format, JsNumber(30), EdoInterval(72, 30))
    assertReads(format, JsNumber(23), EdoInterval(72, 23))
    assertReads(format, JsNumber(19), EdoInterval(72, 19))

    assertReads(format, Json.arr(5, 0), EdoInterval(72, 30))
    assertReads(format, Json.arr(4, -1), EdoInterval(72, 23))
    assertReads(format, Json.arr(3, +1), EdoInterval(72, 19))

    assertReads(format, JsString("5/4"), EdoInterval(72, 23))
    assertReads(format, JsString("8/9"), EdoInterval(72, -12))
  }

  it should "not read invalid data in EdoIntonationStandard" in {
    val format: Format[Interval] = JsonIntervalFormat.formatFor(EdoIntonationStandard(72))
    val error: String = ErrorExpectedIntervalFor(EdoIntonationStandard.typeName)

    assertReadsSingleFailure(format, JsString("blah"), error)
    assertReadsSingleFailure(format, JsString("0/4"), error)
    assertReadsSingleFailure(format, JsString("4/0"), error)

    assertReadsSingleFailure(format, JsArray.empty, error)
    assertReadsSingleFailure(format, Json.arr(1), error)
    assertReadsSingleFailure(format, Json.arr(1, 2, 3), error)
    assertReadsSingleFailure(format, Json.arr("1", "2"), error)
    assertReadsSingleFailure(format, Json.obj("foo" -> "bar"), error)
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

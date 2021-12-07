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

import org.calinburloiu.music.intonation.{Interval, RatioInterval, Scale}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Json, Reads}

class RefTest extends AnyFlatSpec with Matchers {

  implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleFormat.jsonScaleReads

  "refReads" should "read a JSON containing only a reference as an UnresolvedRef" in {
    // Arrange
    val unresolvedRefJson = Json.obj(
      "ref" -> "/path/to/scale.scl"
    )

    // Act
    val unresolvedRefResult = Ref.refReads[Scale[Interval]].reads(unresolvedRefJson)

    // Assert
    unresolvedRefResult.asOpt should contain(UnresolvedRef("/path/to/scale.scl"))
  }

  it should "read a JSON without a reference as a NoRef " +
    "and correctly parse the containing value" in {
    // Arrange
    val scaleJson = Json.obj(
      "name" -> "minor triad",
      "intervals" -> Json.arr("6/5", "3/2")
    )

    // Act
    val noRefResult = Ref.refReads[Scale[Interval]].reads(scaleJson)

    // Assert
    noRefResult.asOpt should contain(
      NoRef(Scale("minor triad", RatioInterval(1, 1), RatioInterval(6, 5), RatioInterval(3, 2)))
    )
  }

  it should "read a JSON containing both a reference and the expected value as a ResolvedRef" in {
    // Arrange
    val resolvedRefJson = Json.obj(
      "ref" -> "/path/to/scale.scl",
      "name" -> "minor triad",
      "intervals" -> Json.arr("6/5", "3/2")
    )

    // Act
    val resolvedRefResult = Ref.refReads[Scale[Interval]].reads(resolvedRefJson)

    // Assert
    resolvedRefResult.asOpt should contain(
      ResolvedRef(
        "/path/to/scale.scl",
        Scale("minor triad", RatioInterval(1, 1), RatioInterval(6, 5), RatioInterval(3, 2))
      )
    )
  }

  it should "fail to read a JSON which either does not contain a reference either " +
    "it does not conform to the expected value" in {
    // Arrange
    val badJson = Json.obj("name" -> "John", "age" -> 20)
    val badJsonWithRef = Json.obj("ref" -> "/a/b/c", "name" -> "John", "age" -> 20)

    // Act
    val badJsonResult = Ref.refReads[Scale[Interval]].reads(badJson)
    val badJsonWithRefResult = Ref.refReads[Scale[Interval]].reads(badJsonWithRef)

    // Assert
    badJsonResult.asOpt should be(empty)
    badJsonWithRefResult.asOpt should be(empty)
  }
}

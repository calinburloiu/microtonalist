package org.calinburloiu.music.intonation.io

import org.calinburloiu.music.intonation.{Interval, RatioInterval, Scale}
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{Json, Reads}

class RefTest extends FlatSpec with Matchers {

  implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleReader.jsonScaleReads

  "refReads" should "read a JSON containing only a reference as an UnresolvedRef" in {
    // Arrange
    val unresolvedRefJson = Json.obj(
      "ref" -> "/path/to/scale.scl"
    )

    // Act
    val unresolvedRefResult = Ref.refReads[Scale[Interval]].reads(unresolvedRefJson)

    // Assert
    unresolvedRefResult.asOpt should contain (UnresolvedRef("/path/to/scale.scl"))
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
    noRefResult.asOpt should contain (
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
    resolvedRefResult.asOpt should contain (
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
    badJsonResult.asOpt should be (empty)
    badJsonWithRefResult.asOpt should be (empty)
  }
}

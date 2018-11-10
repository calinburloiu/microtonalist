package org.calinburloiu.music.intonation

import org.calinburloiu.music.intonation.io.{InvalidJsonScaleException, JsonScaleReader}
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class JsonScaleReaderTest extends FlatSpec with Matchers {

  "A JSON Scale with pitches in cents" should "correctly create a CentsScale object" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "204.3", "315.9", "498.5"
      )
    )

    val result = JsonScaleReader.read(json)

    result.getClass shouldEqual classOf[CentsScale]
    result shouldEqual CentsScale("abc", 0.0, 204.3, 315.9, 498.5)
  }

  "A JSON Scale with pitches as ratios" should "correctly create a RatiosScale object" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "9/8", "5/4", "4/3"
      )
    )

    val result = JsonScaleReader.read(json)

    result.getClass shouldEqual classOf[RatiosScale]
    result shouldEqual RatiosScale("abc", (1, 1), (9, 8), (5, 4), (4, 3))
  }

  "A JSON Scale with pitches in both cents and as ratios" should "correctly create a Scale object" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "9/8", "315.9", "4/3"
      )
    )

    val result = JsonScaleReader.read(json)

    result.getClass shouldEqual classOf[Scale[Interval]]
    result shouldEqual Scale("abc",
      Interval(1.0), RatioInterval(9, 8), CentsInterval(315.9), RatioInterval(4, 3))
  }

  "A JSON Scale without a name" should "correctly create a scale object with an empty string name" in {
    val json = Json.obj(
      "intervals" -> Json.arr(
        "204.3", "315.9", "498.5"
      )
    )

    val result = JsonScaleReader.read(json)

    result.name should be (empty)
  }

  "A JSON Scale with an invalid pitch" should "throw InvalidJsonScaleException" in {
    val json = Json.obj(
      "name" -> "abc",
      "intervals" -> Json.arr(
        "204.3", "xxx", "498.5"
      )
    )

    assertThrows[InvalidJsonScaleException] {
      JsonScaleReader.read(json)
    }
  }
}

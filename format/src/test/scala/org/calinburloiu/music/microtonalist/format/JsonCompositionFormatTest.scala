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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation._
import org.calinburloiu.music.microtonalist.core._
import org.calinburloiu.music.microtonalist.format.ComponentPlayJsonFormat.SubComponentSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, Inside}
import play.api.libs.json._

import java.net.URI
import scala.collection.mutable
import scala.concurrent.Future

class JsonCompositionFormatTest extends AnyFlatSpec with Matchers with Inside with BeforeAndAfter with MockFactory {

  import FormatTestUtils.readCompositionFromResources
  import JsonCompositionFormat._

  private val urisOfReadScales: mutable.ArrayBuffer[URI] = mutable.ArrayBuffer()

  private lazy val compositionFormat: CompositionFormat = {
    val jsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor)
    val scaleFormatRegistry = new ScaleFormatRegistry(Seq(
      new HuygensFokkerScalaScaleFormat,
      jsonScaleFormat
    ))
    val scaleRepo = new FileScaleRepo(scaleFormatRegistry) {
      override def readAsync(uri: URI, context: Option[ScaleFormatContext]): Future[Scale[Interval]] = {
        val result = super.readAsync(uri, context)

        urisOfReadScales += uri

        result
      }
    }
    new JsonCompositionFormat(scaleRepo, NoJsonPreprocessor, jsonScaleFormat)
  }

  private lazy val compositionRepo: CompositionRepo = {
    new FileCompositionRepo(compositionFormat)
  }

  val majorScale: RatiosScale = RatiosScale("Major",
    (1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
  val naturalMinorScale: RatiosScale = RatiosScale("Natural Minor",
    (1, 1), (9, 8), (6, 5), (4, 3), (3, 2), (8, 5), (9, 5), (2, 1))
  val romanianMinorScale: RatiosScale = RatiosScale("Romanian Minor",
    (1, 1), (9, 8), (6, 5), (7, 5), (3, 2), (27, 16), (16, 9), (2, 1))
  val chromaticScale: RatiosScale = RatiosScale("Just Chromatic",
    (1, 1), (16, 15), (9, 8), (6, 5), (5, 4), (4, 3), (7, 5), (3, 2), (8, 5), (5, 3),
    (7, 4), (15, 8), (2, 1))

  after {
    urisOfReadScales.clear()
  }

  it should "take name from tuning spec context when reading an inline scale" in {
    val composition = readCompositionFromResources("format/inline-scale-with-name.mtlist", compositionRepo)

    // Takes name from context
    composition.tuningSpecs.head.scale shouldEqual CentsScale("maj-5", 0, 204, 386, 498, 702)

    // Uses a default name when no name is in context for an inline scale
    composition.globalFill.map(_.scale.name) should contain("")
  }

  it should "successfully read 72-EDO intervals in 72-EDO intonation standard" in {
    val composition = readCompositionFromResources("format/72-edo.mtlist", compositionRepo)

    composition.tuningSpecs.head.transposition shouldEqual EdoInterval(72, (4, -1))
    composition.tuningSpecs.head.scale shouldEqual EdoScale("segah-3", 72, (0, 0), (1, 1), (3, 1))

    // TODO #62 Also test tuningRef intervals
  }

  // TODO #68
  ignore should "successfully read just intonation intervals in 72-EDO intonation standard" in {
    val composition = readCompositionFromResources("format/72-edo.mtlist", compositionRepo)

    composition.tuningSpecs(1).transposition shouldEqual EdoInterval(72, (4, -1))
    composition.tuningSpecs(1).scale shouldEqual EdoScale("mustear-3", 72, (0, 0), (2, 0), (3, 1))
  }

  // TODO #62 Also test tuningRef intervals
  ignore should "successfully interpret concertPitchToBaseInterval from tuningReference in 72-EDO intonation " +
    "standard" in {
  }

  it should "fail to interpret EDO intervals in just intonation standard" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/intonation-standard-incompatibility.mtlist", compositionRepo)
    }
  }

  it should "successfully read a valid composition file" in {
    val composition = readCompositionFromResources("format/minor-major.mtlist", compositionRepo)

    composition.globalFill.map(_.scale) should contain(chromaticScale)
    composition.globalFill.map(_.transposition) should contain(1 /: 1)
    composition.tuningRef.basePitchClass.number shouldEqual 2

    composition.tuningSpecs.head.scale shouldEqual naturalMinorScale
    composition.tuningSpecs.head.transposition shouldEqual RatioInterval(1, 1)

    composition.tuningSpecs(1).transposition shouldEqual RatioInterval(6, 5)
    composition.tuningSpecs(1).scale shouldEqual majorScale

    composition.tuningSpecs(2).transposition shouldEqual RatioInterval(1, 1)
    composition.tuningSpecs(2).scale shouldEqual romanianMinorScale

    composition.metadata should contain(CompositionMetadata(
      name = Some("Minor & Major"),
      composerName = Some("Călin-Andrei Burloiu"),
      authorName = Some("John Doe")
    ))
  }

  it should "fail when a transposition interval in invalid" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/invalid-transposition-interval.mtlist", compositionRepo)
    }
  }

  it should "fail when a scale reference points to a non existent file" in {
    assertThrows[ScaleNotFoundException] {
      readCompositionFromResources("format/non-existent-scale-ref.mtlist", compositionRepo)
    }
  }

  it should "fail when a scale reference points to an invalid file" in {
    assertThrows[ScaleNotFoundException] {
      readCompositionFromResources("format/invalid-referenced-scale.mtlist", compositionRepo)
    }
  }

  it should "fail when a scale defined inside the composition is invalid" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/invalid-scale.mtlist", compositionRepo)
    }
  }

  it should "fail when a composition does not exist" in {
    assertThrows[CompositionNotFoundException] {
      compositionRepo.read(new URI("file:///Users/john/non-existent.mtlist"))
    }
  }

  it should "allow tuning specs without a scale property" in {
    val composition = readCompositionFromResources("format/accidentals.mtlist", compositionRepo)

    composition.tuningSpecs.head.transposition shouldEqual 16 /: 15
    composition.tuningSpecs.head.scale shouldEqual RatiosScale(1 /: 1)

    composition.tuningSpecs(1).transposition shouldEqual 5 /: 4
    composition.tuningSpecs(1).scale shouldEqual RatiosScale("hicaz", 1 /: 1)

    composition.tuningSpecs(2).transposition shouldEqual 1 /: 1
    composition.tuningSpecs(2).scale shouldEqual RatiosScale(1 /: 1)

    composition.tuningSpecs(3).transposition shouldEqual 1 /: 1
    composition.tuningSpecs(3).scale shouldEqual RatiosScale("dügah", 1 /: 1)
  }

  it should "read scales with the same URI once" in {
    readCompositionFromResources("format/same-scale-transposed.mtlist", compositionRepo)

    urisOfReadScales should have length 1
    urisOfReadScales.head.toString should endWith("scales/major.scl")
  }

  "TuningReducerPlayJsonFormat" should "deserialize JSON string containing type" in {
    inside(TuningReducerPlayJsonFormat.reads(JsString("direct"))) {
      case JsSuccess(value, _) => value shouldBe a[DirectTuningReducer]
    }
    inside(TuningReducerPlayJsonFormat.reads(JsString("merge"))) {
      case JsSuccess(value, _) => value shouldBe a[MergeTuningReducer]
    }
    TuningReducerPlayJsonFormat.reads(JsString("bogus")) shouldBe a[JsError]
  }

  it should "deserialize JSON object only containing type by using default factory" in {
    val directTuningReducerJson = Json.obj("type" -> "direct")
    inside(TuningReducerPlayJsonFormat.reads(directTuningReducerJson)) {
      case JsSuccess(value, _) => value shouldBe a[DirectTuningReducer]
    }

    val mergeTuningReducerJson = Json.obj("type" -> "merge")
    inside(TuningReducerPlayJsonFormat.reads(mergeTuningReducerJson)) {
      case JsSuccess(value, _) => value shouldBe a[MergeTuningReducer]
    }

    val bogusTuningReducerJson = Json.obj("type" -> "bogus")
    TuningReducerPlayJsonFormat.reads(bogusTuningReducerJson) shouldBe a[JsError]

    val invalidJson = Json.obj("typ" -> "direct")
    TuningReducerPlayJsonFormat.reads(invalidJson) shouldBe a[JsError]
  }

  it should "serialize JSON string containing types of direct and merge" in {
    TuningReducerPlayJsonFormat.writes(DirectTuningReducer()) shouldEqual JsString("direct")
    TuningReducerPlayJsonFormat.writes(MergeTuningReducer()) shouldEqual JsString("merge")
    a[Error] should be thrownBy TuningReducerPlayJsonFormat.writes(mock[TuningReducer])
  }

  "TuningMapperPlayJsonFormat" should "deserialize JSON object containing type auto and its params" in {
    val autoJsonWithParams = Json.obj(
      "type" -> "auto",
      "shouldMapQuarterTonesLow" -> true,
      "quarterToneTolerance" -> 0.02
    )
    inside(TuningMapperPlayJsonFormat.reads(autoJsonWithParams)) {
      case JsSuccess(tuningMapper, _) =>
        tuningMapper shouldBe a[AutoTuningMapper]
        val autoTuningMapper = tuningMapper.asInstanceOf[AutoTuningMapper]
        autoTuningMapper.shouldMapQuarterTonesLow shouldBe true
        autoTuningMapper.quarterToneTolerance shouldEqual 0.02
    }

    val autoJsonWithDefaultParams = Json.obj("type" -> "auto")
    inside(TuningMapperPlayJsonFormat.reads(autoJsonWithDefaultParams)) {
      case JsSuccess(tuningMapper, _) =>
        tuningMapper shouldBe a[AutoTuningMapper]
        val autoTuningMapper = tuningMapper.asInstanceOf[AutoTuningMapper]
        autoTuningMapper shouldEqual TuningMapper.Default
    }
  }

  it should "serialize JSON object for type auto" in {
    val actual = TuningMapperPlayJsonFormat.writes(AutoTuningMapper(shouldMapQuarterTonesLow = true,
      quarterToneTolerance = 0.1))
    val expected = Json.obj(
      "type" -> "auto",
      "shouldMapQuarterTonesLow" -> true,
      "quarterToneTolerance" -> 0.1
    )
    actual shouldEqual expected
  }

  "a ComponentPlayJsonFormat with mandatory params" should "fail when deserializing JSON without params" in {
    implicit val subComponentFormat: Format[SubComponent] = Json.format[SubComponent]
    val stubFormat = new ComponentPlayJsonFormat[Stub] {
      override val subComponentSpecs: Seq[ComponentPlayJsonFormat.SubComponentSpec[_ <: Stub]] = Seq(
        SubComponentSpec("foo", classOf[SubComponent], Some(subComponentFormat), None)
      )
    }

    val stubJsonStr = JsString("foo")
    val stubJsonObj = Json.obj("type" -> "foo")
    for (js <- Seq(stubJsonStr, stubJsonObj)) {
      inside(stubFormat.reads(js)) {
        case JsError(Seq((_, Seq(JsonValidationError(Seq(message)))))) =>
          message shouldEqual "error.component.params.missing"
      }
    }

    val invalidJs = JsNumber(3)
    inside(stubFormat.reads(invalidJs)) {
      case JsError(Seq((_, Seq(JsonValidationError(Seq(message)))))) =>
        message shouldEqual "error.component.invalid"
    }
  }
}

trait Stub

case class SubComponent(bar: Int, baz: Int) extends Stub

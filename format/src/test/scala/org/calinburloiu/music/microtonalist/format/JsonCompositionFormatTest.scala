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

import org.calinburloiu.music.intonation.{RatioInterval, RatiosScale}
import org.calinburloiu.music.microtonalist.core._
import org.calinburloiu.music.microtonalist.format.ComponentPlayJsonFormat.SubComponentSpec
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json._

import java.net.URI

class JsonCompositionFormatTest extends AnyFlatSpec with Matchers with Inside with MockFactory {

  import JsonCompositionFormat._
  import FormatTestUtils.readCompositionFromResources

  private lazy val compositionRepo = {
    val intonationStandardComponentFormat = IntonationStandardComponentFormat.componentJsonFormat
    val jsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor, intonationStandardComponentFormat)
    val scaleFormatRegistry = new ScaleFormatRegistry(Seq(
      new HuygensFokkerScalaScaleFormat,
      jsonScaleFormat
    ))
    val scaleRepo = new FileScaleRepo(scaleFormatRegistry)
    val compositionFormat = new JsonCompositionFormat(scaleRepo, NoJsonPreprocessor, jsonScaleFormat)
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

  it should "successfully read a valid composition file" in {
    val composition = readCompositionFromResources("format/minor_major.scalist", compositionRepo)

    composition.globalFill.map(_.scaleMapping.scale) should contain(chromaticScale)
    composition.tuningRef.basePitchClass.number shouldEqual 2

    composition.tuningSpecs.head.scaleMapping.scale shouldEqual naturalMinorScale
    composition.tuningSpecs.head.transposition shouldEqual RatioInterval(1, 1)

    composition.tuningSpecs(1).transposition shouldEqual RatioInterval(6, 5)
    composition.tuningSpecs(1).scaleMapping.scale shouldEqual majorScale

    composition.tuningSpecs(2).transposition shouldEqual RatioInterval(1, 1)
    composition.tuningSpecs(2).scaleMapping.scale shouldEqual romanianMinorScale
  }

  it should "fail when a transposition interval in invalid" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/invalid_transposition_interval.scalist", compositionRepo)
    }
  }

  it should "fail when a scale reference points to a non existent file" in {
    assertThrows[ScaleNotFoundException] {
      readCompositionFromResources("format/non_existent_scale_ref.scalist", compositionRepo)
    }
  }

  it should "fail when a scale reference points to an invalid file" in {
    assertThrows[ScaleNotFoundException] {
      readCompositionFromResources("format/invalid_referenced_scale.scalist", compositionRepo)
    }
  }

  it should "fail when a scale defined inside the composition is invalid" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/invalid_scale.scalist", compositionRepo)
    }
  }

  it should "fail when a composition does not exist" in {
    assertThrows[CompositionNotFoundException] {
      compositionRepo.read(new URI("file:///Users/john/non_existent.scalist"))
    }
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
      "mapQuarterTonesLow" -> true,
      "halfTolerance" -> 0.02
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
    val actual = TuningMapperPlayJsonFormat.writes(AutoTuningMapper(shouldMapQuarterTonesLow = true, quarterToneTolerance = 0.1))
    val expected = Json.obj(
      "type" -> "auto",
      "mapQuarterTonesLow" -> true,
      "halfTolerance" -> 0.1
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

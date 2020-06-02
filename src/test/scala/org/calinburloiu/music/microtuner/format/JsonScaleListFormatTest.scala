/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner.format

import java.nio.file.{Path, Paths}

import org.calinburloiu.music.intonation.format.{InvalidScaleFormatException, LocalScaleLibrary, ScaleFormatRegistry, ScaleNotFoundException}
import org.calinburloiu.music.intonation.{RatioInterval, RatiosScale}
import org.calinburloiu.music.microtuner.ScaleList
import org.calinburloiu.music.microtuner.format.ComponentPlayJsonFormat.SubComponentSpec
import org.calinburloiu.music.tuning._
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json._

class JsonScaleListFormatTest extends FlatSpec with Matchers with Inside with MockFactory {

  import JsonScaleListFormat._
  import JsonScaleListFormatTest._

  val majorScale: RatiosScale = RatiosScale("Major",
    (1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
  val naturalMinorScale: RatiosScale = RatiosScale("Natural Minor",
    (1, 1), (9, 8), (6, 5), (4, 3), (3, 2), (8, 5), (9, 5), (2, 1))
  val romanianMinorScale: RatiosScale = RatiosScale("Romanian Minor",
    (1, 1), (9, 8), (6, 5), (7, 5), (3, 2), (27, 16), (16, 9), (2, 1))
  val chromaticScale: RatiosScale = RatiosScale("Just Chromatic",
    (1, 1), (16, 15), (9, 8), (6, 5), (5, 4), (4, 3), (7, 5), (3, 2), (8, 5), (5, 3),
    (7, 4), (15, 8), (2, 1))

  it should "successfully read a valid scale list file" in {
    val scaleList = readScaleListFromResources("scale_lists/minor_major.scalist")

    scaleList.globalFill.scale shouldEqual chromaticScale
    scaleList.origin.basePitchClass.semitone shouldEqual 2

    scaleList.modulations.head.scaleMapping.scale shouldEqual naturalMinorScale
    scaleList.modulations.head.transposition shouldEqual RatioInterval(1, 1)
    scaleList.modulations.head.fill.map(_.scale) should contain(
      RatiosScale((1, 1), (9, 7))
    )

    scaleList.modulations(1).transposition shouldEqual RatioInterval(6, 5)
    scaleList.modulations(1).scaleMapping.scale shouldEqual majorScale
    scaleList.modulations(1).fill.map(_.scale) should contain(chromaticScale)

    scaleList.modulations(2).transposition shouldEqual RatioInterval(5, 3)
    scaleList.modulations(2).scaleMapping.scale shouldEqual romanianMinorScale
    scaleList.modulations(2).fill.map(_.scale) should be(empty)
  }

  it should "fail when a transposition interval in invalid" in {
    assertThrows[InvalidScaleListFormatException] {
      readScaleListFromResources("scale_lists/invalid_transposition_interval.scalist")
    }
  }

  it should "fail when a scale reference points to a non existent file" in {
    assertThrows[ScaleNotFoundException] {
      readScaleListFromResources("scale_lists/non_existent_scale_ref.scalist")
    }
  }

  it should "fail when a scale reference points to an invalid file" in {
    assertThrows[InvalidScaleFormatException] {
      readScaleListFromResources("scale_lists/invalid_referenced_scale.scalist")
    }
  }

  it should "fail when a scale defined inside the scale list is invalid" in {
    assertThrows[InvalidScaleListFormatException] {
      readScaleListFromResources("scale_lists/invalid_scale.scalist")
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
    TuningReducerPlayJsonFormat.writes(new DirectTuningReducer) shouldEqual JsString("direct")
    TuningReducerPlayJsonFormat.writes(new MergeTuningReducer) shouldEqual JsString("merge")
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
        autoTuningMapper.pitchClassConfig shouldEqual
          PitchClassConfig(mapQuarterTonesLow = true, halfTolerance = 0.02)
    }

    val autoJsonWithDefaultParams = Json.obj("type" -> "auto")
    inside(TuningMapperPlayJsonFormat.reads(autoJsonWithDefaultParams)) {
      case JsSuccess(tuningMapper, _) =>
        tuningMapper shouldBe a[AutoTuningMapper]
        val autoTuningMapper = tuningMapper.asInstanceOf[AutoTuningMapper]
        autoTuningMapper.pitchClassConfig shouldEqual PitchClassConfig()
    }
  }

  it should "serialize JSON object for type auto" in {
    val actual = TuningMapperPlayJsonFormat.writes(new AutoTuningMapper(
      PitchClassConfig(mapQuarterTonesLow = true, halfTolerance = 0.1)))
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

object JsonScaleListFormatTest {

  val scaleLibraryPath: Path = Paths.get(getClass.getClassLoader.getResource("scales/").getFile)

  def readScaleListFromResources(path: String): ScaleList = {
    val inputStream = getClass.getClassLoader.getResourceAsStream(path)
    val scaleListReader = new JsonScaleListFormat(new LocalScaleLibrary(ScaleFormatRegistry, scaleLibraryPath))

    scaleListReader.read(inputStream)
  }
}

trait Stub

case class SubComponent(bar: Int, baz: Int) extends Stub

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

package org.calinburloiu.music.microtuner

import java.nio.file.{Path, Paths}

import org.calinburloiu.music.intonation.io.{InvalidScaleFormatException, LocalScaleLibrary, ScaleNotFoundException, ScaleReaderRegistry}
import org.calinburloiu.music.intonation.{RatioInterval, RatiosScale}
import org.calinburloiu.music.microtuner.io.{InvalidScaleListFileException, JsonScaleListReader}
import org.calinburloiu.music.tuning.{TuningListReducerRegistry, TuningMapperRegistry}
import org.scalatest.{FlatSpec, Matchers}

class JsonScaleListReaderTest extends FlatSpec with Matchers {
  import JsonScaleListReaderTest._

  val majorScale = RatiosScale("Major",
    (1, 1), (9, 8), (5, 4), (4, 3), (3, 2), (5, 3), (15, 8), (2, 1))
  val naturalMinorScale = RatiosScale("Natural Minor",
    (1, 1), (9, 8), (6, 5), (4, 3), (3, 2), (8, 5), (9, 5), (2, 1))
  val romanianMinorScale = RatiosScale("Romanian Minor",
    (1, 1), (9, 8), (6, 5), (7, 5), (3, 2), (27, 16), (16, 9), (2, 1))
  val chromaticScale = RatiosScale("Just Chromatic",
    (1, 1), (16, 15), (9, 8), (6, 5), (5, 4), (4, 3), (7, 5), (3, 2), (8, 5), (5, 3),
    (7, 4), (15, 8), (2, 1))

  it should "successfully read a valid scale list file" in {
    val scaleList = readScaleListFromResources("scale_lists/minor_major.scalist")

    scaleList.globalFill.scale shouldEqual chromaticScale
    scaleList.origin.basePitchClass.semitone shouldEqual 2

    scaleList.modulations.head.scaleMapping.scale shouldEqual naturalMinorScale
    scaleList.modulations.head.transposition shouldEqual RatioInterval(1, 1)
    scaleList.modulations.head.fill.map(_.scale) should contain (
      RatiosScale((1, 1), (9, 7))
    )

    scaleList.modulations(1).transposition shouldEqual RatioInterval(6, 5)
    scaleList.modulations(1).scaleMapping.scale shouldEqual majorScale
    scaleList.modulations(1).fill.map(_.scale) should contain (chromaticScale)

    scaleList.modulations(2).transposition shouldEqual RatioInterval(5, 3)
    scaleList.modulations(2).scaleMapping.scale shouldEqual romanianMinorScale
    scaleList.modulations(2).fill.map(_.scale) should be (empty)
  }

  it should "fail when a transposition interval in invalid" in {
    assertThrows[InvalidScaleListFileException] {
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
    assertThrows[InvalidScaleListFileException] {
      readScaleListFromResources("scale_lists/invalid_scale.scalist")
    }
  }
}

object JsonScaleListReaderTest {

  val scaleLibraryPath: Path = Paths.get(getClass.getClassLoader.getResource("scales/").getFile)

  def readScaleListFromResources(path: String): ScaleList = {
    val inputStream = getClass.getClassLoader.getResourceAsStream(path)
    val scaleListReader = new JsonScaleListReader(new LocalScaleLibrary(ScaleReaderRegistry, scaleLibraryPath),
      new TuningMapperRegistry, new TuningListReducerRegistry)

    scaleListReader.read(inputStream)
  }
}

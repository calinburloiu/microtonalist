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
import org.calinburloiu.music.microtonalist.composition._
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, Inside}

import java.net.URI
import scala.collection.mutable
import scala.concurrent.Future

class JsonCompositionFormatTest extends AnyFlatSpec with Matchers with Inside with BeforeAndAfter with MockFactory {

  import FormatTestUtils.readCompositionFromResources

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
    composition.fill.global.map(_.scale.name) should contain("")
  }

  it should "successfully read 72-EDO intervals in 72-EDO intonation standard" in {
    val composition = readCompositionFromResources("format/72-edo.mtlist", compositionRepo)

    composition.tuningReference shouldBe a[ConcertPitchTuningReference]
    val concertPitchTuningReference = composition.tuningReference.asInstanceOf[ConcertPitchTuningReference]
    concertPitchTuningReference.concertPitchToBaseInterval shouldEqual EdoInterval(72, -53)
    concertPitchTuningReference.baseMidiNote shouldEqual MidiNote(60)

    composition.tuningSpecs.head.transposition shouldEqual EdoInterval(72, (4, -1))
    composition.tuningSpecs.head.scale shouldEqual EdoScale("segah-3", 72, (0, 0), (1, 1), (3, 1))
  }

  // TODO #68
  ignore should "successfully read just intonation intervals in 72-EDO intonation standard" in {
    val composition = readCompositionFromResources("format/72-edo.mtlist", compositionRepo)

    composition.tuningSpecs(1).transposition shouldEqual EdoInterval(72, (4, -1))
    composition.tuningSpecs(1).scale shouldEqual EdoScale("mustear-3", 72, (0, 0), (2, 0), (3, 1))
  }

  it should "fail to interpret EDO intervals in just intonation standard" in {
    assertThrows[InvalidCompositionFormatException] {
      readCompositionFromResources("format/intonation-standard-incompatibility.mtlist", compositionRepo)
    }
  }

  it should "successfully read a valid composition file" in {
    val composition = readCompositionFromResources("format/minor-major.mtlist", compositionRepo)

    composition.fill.global.map(_.scale) should contain(chromaticScale)
    composition.fill.global.map(_.transposition) should contain(1 /: 1)
    composition.tuningReference.basePitchClass.number shouldEqual 2

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

  it should "read a default TuningMapper from settings if omitted in TuningSpec" in {
    val composition = readCompositionFromResources("format/default-tuningMapper-from-settings.mtlist", compositionRepo)

    composition.intonationStandard shouldEqual EdoIntonationStandard(72)
    composition.tuningReference shouldEqual StandardTuningReference(PitchClass.C)

    composition.tuningSpecs.size shouldEqual 1

    val tuningSpec = composition.tuningSpecs.head
    tuningSpec.transposition shouldEqual EdoInterval(72, (2, 0))
    tuningSpec.scale shouldEqual EdoScale("Soft Karcığar", 72,
      (0, 0), (2, -3), (3, 0), (5, 0), (6, 3), (9, -3), (10, 0))
    tuningSpec.tuningMapper shouldEqual AutoTuningMapper(
      shouldMapQuarterTonesLow = true,
      quarterToneTolerance = 17.0,
      softChromaticGenusMapping = SoftChromaticGenusMapping.PseudoChromatic
    )
  }
}

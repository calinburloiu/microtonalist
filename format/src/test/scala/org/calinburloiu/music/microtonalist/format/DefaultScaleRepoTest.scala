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
import org.calinburloiu.music.intonation.{
  CentsIntonationStandard, CentsScale, EdoIntonationStandard, EdoScale,
  Interval, JustIntonationStandard, RatiosScale, Scale
}
import org.scalamock.scalatest.AbstractMockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DefaultScaleRepoTest extends AnyFlatSpec with Matchers with AbstractMockFactory {
  private lazy val scaleRepo: DefaultScaleRepo = {
    val jsonScaleFormat = new JsonScaleFormat(NoJsonPreprocessor)
    val scaleFormatRegistry = new ScaleFormatRegistry(Seq(jsonScaleFormat))
    val fileScaleRepo = new FileScaleRepo(scaleFormatRegistry)
    new DefaultScaleRepo(fileScaleRepo, stub[HttpScaleRepo], stub[LibraryScaleRepo])
  }

  private val minorScaleCents = CentsScale("Natural Minor", 0.0,
    203.91, 315.64, 498.04, 701.96, 813.69, 1017.60, 1200.00)
  private val minorScaleJust = RatiosScale("Natural Minor",
    1 /: 1, 9 /: 8, 6 /: 5, 4 /: 3, 3 /: 2, 8 /: 5, 9 /: 5, 2 /: 1)
  private val minorScale72Edo = EdoScale("Natural Minor", 72,
    (0, 0), (2, 0), (3, 1), (5, 0), (7, 0), (8, 1), (10, 1), (12, 0))
  private val minorScale31Edo = EdoScale("Natural Minor", 31, 0, 5, 8, 13, 18, 21, 26, 31)

  private val minorScaleCentsPath = "format/scales/minor-cents.jscl"
  private val minorScaleJustPath = "format/scales/minor-just.jscl"
  private val minorScale72EdoPath = "format/scales/minor-72edo.jscl"

  private val centsContext = Some(ScaleFormatContext(intonationStandard = Some(CentsIntonationStandard)))
  private val justIntonationContext = Some(ScaleFormatContext(intonationStandard = Some(JustIntonationStandard)))
  private val edo72Context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(72))))
  private val edo31Context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(31))))

  "read" should "read a JSON Scale file in cents intonation standard and convert it according to the context" in {
    // No context
    var result: Scale[Interval] = FormatTestUtils.readScaleFromResources(minorScaleCentsPath, scaleRepo, None)
    result almostEquals minorScaleCents shouldBe true
    result.intonationStandard should contain(CentsIntonationStandard)

    // Cents context
    result = FormatTestUtils.readScaleFromResources(minorScaleCentsPath, scaleRepo, centsContext)
    result almostEquals minorScaleCents shouldBe true
    result.intonationStandard should contain(CentsIntonationStandard)

    // Just intonation context
    assertThrows[IncompatibleIntervalsScaleFormatException] {
      FormatTestUtils.readScaleFromResources(minorScaleCentsPath, scaleRepo, justIntonationContext)
    }

    // 72-EDO context
    result = FormatTestUtils.readScaleFromResources(minorScaleCentsPath, scaleRepo, edo72Context)
    result shouldEqual minorScale72Edo
    result.intonationStandard should contain(EdoIntonationStandard(72))
  }

  it should "read a JSON Scale file in just intonation standard and convert it according to the context" in {
    // No context
    var result: Scale[Interval] = FormatTestUtils.readScaleFromResources(minorScaleJustPath, scaleRepo, None)
    result shouldEqual minorScaleJust
    result.intonationStandard should contain(JustIntonationStandard)

    // Cents context
    result = FormatTestUtils.readScaleFromResources(minorScaleJustPath, scaleRepo, centsContext)
    result almostEquals minorScaleCents shouldBe true
    result.intonationStandard should contain(CentsIntonationStandard)

    // Just intonation context
    result = FormatTestUtils.readScaleFromResources(minorScaleJustPath, scaleRepo, justIntonationContext)
    result shouldEqual minorScaleJust
    result.intonationStandard should contain(JustIntonationStandard)

    // 72-EDO context
    result = FormatTestUtils.readScaleFromResources(minorScaleJustPath, scaleRepo, edo72Context)
    result shouldEqual minorScale72Edo
    result.intonationStandard should contain(EdoIntonationStandard(72))
  }

  it should "read a JSON Scale file in 72-EDO intonation standard and convert it according to the context" in {
    // No context
    var result: Scale[Interval] = FormatTestUtils.readScaleFromResources(minorScale72EdoPath, scaleRepo, None)
    result shouldEqual minorScale72Edo
    result.intonationStandard should contain(EdoIntonationStandard(72))

    // Cents context
    val expectedResult = CentsScale("Natural Minor", 0.0, 200.00, 316.67, 500.00, 700.00, 816.67, 1016.67, 1200.00)
    result = FormatTestUtils.readScaleFromResources(minorScale72EdoPath, scaleRepo, centsContext)
    result almostEquals expectedResult shouldBe true
    result.intonationStandard should contain(CentsIntonationStandard)

    // Just intonation context
    assertThrows[IncompatibleIntervalsScaleFormatException] {
      FormatTestUtils.readScaleFromResources(minorScale72EdoPath, scaleRepo, justIntonationContext)
    }

    // 72-EDO context
    result = FormatTestUtils.readScaleFromResources(minorScale72EdoPath, scaleRepo, edo72Context)
    result shouldEqual minorScale72Edo
    result.intonationStandard should contain(EdoIntonationStandard(72))

    // 72-EDO context
    result = FormatTestUtils.readScaleFromResources(minorScale72EdoPath, scaleRepo, edo31Context)
    result shouldEqual minorScale31Edo
    result.intonationStandard should contain(EdoIntonationStandard(31))
  }

  it should "override the name read from the file with the one from the context" in {
    val context = Some(ScaleFormatContext(name = Some("minor")))
    val result: Scale[Interval] = FormatTestUtils.readScaleFromResources(minorScaleJustPath, scaleRepo, context)
    result shouldEqual RatiosScale("minor", minorScaleJust.intervals)
  }
}

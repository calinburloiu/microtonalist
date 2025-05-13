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

import com.google.common.eventbus.{EventBus, Subscribe}
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.intonation.*
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScaleContextConverterTest extends AnyFlatSpec, Matchers, BeforeAndAfter {
  private val businessync: Businessync = Businessync(EventBus())
  private val scaleContextConverter = new ScaleContextConverter(businessync)

  private val epsilon: Double = 1e-1
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  private val min4CentsScale = CentsScale("min-4", 0.0, 204.3, 315.9, 498.5)
  private val maj4RatiosScale = RatiosScale("maj-4", (15, 16), (1, 1), (9, 8), (5, 4), (4, 3))
  private val hicaz4Edo53Scale = EdoScale("hicaz-4", 53, 0, 5, 17, 22)
  private val hicaz4Edo72Scale = EdoScale("hicaz-4", 72, (0, 0), (1, +1), (4, -1), (5, 0))

  private var lastLossyReport: Option[ScaleLossyConversionEvent] = None

  businessync.register(this)
  businessync.subscribe(classOf[ScaleLossyConversionEvent], onLossyConversion)

  @Subscribe
  def onLossyConversion(event: ScaleLossyConversionEvent): Unit = {
    lastLossyReport = Some(event)
  }

  after {
    lastLossyReport = None
  }

  it should "override the scale name with the one from the context" in {
    // Given
    val context = Some(ScaleFormatContext(name = Some("blah")))
    // When
    val result = scaleContextConverter.convert(min4CentsScale, context)
    // Then
    result.name shouldEqual "blah"
  }

  it should "convert a cents scale to 72-EDO intonation standard provided in the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(72))))
    // When
    val result = scaleContextConverter.convert(min4CentsScale, context)
    // Then
    result.intonationStandard should contain(EdoIntonationStandard(72))
    result shouldEqual EdoScale("min-4", 72, (0, 0), (2, 0), (3, 1), (5, 0))
    lastLossyReport should contain(ScaleLossyConversionEvent(Some(CentsIntonationStandard),
      EdoIntonationStandard(72), "min-4"))
  }

  it should "fail to convert a cents scale to just intonation standard provided in the" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(JustIntonationStandard)))
    // Then
    assertThrows[IncompatibleIntervalsScaleFormatException] {
      scaleContextConverter.convert(min4CentsScale, context)
    }
  }

  it should "convert a just scale to cents intonation standard provided in the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(CentsIntonationStandard)))
    // When
    val result = scaleContextConverter.convert(maj4RatiosScale, context)
    // Then
    result.name shouldEqual "maj-4"
    result.intonationStandard should contain(CentsIntonationStandard)
    result.intervals.map(_.cents) should contain theSameElementsAs Seq(-111.7, 0.0, 203.9, 386.3, 498.0)
    lastLossyReport shouldBe empty
  }

  it should "convert a just scale to 72-EDO intonation standard provided in the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(72))))
    // When
    val result = scaleContextConverter.convert(maj4RatiosScale, context)
    // Then
    result.intonationStandard should contain(EdoIntonationStandard(72))
    result shouldEqual EdoScale("maj-4", 72, (-1, -1), (0, 0), (2, 0), (4, -1), (5, 0))
    lastLossyReport should contain(ScaleLossyConversionEvent(Some(JustIntonationStandard),
      EdoIntonationStandard(72), "maj-4"))
  }

  it should "convert a 53-EDO scale to cents intonation standard provided in the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(CentsIntonationStandard)))
    // When
    val result = scaleContextConverter.convert(hicaz4Edo53Scale, context)
    // Then
    result.intonationStandard should contain(CentsIntonationStandard)
    result.name shouldEqual "hicaz-4"
    result.intervals.map(_.cents) should contain theSameElementsAs Seq(0.0, 113.2, 384.9, 498.1)
    lastLossyReport shouldBe empty
  }

  it should "convert a 53-EDO scale to 72-EDO intonation standard provided in the context" in {
    // Given
    val context = Some(ScaleFormatContext(intonationStandard = Some(EdoIntonationStandard(72))))
    // When
    val result = scaleContextConverter.convert(hicaz4Edo53Scale, context)
    // Then
    result.intonationStandard should contain(EdoIntonationStandard(72))
    result shouldEqual hicaz4Edo72Scale
    lastLossyReport should contain(ScaleLossyConversionEvent(Some(EdoIntonationStandard(53)),
      EdoIntonationStandard(72), "hicaz-4"))
  }

  it should "not do any conversion and return the same scale if no context is provided" in {
    // When
    val result = scaleContextConverter.convert(maj4RatiosScale, None)
    // Then
    result shouldBe theSameInstanceAs(maj4RatiosScale)
  }

  it should "not do any conversion and return the same scale if the context has no name and intonation standard" in {
    // Given
    val context = Some(ScaleFormatContext(None, None))
    // When
    val result = scaleContextConverter.convert(maj4RatiosScale, None)
    // Then
    result shouldBe theSameInstanceAs(maj4RatiosScale)
  }
}

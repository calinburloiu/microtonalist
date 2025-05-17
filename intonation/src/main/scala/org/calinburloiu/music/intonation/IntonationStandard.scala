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

package org.calinburloiu.music.intonation

/**
 * Class that specified how intervals are expressed or interpreted: as values in cents, as just intonation ratios or
 * as the number of divisions in a particular EDO tuning.
 *
 * @param typeName Identifier of the intonation standard type.
 */
sealed abstract class IntonationStandard(val typeName: String) {
  def unison: Interval

  /**
   * Determines the quality of an interval or scale conversion when transitioning from the current `IntonationStandard`
   * to a different `IntonationStandard`.
   *
   * @param that The target `IntonationStandard` to which the conversion is assessed.
   * @return the `IntonationConversionQuality` representing the quality of the conversion.
   */
  def conversionQualityTo(that: IntonationStandard): IntonationConversionQuality = that match {
    case _: IntonationStandard if this == that => IntonationConversionQuality.NoConversion
    case CentsIntonationStandard => IntonationConversionQuality.Lossless
    case JustIntonationStandard => IntonationConversionQuality.Impossible
    case EdoIntonationStandard(thatEdo) => this match {
      case EdoIntonationStandard(thisEdo) if thatEdo % thisEdo == 0 => IntonationConversionQuality.Lossless
      case _ => IntonationConversionQuality.Lossy
    }
  }
}

/**
 * Intonation standard which specifies that intervals are expressed or interpreted in cents.
 */
case object CentsIntonationStandard extends IntonationStandard("cents") {
  override def unison: Interval = CentsInterval.Unison
}

/**
 * Intonation standard which specifies that intervals are expressed as just intonation ratios.
 */
case object JustIntonationStandard extends IntonationStandard("justIntonation") {
  override def unison: Interval = RatioInterval.Unison
}

/**
 * Intonation standard which specifies that intervals are expressed or interpreted as the number of divisions in a
 * particular EDO tuning.
 */
case class EdoIntonationStandard(countPerOctave: Int) extends IntonationStandard("edo") {
  require(countPerOctave > 0)

  override def unison: Interval = EdoInterval.unisonFor(countPerOctave)
}

object EdoIntonationStandard {
  val typeName = "edo"
}

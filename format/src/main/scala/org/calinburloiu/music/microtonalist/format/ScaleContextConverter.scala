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

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.businessync.{Businessync, BusinessyncEvent}
import org.calinburloiu.music.intonation.*

/**
 * Provides functionality to convert a scale to a specified intonation standard or to rename it based on the provided
 * [[ScaleFormatContext]].
 *
 * If a conversion is lossy, a [[ScaleLossyConversionEvent]] is published to [[Businessync]].
 *
 * @param businessync A dependency used to publish events when a lossy conversion occurs.
 */
class ScaleContextConverter(businessync: Businessync) extends LazyLogging {

  /**
   * Converts a scale to a specified intonation standard or renames it based on the provided context.
   *
   * @param scale   The scale to be converted or renamed.
   * @param context An optional context that may include a new name and/or an intonation standard to convert to.
   * @return The converted or renamed scale.
   */
  def convert(scale: Scale[Interval], context: Option[ScaleFormatContext]): Scale[Interval] = context match {
    case Some(ScaleFormatContext(maybeName, maybeIntonationStandard)) =>
      val renamedScale = maybeName.map(scale.rename).getOrElse(scale)

      maybeIntonationStandard.map { toIntonationStandard =>
        renamedScale.convertToIntonationStandard(toIntonationStandard) match {
          case ScaleConversionResult(Some(convertedScale), conversionQuality) =>
            if (conversionQuality == IntonationConversionQuality.Lossy) {
              reportLossyConversion(scale.intonationStandard, toIntonationStandard, scale.name)
            }

            convertedScale
          case _ => throw new IncompatibleIntervalsScaleFormatException
        }
      }.getOrElse(renamedScale)
    case None => scale
  }

  private def reportLossyConversion(fromIntonationStandard: Option[IntonationStandard],
                                    toIntonationStandard: IntonationStandard,
                                    scaleName: String): Unit = {
    logger.warn(s"Conversion of scale \"$scaleName\" from ${fromIntonationStandard.getOrElse("N/A")} " +
      s"to $toIntonationStandard is lossy!")
    businessync.publish(ScaleLossyConversionEvent(fromIntonationStandard, toIntonationStandard, scaleName))
  }
}

/**
 * Event triggered when a scale is converted between different intonation standards,
 * potentially losing precision or fidelity during the process.
 *
 * @param fromIntonationStandard The original `IntonationStandard` of the scale before conversion.
 *                               None if no standard is specified (in case of a scale with different types of
 *                               intervals).
 * @param toIntonationStandard   The target `IntonationStandard` to which the scale is converted.
 * @param scaleName              The name of the scale being converted.
 */
case class ScaleLossyConversionEvent(fromIntonationStandard: Option[IntonationStandard],
                                     toIntonationStandard: IntonationStandard,
                                     scaleName: String) extends BusinessyncEvent

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

class ScaleContextConverter(businessync: Businessync) extends LazyLogging {

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

sealed abstract class ScaleContextConverterEvent extends BusinessyncEvent

case class ScaleLossyConversionEvent(fromIntonationStandard: Option[IntonationStandard],
                                     toIntonationStandard: IntonationStandard,
                                     scaleName: String) extends ScaleContextConverterEvent

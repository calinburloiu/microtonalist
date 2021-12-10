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

import org.calinburloiu.music.intonation._

import java.io.{InputStream, OutputStream}

trait ScaleFormat {

  def read(inputStream: InputStream): Scale[Interval]

  def write(scale: Scale[Interval]): OutputStream
}

object ScaleFormat {

  private[format] def createScale(name: String, pitches: Seq[Interval]): Scale[Interval] = {
    val hasUnison = pitches.headOption.exists(interval => interval.isUnison)

    // TODO #4 Handle the case when pitches are EdoIntervals
    if (pitches.isEmpty) {
      Scale(name, CentsInterval(1.0))
    } else if (pitches.forall(_.isInstanceOf[CentsInterval])) {
      val resultPitches = if (hasUnison) pitches else CentsInterval.Unison +: pitches
      CentsScale(name, resultPitches.map(_.asInstanceOf[CentsInterval]))
    } else if (pitches.forall(_.isInstanceOf[RatioInterval])) {
      val resultPitches = if (hasUnison) pitches else RatioInterval.Unison +: pitches
      RatiosScale(name, resultPitches.map(_.asInstanceOf[RatioInterval]))
    } else {
      val resultPitches = if (hasUnison) pitches else RealInterval.Unison +: pitches
      Scale(name, resultPitches)
    }
  }
}

class InvalidScaleFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)

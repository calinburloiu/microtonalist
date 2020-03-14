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

package org.calinburloiu.music.intonation.io

import java.io.{FileInputStream, InputStream}
import java.nio.file.Path

import org.calinburloiu.music.intonation._

// TODO The first pitch is always unison, even if it is not explicit in the file input
trait ScaleReader {

  def read(inputStream: InputStream): Scale[Interval]
}

object ScaleReader {

  private[intonation] def createScale(name: String, pitches: Seq[Interval]): Scale[Interval] = {
    val hasUnison = pitches.headOption.contains(Interval(1.0))

    if (pitches.isEmpty) {
      Scale(name, Interval(1.0))
    } else if (pitches.forall(_.isInstanceOf[CentsInterval])) {
      val resultPitches = if (hasUnison) pitches else CentsInterval(0.0) +: pitches
      CentsScale(name, resultPitches.map(_.asInstanceOf[CentsInterval]))
    } else if (pitches.forall(_.isInstanceOf[RatioInterval])) {
      val resultPitches = if (hasUnison) pitches else RatioInterval(1, 1) +: pitches
      RatiosScale(name, resultPitches.map(_.asInstanceOf[RatioInterval]))
    } else {
      val resultPitches = if (hasUnison) pitches else Interval(1.0) +: pitches
      Scale(name, resultPitches)
    }
  }
}

class InvalidScaleFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)

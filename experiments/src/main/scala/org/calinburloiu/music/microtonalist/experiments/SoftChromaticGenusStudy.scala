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

package org.calinburloiu.music.microtonalist.experiments

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{EdoIntonationStandard, Interval, RatiosScale, Scale}

class SoftChromaticGenusStudy {
  private val maj3SoftHicaz: RatiosScale = RatiosScale("Maj3 Soft Hicaz", 1 /: 1, 12 /: 11, 5 /: 4, 4 /: 3)
  private val lim11SoftHicaz: RatiosScale = RatiosScale("11-limit Soft Hicaz", 1 /: 1, 88 /: 81, 27 /: 22, 4 /: 3)
  private val lim11SofterHicaz: RatiosScale = RatiosScale("11-limit Softer Hicaz", 1 /: 1, 12 /: 11, 27 /: 22, 4 /: 3)

  private val goodEdos: Seq[Int] =  Seq(12, 19, 22, 27, 31, 34, 41, 46, 53, 58, 60, 65, 68, 72, 77, 80, 84, 87, 94, 99)

  private val strictAug2Threshold: Double = 210.0
  private val pseudoChromaticAug2Threshold: Double = 190.0

  def printStruct(scale: Scale[Interval], aug2Threshold: Double): Unit = {
    def formatIntervals(intervals: Seq[Interval]): String = {
      intervals.map { interval =>
        String.format(java.util.Locale.US, "%.2f", interval.cents)
      }.mkString("[", ", ", "]")
    }

    def checkThresholds(recurrentIntervals: Seq[Interval]): (Boolean, Boolean, Boolean) = {
      val Seq(i1, i2, i3) = recurrentIntervals
      def checkQuarterTone(interval: Interval) = interval.cents <= 163.0

      (checkQuarterTone(i1), i2.cents >= aug2Threshold, checkQuarterTone(i3))
    }

    val recurrentIntervals = scale.relativeIntervals
    val checks = checkThresholds(recurrentIntervals)
    val mainCheck = if ((checks._1 || checks._3) && checks._2) "x" else " "

    println(s"[$mainCheck] ${formatIntervals(scale.intervals)}\t${formatIntervals(recurrentIntervals)}\t$checks\t" +
      s"${scale.intonationStandard.get}\t${scale.name}")
  }

  def printStructForAllEdos(scale: Scale[Interval], aug2Threshold: Double, edos: Seq[Int]): Unit = {
    for (edo <- edos) {
      val intonationStandard = EdoIntonationStandard(edo)
      val edoScale = scale.convertToIntonationStandard(intonationStandard).get.scale

      printStruct(edoScale, aug2Threshold)
    }
  }
}

object SoftChromaticGenusStudy extends App {
  private val study = new SoftChromaticGenusStudy
  import study._

  println("== Just Intonation ==")
  printStruct(maj3SoftHicaz, strictAug2Threshold)
  printStruct(lim11SoftHicaz, strictAug2Threshold)
  printStruct(lim11SofterHicaz, pseudoChromaticAug2Threshold)

  println("== EDO ==")
  printStructForAllEdos(maj3SoftHicaz, strictAug2Threshold, goodEdos)
  printStructForAllEdos(lim11SoftHicaz, strictAug2Threshold, goodEdos)
  printStructForAllEdos(lim11SofterHicaz, pseudoChromaticAug2Threshold, goodEdos)
}

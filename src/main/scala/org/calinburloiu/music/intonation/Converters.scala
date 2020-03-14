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

package org.calinburloiu.music.intonation

import java.math.RoundingMode

import com.google.common.base.Preconditions.checkArgument
import com.google.common.math.{DoubleMath, IntMath}

object Converters {

  def fromRealValueToCents(realValue: Double): Double = {
    checkArgument(realValue > 0.0, "Expecting positive realValue, but got %s", realValue)

    1200.0 * Math.log(realValue) / Math.log(2)
  }

  def fromRatioToCents(numerator: Int, denominator: Int): Double = {
    checkArgument(numerator > 0.0, "Expecting positive numerator, but got %s", numerator)
    checkArgument(denominator > 0.0, "Expecting positive denominator, but got %s", denominator)

    fromRealValueToCents(numerator.toDouble / denominator.toDouble)
  }

  def fromCentsToRealValue(cents: Double): Double = Math.pow(2, cents / 1200)

  def fromCentsToHz(cents: Double, baseFreqHz: Double): Double = {
    checkArgument(baseFreqHz > 0.0, "Expecting positive baseFreqHz, but got %s", baseFreqHz)

    baseFreqHz * fromCentsToRealValue(cents)
  }

  def fromHzToCents(freqHz: Double, baseFreqHz: Double): Double = {
    checkArgument(freqHz > 0.0, "Expecting positive freqHz, but got %s", freqHz)
    checkArgument(baseFreqHz > 0.0, "Expecting positive baseFreqHz, but got %s", baseFreqHz)

    fromRealValueToCents(freqHz / baseFreqHz)
  }
}

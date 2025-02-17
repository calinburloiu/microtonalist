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

package org.calinburloiu.music.microtonalist

package object composition {
  /**
   * Error allowed (inclusive) when detecting quarter-tone values.
   *
   * A perfect quarter-tone `q` is an odd multiple of 50 cents. When a tolerance is allowed with this constant its
   * value can be between `q - DefaultQuarterToneTolerance` and `q + DefaultQuarterToneTolerance`, inclusive.
   */
  val DefaultQuarterToneTolerance: Double = 13.0

  /**
   * Rounds a [[Double]] value to the nearest [[Int]] neighbor value. If the neighbors are close to equidistant with
   * respect to the value, then it is rounded according to `halfDown` parameter. How close to equidistant the value
   * can be is controlled by `halfTolerance` parameter.
   *
   * @param value         number to round
   * @param halfDown      true to round towards negative infinity when the value is close to equidistant to its integer
   *                      neighbors, or false otherwise
   * @param halfTolerance how close to equidistant between two integer neighbors the value can be
   * @return
   */
  def roundWithTolerance(value: Double, halfDown: Boolean, halfTolerance: Double): Int = {
    val floorValue = Math.floor(value)
    val fractional = value - floorValue

    if (fractional == 0.5 || fractional >= 0.5 - halfTolerance && fractional <= 0.5 + halfTolerance) {
      if (halfDown) floorValue.toInt else Math.ceil(value).toInt
    } else {
      Math.round(value).toInt
    }
  }
}

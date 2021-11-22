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

package org.calinburloiu.music

package object tuning {
  def roundWithTolerance(value: Double, floorHalf: Boolean, halfTolerance: Double): Int = {
    val fractional = value - Math.floor(value)
    val lowThreshold = 0.5 - halfTolerance
    val highThreshold = 0.5 + halfTolerance

    if (fractional >= lowThreshold && fractional <= highThreshold) {
      if (floorHalf) Math.floor(value).toInt else Math.ceil(value).toInt
    } else {
      Math.round(value).toInt
    }
  }
}

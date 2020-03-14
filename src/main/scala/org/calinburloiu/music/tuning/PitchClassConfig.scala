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

package org.calinburloiu.music.tuning

// TODO We might want to rename this class.
case class PitchClassConfig(
  mapQuarterTonesLow: Boolean,
  halfTolerance: Double = PitchClassConfig.DEFAULT_HALF_TOLERANCE
)

object PitchClassConfig {

  val DEFAULT_HALF_TOLERANCE: Double = 0.5e-2
}

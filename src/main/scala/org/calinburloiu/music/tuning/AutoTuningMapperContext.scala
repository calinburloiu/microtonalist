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

/**
 * Context used to disambiguate mapping to of two possible adjacent pitch classes when mapping a quarter tone interval
 * to a standard tuning (12-EDO) keyboard.
 *
 * @param mapQuarterTonesLow 'true' if a quarter tone should be the lower pitch class with +50 cents deviation or
 *                           `false` if it should be the higher pitch class with -50 cents deviation
 * @param halfTolerance      tolerance value used for deviations when they are close to +50 or -50 cents in order to
 *                           avoid precision errors while mapping a quarter tone to its pitch class
 */
case class AutoTuningMapperContext(mapQuarterTonesLow: Boolean = false,
                                   halfTolerance: Double = AutoTuningMapperContext.DefaultHalfTolerance)

object AutoTuningMapperContext {
  val DefaultHalfTolerance: Double = 0.5e-2
}

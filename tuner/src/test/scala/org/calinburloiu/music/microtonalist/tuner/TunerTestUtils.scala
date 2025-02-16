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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.composition.Tuning

// TODO #98 Move these to TestUtils
object TunerTestUtils {
  val majTuning: Tuning = Tuning.fromOffsets("Just C Major",
    Seq(0.0, 0.0, 3.91, 0.0, -13.69, -1.96, 0.0, 1.96, 0.0, -15.64, 0.0, -11.73))
  val rastTuning: Tuning = Tuning.fromOffsets("C Rast",
    Seq(0.0, 0.0, 3.91, 0.0, -13.69, -1.96, 0.0, 1.96, 0.0, 5.87, -3.91, -11.73))
  val ussakTuning: Tuning = Tuning.fromOffsets("D Ussak",
    Seq(0.0, 0.0, 3.91, 0.0, -45.45, -1.96, 0.0, 1.96, 0.0, 5.87, -3.91, 0))
}

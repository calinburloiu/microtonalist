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

package org.calinburloiu.music.microtuner.midi

case class PitchBendSensitivity(semitones: Int, cents: Int = 0) {
  require(semitones >= 0 && semitones < 128, "semitones should be an unsigned 7-bit value")
  require(cents >= 0 && cents < 128, "cents should be an unsigned 7-bit value")

  val totalCents: Int = 100 * semitones + cents
}

object PitchBendSensitivity {
  val Default: PitchBendSensitivity = PitchBendSensitivity(2)
}

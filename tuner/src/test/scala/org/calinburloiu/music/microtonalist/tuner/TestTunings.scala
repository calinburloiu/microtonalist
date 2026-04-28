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

object TestTunings {
  val justCMaj: Tuning = Tuning("Just C Major",
    c = Some(0.0),
    d = Some(3.91),
    e = Some(-13.69),
    f = Some(-1.96),
    g = Some(1.96),
    a = Some(-15.64),
    b = Some(-11.73)
  )

  val justCRast: Tuning = Tuning("Just C Rast",
    c = Some(0.0),
    d = Some(3.91),
    e = Some(-13.69),
    f = Some(-1.96),
    g = Some(1.96),
    a = Some(5.87),
    aSharpOrBFlat = Some(-3.91),
    b = Some(-11.73)
  )

  val justDUssak: Tuning = Tuning("Just D Ussak",
    d = Some(3.91),
    e = Some(-45.45),
    f = Some(-1.96),
    g = Some(1.96),
    a = Some(5.87),
    aSharpOrBFlat = Some(-3.91),
    c = Some(0.0)
  )
}

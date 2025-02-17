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
  val bEvic: Tuning = Tuning("Evic",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    aSharpOrBFlat = Some(-33.33),
    b = Some(-16.67)
  )

  val gMaj: Tuning = Tuning("G Major",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    b = Some(-16.67)
  )

  val cNihavent5: Tuning = Tuning("Nihavent Pentachord",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(0.0),
    f = Some(0.0),
    g = Some(0.0),
  )

  val eSegah: Tuning = Tuning("Segah",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(-16.67),
    b = Some(-16.67)
  )

  val eSegahDesc: Tuning = Tuning("Segah Descending",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    gSharpOrAFlat = Some(50.0),
    a = Some(-16.67),
    aSharpOrBFlat = Some(0.0),
  )

  val eHuzzam: Tuning = Tuning("Huzzam",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    gSharpOrAFlat = Some(50),
    b = Some(-16.67)
  )

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

  val cRast: Tuning = Tuning("Rast",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(0.0),
    b = Some(-16.67)
  )

  val cNikriz: Tuning = Tuning("Nikriz",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
  )

  val dZengule: Tuning = Tuning("Zengule",
    cSharpOrDFlat = Some(-16.67),
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(16.67),
  )

  val dUssak: Tuning = Tuning("Ussak",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(50),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
  )

  val dSaba: Tuning = Tuning("Saba",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(50),
    f = Some(0.0),
    fSharpOrGFlat = Some(33.33),
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
  )

  val customGlobalFill: Tuning = Tuning((1 to 12).map { v => Some(v.toDouble) })
}

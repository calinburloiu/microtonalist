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

package org.calinburloiu.music.microtonalist.composition

object TestTunings {
  val evic: Tuning = Tuning("Evic",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    aSharpOrBFlat = Some(-33.33),
    b = Some(-16.67)
  )

  val gMajor: Tuning = Tuning("G Major",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    b = Some(-16.67)
  )

  val nihaventPentachord: Tuning = Tuning("Nihavent Pentachord",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(0.0),
    f = Some(0.0),
    g = Some(0.0),
  )

  val segah: Tuning = Tuning("Segah",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(-16.67),
    b = Some(-16.67)
  )

  val segahDesc: Tuning = Tuning("Segah Descending",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    gSharpOrAFlat = Some(50.0),
    a = Some(-16.67),
    aSharpOrBFlat = Some(0.0),
  )

  val huzzam: Tuning = Tuning("Huzzam",
    c = Some(0.0),
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    gSharpOrAFlat = Some(50),
    b = Some(-16.67)
  )

  val justCMajor: Tuning = Tuning("Just C Major",
    c = Some(0.0),
    d = Some(3.91),
    e = Some(-13.69),
    f = Some(-1.96),
    g = Some(1.96),
    a = Some(-15.64),
    b = Some(-11.73)
  )

  val rast: Tuning = Tuning("Rast",
    c = Some(0.0),
    d = Some(0.0),
    e = Some(-16.67),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(0.0),
    b = Some(-16.67)
  )

  val nikriz: Tuning = Tuning("Nikriz",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
  )

  val zengule: Tuning = Tuning("Zengule",
    cSharpOrDFlat = Some(-16.67),
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(16.67),
  )

  val ussak: Tuning = Tuning("Ussak",
    c = Some(0.0),
    d = Some(0.0),
    dSharpOrEFlat = Some(50),
    f = Some(0.0),
    g = Some(0.0),
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
  )

  val saba: Tuning = Tuning("Saba",
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

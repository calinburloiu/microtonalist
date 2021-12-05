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

package org.calinburloiu.music.microtuner.core

object TestPartialTunings {
  val evic: PartialTuning = PartialTuning("Evic",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = None,
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = None,
    aSharpOrBFlat = Some(-33.33),
    b = Some(-16.67)
  )

  val gMajor: PartialTuning = PartialTuning("G Major",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = None,
    e = Some(-16.67),
    f = None,
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )

  val nihaventPentachord: PartialTuning = PartialTuning("Nihavent Pentachord",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(0.0),
    e = None,
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = None,
    aSharpOrBFlat = None,
    b = None
  )

  val segah: PartialTuning = PartialTuning("Segah",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(-16.67),
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )

  val segahDesc: PartialTuning = PartialTuning("Segah Descending",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = Some(50.0),
    a = Some(-16.67),
    aSharpOrBFlat = Some(0.0),
    b = None
  )

  val huzzam: PartialTuning = PartialTuning("Huzzam",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = None,
    dSharpOrEFlat = Some(-33.33),
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = Some(50),
    a = None,
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )

  val justCMajor: PartialTuning = PartialTuning("Just C Major",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(3.91),
    dSharpOrEFlat = None,
    e = Some(-13.69),
    f = Some(-1.96),
    fSharpOrGFlat = None,
    g = Some(1.96),
    gSharpOrAFlat = None,
    a = Some(15.64),
    aSharpOrBFlat = None,
    b = Some(-11.73)
  )

  val rast: PartialTuning = PartialTuning("Rast",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = None,
    e = Some(-16.67),
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = None,
    b = Some(-16.67)
  )

  val nikriz: PartialTuning = PartialTuning("Nikriz",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    e = None,
    f = None,
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
    b = None
  )

  val zengule: PartialTuning = PartialTuning("Zengule",
    c = None,
    cSharpOrDFlat = Some(-16.67),
    d = Some(0.0),
    dSharpOrEFlat = Some(16.67),
    e = None,
    f = None,
    fSharpOrGFlat = Some(-16.67),
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = Some(16.67),
    b = None
  )

  val ussak: PartialTuning = PartialTuning("Ussak",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = Some(50),
    e = None,
    f = Some(0.0),
    fSharpOrGFlat = None,
    g = Some(0.0),
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
    b = None
  )

  val saba: PartialTuning = PartialTuning("Saba",
    c = Some(0.0),
    cSharpOrDFlat = None,
    d = Some(0.0),
    dSharpOrEFlat = Some(50),
    e = None,
    f = Some(0.0),
    fSharpOrGFlat = Some(33.33),
    g = None,
    gSharpOrAFlat = None,
    a = Some(0.0),
    aSharpOrBFlat = Some(0.0),
    b = None
  )

  val customGlobalFill: PartialTuning = PartialTuning((1 to 12).map(Some(_)))
}

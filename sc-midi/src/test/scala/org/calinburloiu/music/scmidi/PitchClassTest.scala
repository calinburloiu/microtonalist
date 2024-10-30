/*
 * Copyright 2024 Calin-Andrei Burloiu
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

package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class PitchClassTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {

  //@formatter:off
  private val pitchClassNamesTables = Table[String, PitchClass](
    ("Name",    "PitchClass"),
    ("C",       PitchClass.C),
    ("B♯",      PitchClass.C),
    ("B#",      PitchClass.C),
    ("0",       PitchClass.C),
    ("C♯",      PitchClass.CSharp),
    ("D♭",      PitchClass.DFlat),
    ("C♯/D♭",   PitchClass.CSharp),
    ("D♭/C♯",   PitchClass.CSharp),
    ("1",       PitchClass.CSharp),
    ("d",       PitchClass.D),
    ("2",       PitchClass.D),
    ("D#/Eb",   PitchClass.EFlat),
    ("Eb/D#",   PitchClass.EFlat),
    ("d#",      PitchClass.EFlat),
    ("eb",      PitchClass.EFlat),
    ("3",       PitchClass.DSharp),
    ("E",       PitchClass.E),
    ("4",       PitchClass.E),
    ("F♭",      PitchClass.E),
    ("F",       PitchClass.F),
    ("E♯",      PitchClass.F),
    ("5",       PitchClass.F),
    ("F♯/Gb",   PitchClass.FSharp),
    ("Gb/F♯",   PitchClass.FSharp),
    ("F♯",      PitchClass.GFlat),
    ("G♭",      PitchClass.GFlat),
    ("6",       PitchClass.GFlat),
    ("G",       PitchClass.G),
    ("7",       PitchClass.G),
    ("g♯/Ab",   PitchClass.GSharp),
    ("Ab/g♯",   PitchClass.GSharp),
    ("A♭",      PitchClass.AFlat),
    ("g♯",      PitchClass.GSharp),
    ("8",       PitchClass.AFlat),
    ("a",       PitchClass.A),
    ("9",       PitchClass.A),
    ("B♭",      PitchClass.BFlat),
    ("a♯",      PitchClass.ASharp),
    ("A♯/B♭",   PitchClass.BFlat),
    ("B♭/A♯",   PitchClass.BFlat),
    ("10",      PitchClass.BFlat),
    ("B",       PitchClass.B),
    ("C♭",      PitchClass.B),
    ("11",      PitchClass.B),
  )
  //@formatter:on

  it should "successfully parse a string into a PitchClass" in {
    forAll(pitchClassNamesTables) { (name, pitchClass) =>
      PitchClass.parse(name) should contain(pitchClass)
    }
  }

  it should "fail to parse an invalid string into a PitchClass" in {
    PitchClass.parse("X#") should be(empty)
    PitchClass.parse("y") should be(empty)
    PitchClass.parse("sdfgsfg") should be(empty)
    PitchClass.parse("Z♯") should be(empty)
    PitchClass.parse("C♯/G♭") should be(empty)
    PitchClass.parse("12") should be(empty)
    PitchClass.parse("-1") should be(empty)
  }
}

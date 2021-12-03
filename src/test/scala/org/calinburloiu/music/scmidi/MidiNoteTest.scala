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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.intonation.PitchClass
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiNoteTest extends AnyFlatSpec with Matchers {
  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  "MidiNote" can "be constructed from a pitch class and octave number" in {
    MidiNote(PitchClass.C, -1).number shouldEqual 0
    MidiNote(PitchClass.E, 0).number shouldEqual 16
    MidiNote(PitchClass.C, 3).number shouldEqual 48
    MidiNote(PitchClass.C, 4).number shouldEqual MidiNote.C4
    MidiNote(PitchClass.GSharp, 4).number shouldEqual MidiNote.GSharp4
    MidiNote(PitchClass.C, 5).number shouldEqual MidiNote.C5
    MidiNote(PitchClass.C, 9).number shouldEqual 120
    MidiNote(PitchClass.G, 9).number shouldEqual 127

    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.fromInt(-1), 4) }
    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.fromInt(12), 4) }
    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.D, -2) }
    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.GSharp, 9) }
    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.B, 9) }
    assertThrows[IllegalArgumentException] { MidiNote(PitchClass.C, 10) }
  }

  it should "assert if it's invalid" in {
    assertThrows[IllegalArgumentException] { MidiNote(-1).assertValid() }
    assertThrows[IllegalArgumentException] { MidiNote(128).assertValid() }
  }

  it should "tell its pitch class" in {
    MidiNote.C4.pitchClass shouldEqual PitchClass.C
    MidiNote.FSharp4.pitchClass shouldEqual PitchClass.FSharp
    MidiNote(PitchClass.BFlat, 3).pitchClass shouldEqual PitchClass.ASharp
    MidiNote(PitchClass.A, 7).pitchClass shouldEqual PitchClass.A

    MidiNote(0).pitchClass shouldEqual PitchClass.C
    MidiNote(127).pitchClass shouldEqual PitchClass.G
  }

  it should "tell its octave" in {
    MidiNote.C4.octave shouldEqual 4
    MidiNote.FSharp4.octave shouldEqual 4
    MidiNote(PitchClass.BFlat, 3).octave shouldEqual 3
    MidiNote(PitchClass.A, 7).octave shouldEqual 7

    MidiNote(0).octave shouldEqual -1
    MidiNote(6).octave shouldEqual -1
    MidiNote(12).octave shouldEqual 0
    MidiNote(19).octave shouldEqual 0
    MidiNote(24).octave shouldEqual 1
    MidiNote(35).octave shouldEqual 1
    MidiNote(127).octave shouldEqual 9
  }

  it should "tell its frequency in 12-EDO" in {
    MidiNote.ConcertPitch.freq shouldEqual 440.0
    MidiNote.A4.freq shouldEqual 440.0

    MidiNote(PitchClass.GSharp, 3).freq shouldEqual 207.65
    MidiNote(PitchClass.AFlat, 3).freq shouldEqual 207.65
    MidiNote.C4.freq shouldEqual 261.63
    MidiNote(PitchClass.B, 6).freq shouldEqual 1975.53

    MidiNote(0).freq shouldEqual 8.18
    MidiNote(16).freq shouldEqual 20.6
    MidiNote(127).freq shouldEqual 12543.85
  }
}

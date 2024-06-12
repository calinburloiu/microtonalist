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

package org.calinburloiu.music.microtonalist.core

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{CentsInterval, RealInterval}
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningRefTest extends AnyFlatSpec with Matchers {

  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  behavior of classOf[StandardTuningRef]
    .getSimpleName

  it should "always return a baseDeviation of 0" in {
    StandardTuningRef(PitchClass.C).baseDeviation shouldEqual 0.0
    StandardTuningRef(PitchClass.FSharp).baseDeviation shouldEqual 0.0
  }

  it should "return baseTuningPitches of 12-EDO" in {
    StandardTuningRef(PitchClass.D).baseTuningPitch shouldEqual TuningPitch(PitchClass.D, 0.0)
    StandardTuningRef(PitchClass.AFlat).baseTuningPitch shouldEqual TuningPitch(PitchClass.AFlat, 0.0)
  }

  behavior of classOf[ConcertPitchTuningRef]
    .getSimpleName

  it should "default the concert pitch frequency to the standard 440 Hz" in {
    ConcertPitchTuningRef(CentsInterval(333.33), MidiNote.C5).concertPitchFreq shouldEqual 440.0
  }

  it should "tune a base MIDI note of A4 relative to standard concert pitch of 440 Hz of A4" in {
    val tuningRef = ConcertPitchTuningRef(
      concertPitchToBaseInterval = RealInterval.Unison, baseMidiNote = MidiNote.A4)
    tuningRef.baseDeviation shouldEqual 0.0
    tuningRef.baseTuningPitch.pitchClass shouldEqual PitchClass.A
    tuningRef.baseTuningPitch.deviation shouldEqual 0.0
  }

  it should "tune a base MIDI note relative to standard concert pitch" in {
    val tuningRef = ConcertPitchTuningRef(
      concertPitchToBaseInterval = 32 /: 27, baseMidiNote = MidiNote.C5)
    tuningRef.baseDeviation shouldEqual -5.87
    tuningRef.baseTuningPitch.pitchClass shouldEqual PitchClass.C
    tuningRef.baseTuningPitch.deviation shouldEqual -5.87
  }

  it should "tune a base MIDI note of A4 relative to 432 Hz concert pitch of A4" in {
    val tuningRef = ConcertPitchTuningRef(
      concertPitchToBaseInterval = RealInterval.Unison, baseMidiNote = MidiNote.A4, concertPitchFreq = 432.0)
    tuningRef.baseDeviation shouldEqual -31.77
    tuningRef.baseTuningPitch.pitchClass shouldEqual PitchClass.A
    tuningRef.baseTuningPitch.deviation shouldEqual -31.77
  }

  it should "tune a base MIDI note relative to 432 Hz concert pitch of A4" in {
    val tuningRef = ConcertPitchTuningRef(
      concertPitchToBaseInterval = 32 /: 27, baseMidiNote = MidiNote.C5, concertPitchFreq = 432.0)
    tuningRef.baseDeviation shouldEqual -(5.87 + 31.77)
    tuningRef.baseTuningPitch.pitchClass shouldEqual PitchClass.C
    tuningRef.baseTuningPitch.deviation shouldEqual -(5.87 + 31.77)
  }

  it should "tune a base MIDI note relative to a custom concert pitch far from A4" in {
    val tuningRef = ConcertPitchTuningRef(concertPitchFreq = 260.0,
      baseMidiNote = MidiNote.E4, concertPitchToBaseInterval = CentsInterval(350.0))
    tuningRef.baseDeviation shouldEqual -60.79
    tuningRef.baseTuningPitch.pitchClass shouldEqual PitchClass.E
    tuningRef.baseTuningPitch.deviation shouldEqual -60.79
  }
}

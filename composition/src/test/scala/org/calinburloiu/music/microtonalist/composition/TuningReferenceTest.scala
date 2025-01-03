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

import org.calinburloiu.music.intonation.RatioInterval.InfixOperator
import org.calinburloiu.music.intonation.{CentsInterval, RealInterval}
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TuningReferenceTest extends AnyFlatSpec with Matchers {

  private val testTolerance: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(testTolerance)

  behavior of classOf[StandardTuningReference]
    .getSimpleName

  it should "always return a baseDeviation of 0" in {
    StandardTuningReference(PitchClass.C).baseDeviation shouldEqual 0.0
    StandardTuningReference(PitchClass.FSharp).baseDeviation shouldEqual 0.0
  }

  it should "return baseTuningPitches of 12-EDO" in {
    StandardTuningReference(PitchClass.D).baseTuningPitch shouldEqual TuningPitch(PitchClass.D, 0.0)
    StandardTuningReference(PitchClass.AFlat).baseTuningPitch shouldEqual TuningPitch(PitchClass.AFlat, 0.0)
  }

  behavior of classOf[ConcertPitchTuningReference]
    .getSimpleName

  it should "default the concert pitch frequency to the standard 440 Hz" in {
    ConcertPitchTuningReference(CentsInterval(333.33), MidiNote.C5).concertPitchFreq shouldEqual 440.0
  }

  it should "tune a base MIDI note of A4 relative to standard concert pitch of 440 Hz of A4" in {
    val tuningReference = ConcertPitchTuningReference(
      concertPitchToBaseInterval = RealInterval.Unison, baseMidiNote = MidiNote.A4)
    tuningReference.baseDeviation shouldEqual 0.0
    tuningReference.baseTuningPitch.pitchClass shouldEqual PitchClass.A
    tuningReference.baseTuningPitch.deviation shouldEqual 0.0
  }

  it should "tune a base MIDI note relative to standard concert pitch" in {
    val tuningReference = ConcertPitchTuningReference(
      concertPitchToBaseInterval = 32 /: 27, baseMidiNote = MidiNote.C5)
    tuningReference.baseDeviation shouldEqual -5.87
    tuningReference.baseTuningPitch.pitchClass shouldEqual PitchClass.C
    tuningReference.baseTuningPitch.deviation shouldEqual -5.87
  }

  it should "tune a base MIDI note of A4 relative to 432 Hz concert pitch of A4" in {
    val tuningReference = ConcertPitchTuningReference(
      concertPitchToBaseInterval = RealInterval.Unison, baseMidiNote = MidiNote.A4, concertPitchFreq = 432.0)
    tuningReference.baseDeviation shouldEqual -31.77
    tuningReference.baseTuningPitch.pitchClass shouldEqual PitchClass.A
    tuningReference.baseTuningPitch.deviation shouldEqual -31.77
  }

  it should "tune a base MIDI note relative to 432 Hz concert pitch of A4" in {
    val tuningReference = ConcertPitchTuningReference(
      concertPitchToBaseInterval = 32 /: 27, baseMidiNote = MidiNote.C5, concertPitchFreq = 432.0)
    tuningReference.baseDeviation shouldEqual -(5.87 + 31.77)
    tuningReference.baseTuningPitch.pitchClass shouldEqual PitchClass.C
    tuningReference.baseTuningPitch.deviation shouldEqual -(5.87 + 31.77)
  }

  it should "tune a base MIDI note relative to a custom concert pitch far from A4" in {
    val tuningReference = ConcertPitchTuningReference(concertPitchFreq = 260.0,
      baseMidiNote = MidiNote.E4, concertPitchToBaseInterval = CentsInterval(350.0))
    tuningReference.baseDeviation shouldEqual -60.79
    tuningReference.baseTuningPitch.pitchClass shouldEqual PitchClass.E
    tuningReference.baseTuningPitch.deviation shouldEqual -60.79
  }
}

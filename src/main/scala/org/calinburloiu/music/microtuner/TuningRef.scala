/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

import org.calinburloiu.music.intonation.{ConcertPitchFreq, Interval, PitchClass, TuningPitch}
import org.calinburloiu.music.microtuner.midi.MidiNote

/**
 * Tuning reference that tells what pitch class to be used for the base pitch and what tuning deviation should have
 * with respect to standard tuning (12-EDO).
 */
sealed trait TuningRef{
  def basePitchClass: PitchClass

  def baseDeviation: Double

  /**
   * @return `Some` pitch class and tuning deviation for the base pitch if it is valid or `None`, otherwise. Typically,
   *         it might not be valid if the deviation absolute value exceeds 100 cents.
   */
  def baseTuningPitch: TuningPitch
}

/**
 * Tuning reference relative to concert pitch.
 *
 * @param concertPitchToBaseInterval Interval between the reference frequency and composition's base pitch.
 * @param baseMidiNote MIDI note number of the composition's base pitch, relative to which scales are tuned.
 * @param concertPitchFreq Reference frequency in Hz, typically known as concert pitch and set to `440.0` Hz.
 */
case class ConcertPitchTuningRef(concertPitchToBaseInterval: Interval,
                                 baseMidiNote: MidiNote,
                                 concertPitchFreq: Double = ConcertPitchFreq) extends TuningRef {
  require(concertPitchFreq > 0, "concertPitchFreq > 0")
  baseMidiNote.assertValid()

  override def basePitchClass: PitchClass = baseMidiNote.pitchClass

  override val baseDeviation: Double = {
    val concertPitchToBaseMidiNoteInterval = Interval(baseMidiNote.freq / concertPitchFreq)
    (concertPitchToBaseInterval - concertPitchToBaseMidiNoteInterval).cents
  }

  override val baseTuningPitch: TuningPitch = TuningPitch(basePitchClass, baseDeviation)
}

/**
 * Tuning reference relative standard tuning (12-EDO).
 *
 * @param basePitchClass The number of the base pitch class (0 is C, 1 is C#/Db, ..., 11 is B).
 */
case class StandardTuningRef(override val basePitchClass: PitchClass) extends TuningRef {
  override def baseDeviation: Double = 0.0

  override def baseTuningPitch: TuningPitch = TuningPitch(basePitchClass, 0.0)
}

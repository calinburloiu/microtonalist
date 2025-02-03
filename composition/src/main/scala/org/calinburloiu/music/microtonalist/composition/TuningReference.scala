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

import org.calinburloiu.music.intonation.{ConcertPitchFreq, Interval, RealInterval}
import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}

/**
 * Tuning reference tells what pitch class from the keyboard instrument must be used for the base pitch of a
 * composition and what tuning deviation should have with respect to standard tuning (12-EDO).
 */
trait TuningReference extends Plugin {

  override val familyName: String = TuningReference.FamilyName

  /**
   * @return a [[TuningPitch]] for the base pitch of the composition
   */
  def baseTuningPitch: TuningPitch

  /**
   * @return the pitch class of the base pitch of the composition
   */
  def basePitchClass: PitchClass

  /**
   * @return the deviation from 12-EDO in cents of the `basePitchClass`
   */
  def baseDeviation: Double
}

object TuningReference {
  val FamilyName: String = "tuningReference"
}

/**
 * Tuning reference relative standard tuning (12-EDO).
 *
 * @param basePitchClass The number of the base pitch class (0 is C, 1 is C#/Db, ..., 11 is B).
 * @param baseDeviation  Deviation in cents of the base pitch with respect to the standard (12-EDO) pitch class tuning.
 */
case class StandardTuningReference(override val basePitchClass: PitchClass,
                                   override val baseDeviation: Double = 0.0) extends TuningReference {

  override val typeName: String = StandardTuningReference.typeName

  override def baseTuningPitch: TuningPitch = TuningPitch(basePitchClass, baseDeviation)
}

object StandardTuningReference {
  val typeName: String = "standard"
}

/**
 * Tuning reference relative to concert pitch.
 *
 * @param concertPitchToBaseInterval Interval between the reference frequency and composition's base pitch.
 * @param baseMidiNote               MIDI note number of the composition's base pitch, relative to which scales are
 *                                   tuned.
 * @param concertPitchFreq           Reference frequency in Hz, typically known as concert pitch and set to `440.0` Hz.
 */
case class ConcertPitchTuningReference(concertPitchToBaseInterval: Interval,
                                       baseMidiNote: MidiNote,
                                       concertPitchFreq: Double = ConcertPitchFreq) extends TuningReference {
  require(concertPitchFreq > 0, "concertPitchFreq > 0")
  baseMidiNote.assertValid()

  override val typeName: String = ConcertPitchTuningReference.typeName

  override def basePitchClass: PitchClass = baseMidiNote.pitchClass

  override val baseDeviation: Double = {
    val concertPitchToBaseMidiNoteInterval = RealInterval(baseMidiNote.freq / concertPitchFreq)
    (concertPitchToBaseInterval - concertPitchToBaseMidiNoteInterval).cents
  }

  override val baseTuningPitch: TuningPitch = TuningPitch(basePitchClass, baseDeviation)
}

object ConcertPitchTuningReference {
  val typeName: String = "concertPitch"
}

// TODO Add support for Scala-app-style implementation; also look at Ableton Live 12 (https://www.ableton
//  .com/en/live-manual/12/using-tuning-systems/#the-tuning-section)

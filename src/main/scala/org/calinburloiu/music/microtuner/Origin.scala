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

import org.calinburloiu.music.intonation.{Interval, PitchClass}

import scala.language.implicitConversions

trait Pitch[+I <: Interval] {
  def absoluteInterval: I
  def freq: Double

  def +[J >: I <: Interval](interval: J): Pitch[J]

  def -(that: Pitch[Interval]): Interval
  def -[J >: I <: Interval](interval: J): Pitch[J]
}

object Pitch {
  def apply(freq: Double): Pitch[Interval] = ???
  def apply[I <: Interval](absoluteInterval: I): Pitch[I] = ???
  def apply[I <: Interval](origin: Origin, relativeInterval: I = Interval.UNISON): Pitch[I] = ???

  implicit def fromFreq(freq: Double): Pitch[Interval] = Pitch(freq)
}

case class MidiNote(number: Int) {
  /** Returns the standard 12-tone equal temperament `Pitch`. */
  def standardPitch: Pitch[Interval] = ???
}

object MidiNote {
  implicit def fromMidiNoteNumber(midiNoteNumber: Int): MidiNote = MidiNote(midiNoteNumber)
}

case class PitchMapping[+I <: Interval](pitch: Pitch[I], midiNote: MidiNote)

trait Origin {

  val baseMidiNote: MidiNote

  val concertPitch: Pitch[Interval]

  val baseToConcertPitchInterval: Interval

  def basePitch: Pitch[Interval]
}



trait OriginOld {

  val basePitchClass: PitchClass

  val refMidiNote: Int

  val concertPitchFreq: Double

  def refToConcertPitchInterval: Interval

  def baseMidiNote: Int

  def refFreq: Double

  def baseToConcertPitchInterval: Interval
}

object OriginOld {

  def apply(basePitchClass: PitchClass): OriginOld = BasePitchClassOriginOld(basePitchClass)
}

// TODO This is a bad implementation done only not to break current functionality
case class BasePitchClassOriginOld(
  override val basePitchClass: PitchClass
) extends OriginOld {

  override val refMidiNote: Int = 69
  override val concertPitchFreq: Double = 440.0

  override def refToConcertPitchInterval: Interval = ???

  override def baseMidiNote: Int = ???

  override def refFreq: Double = ???

  override def baseToConcertPitchInterval: Interval = ???
}

// TODO Name it: concert pitch from ref
case class ConcertPitchOriginOld(
  override val basePitchClass: PitchClass,
  override val refMidiNote: Int,
  override val concertPitchFreq: Double,
  override val refToConcertPitchInterval: Interval
) extends OriginOld {

  override def baseMidiNote: Int = ???

  override def refFreq: Double = ???

  override def baseToConcertPitchInterval: Interval = ???
}

// TODO Name it: concert pitch from base
case class ConcertPitchOrigin02(
  override val basePitchClass: PitchClass,
  override val refMidiNote: Int,
  override val concertPitchFreq: Double,
  override val baseToConcertPitchInterval: Interval
) extends OriginOld {

  override def refToConcertPitchInterval: Interval = ???

  override def baseMidiNote: Int = ???

  override def refFreq: Double = ???
}

/*
 * Copyright 2026 Calin-Andrei Burloiu
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

import org.calinburloiu.music.scmidi.message.PitchBendScMidiMessage

/**
 * Holds the MPE '''Expression Values''' of a note, or the aggregated Expression Values of an output MPE
 * Member Channel.
 *
 * A note's Expression Values are the performer-controlled values of the three MPE control dimensions:
 * its Expression Pitch Bend, its Channel Pressure and its CC #74 (Timbre / Slide). The Tuning Pitch Bend
 * is ''not'' an Expression Value — it belongs to the tuning domain rather than to the expression domain,
 * and is added to the Expression Pitch Bend only when the Pitch Bend emitted on a Member Channel is
 * computed.
 */
private[tuner] trait MpeExpression {
  /**
   * Expression Pitch Bend as a signed 14-bit MIDI Pitch Bend value (-8192 to 8191), excluding any tuning
   * offset. 0 means no bend.
   *
   * The value is held exactly as received on the input Member Channel, in the units the wire carries, and is
   * '''reinterpreted, not rescaled''', when the Member Channel Pitch Bend Sensitivity changes: one and the same
   * raw value means 25 cents at ±2 semitones and 600 cents at ±48, and every note sounding on that channel is
   * reinterpreted alike. That is what an ordinary MIDI receiver does, and a sender wanting a specific deviation
   * after a sensitivity change sends a fresh Pitch Bend.
   *
   * Storing cents instead would conserve a note's deviation across a sensitivity change, but it would also make
   * this value one of two conversions that have to be kept in step — the other being the one that seeds a new
   * note from its input channel's raw Pitch Bend — which is the disagreement #253 reported. Raw removes the
   * possibility of a disagreement rather than fixing one instance of it, and it makes the model integral, so
   * that [[MpeChannelAllocator.diff]] can compare all three dimensions exactly.
   */
  def pitchBend: Int

  /** Channel pressure (aftertouch) value. Ranges from 0 to 127. */
  def pressure: Int

  /** MIDI CC #74 (Timbre / Slide) value. Ranges from 0 to 127; 64 is the centre. */
  def slide: Int
}

private[tuner] object MpeExpression {
  /** Default Expression Pitch Bend value (no bend). */
  val DefaultPitchBend: Int = PitchBendScMidiMessage.NoPitchBendValue

  /** Default channel pressure value (no pressure). */
  val DefaultPressure: Int = 0

  /** Default slide (CC #74) value (centre position). */
  val DefaultSlide: Int = 64
}

private[tuner] class MutableMpeExpression(var pitchBend: Int = MpeExpression.DefaultPitchBend,
                                          var pressure: Int = MpeExpression.DefaultPressure,
                                          var slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

/**
 * Immutable [[MpeExpression]], used to hand Expression Values to [[MpeChannelAllocator]] and to snapshot a
 * channel's aggregate.
 */
private[tuner] case class ImmutableMpeExpression(pitchBend: Int = MpeExpression.DefaultPitchBend,
                                                 pressure: Int = MpeExpression.DefaultPressure,
                                                 slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

private[tuner] object ImmutableMpeExpression {
  val Default: ImmutableMpeExpression = ImmutableMpeExpression()
}

/**
 * Which of an output Member Channel's aggregated Expression Values changed as the result of an operation.
 *
 * `None` means the dimension is unchanged and needs no message on the output channel; `Some(value)` means
 * it changed to `value`.
 */
private[tuner] case class MpeExpressionUpdate(pitchBend: Option[Int] = None,
                                              pressure: Option[Int] = None,
                                              slide: Option[Int] = None)

private[tuner] object MpeExpressionUpdate {
  /** No Expression Value changed. */
  val Unchanged: MpeExpressionUpdate = MpeExpressionUpdate()
}

/** An [[MpeExpressionUpdate]] addressed to a specific output Member Channel. */
private[tuner] case class MpeChannelExpressionUpdate(channel: Int, update: MpeExpressionUpdate)

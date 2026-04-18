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

package org.calinburloiu.music.scmidi.message

import javax.sound.midi.{MidiMessage, ShortMessage}

/**
 * Scala-idiomatic base trait for MIDI messages, wrapping Java's [[javax.sound.midi.MidiMessage]] hierarchy.
 *
 * Unlike Java's [[javax.sound.midi.MidiMessage]] (and its subclasses like [[javax.sound.midi.ShortMessage]]),
 * which expose raw byte data and mutable state, `ScMidiMessage` subtypes are immutable case classes with named,
 * validated parameters, Scala pattern matching support via `unapply`, and convenient factory methods for
 * bidirectional conversion with Java messages.
 */
trait ScMidiMessage {
  /** The underlying [[javax.sound.midi.MidiMessage]]. */
  def javaMessage: MidiMessage
}

/**
 * Type class for converting a Java [[javax.sound.midi.MidiMessage]] into a specific [[ScMidiMessage]] subtype.
 *
 * Implementations are typically provided by companion objects of [[ScMidiMessage]] subtypes, allowing
 * type-safe conversion from the raw Java MIDI representation to the Scala-idiomatic one.
 *
 * @tparam T The target [[ScMidiMessage]] subtype produced by the conversion.
 */
trait FromJavaMidiMessageConverter[T] {
  /**
   * Attempts to convert the given Java [[MidiMessage]] to an instance of `T`.
   *
   * @param message The Java [[MidiMessage]] to convert.
   * @return `Some(T)` if the message matches the expected type and command for `T`; `None` otherwise.
   */
  def fromJavaMessage(message: MidiMessage): Option[T]
}

object ScMidiMessage {
  private val FromJavaMessageMap: Map[Int, MidiMessage => ScMidiMessage] = Map(
    ShortMessage.NOTE_ON -> NoteOnScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.NOTE_OFF -> NoteOffScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PITCH_BEND -> PitchBendScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CONTROL_CHANGE -> CcScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.CHANNEL_PRESSURE -> ChannelPressureScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.POLY_PRESSURE -> PolyPressureScMidiMessage.fromJavaMessage.unlift,
    ShortMessage.PROGRAM_CHANGE -> ProgramChangeScMidiMessage.fromJavaMessage.unlift
  ).withDefaultValue(UnsupportedScMidiMessage.apply)

  /**
   * Converts a Java [[MidiMessage]] to the corresponding [[ScMidiMessage]] subtype.
   *
   * Supported message types are mapped to their Scala-idiomatic counterparts (e.g.
   * [[javax.sound.midi.ShortMessage]] with command `NOTE_ON` becomes [[NoteOnScMidiMessage]]).
   * Unrecognized messages are wrapped in [[UnsupportedScMidiMessage]].
   *
   * @param message The Java [[MidiMessage]] to convert. Must not be null.
   * @return The [[ScMidiMessage]] instance.
   * @throws IllegalArgumentException if the message is null.
   */
  def fromJavaMessage(message: MidiMessage): ScMidiMessage = {
    require(message != null, "message must not be null")

    message match {
      case shortMessage: ShortMessage => FromJavaMessageMap(shortMessage.getCommand)(message)
      case _ => UnsupportedScMidiMessage(message)
    }
  }
}

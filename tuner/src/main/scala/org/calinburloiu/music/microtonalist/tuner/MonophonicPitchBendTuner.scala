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

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.scmidi.message.*
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.{MidiNote, PitchBendSensitivity, PitchBendSensitivityMessages, clampValue, mapShortMessageChannel}

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

/**
 * Tuner that uses pitch bend to tune notes. Because pitch bend MIDI messages affect the whole channel they are sent
 * on, this tuner only supports and enforces monophonic playing.
 *
 * @param outputChannel               Output MIDI channel on which all output is sent, regardless on the input
 *                                    channels used.
 * @param defaultPitchBendSensitivity Default pitch bend range that will be configured via Pitch Bend Sensitivity
 *                                    MIDI RPN.
 */
case class MonophonicPitchBendTuner(outputChannel: Int,
                                    defaultPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default)
  extends Tuner with StrictLogging {
  require(0 <= outputChannel && outputChannel <= 15,
    s"Output MIDI channel must be between 0 and 15, but was $outputChannel!")

  override val typeName: String = MonophonicPitchBendTuner.TypeName

  private var _currTuning: Tuning = Tuning.Standard
  private var _pitchBendSensitivity: PitchBendSensitivity = defaultPitchBendSensitivity

  private val noteStack: mutable.Stack[MidiNote] = mutable.Stack()
  private var _lastSingleNote: MidiNote = 0

  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private var _currTuningPitchBend: Int = 0
  private var _unsentPitchBend: Boolean = false

  private var _lastNoteOnVelocity = NoteOnScMidiMessage.DefaultVelocity
  private var _lastNoteOffVelocity = NoteOffScMidiMessage.DefaultVelocity

  private var _sustainPedal: Int = 0
  private var _sostenutoPedal: Int = 0
  private var _rpnLsb: Int = ScMidiRpn.NullLsb
  private var _rpnMsb: Int = ScMidiRpn.NullMsb

  override def reset(): Seq[MidiMessage] = {
    this._resetState()
    this._init()
  }

  private def _resetState(): Unit = {
    _currTuning = Tuning.Standard
    _pitchBendSensitivity = defaultPitchBendSensitivity
    noteStack.clear()
    _lastSingleNote = 0
    _currExpressionPitchBend = 0
    _currTuningPitchBend = 0
    _unsentPitchBend = false
    _lastNoteOnVelocity = NoteOnScMidiMessage.DefaultVelocity
    _lastNoteOffVelocity = NoteOffScMidiMessage.DefaultVelocity
    _sustainPedal = 0
    _sostenutoPedal = 0
    _rpnLsb = ScMidiRpn.NullLsb
    _rpnMsb = ScMidiRpn.NullMsb
  }

  private def _init(): Seq[MidiMessage] = PitchBendSensitivityMessages.create(
    outputChannel, defaultPitchBendSensitivity)

  override def tune(tuning: Tuning): Seq[MidiMessage] = {
    currTuning = tuning

    // Update pitch bend for the current sounding note
    if (isAnyNoteOn) applyPitchBend().toSeq else Seq.empty
  }

  override def process(message: MidiMessage): Seq[MidiMessage] = {
    val forwardMessage = () => mapShortMessageChannel(message, _ => outputChannel)

    val buffer = mutable.Buffer[MidiMessage]()

    message.asScala match {
      case NoteOnScMidiMessage(_, note, 0) =>
        turnNoteOff(buffer, note, 0)
      case NoteOnScMidiMessage(_, note, velocity) =>
        // Only monophonic playing is allowed, if a note is on, turn it off
        if (isAnyNoteOn) {
          applyNoteOff(buffer, lastNote, _lastNoteOffVelocity)
        }
        turnNoteOn(buffer, note, velocity)
      case NoteOffScMidiMessage(_, note, velocity) =>
        turnNoteOff(buffer, note, velocity)
      case PitchBendScMidiMessage(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        applyPitchBend(buffer)
      case CcScMidiMessage(_, ScMidiCc.SustainPedal, value) =>
        _sustainPedal = value
        buffer += forwardMessage()
      case CcScMidiMessage(_, ScMidiCc.SostenutoPedal, value) =>
        _sostenutoPedal = value
        buffer += forwardMessage()
      case CcScMidiMessage(_, ScMidiCc.RpnLsb, value) =>
        _rpnLsb = value
        buffer += forwardMessage()
      case CcScMidiMessage(_, ScMidiCc.RpnMsb, value) =>
        _rpnMsb = value
        buffer += forwardMessage()
      case CcScMidiMessage(_, ScMidiCc.DataEntryMsb, value) =>
        buffer += forwardMessage()
        applyPitchBendSensitivityMsb(buffer, value)
      case CcScMidiMessage(_, ScMidiCc.DataEntryLsb, value) =>
        buffer += forwardMessage()
        applyPitchBendSensitivityLsb(buffer, value)
      case _ =>
        buffer += forwardMessage()
    }

    buffer.toSeq
  }

  private def currTuning: Tuning = _currTuning

  private def currTuning_=(newTuning: Tuning): Unit = {
    // Update currTuningPitchBend
    val newOffset = newTuning(lastNote.pitchClass)
    if (currTuning(lastNote.pitchClass) != newOffset) {
      currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(newOffset, pitchBendSensitivity)
    }

    _currTuning = newTuning
  }

  private def isSettingPitchBendSensitivity: Boolean =
    _rpnLsb == ScMidiRpn.PitchBendSensitivityLsb && _rpnMsb == ScMidiRpn.PitchBendSensitivityMsb

  private def applyPitchBendSensitivityMsb(buffer: mutable.Buffer[MidiMessage], value: Int): Unit = {
    if (isSettingPitchBendSensitivity) {
      pitchBendSensitivity = pitchBendSensitivity.copy(semitones = value)
      applyPitchBend(buffer)
    }
  }

  private def applyPitchBendSensitivityLsb(buffer: mutable.Buffer[MidiMessage], value: Int): Unit = {
    if (isSettingPitchBendSensitivity) {
      pitchBendSensitivity = pitchBendSensitivity.copy(cents = value)
      applyPitchBend(buffer)
    }
  }

  private def pitchBendSensitivity: PitchBendSensitivity = _pitchBendSensitivity

  private def pitchBendSensitivity_=(value: PitchBendSensitivity): Unit = {
    if (_pitchBendSensitivity != value) {
      _pitchBendSensitivity = value
      // Update currTuningPitchBend for the current note using the new sensitivity
      val offset = currTuning(lastNote.pitchClass)
      currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(offset, _pitchBendSensitivity)
    }
  }

  private def lastNote: MidiNote = noteStack.headOption.getOrElse(_lastSingleNote)

  private def isAnyNoteOn: Boolean = noteStack.nonEmpty

  private def applyNoteOn(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int): Unit = {
    _lastNoteOnVelocity = velocity

    buffer += NoteOnScMidiMessage(outputChannel, note, velocity).asJava
  }

  private def turnNoteOn(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int): Unit = {
    // Update currTuningPitchBend
    val newOffset = currTuning(note.pitchClass)
    if (currTuning(lastNote.pitchClass) != newOffset) {
      currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(newOffset, pitchBendSensitivity)
    }

    interruptPedals(buffer)
    applyPitchBend(buffer)
    applyNoteOn(buffer, note, velocity)

    noteStack.push(note)
  }

  private def applyNoteOff(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int): Unit = {
    if (velocity > 0) {
      _lastNoteOffVelocity = velocity

      buffer += NoteOffScMidiMessage(outputChannel, note, velocity).asJava
    } else {
      _lastNoteOffVelocity = NoteOffScMidiMessage.DefaultVelocity

      buffer += NoteOnScMidiMessage(outputChannel, note, 0).asJava
    }
  }

  private def turnNoteOff(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int): Unit = {
    if (!isAnyNoteOn) {
      // Unexpected note off message! According to the internal state no note is known to be on.
      return
    }

    if (note == noteStack.head) {
      applyNoteOff(buffer, note, velocity)

      val oldOffset = currTuning(lastNote.pitchClass)
      noteStack.pop()
      // Play the next note from the top of the stack if available
      if (isAnyNoteOn) {
        val newOffset = currTuning(lastNote.pitchClass)
        if (oldOffset != newOffset) {
          currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(newOffset, pitchBendSensitivity)
        }

        interruptPedals(buffer)
        applyPitchBend(buffer)
        applyNoteOn(buffer, lastNote, _lastNoteOnVelocity)
      } else {
        _lastSingleNote = note
      }
    } else {
      // Removed from the tail of the stack
      noteStack -= note
    }
  }

  /**
   * Turns off and, depending on the pedal, potentially back on the pedals depressed in order to not violate monophony,
   * by stopping the sustained notes.
   */
  private def interruptPedals(buffer: mutable.Buffer[MidiMessage]): Unit = {
    if (_sustainPedal > 0) {
      buffer += CcScMidiMessage(outputChannel, ScMidiCc.SustainPedal, 0).asJava
      buffer += CcScMidiMessage(outputChannel, ScMidiCc.SustainPedal, _sustainPedal).asJava
    }

    if (_sostenutoPedal > 0) {
      // Sostenuto pedal only has effect if depressed after playing a note, so there is no sense in depressing it again
      _sostenutoPedal = 0

      buffer += CcScMidiMessage(outputChannel, ScMidiCc.SostenutoPedal, 0).asJava
    }
  }

  private def currExpressionPitchBend: Int = _currExpressionPitchBend

  private def currExpressionPitchBend_=(value: Int): Unit = {
    _currExpressionPitchBend = value
    _unsentPitchBend = true
  }

  private def currTuningPitchBend: Int = _currTuningPitchBend

  private def currTuningPitchBend_=(value: Int): Unit = {
    _currTuningPitchBend = value
    _unsentPitchBend = true
  }

  private def currPitchBend: Int = clampValue(
    this.currExpressionPitchBend + this.currTuningPitchBend,
    PitchBendScMidiMessage.MinValue,
    PitchBendScMidiMessage.MaxValue
  )

  /**
   * Generates a pitch bend MIDI message if the pitch bend value has changed since the last call.
   *
   * @return An `Option` containing the newly generated `ShortMessage` with pitch bend if the pitch bend value has
   *         changed, or `None` if there is no change.
   */
  private def applyPitchBend(): Option[ShortMessage] = {
    // Only send the pitch bend value if it changed since the last call
    if (_unsentPitchBend) {
      _unsentPitchBend = false

      Some(PitchBendScMidiMessage(outputChannel, currPitchBend).asJava.asInstanceOf[ShortMessage])
    } else {
      None
    }
  }

  private def applyPitchBend(buffer: mutable.Buffer[MidiMessage]): Unit = {
    buffer ++= applyPitchBend()
  }
}

object MonophonicPitchBendTuner {
  val TypeName: String = "monophonicPitchBend"
}

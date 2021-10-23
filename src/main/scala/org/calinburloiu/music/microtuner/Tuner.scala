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

package org.calinburloiu.music.microtuner

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtuner.midi.{MidiNote, MidiProcessor, MidiTuningFormat, PitchBendSensitivity, ScNoteOffMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage}
import org.calinburloiu.music.tuning.Tuning

import javax.sound.midi.MidiMessage

trait Tuner {
  def tune(tuning: Tuning): Unit
}

trait TunerProcessor extends Tuner with MidiProcessor

class TunerException(cause: Throwable) extends RuntimeException(
  "Failed to send tune message to device! Did you disconnect the device?", cause)


trait LoggerTuner extends Tuner with StrictLogging {

  import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._

  abstract override def tune(tuning: Tuning): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")

    super.tune(tuning)
  }
}

/**
 * MIDI Tuning Standard (MTS) `Tuner` implementation.
 * @param tuningFormat one of the MTS formats supported
 */
class MtsTuner(val tuningFormat: MidiTuningFormat,
               val thru: Boolean) extends TunerProcessor with StrictLogging {

  private val tuningMessageGenerator = tuningFormat.messageGenerator

  @throws[TunerException]
  override def tune(tuning: Tuning): Unit = {
    val sysexMessage = tuningMessageGenerator.generate(tuning)
    // TODO Handle the try somewhere else
    try {
      receiver.send(sysexMessage, -1)
    } catch {
      case e: IllegalStateException => throw new TunerException(e)
    }
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    if (thru) {
      try {
        receiver.send(message, timeStamp)
      } catch {
        case e: IllegalStateException => throw new TunerException(e)
      }
    }
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}

class MonophonicPitchBendTuner(private val channel: Int,
                               val pitchBendSensitivity: PitchBendSensitivity) extends TunerProcessor {
  private[this] var _currTuning: Tuning = Tuning.Edo12
  private[this] var _lastNote: MidiNote = 0
  private[this] var _isNoteOn: Boolean = false
  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private[this] var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private[this] var _currTuningPitchBend: Int = 0

  override def tune(tuning: Tuning): Unit = {
    currTuning = tuning
    // Update pitch bend for the current sounding note
    if (isNoteOn) {
      // TODO Might redundantly send message if the tuning pitch bend did not change
      receiver.send(ScPitchBendMidiMessage(channel, currPitchBend).javaMidiMessage, -1)
    }
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = message match {
    case ScNoteOnMidiMessage(`channel`, note, _) =>
      // Only monophonic playing is allowed, if a note is on, turn it off
      if (isNoteOn) {
        // TODO received timeStamp or -1?
        receiver.send(ScNoteOffMidiMessage(channel, note).javaMidiMessage, timeStamp)
      }

      // Internally mark the note on to update currPitchBend; the message will not be send yet
      markNoteOn(note)

      // Send pitch bend message before the note on message
      // TODO Might redundantly send message if the tuning pitch bend did not change
      receiver.send(ScPitchBendMidiMessage(channel, currPitchBend).javaMidiMessage, timeStamp)
      receiver.send(message, timeStamp)
    case ScNoteOffMidiMessage(`channel`, `lastNote`, _) =>
      markNoteOff()
      receiver.send(message, timeStamp)
    case ScPitchBendMidiMessage(`channel`, newExpressionPitchBend) =>
      _currExpressionPitchBend = newExpressionPitchBend
      receiver.send(ScPitchBendMidiMessage(channel, currPitchBend).javaMidiMessage, timeStamp)
    case _ =>
      receiver.send(message, timeStamp)
  }

  private def currTuning: Tuning = _currTuning
  private def currTuning_=(newTuning: Tuning): Unit = {
    // Update _currTuningPitchBend
    val newDeviation = newTuning(lastNote.pitchClassNumber)
    if (currTuning(lastNote.pitchClassNumber) != newDeviation) {
      _currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    _currTuning = newTuning
  }

  private def lastNote: MidiNote = _lastNote
  private def isNoteOn: Boolean = _isNoteOn
  private def markNoteOn(note: MidiNote): Unit = {
    // Update _currTuningPitchBend
    val newDeviation = currTuning(note.pitchClassNumber)
    if (currTuning(lastNote.pitchClassNumber) != newDeviation) {
      _currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    _lastNote = note
    _isNoteOn = true
  }
  private def markNoteOff(): Unit = {
    _isNoteOn = false
  }

  private def currPitchBend: Int = this._currExpressionPitchBend + this._currTuningPitchBend
}


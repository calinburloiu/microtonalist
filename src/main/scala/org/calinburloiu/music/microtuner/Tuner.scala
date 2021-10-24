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
import org.calinburloiu.music.microtuner.midi.{MidiNote, MidiProcessor, MidiTuningFormat, PitchBendSensitivity, ScNoteOffMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage, mapShortMessageChannel}
import org.calinburloiu.music.tuning.Tuning

import javax.sound.midi.{MidiMessage, ShortMessage}

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

class MonophonicPitchBendTuner(private val outputChannel: Int,
                               val pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default)
  extends TunerProcessor with StrictLogging {
  private[this] var _currTuning: Tuning = Tuning.Edo12
  private[this] var _lastNote: MidiNote = 0
  private[this] var _isNoteOn: Boolean = false
  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private[this] var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private[this] var _currTuningPitchBend: Int = 0
  private[this] var _unsentPitchBend: Boolean = false

  override def tune(tuning: Tuning): Unit = {
    currTuning = tuning
    // Update pitch bend for the current sounding note
    if (isNoteOn) {
      sendPitchBend()
    }
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    val forwardedMessage = mapShortMessageChannel(message, _ => outputChannel)

    def onNoteOff(note: MidiNote): Unit = {
      if (note == lastNote) {
        markNoteOff()
        receiver.send(forwardedMessage, -1)
      }
    }

    message match {
      case ScNoteOnMidiMessage(_, note, _) =>
        // Only monophonic playing is allowed, if a note is on, turn it off
        if (isNoteOn) {
          receiver.send(ScNoteOffMidiMessage(outputChannel, lastNote).javaMidiMessage, -1)
        }

        // Internally mark the note on to update currPitchBend; the message will not be send yet
        markNoteOn(note)

        // Send pitch bend message before the note on message
        sendPitchBend()
        receiver.send(forwardedMessage, -1)
      case ScNoteOffMidiMessage(_, note, _) =>
        onNoteOff(note)
      case ScNoteOnMidiMessage(_, note, 0) =>
        onNoteOff(note)
      case ScPitchBendMidiMessage(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        receiver.send(ScPitchBendMidiMessage(outputChannel, currPitchBend).javaMidiMessage, -1)
      case _ =>
        receiver.send(forwardedMessage, -1)
    }
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")

  private def sendPitchBend(): Unit = {
    if (_unsentPitchBend) {
      receiver.send(ScPitchBendMidiMessage(outputChannel, currPitchBend).javaMidiMessage, -1)
      _unsentPitchBend = false
    }
  }

  private def currTuning: Tuning = _currTuning
  private def currTuning_=(newTuning: Tuning): Unit = {
    // Update currTuningPitchBend
    val newDeviation = newTuning(lastNote.pitchClassNumber)
    if (currTuning(lastNote.pitchClassNumber) != newDeviation) {
      currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    _currTuning = newTuning
  }

  private def lastNote: MidiNote = _lastNote
  private def isNoteOn: Boolean = _isNoteOn
  private def markNoteOn(note: MidiNote): Unit = {
    // Update currTuningPitchBend
    val newDeviation = currTuning(note.pitchClassNumber)
    if (currTuning(lastNote.pitchClassNumber) != newDeviation) {
      currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    _lastNote = note
    _isNoteOn = true
  }
  private def markNoteOff(): Unit = {
    _isNoteOn = false
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

  private def currPitchBend: Int = this.currExpressionPitchBend + this.currTuningPitchBend
}


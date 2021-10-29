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
import org.calinburloiu.music.microtuner.midi.{MidiNote, MidiProcessor, MidiTuningFormat, PitchBendSensitivity, Rpn, ScCcMidiMessage, ScNoteOffMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage, mapShortMessageChannel}
import org.calinburloiu.music.tuning.Tuning

import javax.sound.midi.MidiMessage
import scala.collection.mutable

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
 *
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

  override def close(): Unit = {
    super.close()
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
  }

  override protected def onConnect(): Unit = {
    logger.info(s"Connected the MTS tuner.")
  }

  override protected def onDisconnect(): Unit = {
    tune(Tuning.Edo12)
    logger.info("Disconnected the MTS tuner.")
  }
}

class MonophonicPitchBendTuner(private val outputChannel: Int,
                               val pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default)
  extends TunerProcessor with StrictLogging {
  private[this] var _currTuning: Tuning = Tuning.Edo12

  private[this] val noteStack: mutable.Stack[MidiNote] = mutable.Stack()
  private[this] var _lastSingleNote: MidiNote = 0

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

    message match {
      case ScNoteOnMidiMessage(_, note, 0) =>
        turnNoteOff(note, 0)
      case ScNoteOnMidiMessage(_, note, velocity) =>
        // Only monophonic playing is allowed, if a note is on, turn it off
        if (isNoteOn) {
          // Using the velocity from note on
          sendNoteOff(lastNote, velocity)
        }

        // Internally mark the note on to update currPitchBend; the message will not be send yet
        turnNoteOn(note, velocity)
      case ScNoteOffMidiMessage(_, note, velocity) =>
        turnNoteOff(note, velocity)
      case ScPitchBendMidiMessage(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        sendPitchBend()
      case _ =>
        receiver.send(forwardedMessage, -1)
    }
  }

  override protected def onConnect(): Unit = {
    sendPitchSensitivity(pitchBendSensitivity)

    logger.info(s"Connected the monophonic pitch bend tuner.")
  }

  override protected def onDisconnect(): Unit = {
    // Unset the pitch bend
    val noPitchBendMessage = ScPitchBendMidiMessage(outputChannel, ScPitchBendMidiMessage.NoPitchBendValue)
    receiver.send(noPitchBendMessage.javaMidiMessage, -1)

    // Reset pitch bend sensitivity to the default value
    sendPitchSensitivity(PitchBendSensitivity.Default)

    logger.info(s"Disconnected the monophonic pitch bend tuner.")
  }

  override def close(): Unit = {
    super.close()
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
  }

  private def sendPitchSensitivity(pitchBendSensitivity: PitchBendSensitivity): Unit = {
    Seq(
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.DataEntryMsb, pitchBendSensitivity.semitones),
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.DataEntryLsb, pitchBendSensitivity.cents),
      // Setting the parameter number to Null to prevent accidental changes of values
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      ScCcMidiMessage(outputChannel, ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    ).foreach { scMessage => receiver.send(scMessage.javaMidiMessage, -1) }
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

  private def lastNote: MidiNote = noteStack.headOption.getOrElse(_lastSingleNote)

  private def isNoteOn: Boolean = noteStack.nonEmpty

  private def sendNoteOn(note: MidiNote, velocity: Int): Unit = {
    receiver.send(ScNoteOnMidiMessage(outputChannel, note, velocity).javaMidiMessage, -1)
  }

  private def turnNoteOn(note: MidiNote, velocity: Int): Unit = {
    // Update currTuningPitchBend
    val newDeviation = currTuning(note.pitchClassNumber)
    if (currTuning(lastNote.pitchClassNumber) != newDeviation) {
      currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    // Send pitch bend message before the note on message
    sendPitchBend()
    sendNoteOn(note, velocity)

    noteStack.push(note)
  }

  private def sendNoteOff(note: MidiNote, velocity: Int): Unit = {
    if (velocity > 0) {
      receiver.send(ScNoteOffMidiMessage(outputChannel, note, velocity).javaMidiMessage, -1)
    } else {
      receiver.send(ScNoteOnMidiMessage(outputChannel, note, 0).javaMidiMessage, -1)
    }
  }

  private def turnNoteOff(note: MidiNote, velocity: Int): Unit = {
    if (!isNoteOn) {
      return
    }

    if (note == noteStack.head) {
      sendNoteOff(note, velocity)

      val oldDeviation = currTuning(lastNote.pitchClassNumber)
      noteStack.pop()
      // Play the next note from the top of the stack
      if (isNoteOn) {
        val newDeviation = currTuning(lastNote.pitchClassNumber)
        if (oldDeviation != newDeviation) {
          currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
        }

        // Send pitch bend message before the note on message
        sendPitchBend()
        // Trying to use the note off velocity for note on if possible, otherwise, use the default one
        val noteOnVelocity = if (velocity > 0) velocity else ScNoteOnMidiMessage.DefaultVelocity
        sendNoteOn(lastNote, noteOnVelocity)
      } else {
        _lastSingleNote = note
      }
    } else {
      // Removed from the tail of the stack
      noteStack -= note
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

  private def currPitchBend: Int = Math.max(ScPitchBendMidiMessage.MinValue,
    Math.min(ScPitchBendMidiMessage.MaxValue, this.currExpressionPitchBend + this.currTuningPitchBend))

  private def sendPitchBend(): Unit = {
    if (_unsentPitchBend) {
      receiver.send(ScPitchBendMidiMessage(outputChannel, currPitchBend).javaMidiMessage, -1)
      _unsentPitchBend = false
    }
  }
}

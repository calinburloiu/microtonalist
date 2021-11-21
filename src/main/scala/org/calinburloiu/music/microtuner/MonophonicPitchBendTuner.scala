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
import org.calinburloiu.music.microtuner.midi.{MidiNote, PitchBendSensitivity, Rpn, ScCcMidiMessage, ScNoteOffMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage, clampValue, mapShortMessageChannel}
import org.calinburloiu.music.tuning.OctaveTuning

import javax.sound.midi.MidiMessage
import scala.collection.mutable

/**
 * Tuner that uses pitch bend to tune notes. Because pitch bend MIDI messages affect the whole channel they are sent
 * on, this tuner only supports and enforces monophonic playing.
 *
 * @param outputChannel Output MIDI channel on which all output is sent, regardless on the input channels used.
 * @param pitchBendSensitivity Pitch bend range that will be configured via Pitch Bend Sensitivity MIDI RPN.
 */
class MonophonicPitchBendTuner(private val outputChannel: Int,
                               val pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default)
  extends TunerProcessor with StrictLogging {
  private[this] var _currTuning: OctaveTuning = OctaveTuning.Edo12

  private[this] val noteStack: mutable.Stack[MidiNote] = mutable.Stack()
  private[this] var _lastSingleNote: MidiNote = 0

  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private[this] var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private[this] var _currTuningPitchBend: Int = 0
  private[this] var _unsentPitchBend: Boolean = false

  private[this] var _lastNoteOnVelocity = ScNoteOnMidiMessage.DefaultVelocity
  private[this] var _lastNoteOffVelocity = ScNoteOffMidiMessage.DefaultVelocity

  private[this] var _sustainPedal: Int = 0
  private[this] var _sostenutoPedal: Int = 0

  override def tune(tuning: OctaveTuning): Unit = {
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
          sendNoteOff(lastNote, _lastNoteOffVelocity)
        }
        turnNoteOn(note, velocity)
      case ScNoteOffMidiMessage(_, note, velocity) =>
        turnNoteOff(note, velocity)
      case ScPitchBendMidiMessage(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        sendPitchBend()
      case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, value) =>
        _sustainPedal = value
        receiver.send(forwardedMessage, -1)
      case ScCcMidiMessage(_, ScCcMidiMessage.SostenutoPedal, value) =>
        _sostenutoPedal = value
        receiver.send(forwardedMessage, -1)
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

  private def currTuning: OctaveTuning = _currTuning

  private def currTuning_=(newTuning: OctaveTuning): Unit = {
    // Update currTuningPitchBend
    val newDeviation = newTuning(lastNote.pitchClass)
    if (currTuning(lastNote.pitchClass) != newDeviation) {
      currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    _currTuning = newTuning
  }

  private def lastNote: MidiNote = noteStack.headOption.getOrElse(_lastSingleNote)

  private def isNoteOn: Boolean = noteStack.nonEmpty

  private def sendNoteOn(note: MidiNote, velocity: Int): Unit = {
    receiver.send(ScNoteOnMidiMessage(outputChannel, note, velocity).javaMidiMessage, -1)
    _lastNoteOnVelocity = velocity
  }

  private def turnNoteOn(note: MidiNote, velocity: Int): Unit = {
    // Update currTuningPitchBend
    val newDeviation = currTuning(note.pitchClass)
    if (currTuning(lastNote.pitchClass) != newDeviation) {
      currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
    }

    interruptPedals()
    sendPitchBend()
    sendNoteOn(note, velocity)

    noteStack.push(note)
  }

  private def sendNoteOff(note: MidiNote, velocity: Int): Unit = {
    if (velocity > 0) {
      receiver.send(ScNoteOffMidiMessage(outputChannel, note, velocity).javaMidiMessage, -1)
      _lastNoteOffVelocity = velocity
    } else {
      receiver.send(ScNoteOnMidiMessage(outputChannel, note, 0).javaMidiMessage, -1)
      _lastNoteOffVelocity = ScNoteOffMidiMessage.DefaultVelocity
    }
  }

  private def turnNoteOff(note: MidiNote, velocity: Int): Unit = {
    if (!isNoteOn) {
      // Unexpected note off message! According to the internal state no note is known to be on.
      return
    }

    if (note == noteStack.head) {
      sendNoteOff(note, velocity)

      val oldDeviation = currTuning(lastNote.pitchClass)
      noteStack.pop()
      // Play the next note from the top of the stack if available
      if (isNoteOn) {
        val newDeviation = currTuning(lastNote.pitchClass)
        if (oldDeviation != newDeviation) {
          currTuningPitchBend = ScPitchBendMidiMessage.convertCentsToValue(newDeviation, pitchBendSensitivity)
        }

        interruptPedals()
        sendPitchBend()
        sendNoteOn(lastNote, _lastNoteOnVelocity)
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
  private def interruptPedals(): Unit = {
    if (_sustainPedal > 0) {
      receiver.send(ScCcMidiMessage(outputChannel, ScCcMidiMessage.SustainPedal, 0).javaMidiMessage, -1)
      receiver.send(ScCcMidiMessage(outputChannel, ScCcMidiMessage.SustainPedal, _sustainPedal).javaMidiMessage, -1)
    }
    if (_sostenutoPedal > 0) {
      // Sostenuto pedal only has effect if depressed after playing a note, so there is no sense in depressing it again
      receiver.send(ScCcMidiMessage(outputChannel, ScCcMidiMessage.SostenutoPedal, 0).javaMidiMessage, -1)
      _sostenutoPedal = 0
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
    ScPitchBendMidiMessage.MinValue,
    ScPitchBendMidiMessage.MaxValue
  )

  private def sendPitchBend(): Unit = {
    // Only send the pitch bend value if it changed since the last call
    if (_unsentPitchBend) {
      receiver.send(ScPitchBendMidiMessage(outputChannel, currPitchBend).javaMidiMessage, -1)
      _unsentPitchBend = false
    }
  }
}

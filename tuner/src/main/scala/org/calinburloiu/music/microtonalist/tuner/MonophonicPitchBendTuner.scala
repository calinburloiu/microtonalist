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
import org.calinburloiu.music.scmidi.{MidiNote, PitchBendSensitivity, PitchBendSensitivityMessages, ScMidiChannelStateTracker, clampValue, mapShortMessageChannel}

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

  // The tuner is monophonic and channel-agnostic on input, so all incoming messages are normalized to a
  // single tracker slot. `outputChannel` is reused as that slot — it's already a valid 0..15 channel.
  private def trackedChannel: Int = outputChannel

  private var _currTuning: Tuning = Tuning.Standard
  private var _pitchBendSensitivity: PitchBendSensitivity = defaultPitchBendSensitivity

  private val tracker: ScMidiChannelStateTracker = ScMidiChannelStateTracker()
  private var _lastSingleNote: MidiNote = 0

  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private var _currTuningPitchBend: Int = 0
  private var _unsentPitchBend: Boolean = false

  private var _lastNoteOnVelocity = NoteOnScMidiMessage.DefaultVelocity
  private var _lastNoteOffVelocity = NoteOffScMidiMessage.DefaultVelocity

  override def reset(): Seq[MidiMessage] = {
    this._resetState()
    this._init()
  }

  private def _resetState(): Unit = {
    _currTuning = Tuning.Standard
    _pitchBendSensitivity = defaultPitchBendSensitivity
    tracker.reset()
    _lastSingleNote = 0
    _currExpressionPitchBend = 0
    _currTuningPitchBend = 0
    _unsentPitchBend = false
    _lastNoteOnVelocity = NoteOnScMidiMessage.DefaultVelocity
    _lastNoteOffVelocity = NoteOffScMidiMessage.DefaultVelocity
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
    val scMessage = message.asScala

    // `turnNoteOn` / `turnNoteOff` need to know which notes were held down *before* this message.
    // Capture the pre-message state once, then update the tracker so all other reads (CC values,
    // RPN selector, Channel Pressure, etc.) see fresh state during the rest of the handling.
    val prevNotes = tracker.orderedActiveNotes(trackedChannel)
    val prevLastNote = prevNotes.lastOption.getOrElse(_lastSingleNote)
    sendToTracker(scMessage)

    scMessage match {
      case NoteOnScMidiMessage(_, note, 0) =>
        turnNoteOff(buffer, note, 0, prevNotes)
      case NoteOnScMidiMessage(_, note, velocity) =>
        // Only monophonic playing is allowed, if a note is on, turn it off
        if (prevNotes.nonEmpty) {
          applyNoteOff(buffer, prevLastNote, _lastNoteOffVelocity)
        }
        turnNoteOn(buffer, note, velocity, prevLastNote)
      case NoteOffScMidiMessage(_, note, velocity) =>
        turnNoteOff(buffer, note, velocity, prevNotes)
      case PitchBendScMidiMessage(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        applyPitchBend(buffer)
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

  private def sendToTracker(scMessage: ScMidiMessage): Unit = {
    // Re-pressing an already-active note must move it to the most-recently-inserted position so
    // that `tracker.orderedActiveNotes(...).last` continues to reflect the audibly sounding note.
    // The tracker stores active notes in a `LinkedHashMap`, which keeps the original position
    // when an existing key is updated, so explicitly remove the note first.
    scMessage match {
      case m: NoteOnScMidiMessage if m.velocity > 0 && tracker.isNoteActive(trackedChannel, m.midiNote) =>
        tracker.send(NoteOffScMidiMessage(trackedChannel, m.midiNote))
      case _ =>
    }

    val normalized = scMessage match {
      case m: NoteOnScMidiMessage => m.copy(channel = trackedChannel)
      case m: NoteOffScMidiMessage => m.copy(channel = trackedChannel)
      case m: PitchBendScMidiMessage => m.copy(channel = trackedChannel)
      case m: CcScMidiMessage => m.copy(channel = trackedChannel)
      case m: ChannelPressureScMidiMessage => m.copy(channel = trackedChannel)
      case m: PolyPressureScMidiMessage => m.copy(channel = trackedChannel)
      case m: ProgramChangeScMidiMessage => m.copy(channel = trackedChannel)
      case m => m
    }
    tracker.send(normalized)
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
    tracker.rpnSelector(trackedChannel) == ScMidiChannelStateTracker.RpnSelector.Rpn(
      ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

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

  private def lastNote: MidiNote =
    tracker.orderedActiveNotes(trackedChannel).lastOption.getOrElse(_lastSingleNote)

  private def isAnyNoteOn: Boolean = tracker.orderedActiveNotes(trackedChannel).nonEmpty

  private def applyNoteOn(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int): Unit = {
    _lastNoteOnVelocity = velocity

    buffer += NoteOnScMidiMessage(outputChannel, note, velocity).asJava
  }

  private def turnNoteOn(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int,
                         prevLastNote: MidiNote): Unit = {
    // Update currTuningPitchBend by comparing against the tuning offset of the previously held note
    val newOffset = currTuning(note.pitchClass)
    if (currTuning(prevLastNote.pitchClass) != newOffset) {
      currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(newOffset, pitchBendSensitivity)
    }

    interruptPedals(buffer)
    applyPitchBend(buffer)
    applyNoteOn(buffer, note, velocity)
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

  private def turnNoteOff(buffer: mutable.Buffer[MidiMessage], note: MidiNote, velocity: Int,
                          prevNotes: Seq[MidiNote]): Unit = {
    if (prevNotes.nonEmpty && prevNotes.last == note) {
      applyNoteOff(buffer, note, velocity)

      val oldOffset = currTuning(note.pitchClass)
      // After tracker.send the released note is gone, so the post-state can be read fresh
      val notesAfter = tracker.orderedActiveNotes(trackedChannel)
      // Play the next note from the previous one held down, if available
      if (notesAfter.nonEmpty) {
        val newLast = notesAfter.last
        val newOffset = currTuning(newLast.pitchClass)
        if (oldOffset != newOffset) {
          currTuningPitchBend = PitchBendScMidiMessage.convertCentsToValue(newOffset, pitchBendSensitivity)
        }

        interruptPedals(buffer)
        applyPitchBend(buffer)
        applyNoteOn(buffer, newLast, _lastNoteOnVelocity)
      } else {
        _lastSingleNote = note
      }
    }
    // Otherwise: either no note was on (unexpected note off) or the released note was not the most recent;
    // the tracker has already removed it on send, so no audible change is needed.
  }

  /**
   * Turns off and, depending on the pedal, potentially back on the pedals depressed in order to not violate monophony,
   * by stopping the sustained notes.
   */
  private def interruptPedals(buffer: mutable.Buffer[MidiMessage]): Unit = {
    val sustain = tracker.cc(trackedChannel, ScMidiCc.SustainPedal, Some(0))
    if (sustain > 0) {
      buffer += CcScMidiMessage(outputChannel, ScMidiCc.SustainPedal, 0).asJava
      buffer += CcScMidiMessage(outputChannel, ScMidiCc.SustainPedal, sustain).asJava
    }

    val sostenuto = tracker.cc(trackedChannel, ScMidiCc.SostenutoPedal, Some(0))
    if (sostenuto > 0) {
      // Sostenuto pedal only has effect if depressed after playing a note, so there is no sense in depressing it again.
      // Replay a SostenutoPedal=0 to the tracker to reflect the interrupted state internally.
      tracker.send(CcScMidiMessage(trackedChannel, ScMidiCc.SostenutoPedal, 0))

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

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

import org.calinburloiu.music.scmidi.message.*
import org.calinburloiu.music.scmidi.{MidiChannelStateTracker, clampValue}
import org.calinburloiu.music.scmidi.{MidiNote, PitchBendSensitivity, PitchBendSensitivityMessages, RpnMessages}

import scala.collection.mutable

/**
 * Tuner that uses pitch bend to tune notes. Because pitch bend MIDI messages affect the whole channel they are sent
 * on, this tuner only supports and enforces monophonic playing.
 *
 * It can only tune exactly the offsets within its current pitch bend sensitivity, see [[canTune]], and clamps any other
 * offset to the sensitivity. This includes an offset of the current tuning that the sensitivity decreases below, on
 * reset or when the input sends a Pitch Bend Sensitivity RPN.
 *
 * @param outputChannel               Output MIDI channel on which all output is sent, regardless on the input
 *                                    channels used.
 * @param defaultPitchBendSensitivity Default pitch bend range that will be configured via Pitch Bend Sensitivity
 *                                    MIDI RPN.
 */
case class MonophonicPitchBendTuner(outputChannel: Int,
                                    defaultPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default)
  extends Tuner {
  require(0 <= outputChannel && outputChannel <= 15,
    s"Output MIDI channel must be between 0 and 15, but was $outputChannel!")

  override val typeName: String = MonophonicPitchBendTuner.TypeName

  // The tuner is monophonic and channel-agnostic on input, so all incoming messages are normalized to a
  // single tracker slot. `outputChannel` is reused as that slot — it's already a valid 0..15 channel.
  private def trackedChannel: Int = outputChannel

  private var _pitchBendSensitivity: PitchBendSensitivity = defaultPitchBendSensitivity

  private val tracker: MidiChannelStateTracker = MidiChannelStateTracker()
  private var _lastSingleNote: MidiNote = 0

  /** Pitch bend applied by the performer to the current note before applying the extra tuning value */
  private var _currExpressionPitchBend: Int = 0
  /** Extra pitch bend added to achieve the tuning for the current note */
  private var _currTuningPitchBend: Int = 0
  private var _unsentPitchBend: Boolean = false

  private var _lastNoteOnVelocity = NoteOnMidiMsg.DefaultVelocity
  private var _lastNoteOffVelocity = NoteOffMidiMsg.DefaultVelocity

  /**
   * @inheritdoc
   *
   * This tuner stops the note sounding, if any, and releases the Sustain and Sostenuto pedals left down. After
   * clearing its state, it resets the pitch bend and configures the pitch bend sensitivity.
   *
   * The pitch bend is reset to 0 whatever the output device holds, because the cleared state assumes that it holds no
   * pitch bend, whereas it may still hold the one of the last note played.
   */
  override protected def onReset(): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    stopSounding(buffer)
    _resetState()

    buffer += PitchBendMidiMsg(outputChannel, 0)
    buffer ++= PitchBendSensitivityMessages.create(outputChannel, defaultPitchBendSensitivity)

    buffer.toSeq
  }

  /** Stops the note sounding on the output device, if any, and releases the pedals that are down. */
  private def stopSounding(buffer: mutable.Buffer[MidiMsg]): Unit = {
    // Only the last note held sounds, the previous ones having been stopped to keep playing monophonic
    if (isAnyNoteOn) {
      applyNoteOff(buffer, lastNote, _lastNoteOffVelocity)
    }

    for (pedal <- Tuner.NoteHoldingPedals if tracker.cc(trackedChannel, pedal, Some(0)) > 0) {
      buffer += CcMidiMsg(outputChannel, pedal, 0)
    }
  }

  private def _resetState(): Unit = {
    _pitchBendSensitivity = defaultPitchBendSensitivity
    tracker.reset()
    _lastSingleNote = 0
    _currExpressionPitchBend = 0
    _currTuningPitchBend = 0
    _unsentPitchBend = false
    _lastNoteOnVelocity = NoteOnMidiMsg.DefaultVelocity
    _lastNoteOffVelocity = NoteOffMidiMsg.DefaultVelocity
  }

  /**
   * @inheritdoc
   *
   * This tuner can tune a tuning whose offsets are all within the current pitch bend sensitivity, which starts as the
   * default one, changes when the input sends a Pitch Bend Sensitivity RPN, and returns to the default on reset.
   */
  override def canTune(tuning: Tuning): Boolean =
    tuning.offsets.forall(offset => Math.abs(offset) <= pitchBendSensitivity.totalCents)

  override protected def onTune(tuning: Tuning, previousTuning: Option[Tuning]): Seq[MidiMsg] = {
    // Only a changed value is marked for sending, so that a tuning which keeps the last note's offset sends nothing
    val newTuningPitchBend = tuningPitchBendOf(tuning(lastNote.pitchClass))
    if (newTuningPitchBend != currTuningPitchBend) {
      currTuningPitchBend = newTuningPitchBend
    }

    // Update pitch bend for the current sounding note
    if (isAnyNoteOn) applyPitchBend().toSeq else Seq.empty
  }

  override def process(message: MidiMsg): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    val forwardMessage = () => message match {
      case channelMessage: ChannelMidiMsg => channelMessage.mapChannel(_ => outputChannel)
      case _ => message
    }

    // `turnNoteOn` / `turnNoteOff` need to know which notes were held down *before* this message.
    // Capture the pre-message state once, then update the tracker so all other reads (CC values,
    // RPN selector, Channel Pressure, etc.) see fresh state during the rest of the handling.
    val prevNotes = tracker.orderedActiveNotes(trackedChannel)
    val prevLastNote = prevNotes.lastOption.getOrElse(_lastSingleNote)
    sendToTracker(message)

    message match {
      case NoteOnMidiMsg(_, note, 0) =>
        turnNoteOff(buffer, note, 0, prevNotes)
      case NoteOnMidiMsg(_, note, velocity) =>
        // Only monophonic playing is allowed, if a note is on, turn it off
        if (prevNotes.nonEmpty) {
          applyNoteOff(buffer, prevLastNote, _lastNoteOffVelocity)
        }
        turnNoteOn(buffer, note, velocity, prevLastNote)
      case NoteOffMidiMsg(_, note, velocity) =>
        turnNoteOff(buffer, note, velocity, prevNotes)
      case PitchBendMidiMsg(_, newExpressionPitchBend) =>
        currExpressionPitchBend = newExpressionPitchBend
        applyPitchBend(buffer)
      case CcMidiMsg(_, MidiCc.DataEntryMsb, value) =>
        buffer += forwardMessage()
        applyPitchBendSensitivityMsb(buffer, value)
      case CcMidiMsg(_, MidiCc.DataEntryLsb, value) =>
        buffer += forwardMessage()
        applyPitchBendSensitivityLsb(buffer, value)
      case _ =>
        buffer += forwardMessage()
    }

    buffer.toSeq
  }

  private def sendToTracker(message: MidiMsg): Unit = {
    val normalized = message match {
      case m: ChannelMidiMsg => m.mapChannel(_ => trackedChannel)
      case m => m
    }
    tracker.send(normalized)
  }

  private def isSettingPitchBendSensitivity: Boolean =
    tracker.rpnSelector(trackedChannel) == RpnMessages.PitchBendSensitivitySelector

  private def applyPitchBendSensitivityMsb(buffer: mutable.Buffer[MidiMsg], value: Int): Unit = {
    if (isSettingPitchBendSensitivity) {
      pitchBendSensitivity = pitchBendSensitivity.copy(semitones = value)
      applyPitchBend(buffer)
    }
  }

  private def applyPitchBendSensitivityLsb(buffer: mutable.Buffer[MidiMsg], value: Int): Unit = {
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
      val offset = tuning(lastNote.pitchClass)
      currTuningPitchBend = tuningPitchBendOf(offset)
    }
  }

  /**
   * The Pitch Bend that tunes a note by the given tuning offset, in the current pitch bend sensitivity.
   *
   * The offset is clamped to the pitch bend sensitivity first, since the tuning may be beyond it: [[tune]] applies a
   * tuning that [[canTune]] does not accept, and the sensitivity may decrease afterward, on reset or when the input
   * sends a Pitch Bend Sensitivity RPN.
   */
  private def tuningPitchBendOf(offset: Double): Int = {
    val maxOffset = pitchBendSensitivity.totalCents
    PitchBendMidiMsg.convertCentsToValue(clampValue(offset, -maxOffset, maxOffset), pitchBendSensitivity)
  }

  private def lastNote: MidiNote =
    tracker.orderedActiveNotes(trackedChannel).lastOption.getOrElse(_lastSingleNote)

  private def isAnyNoteOn: Boolean = tracker.orderedActiveNotes(trackedChannel).nonEmpty

  private def applyNoteOn(buffer: mutable.Buffer[MidiMsg], note: MidiNote, velocity: Int): Unit = {
    _lastNoteOnVelocity = velocity

    buffer += NoteOnMidiMsg(outputChannel, note, velocity)
  }

  private def turnNoteOn(buffer: mutable.Buffer[MidiMsg], note: MidiNote, velocity: Int,
                         prevLastNote: MidiNote): Unit = {
    // Update currTuningPitchBend by comparing against the tuning offset of the previously held note
    val newOffset = tuning(note.pitchClass)
    if (tuning(prevLastNote.pitchClass) != newOffset) {
      currTuningPitchBend = tuningPitchBendOf(newOffset)
    }

    interruptPedals(buffer)
    applyPitchBend(buffer)
    applyNoteOn(buffer, note, velocity)
  }

  private def applyNoteOff(buffer: mutable.Buffer[MidiMsg], note: MidiNote, velocity: Int): Unit = {
    if (velocity > 0) {
      _lastNoteOffVelocity = velocity

      buffer += NoteOffMidiMsg(outputChannel, note, velocity)
    } else {
      _lastNoteOffVelocity = NoteOffMidiMsg.DefaultVelocity

      buffer += NoteOnMidiMsg(outputChannel, note, 0)
    }
  }

  private def turnNoteOff(buffer: mutable.Buffer[MidiMsg], note: MidiNote, velocity: Int,
                          prevNotes: Seq[MidiNote]): Unit = {
    // `turnNoteOff` runs after `sendToTracker`, so `isNoteActive` reads the post-release state: `false` means this
    // Note Off discharged the note's last unmatched Note On and it must actually stop sounding.
    if (prevNotes.nonEmpty && prevNotes.last == note && !tracker.isNoteActive(trackedChannel, note)) {
      applyNoteOff(buffer, note, velocity)

      val oldOffset = tuning(note.pitchClass)
      // The guard established that this Note Off discharged the note's last reference, so the post-update state no
      // longer holds it and can be read fresh
      val notesAfter = tracker.orderedActiveNotes(trackedChannel)
      // Play the next note from the previous one held down, if available
      if (notesAfter.nonEmpty) {
        val newLast = notesAfter.last
        val newOffset = tuning(newLast.pitchClass)
        if (oldOffset != newOffset) {
          currTuningPitchBend = tuningPitchBendOf(newOffset)
        }

        interruptPedals(buffer)
        applyPitchBend(buffer)
        applyNoteOn(buffer, newLast, _lastNoteOnVelocity)
      } else {
        _lastSingleNote = note
      }
    }
    // Otherwise: no note was on (unexpected note off), the released note was not the most recent, or the note is
    // still held down by another Note On this Note Off did not discharge; the tracker has already recorded the
    // release, so no audible change is needed. A partial release under the last case is deliberately inaudible —
    // the note has not stopped sounding — so it also leaves `_lastNoteOffVelocity` unlearned, still holding
    // whatever velocity the note's most recent full release recorded.
  }

  /**
   * Turns off and, depending on the pedal, potentially back on the pedals depressed in order to not violate monophony,
   * by stopping the sustained notes.
   */
  private def interruptPedals(buffer: mutable.Buffer[MidiMsg]): Unit = {
    val sustain = tracker.cc(trackedChannel, MidiCc.SustainPedal, Some(0))
    if (sustain > 0) {
      buffer += CcMidiMsg(outputChannel, MidiCc.SustainPedal, 0)
      buffer += CcMidiMsg(outputChannel, MidiCc.SustainPedal, sustain)
    }

    val sostenuto = tracker.cc(trackedChannel, MidiCc.SostenutoPedal, Some(0))
    if (sostenuto > 0) {
      // Sostenuto pedal only has effect if depressed after playing a note, so there is no sense in depressing it again.
      // Replay a SostenutoPedal=0 to the tracker to reflect the interrupted state internally.
      tracker.send(CcMidiMsg(trackedChannel, MidiCc.SostenutoPedal, 0))

      buffer += CcMidiMsg(outputChannel, MidiCc.SostenutoPedal, 0)
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
    PitchBendMidiMsg.MinValue,
    PitchBendMidiMsg.MaxValue
  )

  /**
   * Generates a pitch bend MIDI message if the pitch bend value has changed since the last call.
   *
   * @return An `Option` containing the newly generated `PitchBendMidiMsg` with pitch bend if the pitch bend value has
   *         changed, or `None` if there is no change.
   */
  private def applyPitchBend(): Option[PitchBendMidiMsg] = {
    // Only send the pitch bend value if it changed since the last call
    if (_unsentPitchBend) {
      _unsentPitchBend = false

      Some(PitchBendMidiMsg(outputChannel, currPitchBend))
    } else {
      None
    }
  }

  private def applyPitchBend(buffer: mutable.Buffer[MidiMsg]): Unit = {
    buffer ++= applyPitchBend()
  }
}

object MonophonicPitchBendTuner {
  val TypeName: String = "monophonicPitchBend"
}

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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.*

import javax.annotation.concurrent.NotThreadSafe
import scala.collection.mutable

/**
 * A [[ScMidiReceiver]] that tracks per-channel MIDI state derived from the messages it receives: active notes
 * (with their velocities, Polyphonic Key Pressure, and a count of the Note On messages no Note Off has yet
 * discharged), Control Change values, Registered and Non-Registered Parameter Number values together with the
 * parameter each channel currently has selected, Channel Pressure, Pitch Bend, and Program Change.
 *
 * Notes are reference-counted: a note struck twice without an intervening release stays active until it has received
 * two Note Off messages, which is what lets a consumer discharge MIDI 1.0's one-Note-Off-per-Note-On obligation.
 * See [[referenceCount]].
 *
 * Default values for Control Change, Registered Parameter Number, and Non-Registered Parameter Number lookups
 * may be supplied via the constructor; if not, the companion object's [[ScMidiChannelStateTracker.DefaultCcValues]],
 * [[ScMidiChannelStateTracker.DefaultRpnValues]], and [[ScMidiChannelStateTracker.DefaultNrpnValues]] are consulted.
 *
 * '''Not thread-safe.''' External synchronization is required when accessed from multiple threads. It should usually
 * be used from a track thread.
 *
 * @param ccDefaults                  per-CC-number default values that override the companion's defaults.
 * @param rpnDefaults                 per-RPN default values that override the companion's defaults.
 * @param nrpnDefaults                per-NRPN default values that override the companion's defaults.
 * @param shallRespondToResetMessages whether the reset Channel Mode messages — All Sound Off (120), Reset All
 *                                    Controllers (121), and All Notes Off (123) — mutate the tracked state. Defaults
 *                                    to `false`, which records those messages as received but leaves the state
 *                                    untouched. Set to `true` when the tracker models a receiver that is known
 *                                    to act on these messages. Independent of this flag, [[reset]] always clears
 *                                    everything.
 */
@NotThreadSafe
class ScMidiChannelStateTracker(ccDefaults: Map[Int, Int] = Map.empty,
                                rpnDefaults: Map[(Int, Int), (Int, Int)] = Map.empty,
                                nrpnDefaults: Map[(Int, Int), (Int, Int)] = Map.empty,
                                shallRespondToResetMessages: Boolean = false) extends ScMidiReceiver {

  import ScMidiChannelStateTracker.*

  private val channelStates: Array[ChannelState] = Array.fill(ChannelCount)(ChannelState())
  private var _closed: Boolean = false

  override def send(message: MidiMsg, timeStamp: Long = -1L): Unit = if (!_closed) message match {
    case NoteOnMidiMsg(channel, midiNote, NoteOnMidiMsg.NoteOffVelocity) =>
      releaseNote(channel, midiNote)
    case NoteOnMidiMsg(channel, midiNote, velocity) =>
      val activeNotes = channelStates(channel).activeNotes
      activeNotes.remove(midiNote) match {
        case Some(activeNote) =>
          activeNote.velocity = velocity
          activeNote.referenceCount += 1
          // Removed and re-inserted rather than updated in place: a LinkedHashMap keeps an updated key at its
          // original position, and active notes are ordered by their most recent Note On.
          activeNotes(midiNote) = activeNote
        case None =>
          activeNotes(midiNote) = ActiveNote(velocity)
      }
    case NoteOffMidiMsg(channel, midiNote, _) =>
      releaseNote(channel, midiNote)
    case PolyPressureMidiMsg(channel, midiNote, value) =>
      channelStates(channel).activeNotes.get(midiNote).foreach(_.polyPressure = value)
    case CcMidiMsg(channel, ccNumber, ccValue) =>
      val state = channelStates(channel)
      state.ccValues(ccNumber) = ccValue
      handleParameterCc(state, ccNumber, ccValue)
      handleChannelModeCc(state, ccNumber)
    case ChannelPressureMidiMsg(channel, value) =>
      channelStates(channel).channelPressure = Some(value)
    case PitchBendMidiMsg(channel, value) =>
      channelStates(channel).pitchBend = Some(value)
    case ProgramChangeMidiMsg(channel, program) =>
      channelStates(channel).programChange = Some(program)
    case _ =>
  }

  override def close(): Unit = {
    _closed = true
  }

  /**
   * Clears all per-channel state on every channel, returning the tracker to the same state as a freshly constructed
   * instance. Constructor-supplied defaults are preserved. No-op once [[close]] has been called.
   *
   * Unlike the Reset All Controllers Channel Mode message — which the MIDI 1.0 spec scopes to a single channel and
   * leaves Bank Select, Volume, Pan, Program Change, and recorded RPN/NRPN values intact — this method wipes
   * everything.
   *
   * @see [[reset(channel:Int):Unit reset(channel: Int)]] for per-channel reset.
   */
  def reset(): Unit = if (!_closed) {
    for (channel <- 0 until ChannelCount) {
      channelStates(channel) = ChannelState()
    }
  }

  /**
   * Clears the per-channel state of a single channel, returning it to the same state as on a freshly constructed
   * tracker and leaving every other channel untouched. Constructor-supplied defaults are preserved. No-op once
   * [[close]] has been called.
   *
   * @param channel The 0-indexed MIDI channel (0-15) to clear.
   */
  def reset(channel: Int): Unit = {
    MidiRequirements.requireChannel(channel)
    if (!_closed) {
      channelStates(channel) = ChannelState()
    }
  }

  /** @return whether [[close]] has been called on this tracker. */
  def isClosed: Boolean = _closed

  /** @return the set of currently active notes on the given channel — those holding at least one undischarged
   *          Note On. */
  def activeNotes(channel: Int): Set[MidiNote] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.keySet.toSet
  }

  /** @return the currently active notes on the given channel, in order of their most recent Note On. Each note
   *          appears exactly once, no matter how large its reference count is; a duplicate Note On for an
   *          already-active note does not add a second entry, it only moves the existing one to the end. */
  def orderedActiveNotes(channel: Int): Seq[MidiNote] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.keys.toSeq
  }

  /** @return `true` if the given note is currently active on the given channel; false otherwise. */
  def isNoteActive(channel: Int, midiNote: MidiNote): Boolean = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.contains(midiNote)
  }

  /**
   * @return the number of Note On messages received for the given note on the given channel that no Note Off has yet
   *         discharged, or `0` if the note is not active.
   */
  def referenceCount(channel: Int, midiNote: MidiNote): Int = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.referenceCount).getOrElse(0)
  }

  /** @return the velocity of the given note on the given channel, or `0` if the note is not active. */
  def velocity(channel: Int, midiNote: MidiNote): Int = velocityOption(channel, midiNote).getOrElse(0)

  /**
   * @return the velocity of the given note on the given channel, or `None` if the note is not active. A duplicate
   *         Note On overwrites it with the most recent value.
   */
  def velocityOption(channel: Int, midiNote: MidiNote): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.velocity)
  }

  /**
   * @return the most recent Polyphonic Key Pressure value for the given note on the given channel — `0` if
   *         the note is active but no Polyphonic Key Pressure has been received for it yet, or if the note
   *         is not active.
   */
  def polyPressure(channel: Int, midiNote: MidiNote): Int = polyPressureOption(channel, midiNote).getOrElse(0)

  /**
   * @return the most recent Polyphonic Key Pressure value for the given note on the given channel — `Some(0)` if
   *         the note is active but no Polyphonic Key Pressure has been received for it yet, or `None` if the note
   *         is not active. A duplicate Note On retains it: with two voices sounding for one key, pressure addressed
   *         to that key applies to both.
   */
  def polyPressureOption(channel: Int, midiNote: MidiNote): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.polyPressure)
  }

  /** @return the most recent Channel Pressure recorded on the given channel, or `0` if none has been received. */
  def channelPressure(channel: Int): Int = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).channelPressure.getOrElse(0)
  }

  /** @return the most recent Pitch Bend recorded on the given channel, or `0` (no pitch bend) if none has been
   *          received. */
  def pitchBend(channel: Int): Int = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).pitchBend.getOrElse(0)
  }

  /** @return the recorded value of the given CC on the given channel, or `None` if it has not been set. */
  def ccOption(channel: Int, ccNumber: Int): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).ccValues.get(ccNumber)
  }

  /**
   * Retrieves the recorded value of the given CC on the given channel, or a default if not set.
   *
   * Lookup order: the recorded value, then `overrideDefaultValue`, then the constructor's `ccDefaults`,
   * then the companion's [[ScMidiChannelStateTracker.DefaultCcValues]]. If no value is found through any
   * of these, a [[NoSuchElementException]] is thrown.
   *
   * @return the recorded value of the given CC on the given channel, or a default if not set.
   */
  def cc(channel: Int, ccNumber: Int, overrideDefaultValue: Option[Int] = None): Int = {
    ccOption(channel, ccNumber)
      .orElse(overrideDefaultValue)
      .orElse(resolvedCcDefault(ccNumber))
      .getOrElse(throw new NoSuchElementException(
        s"No value, override, or default available for CC $ccNumber on channel $channel"
      ))
  }

  /**
   * Convenience getter that returns the current Bank Select MSB and LSB on the given channel as a tuple.
   *
   * Each value is resolved through [[cc]], so it benefits from the same default-fallback behaviour as any other
   * CC: a recorded value is preferred, then the constructor's `ccDefaults`, then the companion's defaults
   * (`(0, 0)` by default).
   *
   * @return `(msb, lsb)` for Bank Select on the given channel.
   */
  def bankSelect(channel: Int): (Int, Int) =
    (cc(channel, message.MidiCc.BankSelectMsb), cc(channel, message.MidiCc.BankSelectLsb))

  /** @return the most recent Program Change recorded on the given channel, or `0` if none has been received. */
  def programChange(channel: Int): Int = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).programChange.getOrElse(0)
  }

  /**
   * @return the current RPN/NRPN selector state on the given channel. [[RpnSelector.None]] is returned when no
   *         parameter is selected — before any RPN/NRPN CC messages have been received, after a Reset All Controllers
   *         or a Null RPN/NRPN (in either order of its two CCs), and while only one of a parameter's two selector CCs
   *         has arrived, which selects nothing until the other completes the pair.
   */
  def rpnSelector(channel: Int): RpnSelector = {
    MidiRequirements.requireChannel(channel)
    selectorOf(channelStates(channel))
  }

  /**
   * @return the parameter the given channel is assembling from its selector CCs, each half either received or still
   *         pending. Unlike [[rpnSelector]], which collapses every incomplete parameter into [[RpnSelector.None]],
   *         this tells a channel waiting for the second CC of a parameter apart from one holding no selection at all,
   *         and shows which half it is still waiting for.
   */
  def partialRpnSelector(channel: Int): PartialRpnSelector = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).partialRpnSelector
  }

  /**
   * @return the recorded `(valueMsb, valueLsb)` for the given RPN on the given channel, or `None` if no Data Entry
   *         (or Data Increment / Decrement) has updated this RPN.
   */
  def rpnOption(channel: Int, parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).rpnValues.get((parameterMsb, parameterLsb))
  }

  /**
   * Retrieves the `(valueMsb, valueLsb)` for the given RPN on the given channel, or a default if not recorded.
   *
   * Lookup order: the recorded value, then `overrideDefaultValue`, then the constructor's `rpnDefaults`,
   * then the companion's [[ScMidiChannelStateTracker.DefaultRpnValues]]. If no value is found through any of these,
   * a [[NoSuchElementException]] is thrown.
   *
   * @return the `(valueMsb, valueLsb)` for the given RPN, or a default if not recorded.
   */
  def rpn(channel: Int, parameterMsb: Int, parameterLsb: Int,
          overrideDefaultValue: Option[(Int, Int)] = None): (Int, Int) = {
    rpnOption(channel, parameterMsb, parameterLsb)
      .orElse(overrideDefaultValue)
      .orElse(resolvedRpnDefault(parameterMsb, parameterLsb))
      .getOrElse(throw new NoSuchElementException(
        s"No value, override, or default available for RPN ($parameterMsb, $parameterLsb) on channel $channel"
      ))
  }

  /**
   * @return the recorded `(valueMsb, valueLsb)` for the given NRPN on the given channel, or `None` if no Data Entry
   *         (or Data Increment / Decrement) has updated this NRPN.
   */
  def nrpnOption(channel: Int, parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).nrpnValues.get((parameterMsb, parameterLsb))
  }

  /**
   * Retrieves the `(valueMsb, valueLsb)` for the given NRPN on the given channel, or a default if not recorded.
   *
   * Lookup order: the recorded value, then `overrideDefaultValue`, then the constructor's `nrpnDefaults`,
   * then the companion's [[ScMidiChannelStateTracker.DefaultNrpnValues]]. If no value is found through any of these,
   * a [[NoSuchElementException]] is thrown.
   *
   * @return the `(valueMsb, valueLsb)` for the given NRPN, or a default if not recorded.
   */
  def nrpn(channel: Int, parameterMsb: Int, parameterLsb: Int,
           overrideDefaultValue: Option[(Int, Int)] = None): (Int, Int) = {
    nrpnOption(channel, parameterMsb, parameterLsb)
      .orElse(overrideDefaultValue)
      .orElse(resolvedNrpnDefault(parameterMsb, parameterLsb))
      .getOrElse(throw new NoSuchElementException(
        s"No value, override, or default available for NRPN ($parameterMsb, $parameterLsb) on channel $channel"
      ))
  }

  /**
   * Discharges one Note On for the given note, removing it from the channel's active notes when the last one is
   * discharged. A release for a note that holds no active count is a no-op.
   */
  private def releaseNote(channel: Int, midiNote: MidiNote): Unit = {
    val activeNotes = channelStates(channel).activeNotes
    activeNotes.get(midiNote).foreach { activeNote =>
      activeNote.referenceCount -= 1
      if (activeNote.referenceCount == 0) activeNotes -= midiNote
    }
  }

  private def resolvedCcDefault(ccNumber: Int): Option[Int] =
    ccDefaults.get(ccNumber).orElse(DefaultCcValues.get(ccNumber))

  private def handleParameterCc(state: ChannelState, ccNumber: Int, value: Int): Unit = ccNumber match {
    case MidiCc.RpnMsb =>
      val (_, lsb) = rpnHalves(state.partialRpnSelector)
      state.partialRpnSelector = assembledRpn(msb = Some(value), lsb = lsb)
    case MidiCc.RpnLsb =>
      val (msb, _) = rpnHalves(state.partialRpnSelector)
      state.partialRpnSelector = assembledRpn(msb = msb, lsb = Some(value))
    case MidiCc.NrpnMsb =>
      val (_, lsb) = nrpnHalves(state.partialRpnSelector)
      state.partialRpnSelector = assembledNrpn(msb = Some(value), lsb = lsb)
    case MidiCc.NrpnLsb =>
      val (msb, _) = nrpnHalves(state.partialRpnSelector)
      state.partialRpnSelector = assembledNrpn(msb = msb, lsb = Some(value))
    case MidiCc.DataEntryMsb => writeDataEntry(state, isMsb = true, value)
    case MidiCc.DataEntryLsb => writeDataEntry(state, isMsb = false, value)
    case MidiCc.DataIncrement => applyDataDelta(state, delta = 1)
    case MidiCc.DataDecrement => applyDataDelta(state, delta = -1)
    case _ => // not part of the RPN/NRPN protocol
  }

  /**
   * The halves of the Registered Parameter being assembled on the channel, both pending when what it is assembling
   * is not an RPN: a selector CC of one kind starts a fresh parameter rather than inheriting a half of the other's.
   */
  private def rpnHalves(partial: PartialRpnSelector): (Option[Int], Option[Int]) = partial match {
    case PartialRpnSelector.Rpn(msb, lsb) => (msb, lsb)
    case _ => (None, None)
  }

  /** The halves of the Non-Registered Parameter being assembled on the channel; the counterpart of [[rpnHalves]]. */
  private def nrpnHalves(partial: PartialRpnSelector): (Option[Int], Option[Int]) = partial match {
    case PartialRpnSelector.Nrpn(msb, lsb) => (msb, lsb)
    case _ => (None, None)
  }

  /**
   * The Registered Parameter the given halves assemble into: the Null Function deselects and clears both halves,
   * whichever of its two CCs completed the pair — which is what makes Null detection insensitive to the order MIDI
   * 1.0 lets them arrive in — so that selecting a parameter afterwards takes both of its CCs again.
   *
   * A half that is still pending cannot complete the Null pair, so a lone Null MSB or LSB leaves the parameter
   * half-assembled rather than deselecting: 127 is a parameter number like any other.
   */
  private def assembledRpn(msb: Option[Int], lsb: Option[Int]): PartialRpnSelector =
    if (msb.contains(MidiRpn.NullMsb) && lsb.contains(MidiRpn.NullLsb)) PartialRpnSelector.None
    else PartialRpnSelector.Rpn(msb, lsb)

  /** The Non-Registered counterpart of [[assembledRpn]], its Null Function being NRPN 7F 7F. */
  private def assembledNrpn(msb: Option[Int], lsb: Option[Int]): PartialRpnSelector =
    if (msb.contains(MidiNrpn.NullMsb) && lsb.contains(MidiNrpn.NullLsb)) PartialRpnSelector.None
    else PartialRpnSelector.Nrpn(msb, lsb)

  /**
   * The parameter the channel holds selected: the one its two selector CCs have completed, both halves having
   * arrived. A parameter with a half still pending gives a Data Entry, Data Increment or Data Decrement nothing to
   * apply to, so it reads as [[RpnSelector.None]] exactly as an absent selection does.
   */
  private def selectorOf(state: ChannelState): RpnSelector = state.partialRpnSelector match {
    case PartialRpnSelector.Rpn(Some(msb), Some(lsb)) => RpnSelector.Rpn(msb, lsb)
    case PartialRpnSelector.Nrpn(Some(msb), Some(lsb)) => RpnSelector.Nrpn(msb, lsb)
    case _ => RpnSelector.None
  }

  private def handleChannelModeCc(state: ChannelState, ccNumber: Int): Unit =
    if (shallRespondToResetMessages) ccNumber match {
      case MidiCc.AllSoundOff | MidiCc.AllNotesOff =>
        state.activeNotes.clear()
      case MidiCc.ResetAllControllers =>
        ResetAllControllersCcNumbers.foreach(state.ccValues.remove)
        state.activeNotes.valuesIterator.foreach(_.polyPressure = 0)
        state.channelPressure = None
        state.pitchBend = None
        state.partialRpnSelector = PartialRpnSelector.None
      case _ =>
    }

  private def writeDataEntry(state: ChannelState, isMsb: Boolean, value: Int): Unit = selectorOf(state) match {
    case RpnSelector.Rpn(rmsb, rlsb) =>
      val (curMsb, curLsb) = state.rpnValues.get((rmsb, rlsb))
        .orElse(resolvedRpnDefault(rmsb, rlsb))
        .getOrElse((0, 0))
      val updated = if (isMsb) (value, curLsb) else (curMsb, value)
      state.rpnValues((rmsb, rlsb)) = updated
    case RpnSelector.Nrpn(nmsb, nlsb) =>
      val (curMsb, curLsb) = state.nrpnValues.get((nmsb, nlsb))
        .orElse(resolvedNrpnDefault(nmsb, nlsb))
        .getOrElse((0, 0))
      val updated = if (isMsb) (value, curLsb) else (curMsb, value)
      state.nrpnValues((nmsb, nlsb)) = updated
    case RpnSelector.None =>
  }

  private def applyDataDelta(state: ChannelState, delta: Int): Unit = selectorOf(state) match {
    case RpnSelector.Rpn(rmsb, rlsb) =>
      state.rpnValues.get((rmsb, rlsb)).orElse(resolvedRpnDefault(rmsb, rlsb))
        .foreach { starting => state.rpnValues((rmsb, rlsb)) = bumped(starting, delta) }
    case RpnSelector.Nrpn(nmsb, nlsb) =>
      state.nrpnValues.get((nmsb, nlsb)).orElse(resolvedNrpnDefault(nmsb, nlsb))
        .foreach { starting => state.nrpnValues((nmsb, nlsb)) = bumped(starting, delta) }
    case RpnSelector.None =>
  }

  private def resolvedRpnDefault(parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] =
    rpnDefaults.get((parameterMsb, parameterLsb))
      .orElse(DefaultRpnValues.get((parameterMsb, parameterLsb)))

  private def resolvedNrpnDefault(parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] =
    nrpnDefaults.get((parameterMsb, parameterLsb))
      .orElse(DefaultNrpnValues.get((parameterMsb, parameterLsb)))

  private def bumped(value: (Int, Int), delta: Int): (Int, Int) = {
    val combined = (value._1 << 7) | value._2
    val clamped = math.max(0, math.min(Max14BitValue, combined + delta))
    ((clamped >> 7) & 0x7F, clamped & 0x7F)
  }
}

object ScMidiChannelStateTracker {

  /** The number of MIDI channels (1..16, 0-indexed as 0..15). */
  private val ChannelCount: Int = 16

  /** Maximum 14-bit value (`(127 << 7) | 127`). */
  private val Max14BitValue: Int = (1 << 14) - 1

  /**
   * CC numbers cleared from `ccValues` on Reset All Controllers (MIDI 1.0 RP-015). Bank Select, Volume, Pan, and
   * Program Change are intentionally preserved. In addition to clearing these CC values, the handler also resets
   * Channel Pressure, Polyphonic Key Pressure on every active note of the channel, Pitch Bend, and the RPN/NRPN
   * selector to their default states, following the response the MMA recommends for the message.
   */
  private val ResetAllControllersCcNumbers: Set[Int] = Set(
    MidiCc.DataEntryMsb,
    MidiCc.DataEntryLsb,
    MidiCc.DataIncrement,
    MidiCc.DataDecrement,
    MidiCc.ModulationMsb,
    MidiCc.ModulationLsb,
    MidiCc.ExpressionMsb,
    MidiCc.ExpressionLsb,
    MidiCc.SustainPedal,
    MidiCc.PortamentoPedal,
    MidiCc.SostenutoPedal,
    MidiCc.SoftPedal,
    MidiCc.LegatoFootswitch,
    MidiCc.Hold2Pedal,
    MidiCc.RpnMsb,
    MidiCc.RpnLsb,
    MidiCc.NrpnMsb,
    MidiCc.NrpnLsb,
  )

  /**
   * Default values for known Control Change controllers, used by [[ScMidiChannelStateTracker.cc]] when a recorded
   * value, an override, or a constructor-supplied default is unavailable. These match common MIDI 1.0 defaults.
   */
  val DefaultCcValues: Map[Int, Int] = Map(
    MidiCc.BankSelectMsb -> 0,
    MidiCc.BankSelectLsb -> 0,
    MidiCc.ModulationMsb -> 0,
    MidiCc.ModulationLsb -> 0,
    MidiCc.VolumeMsb -> 100,
    MidiCc.VolumeLsb -> 0,
    MidiCc.PanMsb -> 64,
    MidiCc.PanLsb -> 0,
    MidiCc.ExpressionMsb -> 127,
    MidiCc.ExpressionLsb -> 0,
    MidiCc.SustainPedal -> 0,
    MidiCc.SostenutoPedal -> 0,
    MidiCc.SoftPedal -> 0,
    MidiCc.MpeSlide -> 64,
    MidiCc.RpnMsb -> MidiRpn.NullMsb,
    MidiCc.RpnLsb -> MidiRpn.NullLsb,
    MidiCc.NrpnMsb -> MidiNrpn.NullMsb,
    MidiCc.NrpnLsb -> MidiNrpn.NullLsb,
  )

  /**
   * Default values for known Registered Parameter Numbers, used by [[ScMidiChannelStateTracker.rpn]] and by Data
   * Increment / Decrement when neither a recorded value nor a constructor-supplied default is available for the
   * currently selected RPN.
   *
   * Values are taken from the MIDI 1.0 spec (RP-018, RP-024). The map key is `(parameterMsb, parameterLsb)`; the
   * value is the default `(valueMsb, valueLsb)`.
   */
  val DefaultRpnValues: Map[(Int, Int), (Int, Int)] = Map(
    // Pitch Bend Sensitivity (0,0): ±2 semitones, 0 cents.
    (MidiRpn.PitchBendSensitivityMsb, MidiRpn.PitchBendSensitivityLsb) -> (2, 0),
    // Channel Fine Tuning (0,1): centred at 8192 → (64, 0).
    (MidiRpn.FineTuningMsb, MidiRpn.FineTuningLsb) -> (64, 0),
    // Channel Coarse Tuning (0,2): centred at 64 semitones; LSB unused.
    (MidiRpn.CoarseTuningMsb, MidiRpn.CoarseTuningLsb) -> (64, 0),
    // Tuning Program Select (0,3): 0.
    (MidiRpn.TuningProgramSelectMsb, MidiRpn.TuningProgramSelectLsb) -> (0, 0),
    // Tuning Bank Select (0,4): 0.
    (MidiRpn.TuningBankSelectMsb, MidiRpn.TuningBankSelectLsb) -> (0, 0)
  )

  /**
   * Default values for Non-Registered Parameter Numbers. NRPN are vendor-specific, so this ships empty for
   * symmetry with [[DefaultCcValues]] and [[DefaultRpnValues]]; per-device defaults are supplied through the
   * constructor's `nrpnDefaults` parameter.
   */
  val DefaultNrpnValues: Map[(Int, Int), (Int, Int)] = Map.empty

  private class ActiveNote(var velocity: Int, var polyPressure: Int = 0, var referenceCount: Int = 1)

  private class ChannelState {
    val activeNotes: mutable.LinkedHashMap[MidiNote, ActiveNote] = mutable.LinkedHashMap.empty
    val ccValues: mutable.Map[Int, Int] = mutable.Map.empty
    val rpnValues: mutable.Map[(Int, Int), (Int, Int)] = mutable.Map.empty
    val nrpnValues: mutable.Map[(Int, Int), (Int, Int)] = mutable.Map.empty
    var partialRpnSelector: PartialRpnSelector = PartialRpnSelector.None
    var channelPressure: Option[Int] = None
    var pitchBend: Option[Int] = None
    var programChange: Option[Int] = None
  }
}

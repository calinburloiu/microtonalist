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
 * (with their velocities and Polyphonic Key Pressure), Control Change values, Registered and Non-Registered
 * Parameter Number values, Channel Pressure, Pitch Bend, and Program Change.
 *
 * Default values for Control Change, Registered Parameter Number, and Non-Registered Parameter Number lookups
 * may be supplied via the constructor; if not, the companion object's [[ScMidiChannelStateTracker.DefaultCcValues]],
 * [[ScMidiChannelStateTracker.DefaultRpnValues]], and [[ScMidiChannelStateTracker.DefaultNrpnValues]] are consulted.
 *
 * '''Not thread-safe.''' External synchronization is required when accessed from multiple threads. It should usually
 * be used from a track thread.
 *
 * @param ccDefaults   per-CC-number default values that override the companion's defaults.
 * @param rpnDefaults  per-RPN default values that override the companion's defaults.
 * @param nrpnDefaults per-NRPN default values that override the companion's defaults.
 */
@NotThreadSafe
class ScMidiChannelStateTracker(ccDefaults: Map[Int, Int] = Map.empty,
                                rpnDefaults: Map[(Int, Int), (Int, Int)] = Map.empty,
                                nrpnDefaults: Map[(Int, Int), (Int, Int)] = Map.empty) extends ScMidiReceiver {

  import ScMidiChannelStateTracker.*

  private val channelStates: Array[ChannelState] = Array.fill(ChannelCount)(ChannelState())
  private var _closed: Boolean = false

  override def send(message: ScMidiMessage, timeStamp: Long = -1L): Unit = if (!_closed) message match {
    // TODO #155 Is the default unapply suppressed?
    case noteOn: NoteOnScMidiMessage if noteOn.velocity == NoteOnScMidiMessage.NoteOffVelocity =>
      channelStates(noteOn.channel).activeNotes -= noteOn.midiNote
    case noteOn: NoteOnScMidiMessage =>
      channelStates(noteOn.channel).activeNotes(noteOn.midiNote) = ActiveNote(noteOn.velocity)
    case noteOff: NoteOffScMidiMessage =>
      channelStates(noteOff.channel).activeNotes -= noteOff.midiNote
    case polyPressure: PolyPressureScMidiMessage =>
      channelStates(polyPressure.channel).activeNotes.get(polyPressure.midiNote)
        .foreach(_.polyPressure = polyPressure.value)
    case cc: CcScMidiMessage =>
      val state = channelStates(cc.channel)
      state.ccValues(cc.number) = cc.value
      handleParameterCc(state, cc.number, cc.value)
      handleChannelModeCc(state, cc.number)
    case channelPressure: ChannelPressureScMidiMessage =>
      channelStates(channelPressure.channel).channelPressure = Some(channelPressure.value)
    case pitchBend: PitchBendScMidiMessage =>
      channelStates(pitchBend.channel).pitchBend = Some(pitchBend.value)
    case programChange: ProgramChangeScMidiMessage =>
      channelStates(programChange.channel).programChange = Some(programChange.program)
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
   */
  def reset(): Unit = if (!_closed) {
    for (channel <- 0 until ChannelCount) {
      channelStates(channel) = ChannelState()
    }
  }

  /** @return whether [[close]] has been called on this tracker. */
  def isClosed: Boolean = _closed

  /** @return the set of currently active notes on the given channel. */
  def activeNoteSet(channel: Int): Set[MidiNote] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.keySet.toSet
  }

  /** @return the currently active notes on the given channel, in the order they were turned on. */
  def activeNoteSeq(channel: Int): Seq[MidiNote] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.keys.toSeq
  }

  // TODO #155 Add method `def isNoteActive(channel: Int, midiNote: MidiNote): boolean`.

  // TODO #155 Add separate methods `velocity` and `velocityOption` which return `Int` and `Option[Int]`, respectively.

  /** @return the velocity recorded for an active note on the given channel, or `None` if the note is not active. */
  def velocity(channel: Int, midiNote: MidiNote): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.velocity)
  }

  // TODO #155 Add separate methods `polyPressure` and `polyPressureOption` which return `Int` and `Option[Int]`,
  //  respectively.

  /**
   * @return the most recent Polyphonic Key Pressure value for an active note on the given channel — `Some(0)` if
   *         the note is active but no Polyphonic Key Pressure has been received for it yet, or `None` if the note
   *         is not active.
   */
  def polyPressure(channel: Int, midiNote: MidiNote): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).activeNotes.get(midiNote).map(_.polyPressure)
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

  private def resolvedCcDefault(ccNumber: Int): Option[Int] =
    ccDefaults.get(ccNumber).orElse(DefaultCcValues.get(ccNumber))

  private def handleParameterCc(state: ChannelState, ccNumber: Int, value: Int): Unit = ccNumber match {
    case ScMidiCc.RpnMsb =>
      state.selector = state.selector match {
        case Selector.Rpn(_, lsb) => Selector.Rpn(value, lsb)
        case _ => Selector.Rpn(value, lsb = ScMidiRpn.NullLsb)
      }
    case ScMidiCc.RpnLsb =>
      val msb = state.selector match {
        case Selector.Rpn(m, _) => m
        case _ => ScMidiRpn.NullMsb
      }
      state.selector =
        if (msb == ScMidiRpn.NullMsb && value == ScMidiRpn.NullLsb) Selector.None
        else Selector.Rpn(msb, value)
    case ScMidiCc.NrpnMsb =>
      state.selector = state.selector match {
        case Selector.Nrpn(_, lsb) => Selector.Nrpn(value, lsb)
        case _ => Selector.Nrpn(value, lsb = ScMidiNrpn.NullLsb)
      }
    case ScMidiCc.NrpnLsb =>
      val msb = state.selector match {
        case Selector.Nrpn(m, _) => m
        case _ => ScMidiNrpn.NullMsb
      }
      state.selector =
        if (msb == ScMidiNrpn.NullMsb && value == ScMidiNrpn.NullLsb) Selector.None
        else Selector.Nrpn(msb, value)
    case ScMidiCc.DataEntryMsb => writeDataEntry(state, isMsb = true, value)
    case ScMidiCc.DataEntryLsb => writeDataEntry(state, isMsb = false, value)
    case ScMidiCc.DataIncrement => applyDataDelta(state, delta = 1)
    case ScMidiCc.DataDecrement => applyDataDelta(state, delta = -1)
    case _ => // not part of the RPN/NRPN protocol
  }

  private def handleChannelModeCc(state: ChannelState, ccNumber: Int): Unit = ccNumber match {
    case ScMidiCc.AllSoundOff | ScMidiCc.AllNotesOff =>
      state.activeNotes.clear()
    case ScMidiCc.ResetAllControllers =>
      ResetAllControllersCcNumbers.foreach(state.ccValues.remove)
      state.channelPressure = None
      state.pitchBend = None
      state.selector = Selector.None
    case _ =>
  }

  private def writeDataEntry(state: ChannelState, isMsb: Boolean, value: Int): Unit = state.selector match {
    case Selector.Rpn(rmsb, rlsb) =>
      val (curMsb, curLsb) = state.rpnValues.get((rmsb, rlsb))
        .orElse(resolvedRpnDefault(rmsb, rlsb))
        .getOrElse((0, 0))
      val updated = if (isMsb) (value, curLsb) else (curMsb, value)
      state.rpnValues((rmsb, rlsb)) = updated
    case Selector.Nrpn(nmsb, nlsb) =>
      val (curMsb, curLsb) = state.nrpnValues.get((nmsb, nlsb))
        .orElse(resolvedNrpnDefault(nmsb, nlsb))
        .getOrElse((0, 0))
      val updated = if (isMsb) (value, curLsb) else (curMsb, value)
      state.nrpnValues((nmsb, nlsb)) = updated
    case Selector.None =>
  }

  private def applyDataDelta(state: ChannelState, delta: Int): Unit = state.selector match {
    case Selector.Rpn(rmsb, rlsb) =>
      state.rpnValues.get((rmsb, rlsb)).orElse(resolvedRpnDefault(rmsb, rlsb))
        .foreach { starting => state.rpnValues((rmsb, rlsb)) = bumped(starting, delta) }
    case Selector.Nrpn(nmsb, nlsb) =>
      state.nrpnValues.get((nmsb, nlsb)).orElse(resolvedNrpnDefault(nmsb, nlsb))
        .foreach { starting => state.nrpnValues((nmsb, nlsb)) = bumped(starting, delta) }
    case Selector.None =>
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

  // TODO #155 `channelPressure` must return `Int`. If no value was received it should return `0`, the default value.
  /** @return the most recent Channel Pressure recorded on the given channel, or `None` if none has been received. */
  def channelPressure(channel: Int): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).channelPressure
  }

  // TODO #155 `pitchBend` must return `Int`. If no value was received it should return `0`, the default value.
  /** @return the most recent Pitch Bend recorded on the given channel, or `None` if none has been received. */
  def pitchBend(channel: Int): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).pitchBend
  }

  // TODO #155 `programChange` must return `Int`. If no value was received it should return `0`, the default value.
  /** @return the most recent Program Change recorded on the given channel, or `None` if none has been received. */
  def programChange(channel: Int): Option[Int] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).programChange
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
    (cc(channel, message.ScMidiCc.BankSelectMsb), cc(channel, message.ScMidiCc.BankSelectLsb))

  // TODO #155 Add separate getter methods for RPN and NRPM for optional and non-optional value. The latter should have
  //  an `overrideDefaultValue` and resolve default in a similar way with the `cc` method.

  // TODO #155 Add ScalaDoc
  def selector(channel: Int): Selector = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).selector
  }

  /**
   * @return the recorded `(valueMsb, valueLsb)` for the given RPN on the given channel, or `None` if no Data Entry
   *         (or Data Increment / Decrement) has updated this RPN.
   */
  def rpn(channel: Int, parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).rpnValues.get((parameterMsb, parameterLsb))
  }

  /**
   * @return the recorded `(valueMsb, valueLsb)` for the given NRPN on the given channel, or `None` if no Data Entry
   *         (or Data Increment / Decrement) has updated this NRPN.
   */
  def nrpn(channel: Int, parameterMsb: Int, parameterLsb: Int): Option[(Int, Int)] = {
    MidiRequirements.requireChannel(channel)
    channelStates(channel).nrpnValues.get((parameterMsb, parameterLsb))
  }
}

object ScMidiChannelStateTracker {

  /** The number of MIDI channels (1..16, 0-indexed as 0..15). */
  private val ChannelCount: Int = 16

  /** Maximum 14-bit value (`(127 << 7) | 127`). */
  private val Max14BitValue: Int = (1 << 14) - 1

  /**
   * CC numbers cleared on Reset All Controllers (MIDI 1.0 RP-015). Bank Select, Volume, Pan, and Program Change are
   * intentionally preserved.
   */
  private val ResetAllControllersCcNumbers: Set[Int] = Set(
    ScMidiCc.ModulationMsb,
    ScMidiCc.ModulationLsb,
    ScMidiCc.ExpressionMsb,
    ScMidiCc.ExpressionLsb,
    ScMidiCc.SustainPedal,
    ScMidiCc.PortamentoPedal,
    ScMidiCc.SostenutoPedal,
    ScMidiCc.SoftPedal,
    ScMidiCc.LegatoFootswitch,
    ScMidiCc.Hold2Pedal
  )

  /**
   * Default values for known Control Change controllers, used by [[ScMidiChannelStateTracker.cc]] when a recorded
   * value, an override, or a constructor-supplied default is unavailable. These match common MIDI 1.0 defaults.
   */
  val DefaultCcValues: Map[Int, Int] = Map(
    ScMidiCc.BankSelectMsb -> 0,
    ScMidiCc.BankSelectLsb -> 0,
    ScMidiCc.ModulationMsb -> 0,
    ScMidiCc.ModulationLsb -> 0,
    ScMidiCc.VolumeMsb -> 100,
    ScMidiCc.VolumeLsb -> 0,
    ScMidiCc.PanMsb -> 64,
    ScMidiCc.PanLsb -> 0,
    ScMidiCc.ExpressionMsb -> 127,
    ScMidiCc.ExpressionLsb -> 0,
    ScMidiCc.SustainPedal -> 0,
    ScMidiCc.SostenutoPedal -> 0,
    ScMidiCc.SoftPedal -> 0,
    ScMidiCc.MpeSlide -> 64,
    ScMidiCc.RpnMsb -> ScMidiRpn.NullMsb,
    ScMidiCc.RpnLsb -> ScMidiRpn.NullLsb,
    ScMidiCc.NrpnMsb -> ScMidiNrpn.NullMsb,
    ScMidiCc.NrpnLsb -> ScMidiNrpn.NullLsb,
  )

  // TODO #155 This is not only used for Data Increment / Decrement, but also when retrieving a RPN value that was not
  //  set
  /**
   * Default values for known Registered Parameter Numbers, used by Data Increment / Decrement when neither a
   * recorded value nor a constructor-supplied default is available for the currently selected RPN.
   *
   * Values are taken from the MIDI 1.0 spec (RP-018, RP-024). The map key is `(parameterMsb, parameterLsb)`; the
   * value is the default `(valueMsb, valueLsb)`.
   */
  val DefaultRpnValues: Map[(Int, Int), (Int, Int)] = Map(
    // Pitch Bend Sensitivity (0,0): ±2 semitones, 0 cents.
    (ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb) -> (2, 0),
    // Channel Fine Tuning (0,1): centred at 8192 → (64, 0).
    (ScMidiRpn.FineTuningMsb, ScMidiRpn.FineTuningLsb) -> (64, 0),
    // Channel Coarse Tuning (0,2): centred at 64 semitones; LSB unused.
    (ScMidiRpn.CoarseTuningMsb, ScMidiRpn.CoarseTuningLsb) -> (64, 0),
    // Tuning Program Select (0,3): 0.
    (ScMidiRpn.TuningProgramSelectMsb, ScMidiRpn.TuningProgramSelectLsb) -> (0, 0),
    // Tuning Bank Select (0,4): 0.
    (ScMidiRpn.TuningBankSelectMsb, ScMidiRpn.TuningBankSelectLsb) -> (0, 0)
  )

  /**
   * Default values for Non-Registered Parameter Numbers. NRPN are vendor-specific, so this ships empty for
   * symmetry with [[DefaultCcValues]] and [[DefaultRpnValues]]; per-device defaults are supplied through the
   * constructor's `nrpnDefaults` parameter.
   */
  val DefaultNrpnValues: Map[(Int, Int), (Int, Int)] = Map.empty

  // TODO #155 Add ScalaDoc
  enum Selector {
    case None
    case Rpn(msb: Int, lsb: Int)
    case Nrpn(msb: Int, lsb: Int)
  }

  private class ActiveNote(val velocity: Int, var polyPressure: Int = 0)

  private class ChannelState {
    val activeNotes: mutable.LinkedHashMap[MidiNote, ActiveNote] = mutable.LinkedHashMap.empty
    val ccValues: mutable.Map[Int, Int] = mutable.Map.empty
    val rpnValues: mutable.Map[(Int, Int), (Int, Int)] = mutable.Map.empty
    val nrpnValues: mutable.Map[(Int, Int), (Int, Int)] = mutable.Map.empty
    var selector: Selector = Selector.None
    var channelPressure: Option[Int] = None
    var pitchBend: Option[Int] = None
    var programChange: Option[Int] = None
  }
}

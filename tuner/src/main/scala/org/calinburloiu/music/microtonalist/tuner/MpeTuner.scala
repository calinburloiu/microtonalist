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
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.message.*
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

/**
 * Defines how incoming MIDI messages are interpreted before being processed by the [[MpeTuner]].
 */
enum MpeInputMode {
  /**
   * Conventional MIDI where all notes may arrive on a single channel or across channels
   * without MPE Zone structure. This input is converted to MPE by redirecting
   * Pitch Bend to the Master Channel and initializing control dimensions before Note On.
   */
  case NonMpe

  /**
   * MIDI conforming to the MPE Specification, with notes already distributed across
   * Member Channels within Zones.
   */
  case Mpe
}

/**
 * Tuner that uses MIDI Polyphonic Expression (MPE) to apply microtonal tunings to polyphonic MIDI streams.
 *
 * The MPE Tuner leverages the MPE protocol's per-note pitch control to apply pitch-class-based
 * tuning offsets via per-channel Pitch Bend messages. It supports real-time tuning changes
 * by updating the Pitch Bend on all occupied Member Channels whenever the active Tuning
 * is modified.
 *
 * The tuner processes incoming MPE Configuration Messages (MCM) and Pitch Bend Sensitivity (PBS)
 * RPN messages to dynamically reconfigure zones and pitch bend ranges.
 *
 * For the technical specification check the white paper in `docs/architecture/tuner/mpe-tuner-paper.md`.
 * Comments in this file cite its sections by name rather than by number, so that they survive a renumbering.
 *
 * @param initialZones     The initial [[MpeZones]] configuration for the Lower and Upper Zones.
 * @param initialInputMode Initial [[MpeInputMode]]. The tuner switches to MPE mode automatically
 *                         upon receiving an MPE Configuration Message.
 */
class MpeTuner(private val initialZones: MpeZones = MpeZones.DefaultZones,
               private val initialInputMode: MpeInputMode = MpeInputMode.NonMpe) extends Tuner with StrictLogging {

  import MpeTuner.*

  override val typeName: String = MpeTuner.TypeName

  private var _zones: MpeZones = initialZones
  private var _inputMode: MpeInputMode = initialInputMode

  private var _tuning: Tuning = Tuning.Standard

  private var lowerAllocator: Option[MpeChannelAllocator] = createAllocator(lowerZone)
  private var upperAllocator: Option[MpeChannelAllocator] = createAllocator(upperZone)

  // Per-input-channel state derived from incoming MIDI messages: Channel Pressure, CC #74 (MPE Slide /
  // timbre), and the RPN selector state machine. Used to seed the output Member Channel at Note On
  // (MPE input mode only) and to drive the RPN-based protocol for MCM and PBS.
  private val tracker: ScMidiChannelStateTracker = ScMidiChannelStateTracker()

  warnOnNonMpeInputWithBothZones()

  /**
   * @return current [[MpeZones]] configuration for the Lower and Upper Zones.
   */
  def zones: MpeZones = _zones

  /**
   * @return current input mode
   */
  def inputMode: MpeInputMode = _inputMode

  /**
   * @return current tuning
   */
  def tuning: Tuning = _tuning

  override def reset(): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // Emit Note Off for every active note before switching input mode / zone layout,
    // so downstream receivers are never left with hanging notes (MPE spec Section 2.1.4).
    stopAllNotes(buffer)
    _zones = initialZones
    _inputMode = initialInputMode
    resetState()
    warnOnNonMpeInputWithBothZones()
    buffer ++= configurationMessages()
    buffer.toSeq
  }

  override def tune(tuning: Tuning): Seq[MidiMessage] = {
    _tuning = tuning
    val buffer = mutable.Buffer[MidiMessage]()

    // Update pitch bend on all occupied member channels
    lowerAllocator.foreach(updateTuningOnZone(_, buffer))
    upperAllocator.foreach(updateTuningOnZone(_, buffer))

    buffer.toSeq
  }

  override def process(message: MidiMessage): Seq[MidiMessage] = {
    message match {
      case msg: ShortMessage => processShortMessage(msg)
      case _ => Seq(message)
    }
  }

  private def lowerZone: MpeZone = _zones.lower

  private def upperZone: MpeZone = _zones.upper

  /**
   * Warns when the Tuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is
   * routed exclusively to the Lower Zone, so the Upper Zone is unreachable and its Member Channels are
   * wasted. Logged at construction and again on `reset()`, where the initial configuration is re-applied.
   */
  private def warnOnNonMpeInputWithBothZones(): Unit = {
    if (_inputMode == MpeInputMode.NonMpe && lowerZone.isEnabled && upperZone.isEnabled) {
      logger.warn("MpeTuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is " +
        "routed exclusively to the Lower Zone, so the Upper Zone's Member Channels are unreachable. " +
        s"Consider disabling the Upper Zone or switching to MPE Input Mode. Zones: ${_zones}")
    }
  }

  /**
   * Clears internal mutable state and recreates allocators from `currentZones`.
   */
  private def resetState(): Unit = {
    _tuning = Tuning.Standard
    tracker.reset()

    lowerAllocator = createAllocator(lowerZone)
    upperAllocator = createAllocator(upperZone)
  }

  /**
   * Returns MCM and PBS messages for all enabled zones in `currentZones`.
   */
  private def configurationMessages(): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()

    buffer ++= mcmMessages(lowerZone)
    buffer ++= pitchBendSensitivityMessages(lowerZone)
    buffer ++= mcmMessages(upperZone)
    buffer ++= pitchBendSensitivityMessages(upperZone)

    buffer.toSeq
  }

  private def processShortMessage(message: ShortMessage): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    val scMessage = message.asScala
    tracker.send(scMessage)

    scMessage match {
      case msg: NoteOnScMidiMessage if msg.velocity > 0 =>
        processNoteOn(buffer, msg)
      case msg: NoteOnScMidiMessage =>
        // Note On with velocity 0 is a Note Off per MIDI spec.
        processNoteOff(buffer, NoteOffScMidiMessage(msg.channel, msg.midiNote))
      case msg: NoteOffScMidiMessage => processNoteOff(buffer, msg)
      case msg: PitchBendScMidiMessage => processPitchBend(buffer, msg)
      case msg: CcScMidiMessage => processCc(buffer, msg)
      case msg: ChannelPressureScMidiMessage => processChannelPressure(buffer, msg)
      case msg: PolyPressureScMidiMessage => processPolyPressure(buffer, msg)
      case msg: ProgramChangeScMidiMessage =>
        // Forward on the zone's master channel
        resolveZoneMasterChannel(msg.channel).foreach { masterCh =>
          buffer += msg.mapChannel(_ => masterCh).asJava
        }
      case _ =>
        buffer += message
    }

    buffer.toSeq
  }

  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage): Unit = {
    if (_inputMode == MpeInputMode.Mpe && isMasterChannel(msg.channel)) {
      // Master Channel notes are forwarded as-is: no allocator, no tuning offset, no control
      // dimension setup. They play in 12-EDO (modulated only by Master Pitch Bend) because
      // applying a per-pitch-class tuning offset on the Master Channel would affect every
      // note in the Zone. `tracker` keeps them, which is all stopAllNotes needs.
      buffer += msg.asJava
    } else {
      processMemberNoteOn(buffer, msg)
    }
  }

  private def processMemberNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    getAllocatorForInput(inputChannel) match {
      case Some(alloc) =>
        val zone = currentZone(alloc)
        val isMpeInput = _inputMode == MpeInputMode.Mpe
        // In MPE Input Mode the note's Expression Values are initialized from the state remembered for its
        // input Member Channel; in Non-MPE Input Mode there are none to take, and the allocator's defaults
        // apply — which is also what keeps CC #74 off the Member Channel in that mode.
        val expression = Option.when(isMpeInput)(inputExpressionOf(inputChannel, zone))
        val preferredChannel = Option.when(isMpeInput && zone.memberChannels.contains(inputChannel))(inputChannel)

        val result = alloc.allocate(MpeNoteIdentity(inputChannel, midiNote), expression, preferredChannel)
        val outChannel = result.channel

        // Dropped notes are released before every message emitted for the new note: emitting the setup
        // messages first would retune the notes being dropped on their way out.
        result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReasonOnNoteOn))

        // Pitch Bend, CC #74, Channel Pressure, then the Note On. Pitch Bend is emitted unconditionally on
        // a fresh allocation: what goes on the wire is Tuning Pitch Bend + Expression Pitch Bend, and the
        // tuning half is invisible to the allocator — a channel that was unoccupied retains the bend of a
        // note of a different pitch class and has missed every tune() that ran while it was empty. On a
        // duplicate Note On the channel was occupied by this very identity throughout, so the tuning half
        // is current by construction and Pitch Bend follows the same "only when changed" rule as the rest.
        if (!result.isDuplicate || result.update.pitchBendCents.isDefined) {
          emitOutputPitchBend(buffer, outChannel, alloc)
        }
        emitSlide(buffer, outChannel, result.update)
        emitPressure(buffer, outChannel, result.update)

        buffer += NoteOnScMidiMessage(outChannel, midiNote, velocity).asJava

      case None =>
        // No allocator for this channel, forward as-is
        buffer += NoteOnScMidiMessage(inputChannel, midiNote, velocity).asJava
    }
  }

  /**
   * The Expression Values a note arriving on an input Member Channel starts with, taken from the control
   * state remembered for that channel — the state-tracking obligation the MPE Specification places on
   * receivers, so that a Pitch Bend, CC #74 or Channel Pressure sent before the Note On is not lost.
   */
  private def inputExpressionOf(inputChannel: Int, zone: MpeZone): MpeExpression = ImmutableMpeExpression(
    pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
      tracker.pitchBend(inputChannel), zone.memberPitchBendSensitivity),
    pressure = tracker.channelPressure(inputChannel),
    slide = tracker.cc(inputChannel, ScMidiCc.MpeSlide))

  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], msg: NoteOffScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    if (_inputMode == MpeInputMode.Mpe && isMasterChannel(inputChannel)) {
      buffer += msg.asJava
    } else {
      getAllocatorForInput(inputChannel).foreach { alloc =>
        // The Channel Pressure reset applies in Non-MPE Input Mode only: there the Tuner is the controller
        // that synthesized the value, whereas in MPE Input Mode the dimension passes through from the
        // sender and a conforming sender's own pre-release reset reaches the output as an ordinary update.
        val resetPressureOnEmpty = _inputMode == MpeInputMode.NonMpe

        alloc.release(MpeNoteIdentity(inputChannel, midiNote), resetPressureOnEmpty) match {
          case Some(result) =>
            val outChannel = result.channel

            // The reset is the sole control message emitted before the Note Off; every other recomputed
            // value follows it, so that the released note's control state is final at the moment of release.
            if (result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

            buffer += NoteOffScMidiMessage(outChannel, midiNote, velocity).asJava

            if (result.update.pitchBendCents.isDefined) emitOutputPitchBend(buffer, outChannel, alloc)
            emitSlide(buffer, outChannel, result.update)
            if (!result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

          case None =>
            // A `None` result means the identity holds no active count — chiefly after the Tuner dropped
            // the note itself, having already emitted its Note Offs, which is routine and already logged at
            // the drop site. But a stale Note Off after a mid-stream MCM, or a sender resuming after a MIDI
            // panic, would look identical here, so a trace line keeps those cases distinguishable from
            // normal operation.
            logger.trace(s"Discarding Note Off for $midiNote on input channel $inputChannel: " +
              "the identity holds no active count")
        }
      }
    }
  }

  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], msg: PitchBendScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val pitchBendValue = msg.value

    if (inputMode == MpeInputMode.Mpe) {
      // Check if it's a master channel
      if (isMasterChannel(inputChannel)) {
        // Forward master channel pitch bend without modification
        buffer += msg.asJava
      } else {
        // Per-note pitch bend in MPE input - treat as Expression Pitch Bend.
        // The allocator fans the update out by itself to every output channel holding a note of
        // this input channel.
        getAllocatorForInput(inputChannel).foreach { alloc =>
          val pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
            pitchBendValue, currentZone(alloc).memberPitchBendSensitivity)
          emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(inputChannel, pitchBendCents),
            alloc, DropReasonOnPitchBend)
        }
      }
    } else {
      // Non-MPE input: redirect pitch bend to master channel as zone-level pitch bend
      if (lowerZone.isEnabled) {
        buffer += PitchBendScMidiMessage(lowerZone.masterChannel, pitchBendValue).asJava
      } else if (upperZone.isEnabled) {
        buffer += PitchBendScMidiMessage(upperZone.masterChannel, pitchBendValue).asJava
      }
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMessage], msg: CcScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val ccNumber = msg.number
    val ccValue = msg.value
    val isMcmRpn = tracker.rpnSelector(inputChannel) == ScMidiChannelStateTracker.RpnSelector.Rpn(
      ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)
    val isPbsRpn = tracker.rpnSelector(inputChannel) == ScMidiChannelStateTracker.RpnSelector.Rpn(
      ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

    ccNumber match {
      // RPN state machine — the selector is tracked by `tracker`.
      // For known RPNs handled internally (MCM, PBS), suppress the selector CC here: `processMcm`
      // re-emits the full MCM (including RPN setup) downstream, and `applyPbsUpdate` re-emits the
      // PBS RPN setup immediately before each Data Entry. Forwarding the original selector here
      // would duplicate those messages on the destination channel. Unknown RPNs still pass through
      // (routed to the Zone's Master Channel in non-MPE input mode).
      case ScMidiCc.RpnLsb | ScMidiCc.RpnMsb if isMcmRpn || isPbsRpn =>
      case ScMidiCc.RpnLsb | ScMidiCc.RpnMsb =>
        if (inputMode == MpeInputMode.NonMpe) {
          forwardOnZoneMasterChannel(buffer, msg)
        } else {
          buffer += msg.asJava
        }
      case ScMidiCc.DataEntryMsb if isMcmRpn && (inputChannel == 0 || inputChannel == 15) =>
        processMcm(buffer, inputChannel, ccValue)
      case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb if isPbsRpn =>
        processPbs(buffer, inputChannel, ccNumber, ccValue)

      // CC #74 (MPE Slide / timbre): in MPE mode it is a per-note Expression Value of the notes active on
      // the input channel; in non-MPE mode it is a Zone-level control on the Master Channel, and never
      // reaches a Member Channel.
      // TODO #250 In MPE mode a CC #74 arriving on a Master Channel is dropped, for the same reason and with
      //  the same regression as the Channel Pressure case in `processChannelPressure`.
      case ScMidiCc.MpeSlide =>
        if (inputMode == MpeInputMode.Mpe) {
          getAllocatorForInput(inputChannel).foreach { alloc =>
            emitExpressionUpdateResult(buffer, alloc.updateSlide(inputChannel, ccValue), alloc, NoDropExpected)
          }
        } else {
          forwardOnZoneMasterChannel(buffer, msg)
        }
      // All other CCs are forwarded on the master channel of the zone the input belongs to
      case _ =>
        forwardOnZoneMasterChannel(buffer, msg)
    }
  }

  /**
   * Processes an incoming MPE Configuration Message (MCM).
   *
   * Stops all active notes, reconfigures zones (with overlap resolution), resets internal state,
   * and outputs the new configuration messages downstream.
   */
  private def processMcm(buffer: mutable.Buffer[MidiMessage], channel: Int, memberCount: Int): Unit = {
    assert(channel == 0 || channel == 15, "MCM messages are only sent to channel 0 or 15!")
    // Per MPE spec Section 2.4, receiving MCM resets PBS to defaults
    val (zoneType, newZone) = if (channel == 0)
      (MpeZoneType.Lower, MpeZone(MpeZoneType.Lower, memberCount))
    else
      (MpeZoneType.Upper, MpeZone(MpeZoneType.Upper, memberCount))

    logger.info(s"MCM received on channel $channel: configuring $zoneType zone with $memberCount member channel(s)...")

    // Remember the other zone before update to detect overlap resolution changes
    val otherZoneBefore = if (channel == 0) upperZone else lowerZone

    // Stop all active notes before reconfiguring
    stopAllNotes(buffer)

    // Update zones with overlap resolution
    _zones = _zones.update(newZone)

    // Reset internal state and recreate allocators
    resetState()

    // Forward MCM for the updated zone. PBS is not sent because the downstream receiver
    // resets PBS to defaults upon receiving MCM (MPE spec Section 2.4).
    val updatedZone = if (channel == 0) lowerZone else upperZone
    logger.info(s"$zoneType zone updated: $updatedZone")
    buffer ++= mcmMessages(updatedZone)

    // Forward MCM for the other zone only if it was changed by overlap resolution
    val otherZoneAfter = if (channel == 0) upperZone else lowerZone
    if (otherZoneAfter != otherZoneBefore) {
      val otherZoneType = if (channel == 0) MpeZoneType.Upper else MpeZoneType.Lower
      logger.info(s"$otherZoneType zone adjusted by overlap resolution: $otherZoneAfter")
      buffer ++= mcmMessages(otherZoneAfter)
    }

    // Switch to MPE input mode
    _inputMode = MpeInputMode.Mpe
  }

  /**
   * Processes an incoming Pitch Bend Sensitivity RPN Data Entry MSB (semitones) or LSB (cents).
   *
   * In non-MPE input mode, all PBS input is treated as a zone-level master PBS update and forwarded
   * to the routing Zone's Master Channel (lower preferred), regardless of the input channel.
   * In MPE input mode, the zone and master/member role are determined by the input channel.
   */
  private def processPbs(buffer: mutable.Buffer[MidiMessage], channel: Int,
                         ccNumber: Int, ccValue: Int): Unit = {
    if (inputMode == MpeInputMode.NonMpe) {
      routingZoneForNonMpeInput.foreach { zone =>
        val updatedZone = zone.copy(
          masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
        applyPbsUpdate(buffer, zone.masterChannel, ccNumber, ccValue, updatedZone, isMaster = true)
      }
    } else {
      findZoneForChannel(channel) match {
        case Some((zone, isMaster)) =>
          val updatedZone = if (isMaster)
            zone.copy(masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
          else
            zone.copy(memberPitchBendSensitivity = patchPbs(zone.memberPitchBendSensitivity, ccNumber, ccValue))
          applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster)
        case None =>
          buffer += CcScMidiMessage(channel, ccNumber, ccValue).asJava
      }
    }
  }

  /**
   * Returns `current` with one component replaced from a PBS Data Entry CC: `semitones` from
   * `DataEntryMsb`, `cents` from `DataEntryLsb`. The untouched component is preserved so that
   * a sender updating only one half of PBS does not overwrite the other half.
   *
   * Why not read PBS from `tracker.rpn` instead? The tracker resolves missing RPN halves against
   * the MIDI 1.0 default `(2, 0)` baked into [[ScMidiChannelStateTracker.DefaultRpnValues]], so
   * once a sender writes only LSB the tracker reports `(2, lsbValue)` — losing the previously
   * configured semitones (e.g. the 48-semitone default for MPE Member Channels). The tracker has
   * neither per-channel RPN defaults nor a record of which halves the sender has actually written,
   * so it cannot distinguish "sender wrote 2 semitones" from "sender wrote nothing, and we filled
   * in the protocol default". Patching against the zone's stored PBS sidesteps that entirely.
   */
  private def patchPbs(current: PitchBendSensitivity, ccNumber: Int, ccValue: Int): PitchBendSensitivity = {
    if (ccNumber == ScMidiCc.DataEntryMsb) current.copy(semitones = ccValue)
    else current.copy(cents = ccValue)
  }

  /**
   * The zone used to route zone-level messages in non-MPE input mode: the Lower Zone if enabled,
   * otherwise the Upper Zone if enabled, otherwise none.
   */
  private def routingZoneForNonMpeInput: Option[MpeZone] = {
    if (lowerZone.isEnabled) Some(lowerZone)
    else if (upperZone.isEnabled) Some(upperZone)
    else None
  }

  /**
   * Applies a PBS update: updates the internal zone configuration, forwards the Data Entry message
   * on the target channel, and recomputes pitch bends on occupied member channels if needed.
   *
   * The Data Entry CC is forwarded only on `channel` — not broadcast to all member channels.
   * Per the MPE Specification, the sender is responsible for sending PBS to all member channels;
   * the tuner forwards each received Data Entry on the destination channel 1:1.
   *
   * The PBS RPN selector (RPN MSB/LSB = 0, 0) is re-emitted on `channel` immediately before the
   * Data Entry to guard against interleaving from other devices that may have changed the active
   * RPN on this channel between the original selector and the Data Entry. The original selector
   * CCs from the sender are suppressed upstream in `processCc` to avoid duplication.
   */
  private def applyPbsUpdate(buffer: mutable.Buffer[MidiMessage], channel: Int,
                             ccNumber: Int, ccValue: Int,
                             updatedZone: MpeZone, isMaster: Boolean): Unit = {
    _zones = _zones.update(updatedZone)

    if (logger.underlying.isInfoEnabled) {
      val channelRole = if (isMaster) "master" else "member"
      val pbsField = if (ccNumber == ScMidiCc.DataEntryMsb) "semitones" else "cents"
      logger.info(s"PBS updated on $channelRole channel $channel of ${updatedZone.zoneType} zone: $pbsField = $ccValue")
    }

    // Forward the RPN setup and Data Entry CC on the original channel only.
    // The RPN is re-sent to guard against interleaving from other devices that may have changed the
    // active RPN on this channel.
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava
    buffer += CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava
    buffer += CcScMidiMessage(channel, ccNumber, ccValue).asJava

    // Recompute pitch bends on occupied member channels if member PBS changed
    if (!isMaster) {
      val alloc = if (updatedZone.zoneType == MpeZoneType.Lower) lowerAllocator else upperAllocator
      alloc.foreach(updateTuningOnZone(_, buffer))
    }
  }

  /**
   * Finds the zone and master/member status for a given channel.
   *
   * @return `Some((zone, isMaster))` if the channel belongs to a zone, `None` otherwise.
   */
  private def findZoneForChannel(channel: Int): Option[(MpeZone, Boolean)] = {
    findChannelRole(lowerZone, channel).orElse(findChannelRole(upperZone, channel))
  }

  private def findChannelRole(zone: MpeZone, channel: Int): Option[(MpeZone, Boolean)] = {
    if (!zone.isEnabled) None
    else if (channel == zone.masterChannel) Some((zone, true))
    else if (zone.memberChannels.contains(channel)) Some((zone, false))
    else None
  }

  /**
   * Emits a Note Off for every note the Tuner currently considers active: the allocators' own bindings for
   * Member Channel notes, and — in MPE Input Mode — the Master Channel notes the tracker holds, which are
   * forwarded on the channel they arrived on.
   *
   * Unlike [[emitDroppedNoteOffs]], this emits exactly one Note Off per active Note Identity, not one per
   * reference count — a duplicated Note On does not get a duplicated Note Off here. This is within spec: the
   * one-Note-Off-per-Note-On obligation of the paper's "Note Identity and Reference Counting" section is
   * explicitly exempted for notes ended by Zone reconfiguration, and its "Zones" section leaves the choice
   * of whether to emit any Note Off at all — before an MCM or a reset — to the implementation. The asymmetry
   * with `emitDroppedNoteOffs` is deliberate, not an oversight.
   */
  private def stopAllNotes(buffer: mutable.Buffer[MidiMessage]): Unit = {
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      (noteIdentity, outChannel) <- alloc.activeAllocations
    } {
      buffer += NoteOffScMidiMessage(outChannel, noteIdentity.midiNote).asJava
    }

    if (_inputMode == MpeInputMode.Mpe) {
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled
        midiNote <- tracker.activeNotes(zone.masterChannel)
      } {
        buffer += NoteOffScMidiMessage(zone.masterChannel, midiNote).asJava
      }
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage],
                                     msg: ChannelPressureScMidiMessage): Unit = {
    if (inputMode == MpeInputMode.Mpe) {
      // Per-note pressure in MPE input: it belongs to every note active on the input channel, wherever the
      // pitch-class invariant placed them.
      // TODO #250 A Master Channel carries no allocated note, so nothing is emitted for one at all. The
      //  paper's "Master Channel Forwarding" section requires it to be forwarded unmodified as a Zone-level
      //  control instead. Note this is a regression: before the Expression Value model, a Master Channel
      //  note was recorded in the Tuner's own note map and its Channel Pressure was forwarded on the Master
      //  Channel for as long as such a note sounded.
      getAllocatorForInput(msg.channel).foreach { alloc =>
        emitExpressionUpdateResult(buffer, alloc.updatePressure(msg.channel, msg.value), alloc, NoDropExpected)
      }
    } else {
      // Non-MPE input: Channel Pressure applies to all notes on the input channel. Route to the
      // Zone's Master Channel as Zone-level pressure.
      forwardOnZoneMasterChannel(buffer, msg)
    }
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMessage],
                                  msg: PolyPressureScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val pressure = msg.value

    if (_inputMode == MpeInputMode.Mpe) {
      // MPE spec §2.5: Polyphonic Key Pressure must not be sent on Member Channels, but may
      // be sent for notes on the Master Channel. Forward it as-is on the Master Channel;
      // drop it silently on Member Channels.
      if (isMasterChannel(inputChannel)) {
        buffer += msg.asJava
      }
    } else {
      // Non-MPE input: convert Polyphonic Key Pressure to Channel Pressure on the allocated Member
      // Channel, since MPE forbids Polyphonic Key Pressure on Member Channels. The value is the addressed
      // note's own Expression Value and is averaged with those of the other notes on its output channel.
      getAllocatorForInput(inputChannel).foreach { alloc =>
        emitExpressionUpdateResult(buffer,
          alloc.updatePressure(MpeNoteIdentity(inputChannel, midiNote), pressure), alloc, NoDropExpected)
      }
    }
  }

  /**
   * Forwards `msg` on the master channel of the zone that `msg.channel` belongs to.
   *
   * For non-MPE input, all messages are routed to the first enabled zone (lower preferred).
   * For MPE input, the zone is determined by which zone's channel range contains `msg.channel`.
   */
  private def forwardOnZoneMasterChannel(buffer: mutable.Buffer[MidiMessage],
                                         msg: ChannelScMidiMessage): Unit = {
    resolveZoneMasterChannel(msg.channel).foreach { masterCh =>
      buffer += msg.mapChannel(_ => masterCh).asJava
    }
  }

  /**
   * Resolves the output channel for zone-level messages based on the `inputChannel`.
   *
   * For non-MPE input, returns the first enabled zone's master channel (lower preferred).
   * For MPE input, determines the zone by checking which zone's channel range (master or member)
   * contains `inputChannel` and returns that zone's master channel. If the input channel does not
   * belong to any zone, it is outside the MPE zone structure and the message passes through on
   * the original channel.
   */
  // TODO #250 Message routing and filtering conformance: a channel outside every Zone must have its
  //  messages discarded rather than passed through, including when no Zone is enabled at all; a
  //  Zone-level message arriving on an input Member Channel must be discarded; Master Channel CC #74
  //  and Channel Pressure must be forwarded as Zone-level controls; uninterpreted RPN/NRPN traffic
  //  must be routed per the paper and an invalid MCM ignored in its entirety; a forwarded Pitch Bend
  //  Sensitivity sequence must be closed with an RPN Null; a Zone reconfiguration must reset state
  //  only for the channels entering or leaving MPE control and must not discard the active Tuning;
  //  and the MIDI Mode messages 124-127 must never be forwarded.
  private def resolveZoneMasterChannel(inputChannel: Int): Option[Int] = {
    if (inputMode == MpeInputMode.NonMpe) {
      routingZoneForNonMpeInput.map(_.masterChannel)
    } else {
      findZoneForChannel(inputChannel) match {
        case Some((zone, _)) => Some(zone.masterChannel)
        case None => Some(inputChannel)
      }
    }
  }

  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val totalCents = tuningOffsetCents + alloc.channelExpression(channel).pitchBendCents
    val pbs = zone.memberPitchBendSensitivity
    val clampedCents = clampValue(totalCents, -pbs.totalCents, pbs.totalCents)
    PitchBendScMidiMessage.convertCentsToValue(clampedCents, pbs)
  }

  /**
   * Emits Note Off messages for dropped notes: one per Note On forwarded for each note, at the neutral
   * release velocity 64 that a note ended by the Tuner's own decision receives.
   */
  private def emitDroppedNoteOffs(buffer: mutable.Buffer[MidiMessage], droppedNotes: MpeDroppedNotes,
                                  reason: String): Unit = {
    logger.trace(s"Dropping notes ${droppedNotes.notes.map(_.noteIdentity.midiNote)} " +
      s"on channel ${droppedNotes.channel} ($reason)")
    for {
      droppedNote <- droppedNotes.notes
      _ <- 1 to droppedNote.referenceCount
    } {
      buffer += NoteOffScMidiMessage(droppedNotes.channel, droppedNote.noteIdentity.midiNote).asJava
    }
  }

  /**
   * Emits a CC #74 (Slide) message on `channel` if `update` carries a new value.
   */
  private def emitSlide(buffer: mutable.Buffer[MidiMessage], channel: Int, update: MpeExpressionUpdate): Unit =
    update.slide.foreach { value => buffer += CcScMidiMessage(channel, ScMidiCc.MpeSlide, value).asJava }

  /**
   * Emits a Channel Pressure message on `channel` if `update` carries a new value.
   */
  private def emitPressure(buffer: mutable.Buffer[MidiMessage], channel: Int, update: MpeExpressionUpdate): Unit =
    update.pressure.foreach { value => buffer += ChannelPressureScMidiMessage(channel, value).asJava }

  /**
   * Emits the control dimension messages for the Expression Values that changed on an output Member
   * Channel, in the relative order Pitch Bend, CC #74, Channel Pressure.
   */
  private def emitExpressionUpdate(buffer: mutable.Buffer[MidiMessage], channel: Int,
                                   update: MpeExpressionUpdate, alloc: MpeChannelAllocator): Unit = {
    if (update.pitchBendCents.isDefined) emitOutputPitchBend(buffer, channel, alloc)
    emitSlide(buffer, channel, update)
    emitPressure(buffer, channel, update)
  }

  /**
   * Applies an Expression Value update received on an input Member Channel: emits the Note Offs of any
   * notes the update dropped first, then the recomputed Expression Values of each affected output channel.
   *
   * @param dropReason The reason logged for each dropped note. Only an Expression Pitch Bend update can
   *                    actually produce drops, so it is the sole path that passes a meaningful reason;
   *                    callers on the slide and pressure paths pass [[NoDropExpected]] instead.
   */
  private def emitExpressionUpdateResult(buffer: mutable.Buffer[MidiMessage], result: MpeExpressionUpdateResult,
                                         alloc: MpeChannelAllocator, dropReason: String): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, dropReason))
    result.channelUpdates.foreach { channelUpdate =>
      emitExpressionUpdate(buffer, channelUpdate.channel, channelUpdate.update, alloc)
    }
  }

  /**
   * Emits a Pitch Bend message for a channel based on the current tuning offset, if the channel has active notes.
   * The zone used for pitch bend computation is resolved from `_zones` based on the allocator's zone type.
   */
  private def emitOutputPitchBend(buffer: mutable.Buffer[MidiMessage], channel: Int,
                                  alloc: MpeChannelAllocator): Unit = {
    val zone = currentZone(alloc)
    alloc.channelPitchClass(channel).foreach { pc =>
      val tuningOffset = _tuning(pc)
      val totalPitchBend = computeOutputPitchBend(channel, alloc, zone, tuningOffset)
      buffer += PitchBendScMidiMessage(channel, totalPitchBend).asJava
    }
  }

  private def updateTuningOnZone(alloc: MpeChannelAllocator,
                                 buffer: mutable.Buffer[MidiMessage]): Unit = {
    val zone = currentZone(alloc)
    // Only occupied channels have a pitch class assigned
    for (ch <- zone.memberChannels) {
      emitOutputPitchBend(buffer, ch, alloc)
    }
  }

  private def currentZone(alloc: MpeChannelAllocator): MpeZone = alloc.zoneType match {
    case MpeZoneType.Lower => lowerZone
    case MpeZoneType.Upper => upperZone
  }

  private def mcmMessages(zone: MpeZone): Seq[MidiMessage] = {
    // MCM: RPN 6 on master channel with data = memberCount
    Seq(
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.DataEntryMsb, zone.memberCount),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      CcScMidiMessage(zone.masterChannel, ScMidiCc.RpnMsb, ScMidiRpn.NullMsb)
    ).map(_.asJava)
  }

  private def pitchBendSensitivityMessages(zone: MpeZone): Seq[MidiMessage] = {
    if (zone.isEnabled) {
      val buffer = mutable.Buffer[MidiMessage]()

      // Master channel PBS
      buffer ++= PitchBendSensitivityMessages.create(zone.masterChannel, zone.masterPitchBendSensitivity)

      // Member channel PBS
      zone.memberChannels.foreach { ch =>
        buffer ++= PitchBendSensitivityMessages.create(ch, zone.memberPitchBendSensitivity)
      }

      buffer.toSeq
    } else {
      Seq.empty
    }
  }

  private def createAllocator(zone: MpeZone): Option[MpeChannelAllocator] = {
    if (zone.isEnabled) Some(MpeChannelAllocator(zone)) else None
  }

  private def getAllocatorForInput(inputChannel: Int): Option[MpeChannelAllocator] = {
    // For non-MPE input, use the first enabled zone's allocator
    if (_inputMode == MpeInputMode.NonMpe) {
      lowerAllocator.orElse(upperAllocator)
    } else {
      // For MPE input, determine zone based on input channel
      if (lowerZone.isEnabled && (lowerZone.memberChannels.contains(inputChannel) ||
        inputChannel == lowerZone.masterChannel)) {
        lowerAllocator
      } else if (upperZone.isEnabled && (upperZone.memberChannels.contains(inputChannel) ||
        inputChannel == upperZone.masterChannel)) {
        upperAllocator
      } else {
        lowerAllocator.orElse(upperAllocator)
      }
    }
  }

  private def isMasterChannel(channel: Int): Boolean = {
    (lowerZone.isEnabled && channel == lowerZone.masterChannel) ||
      (upperZone.isEnabled && channel == upperZone.masterChannel)
  }
}

/** Plugin type name and the reasons logged when [[MpeTuner]] ends a note by its own decision. */
object MpeTuner {
  /** The `Tuner` plugin type name this tuner is (de)serialized under. */
  val TypeName: String = "mpe"

  /**
   * Logged when a Note On drops notes. The allocation algorithm freeing an occupied channel is the common
   * cause, but a new note assigned to a channel holding a High Expression Pitch Bend note — or one whose own
   * bend is high — drops its co-residents too, as does a duplicate Note On whose overridden Expression
   * Values raise it to a high bend.
   */
  private val DropReasonOnNoteOn: String = "channel freed, or High Expression Pitch Bend, on a new Note On"

  /** Logged when an Expression Pitch Bend makes a note diverge from the others sharing its channel. */
  private val DropReasonOnPitchBend: String = "High Expression Pitch Bend diverging on a shared channel"

  /**
   * A drop reason for callers of `emitExpressionUpdateResult` whose update can never actually produce drops:
   * `result.droppedNotes` is always empty for slide and pressure updates (see [[MpeExpressionUpdateResult]]),
   * so this value is never surfaced in a log line.
   */
  private val NoDropExpected: String = "unreachable: slide/pressure updates never drop notes"
}

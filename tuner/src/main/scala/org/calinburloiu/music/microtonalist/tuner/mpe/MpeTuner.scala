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

package org.calinburloiu.music.microtonalist.tuner.mpe

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.*

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
 * For the technical specification check the white paper in `docs/tuner/mpe-tuner-paper.md`.
 *
 * @param initialZones     The initial [[MpeZones]] configuration for the Lower and Upper Zones.
 * @param initialInputMode Initial [[MpeInputMode]]. The tuner switches to MPE mode automatically
 *                         upon receiving an MPE Configuration Message.
 */
class MpeTuner(private val initialZones: MpeZones = MpeZones.DefaultZones,
               private val initialInputMode: MpeInputMode = MpeInputMode.NonMpe) extends Tuner with StrictLogging {

  override val typeName: String = MpeTuner.TypeName

  private var _zones: MpeZones = initialZones
  private var _inputMode: MpeInputMode = initialInputMode

  private var _tuning: Tuning = Tuning.Standard

  private var lowerAllocator: Option[MpeChannelAllocator] = createAllocator(lowerZone)
  private var upperAllocator: Option[MpeChannelAllocator] = createAllocator(upperZone)

  // Track which channel each note is on: (midiNote, inputChannel) -> outputChannel
  private val noteChannelMap: mutable.Map[(MidiNote, Int), Int] = mutable.Map.empty
  // Track input->output channel mapping for MPE input mode
  private val mpeInputChannelMap: mutable.Map[Int, Int] = mutable.Map.empty

  // Per-input-channel control dimension state used to seed the output Member Channel at Note On
  // (MPE input mode only). Non-MPE inputs seed Member Channels with neutral defaults.
  private val channelPressureMap: mutable.Map[Int, Int] = mutable.Map.empty
  private val channelSlideMap: mutable.Map[Int, Int] = mutable.Map.empty

  // RPN state machine: tracks last received CC#100 (RPN LSB) and CC#101 (RPN MSB) per channel
  private val rpnLsbState: mutable.Map[Int, Int] = mutable.Map.empty
  private val rpnMsbState: mutable.Map[Int, Int] = mutable.Map.empty

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
   * Clears internal mutable state and recreates allocators from `currentZones`.
   */
  private def resetState(): Unit = {
    _tuning = Tuning.Standard
    noteChannelMap.clear()
    mpeInputChannelMap.clear()
    channelPressureMap.clear()
    channelSlideMap.clear()
    rpnLsbState.clear()
    rpnMsbState.clear()

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

    ScMidiMessage.fromJavaMessage(message) match {
      case msg: ScNoteOnMidiMessage if msg.velocity > 0 =>
        processNoteOn(buffer, msg)
      case msg: ScNoteOnMidiMessage =>
        // Note On with velocity 0 is a Note Off per MIDI spec.
        processNoteOff(buffer, ScNoteOffMidiMessage(msg.channel, msg.midiNote))
      case msg: ScNoteOffMidiMessage => processNoteOff(buffer, msg)
      case msg: ScPitchBendMidiMessage => processPitchBend(buffer, msg)
      case msg: ScCcMidiMessage => processCc(buffer, msg)
      case msg: ScChannelPressureMidiMessage => processChannelPressure(buffer, msg)
      case msg: ScPolyPressureMidiMessage => processPolyPressure(buffer, msg)
      case msg: ScProgramChangeMidiMessage =>
        // Forward on the zone's master channel
        resolveZoneMasterChannel(msg.channel).foreach { masterCh =>
          buffer += msg.copy(channel = masterCh).javaMessage
        }
      case _ =>
        buffer += message
    }

    buffer.toSeq
  }

  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], msg: ScNoteOnMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote

    if (_inputMode == MpeInputMode.Mpe && isMasterChannel(inputChannel)) {
      // Master Channel notes are forwarded as-is: no allocator, no tuning offset, no control
      // dimension setup. They play in 12-EDO (modulated only by Master Pitch Bend) because
      // applying a per-pitch-class tuning offset on the Master Channel would affect every
      // note in the Zone.
      noteChannelMap((midiNote, inputChannel)) = inputChannel
      buffer += msg.javaMessage
    } else {
      processMemberNoteOn(buffer, msg)
    }
  }

  private def processMemberNoteOn(buffer: mutable.Buffer[MidiMessage], msg: ScNoteOnMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity
    val allocator = getAllocatorForInput(inputChannel)

    allocator match {
      case Some(alloc) =>
        val zone = currentZone(alloc)
        val preferredChannel = if (inputMode == MpeInputMode.Mpe && zone.memberChannels.contains(inputChannel))
          Some(inputChannel) else None

        val result = alloc.allocate(midiNote, preferredChannel = preferredChannel)
        val outChannel = result.channel

        // Handle dropped notes
        emitDroppedNoteOffs(buffer, result.droppedNotes, "allocation overflow on new Note On")

        // Track the note
        noteChannelMap((midiNote, inputChannel)) = outChannel
        if (inputMode == MpeInputMode.Mpe) {
          mpeInputChannelMap(inputChannel) = outChannel
        }

        // Compute and send control dimensions before Note On
        val tuningOffset = _tuning(midiNote.pitchClass)
        val totalPitchBend = computeOutputPitchBend(outChannel, alloc, zone, tuningOffset)
        buffer += ScPitchBendMidiMessage(outChannel, totalPitchBend).javaMessage

        // CC #74 (slide) and Channel Pressure: in MPE mode, seed the output Member Channel with
        // the sender's last-known per-input-channel value so per-note articulation carries over
        // across Note boundaries. In non-MPE mode, sender CC #74 / CP arrive on the Master Channel
        // as Zone-level controls, so the Member Channel is initialized to neutral defaults
        // (MPE spec §3.3.5 Initial-64 for CC #74, §3.3.4 CP=0) to avoid double-counting.
        val slide = if (inputMode == MpeInputMode.Mpe) channelSlideMap.getOrElse(inputChannel, 64) else 64
        buffer += ScCcMidiMessage(outChannel, ScCcMidiMessage.MpeSlide, slide).javaMessage

        val pressure = if (inputMode == MpeInputMode.Mpe) channelPressureMap.getOrElse(inputChannel, 0) else 0
        buffer += ScChannelPressureMidiMessage(outChannel, pressure).javaMessage

        // Note On
        buffer += ScNoteOnMidiMessage(outChannel, midiNote, velocity).javaMessage

      case None =>
        // No allocator for this channel, forward as-is
        buffer += ScNoteOnMidiMessage(inputChannel, midiNote, velocity).javaMessage
    }
  }

  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], msg: ScNoteOffMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    noteChannelMap.remove((midiNote, inputChannel)) match {
      case Some(outChannel) =>
        val allocator = getAllocatorForOutput(outChannel)
        allocator.foreach(_.release(midiNote, outChannel))
        buffer += ScNoteOffMidiMessage(outChannel, midiNote, velocity).javaMessage
        // Remove the input→output routing only when no active note still uses that output channel.
        // A stale entry would misroute subsequent expressive Pitch Bend / Channel Pressure / CC #74
        // arriving on this input channel to an unrelated output note, corrupting its intonation.
        if (inputMode == MpeInputMode.Mpe) {
          if (!noteChannelMap.values.exists(_ == mpeInputChannelMap.getOrElse(inputChannel, -1))) {
            mpeInputChannelMap.remove(inputChannel)
          }
        }
      case None =>
        buffer += msg.javaMessage
    }
  }

  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], msg: ScPitchBendMidiMessage): Unit = {
    val inputChannel = msg.channel
    val pitchBendValue = msg.value

    if (inputMode == MpeInputMode.Mpe) {
      // Check if it's a master channel
      if (isMasterChannel(inputChannel)) {
        // Forward master channel pitch bend without modification
        buffer += msg.javaMessage
      } else {
        // Per-note pitch bend in MPE input - treat as expressive pitch bend
        mpeInputChannelMap.get(inputChannel).foreach { outChannel =>
          val allocator = getAllocatorForOutput(outChannel)
          allocator.foreach { alloc =>
            val pitchBendCents = ScPitchBendMidiMessage.convertValueToCents(
              pitchBendValue, currentZone(alloc).memberPitchBendSensitivity)
            val droppedNotes = alloc.updateExpressivePitchBend(outChannel, pitchBendCents)
            emitDroppedNoteOffs(buffer, droppedNotes, "expressive pitch bend too high")
            emitTuningPitchBend(buffer, outChannel, alloc)
          }
        }
      }
    } else {
      // Non-MPE input: redirect pitch bend to master channel as zone-level pitch bend
      if (lowerZone.isEnabled) {
        buffer += ScPitchBendMidiMessage(lowerZone.masterChannel, pitchBendValue).javaMessage
      } else if (upperZone.isEnabled) {
        buffer += ScPitchBendMidiMessage(upperZone.masterChannel, pitchBendValue).javaMessage
      }
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMessage], msg: ScCcMidiMessage): Unit = {
    val inputChannel = msg.channel
    val ccNumber = msg.number
    val ccValue = msg.value
    val rpnLsb = rpnLsbState.getOrElse(inputChannel, Rpn.NullLsb)
    val rpnMsb = rpnMsbState.getOrElse(inputChannel, Rpn.NullMsb)
    val isMcmRpn = rpnMsb == Rpn.MpeConfigurationMessageMsb && rpnLsb == Rpn.MpeConfigurationMessageLsb
    val isPbsRpn = rpnMsb == Rpn.PitchBendSensitivityMsb && rpnLsb == Rpn.PitchBendSensitivityLsb

    ccNumber match {
      // RPN state machine
      case ScCcMidiMessage.RpnLsb =>
        rpnLsbState(inputChannel) = ccValue
        buffer += ScCcMidiMessage(inputChannel, ccNumber, ccValue).javaMessage
      case ScCcMidiMessage.RpnMsb =>
        rpnMsbState(inputChannel) = ccValue
        buffer += ScCcMidiMessage(inputChannel, ccNumber, ccValue).javaMessage
      case ScCcMidiMessage.DataEntryMsb if isMcmRpn && (inputChannel == 0 || inputChannel == 15) =>
        processMcm(buffer, inputChannel, ccValue)
      case ScCcMidiMessage.DataEntryMsb | ScCcMidiMessage.DataEntryLsb if isPbsRpn =>
        processPbs(buffer, inputChannel, ccNumber, ccValue)

      // CC #74 (MPE Slide / timbre): in MPE mode, route to the allocated Member Channel as
      // per-note timbre; in non-MPE mode, route to the Zone's Master Channel as Zone-level timbre
      // (MPE spec §2.6: CC #74 is both Note-Level and Zone-Level).
      case ScCcMidiMessage.MpeSlide =>
        if (inputMode == MpeInputMode.Mpe) {
          channelSlideMap(inputChannel) = ccValue
          forwardToMemberChannel(buffer, inputChannel, ScCcMidiMessage(_, ScCcMidiMessage.MpeSlide, ccValue))
        } else {
          forwardOnZoneMasterChannel(buffer, inputChannel, ScCcMidiMessage(_, ScCcMidiMessage.MpeSlide, ccValue))
        }
      // All other CCs are forwarded on the master channel of the zone the input belongs to
      case _ =>
        forwardOnZoneMasterChannel(buffer, inputChannel, ScCcMidiMessage(_, ccNumber, ccValue))
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
   */
  private def processPbs(buffer: mutable.Buffer[MidiMessage], channel: Int,
                         ccNumber: Int, ccValue: Int): Unit = {
    findZoneForChannel(channel) match {
      case Some((zone, isMaster)) =>
        val currentPbs = if (isMaster) zone.masterPitchBendSensitivity else zone.memberPitchBendSensitivity
        val newPbs = if (ccNumber == ScCcMidiMessage.DataEntryMsb) {
          currentPbs.copy(semitones = ccValue)
        } else {
          currentPbs.copy(cents = ccValue)
        }
        val updatedZone = if (isMaster) zone.copy(masterPitchBendSensitivity = newPbs)
        else zone.copy(memberPitchBendSensitivity = newPbs)
        applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster)
      case None =>
        // Channel not in any zone, forward as-is
        buffer += ScCcMidiMessage(channel, ccNumber, ccValue).javaMessage
    }
  }

  /**
   * Applies a PBS update: updates the internal zone configuration, forwards the RPN setup and
   * Data Entry message on the original channel, and recomputes pitch bends on occupied member
   * channels if needed.
   *
   * The RPN and Data Entry CCs are forwarded only on the original `channel` — not broadcast to all
   * member channels. Per the MPE Specification, the sender is responsible for sending PBS to all
   * member channels. Forwarding on each received channel preserves the 1:1 input-to-output ratio.
   *
   * The RPN is always re-sent before the Data Entry to guard against interleaving from other
   * devices that may have changed the active RPN on the output channel.
   */
  private def applyPbsUpdate(buffer: mutable.Buffer[MidiMessage], channel: Int,
                             ccNumber: Int, ccValue: Int,
                             updatedZone: MpeZone, isMaster: Boolean): Unit = {
    _zones = _zones.update(updatedZone)

    if (logger.underlying.isInfoEnabled) {
      val channelRole = if (isMaster) "master" else "member"
      val pbsField = if (ccNumber == ScCcMidiMessage.DataEntryMsb) "semitones" else "cents"
      logger.info(s"PBS updated on $channelRole channel $channel of ${updatedZone.zoneType} zone: $pbsField = $ccValue")
    }

    // Forward the RPN setup and Data Entry CC on the original channel only.
    // The RPN is re-sent to guard against interleaving from other devices that may have changed the
    // active RPN on this channel.
    buffer += ScCcMidiMessage(channel, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb).javaMessage
    buffer += ScCcMidiMessage(channel, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb).javaMessage
    buffer += ScCcMidiMessage(channel, ccNumber, ccValue).javaMessage

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
   * Sends Note Off for all active notes tracked in `noteChannelMap`.
   */
  private def stopAllNotes(buffer: mutable.Buffer[MidiMessage]): Unit = {
    noteChannelMap.foreach { case ((midiNote, _), outChannel) =>
      buffer += ScNoteOffMidiMessage(outChannel, midiNote).javaMessage
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage],
                                     msg: ScChannelPressureMidiMessage): Unit = {
    val inputChannel = msg.channel
    val pressure = msg.value

    if (inputMode == MpeInputMode.Mpe) {
      // Per-note pressure in MPE input: route to the allocated Member Channel.
      channelPressureMap(inputChannel) = pressure
      forwardToMemberChannel(buffer, inputChannel, ScChannelPressureMidiMessage(_, pressure))
    } else {
      // Non-MPE input: Channel Pressure applies to all notes on the input channel. Route to the
      // Zone's Master Channel as Zone-level pressure (MPE spec §2.5: CP is both Note-Level and
      // Zone-Level; receivers combine Master + Member data per sounding note).
      forwardOnZoneMasterChannel(buffer, inputChannel, ScChannelPressureMidiMessage(_, pressure))
    }
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMessage],
                                  msg: ScPolyPressureMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val pressure = msg.value

    if (_inputMode == MpeInputMode.Mpe) {
      // MPE spec §2.5: Polyphonic Key Pressure must not be sent on Member Channels, but may
      // be sent for notes on the Master Channel. Forward it as-is on the Master Channel;
      // drop it silently on Member Channels.
      if (isMasterChannel(inputChannel)) {
        buffer += msg.javaMessage
      }
    } else {
      // Non-MPE input: convert Polyphonic Key Pressure to Channel Pressure on the allocated
      // Member Channel, since MPE forbids Polyphonic Key Pressure on Member Channels.
      noteChannelMap.get((midiNote, inputChannel)).foreach { outChannel =>
        buffer += ScChannelPressureMidiMessage(outChannel, pressure).javaMessage
      }
    }
  }

  /**
   * Forwards a per-note control message (Channel Pressure, CC #74) to the output Member Channel
   * that currently holds the note originated on `inputChannel`. MPE input mode only: in non-MPE
   * mode these controls are routed to the Master Channel by the callers.
   */
  private def forwardToMemberChannel(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                                     makeMessage: Int => ScMidiMessage): Unit = {
    mpeInputChannelMap.get(inputChannel).foreach { outChannel =>
      buffer += makeMessage(outChannel).javaMessage
    }
  }

  /**
   * Forwards a message on the master channel of the zone that the `inputChannel` belongs to.
   *
   * For non-MPE input, all messages are routed to the first enabled zone (lower preferred).
   * For MPE input, the zone is determined by which zone's channel range contains `inputChannel`.
   */
  private def forwardOnZoneMasterChannel(buffer: mutable.Buffer[MidiMessage], inputChannel: Int,
                                         makeMessage: Int => ScMidiMessage): Unit = {
    resolveZoneMasterChannel(inputChannel).foreach { masterCh =>
      buffer += makeMessage(masterCh).javaMessage
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
  private def resolveZoneMasterChannel(inputChannel: Int): Option[Int] = {
    if (inputMode == MpeInputMode.NonMpe) {
      if (lowerZone.isEnabled) Some(lowerZone.masterChannel)
      else if (upperZone.isEnabled) Some(upperZone.masterChannel)
      else None
    } else {
      findZoneForChannel(inputChannel) match {
        case Some((zone, _)) => Some(zone.masterChannel)
        case None => Some(inputChannel)
      }
    }
  }

  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val notes = alloc.activeNotes(channel)
    val avgExpressiveBendCents = if (notes.nonEmpty) {
      notes.map(alloc.expressionFor(channel, _).pitchBendCents).sum / notes.size
    } else {
      0.0
    }

    val totalCents = tuningOffsetCents + avgExpressiveBendCents
    val pbs = zone.memberPitchBendSensitivity
    val clampedCents = clampValue(totalCents, -pbs.totalCents, pbs.totalCents)
    ScPitchBendMidiMessage.convertCentsToValue(clampedCents, pbs)
  }

  /**
   * Emits Note Off messages for dropped notes, if any.
   */
  private def emitDroppedNoteOffs(buffer: mutable.Buffer[MidiMessage], droppedNotes: Option[DroppedNotes],
                                  reason: String): Unit = {
    droppedNotes.foreach { dropped =>
      logger.trace(s"Dropping notes ${dropped.notes} on channel ${dropped.channel} ($reason)")
      dropped.notes.foreach { midiNote =>
        buffer += ScNoteOffMidiMessage(dropped.channel, midiNote).javaMessage
      }
    }
  }

  /**
   * Emits a Pitch Bend message for a channel based on the current tuning offset, if the channel has active notes.
   * The zone used for pitch bend computation is resolved from `_zones` based on the allocator's zone type.
   */
  private def emitTuningPitchBend(buffer: mutable.Buffer[MidiMessage], channel: Int,
                                  alloc: MpeChannelAllocator): Unit = {
    val zone = currentZone(alloc)
    alloc.channelPitchClass(channel).foreach { pc =>
      val tuningOffset = _tuning(pc)
      val totalPitchBend = computeOutputPitchBend(channel, alloc, zone, tuningOffset)
      buffer += ScPitchBendMidiMessage(channel, totalPitchBend).javaMessage
    }
  }

  private def updateTuningOnZone(alloc: MpeChannelAllocator,
                                 buffer: mutable.Buffer[MidiMessage]): Unit = {
    val zone = currentZone(alloc)
    // Only occupied channels have a pitch class assigned
    for (ch <- zone.memberChannels) {
      emitTuningPitchBend(buffer, ch, alloc)
    }
  }

  private def currentZone(alloc: MpeChannelAllocator): MpeZone = alloc.zoneType match {
    case MpeZoneType.Lower => lowerZone
    case MpeZoneType.Upper => upperZone
  }

  private def mcmMessages(zone: MpeZone): Seq[MidiMessage] = {
    // MCM: RPN 6 on master channel with data = memberCount
    Seq(
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.DataEntryMsb, zone.memberCount),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      ScCcMidiMessage(zone.masterChannel, ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    ).map(_.javaMessage)
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

  private def getAllocatorForOutput(outputChannel: Int): Option[MpeChannelAllocator] = {
    if (lowerZone.isEnabled && lowerZone.memberChannels.contains(outputChannel)) {
      lowerAllocator
    } else if (upperZone.isEnabled && upperZone.memberChannels.contains(outputChannel)) {
      upperAllocator
    } else {
      None
    }
  }

  private def isMasterChannel(channel: Int): Boolean = {
    (lowerZone.isEnabled && channel == lowerZone.masterChannel) ||
      (upperZone.isEnabled && channel == upperZone.masterChannel)
  }
}

object MpeTuner {
  val TypeName: String = "mpe"
}

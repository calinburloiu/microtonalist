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

import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.message.*

import scala.collection.mutable

/**
 * Defines how incoming MIDI messages are interpreted before being processed by the [[MpeTuner]].
 */
enum MpeInputMode {
  /**
   * Conventional MIDI, with no Zone structure, which the Tuner converts to MPE.
   */
  case NonMpe

  /**
   * MIDI conforming to the MPE Specification, with notes already distributed across Member Channels within Zones.
   */
  case Mpe
}

/**
 * Tuner that applies microtonal tunings to polyphonic MIDI streams with MIDI Polyphonic Expression (MPE): each note
 * gets a Member Channel whose Pitch Bend carries the tuning offset of its pitch class, and a tuning change updates the
 * Pitch Bend of every occupied Member Channel.
 *
 * It follows the MPE Configuration Messages and Pitch Bend Sensitivity RPNs it receives. Its internal flow is outlined
 * in `docs/architecture/tuner/mpe-tuner.md`, and its design in `docs/architecture/tuner/mpe-tuner-paper.md`, which the
 * comments in this file cite by section name.
 *
 * @param initialZones The initial [[MpeZones]] configuration for the Lower and Upper Zones.
 * @param initialInputMode Initial [[MpeInputMode]]. The tuner switches to MPE mode automatically upon receiving an MPE
 *   Configuration Message.
 */
class MpeTuner(private val initialZones: MpeZones = MpeZones.DefaultZones,
               private val initialInputMode: MpeInputMode = MpeInputMode.NonMpe) extends Tuner {

  import MpeTuner.*

  override val typeName: String = MpeTuner.TypeName

  private var _zones: MpeZones = initialZones
  private var _inputMode: MpeInputMode = initialInputMode

  private var lowerAllocator: Option[MpeChannelAllocator] = createAllocator(lowerZone)
  private var upperAllocator: Option[MpeChannelAllocator] = createAllocator(upperZone)

  /**
   * Per-input-channel state: it seeds a new note's Expression Values in MPE Input Mode and tells which parameter a Data
   * Entry addresses.
   */
  private val tracker: MidiChannelStateTracker = MidiChannelStateTracker()

  /**
   * The parameter the Tuner last left selected on each output channel, so that a relayed sequence repeats its selector
   * only when the parameter changes; an absent entry means "not known". It stays exact only while every RPN sequence
   * the Tuner emits is recorded here, hence `emitMcmSequence` and `emitPbsSequence`, and every relayed message that
   * deselects at the receiver removes its entry: see [[MpeMessageRouting.deselectsOnRelay]] and the System Reset case
   * in `process`.
   */
  private val outputRpnSelectors: mutable.Map[Int, RpnSelector] = mutable.Map.empty

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
   * @inheritdoc
   *
   * This tuner stops its notes, returns the controls it sent to their defaults, restores the initial Zones and input
   * mode, and restates the Zones' MPE Configuration Messages and Pitch Bend Sensitivities. It doesn't reset what
   * another source left on the output device.
   */
  override protected def onReset(): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    // Notes stop before the Zones change, or they would hang (MPE Specification §2.1.4).
    stopNotesOn(buffer, AllChannels)
    releaseForwardedControls(buffer)
    releaseMemberChannelControls(buffer)

    _zones = initialZones
    _inputMode = initialInputMode

    resetState()
    warnOnNonMpeInputWithBothZones()
    emitConfiguration(buffer)

    buffer.toSeq
  }

  /**
   * @inheritdoc
   *
   * This tuner can tune exactly the offsets within the Member Pitch Bend Sensitivity of each Zone the input reaches:
   * every enabled Zone in MPE Input Mode, only the one non-MPE input is routed to in Non-MPE Input Mode.
   */
  override def canTune(tuning: Tuning): Boolean = reachableZones.forall { zone =>
    val maxOffset = zone.memberPitchBendSensitivity.totalCents
    tuning.offsets.forall(offset => Math.abs(offset) <= maxOffset)
  }

  override protected def onTune(tuning: Tuning, previousTuning: Option[Tuning]): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()

    lowerAllocator.foreach(updateTuningOnZone(buffer, _, tuning))
    upperAllocator.foreach(updateTuningOnZone(buffer, _, tuning))

    buffer.toSeq
  }

  override def process(message: MidiMsg): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    // A Note On with velocity 0 is a Note Off, normalized here so that the tracker and the router see a single shape.
    val normalizedMessage = message match {
      case msg: NoteOnMidiMsg if msg.velocity == NoteOnMidiMsg.NoteOffVelocity =>
        NoteOffMidiMsg(msg.channel, msg.midiNote)
      case other => other
    }
    tracker.send(normalizedMessage)

    normalizedMessage match {
      case msg: ChannelMidiMsg =>
        val role = MpeMessageRouting.roleOf(_inputMode, _zones, msg.channel)
        val rpnSelector = tracker.rpnSelector(msg.channel)
        MpeMessageRouting.route(role, msg, rpnSelector) match {
          case MpeRoutingVerdict.Discard =>
            // Out-of-zone and wrong-level traffic is normal in a mixed rig, so this stays at trace level.
            logger.trace(s"Discarding $msg received on a channel with role $role")
          case MpeRoutingVerdict.ForwardOn(channel) =>
            buffer += msg.mapChannel(_ => channel)
            // A relayed Reset All Controllers deselects the parameter at the receiver, which voids the record.
            if (MpeMessageRouting.deselectsOnRelay(msg)) outputRpnSelectors.remove(channel)
          case MpeRoutingVerdict.ForwardRpnSequenceOn(channel) => msg match {
              case cc: CcMidiMsg =>
                val (messages, latchedSelector) =
                  MpeMessageRouting.rpnSequence(rpnSelector, cc, channel, latchedSelectorOn(channel))
                buffer ++= messages
                outputRpnSelectors(channel) = latchedSelector
              case _ =>
                // `route` returns this verdict only for a Data Entry, Data Increment or Data Decrement CC.
                logger.error(s"Unexpected RPN sequence verdict for $msg")
            }
          case MpeRoutingVerdict.Interpret =>
            interpret(buffer, msg, role, rpnSelector)
        }
      case _ =>
        // System messages affect the whole system and pass through.
        buffer += normalizedMessage
        // A System Reset returns every receiving channel to its power-up state, parameter selection included.
        if (normalizedMessage == SystemResetMidiMsg) outputRpnSelectors.clear()
    }

    buffer.toSeq
  }

  private def lowerZone: MpeZone = _zones.lower

  private def upperZone: MpeZone = _zones.upper

  /**
   * The enabled Zones whose Member Channels the input can reach: in Non-MPE Input Mode, only the one
   * [[MpeMessageRouting.roleOf]] routes it to.
   */
  private def reachableZones: Seq[MpeZone] = {
    val enabledZones = Seq(lowerZone, upperZone).filter(_.isEnabled)
    if (_inputMode == MpeInputMode.NonMpe) enabledZones.take(1) else enabledZones
  }

  /** Warns that non-MPE input can't reach the Upper Zone when both Zones are enabled. */
  private def warnOnNonMpeInputWithBothZones(): Unit = {
    if (_inputMode == MpeInputMode.NonMpe && lowerZone.isEnabled && upperZone.isEnabled) {
      logger.warn("MpeTuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is " +
        "routed to a single Zone, the Lower Zone taking precedence, so the Upper Zone's Member Channels " +
        s"are unreachable. Consider disabling the Upper Zone or switching to MPE Input Mode. Zones: ${_zones}")
    }
  }

  /** Clears the tracked state and recreates the allocators, keeping the current tuning. */
  private def resetState(): Unit = {
    tracker.reset()
    // Cleared even though the configuration a reset emits re-records the Master Channels: a channel may change role.
    outputRpnSelectors.clear()

    lowerAllocator = createAllocator(lowerZone)
    upperAllocator = createAllocator(upperZone)
  }

  private def emitConfiguration(buffer: mutable.Buffer[MidiMsg]): Unit = {
    emitMcmSequence(buffer, lowerZone)
    emitZonePbsSequences(buffer, lowerZone)
    emitMcmSequence(buffer, upperZone)
    emitZonePbsSequences(buffer, upperZone)
  }

  /** Handles the messages [[MpeMessageRouting.route]] leaves to the Tuner. */
  private def interpret(buffer: mutable.Buffer[MidiMsg], msg: ChannelMidiMsg,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg match {
    case m: NoteOnMidiMsg => processNoteOn(buffer, m, role)
    case m: NoteOffMidiMsg => processNoteOff(buffer, m, role)
    case m: PitchBendMidiMsg => processPitchBend(buffer, m, role)
    case m: ChannelPressureMidiMsg => processChannelPressure(buffer, m, role)
    case m: PolyPressureMidiMsg => processPolyPressure(buffer, m, role)
    case m: CcMidiMsg => processCc(buffer, m, role, rpnSelector)
    case m =>
      // `route` forwards or discards the other channel messages: Program Change and the Channel Mode messages.
      logger.error(s"Unexpected request to interpret $m")
  }

  private def processNoteOn(buffer: mutable.Buffer[MidiMsg], msg: NoteOnMidiMsg,
                            role: MpeChannelRole): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    allocatorFor(role).foreach { alloc =>
      val zone = currentZone(alloc)
      val isMpeInput = role match {
        case MpeChannelRole.Member(_) => true
        case _ => false
      }
      // In Non-MPE Input Mode the note starts from the allocator's defaults, which keeps CC #74 off its Member Channel.
      val expression = Option.when(isMpeInput)(inputExpressionOf(inputChannel))
      val preferredChannel = Option.when(isMpeInput && zone.memberChannels.contains(inputChannel))(inputChannel)

      val result = alloc.allocate(MpeNoteIdentity(inputChannel, midiNote), expression, preferredChannel)
      val outChannel = result.channel

      // Dropped notes go first: the new note's setup messages would retune them on their way out.
      result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnNoteOn))

      // Pitch Bend goes out on every fresh allocation: its tuning half is invisible to the allocator, and an unoccupied
      // channel keeps the bend of an earlier note and missed every tune() while empty. A duplicate Note On changes
      // nothing, so it goes out alone.
      if (!result.isDuplicate) {
        emitPitchBend(buffer, outChannel, alloc, tuning)
      }
      emitSlide(buffer, outChannel, result.update)
      emitPressure(buffer, outChannel, result.update)

      buffer += NoteOnMidiMsg(outChannel, midiNote, velocity)
    }
  }

  /**
   * The Expression Values of a note arriving on an input Member Channel: the state the MPE Specification has a receiver
   * remember for that channel, so that a control sent before the Note On isn't lost. The Pitch Bend is taken raw, like
   * the value stored for the notes already active on that channel (see [[MpeExpression.pitchBend]]).
   */
  private def inputExpressionOf(inputChannel: Int): MpeExpression = ImmutableMpeExpression(
    pitchBend = tracker.pitchBend(inputChannel),
    pressure = tracker.channelPressure(inputChannel),
    slide = tracker.cc(inputChannel, MidiCc.MpeSlide))

  private def processNoteOff(buffer: mutable.Buffer[MidiMsg], msg: NoteOffMidiMsg,
                             role: MpeChannelRole): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    allocatorFor(role).foreach { alloc =>
      // Only in Non-MPE Input Mode, where the Tuner synthesized the pressure; an MPE sender resets its own.
      val resetPressureOnEmpty = role match {
        case MpeChannelRole.NonMpeInput(_) => true
        case _ => false
      }

      alloc.release(MpeNoteIdentity(inputChannel, midiNote), resetPressureOnEmpty) match {
        case Some(result) =>
          val outChannel = result.channel

          // The pressure reset alone precedes the Note Off, so that the released note's state is final when it ends.
          if (result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

          buffer += NoteOffMidiMsg(outChannel, midiNote, velocity)

          if (result.update.pitchBend.isDefined) emitPitchBend(buffer, outChannel, alloc, tuning)
          emitSlide(buffer, outChannel, result.update)
          if (!result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

        case None =>
          // Chiefly a note the Tuner already dropped, which is routine. A stale Note Off after an MCM or a MIDI panic
          // looks the same, hence the trace line.
          logger.trace(s"Discarding Note Off for $midiNote on input channel $inputChannel: " +
            "the identity holds no active count")
      }
    }
  }

  private def processPitchBend(buffer: mutable.Buffer[MidiMsg], msg: PitchBendMidiMsg,
                               role: MpeChannelRole): Unit = {
    // The allocator applies it to every output channel holding a note of this input channel.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(msg.channel, msg.value),
        alloc, DropReason.OnPitchBend)
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMsg], msg: CcMidiMsg,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg.number match {
    case MidiCc.MpeSlide =>
      allocatorFor(role).foreach { alloc =>
        emitExpressionUpdateResult(buffer, alloc.updateSlide(msg.channel, msg.value), alloc, DropReason.NotExpected)
      }
    case MidiCc.DataEntryMsb if MpeMessageRouting.isMcm(rpnSelector) =>
      processMcm(buffer, msg.channel, msg.value)
    case MidiCc.DataEntryMsb | MidiCc.DataEntryLsb if MpeMessageRouting.isPbs(rpnSelector) =>
      processPbs(buffer, msg.channel, msg.number, msg.value, role)
    case _ =>
      // Explicit arms rather than a catch-all, so that a future routing table row can't silently rewrite a Zone's Pitch
      // Bend Sensitivity through `applyPbsUpdate`.
      logger.error(s"Unexpected request to interpret $msg")
  }

  /**
   * Processes an MPE Configuration Message: reconfigures the Zones, stops the notes and resets the state of the
   * channels entering or leaving MPE control, and restates the result downstream. The addressed Zone takes the
   * specification's default sensitivities, as at a conforming receiver (MPE Specification §2.4). See the paper's
   * "Zones" section.
   */
  private def processMcm(buffer: mutable.Buffer[MidiMsg], channel: Int, memberCount: Int): Unit = {
    assert(channel == 0 || channel == 15, "MCM messages are only sent to channel 0 or 15!")
    assert(MpeZone.isValidMemberCount(memberCount),
      s"An invalid MCM member count of $memberCount reached the MpeTuner!")
    val (zoneType, newZone) = if (channel == 0)
      (MpeZoneType.Lower, MpeZone(MpeZoneType.Lower, memberCount))
    else
      (MpeZoneType.Upper, MpeZone(MpeZoneType.Upper, memberCount))

    logger.info(s"MCM received on channel $channel: configuring $zoneType zone with $memberCount member channel(s)...")

    val zonesBefore = _zones
    val zonesAfter = zonesBefore.update(newZone)

    // Read from `zonesBefore` rather than the `_zones`-backed getters, so it survives the reassignment below.
    val otherZoneBefore = if (channel == 0) zonesBefore.upper else zonesBefore.lower

    // Leaving Non-MPE Input Mode affects every channel, conservatively, the Expression Values having been synthesized
    // under other semantics. Within MPE Input Mode, only the channels whose Zone assignment changes are affected.
    val affected =
      if (_inputMode == MpeInputMode.NonMpe) AllChannels else channelsAffectedByMcm(zonesBefore, zonesAfter)

    // Stop the affected notes while the old allocators are in place. This must cover exactly the notes
    // `rebuildAllocator` drops below for the same `affected` set, or a note hangs or takes an unmatched Note Off. The
    // rebuild's divergence-rule drops are disjoint from them, and `emitZoneConfigurationResult` sounds those off.
    stopNotesOn(buffer, affected)

    _zones = zonesAfter
    affected.foreach(tracker.reset)

    // The addressed Zone's MCM, then its sensitivities: after the MCM, which would reset them, and before the retuning
    // pass, whose Pitch Bends are encoded against them.
    val updatedZone = if (channel == 0) lowerZone else upperZone
    logger.info(s"$zoneType zone updated: $updatedZone")
    emitMcmSequence(buffer, updatedZone)
    emitZonePbsSequences(buffer, updatedZone)

    // The other Zone takes an MCM only if overlap resolution moved its boundary.
    val otherZoneAfter = if (channel == 0) upperZone else lowerZone
    if (otherZoneAfter != otherZoneBefore) {
      val otherZoneType = if (channel == 0) MpeZoneType.Upper else MpeZoneType.Lower
      logger.info(s"$otherZoneType zone adjusted by overlap resolution: $otherZoneAfter")
      emitMcmSequence(buffer, otherZoneAfter)
    }

    // Its Pitch Bend Sensitivity is restated either way, unchanged, as JUCE's `MPEZoneLayout` does (the paper's "Zones"
    // section), since the retuning pass below re-emits Pitch Bend on both Zones.
    emitZonePbsSequences(buffer, otherZoneAfter)

    // Rebuild the allocators against the new Zones, after the MCMs whose sensitivities the retuning Pitch Bends are
    // encoded against. Lower before Upper, as `tune()` orders them; the unaddressed Zone's bit-identical Pitch Bends
    // are deliberate redundancy against receivers that don't fully conform.
    val lowerRebuild = rebuildAllocator(lowerAllocator, lowerZone, affected)
    val upperRebuild = rebuildAllocator(upperAllocator, upperZone, affected)
    lowerAllocator = lowerRebuild.map(_.allocator)
    upperAllocator = upperRebuild.map(_.allocator)
    Seq(lowerRebuild, upperRebuild).flatten.foreach { rebuild =>
      emitZoneConfigurationResult(buffer, rebuild.settlement, rebuild.allocator)
    }

    _inputMode = MpeInputMode.Mpe

    // The addressed Zone's default sensitivities, or a Zone that became reachable, may not fit the tuning.
    warnIfCannotTune()
  }

  /**
   * Rebuilds a Zone's allocator after a reconfiguration, keeping the state of the Member Channels it left untouched.
   *
   * @return the allocator and what the caller must emit for it, or `None` for a disabled Zone, which has no allocator.
   */
  private def rebuildAllocator(previous: Option[MpeChannelAllocator],
                               zone: MpeZone,
                               affected: Set[Int]): Option[MpeRebuildResult] =
    previous match {
      case Some(alloc) if zone.isEnabled =>
        Some(MpeChannelAllocator.retaining(zone, alloc, affected,
          expressionPitchBendThresholdOf(zone.memberPitchBendSensitivity)))
      case _ => createAllocator(zone).map(MpeRebuildResult(_))
    }

  /**
   * Processes a Pitch Bend Sensitivity Data Entry MSB (semitones) or LSB (cents). A non-MPE input has no Master Channel
   * of its own, so its sensitivity configures the routing Zone's Master Channel, where its Pitch Bend goes.
   */
  private def processPbs(buffer: mutable.Buffer[MidiMsg], channel: Int, ccNumber: Int, ccValue: Int,
                         role: MpeChannelRole): Unit = role match {
    case MpeChannelRole.NonMpeInput(zone) =>
      val updatedZone = zone.copy(
        masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, zone.masterChannel, ccNumber, ccValue, updatedZone, isMaster = true)
    case MpeChannelRole.Master(zone) =>
      val updatedZone = zone.copy(
        masterPitchBendSensitivity = patchPbs(zone.masterPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster = true)
    case MpeChannelRole.Member(zone) =>
      val updatedZone = zone.copy(
        memberPitchBendSensitivity = patchPbs(zone.memberPitchBendSensitivity, ccNumber, ccValue))
      applyPbsUpdate(buffer, channel, ccNumber, ccValue, updatedZone, isMaster = false)
    case MpeChannelRole.Outside =>
      logger.error(s"Unexpected request to interpret Pitch Bend Sensitivity on out-of-zone channel $channel")
  }

  /**
   * Returns `current` with the half a Data Entry CC writes replaced, keeping the other half.
   *
   * It doesn't read `tracker.rpn`, which fills a half the sender never wrote with the MIDI 1.0 default, losing, say,
   * the 48 semitones of an MPE Member Channel.
   */
  private def patchPbs(current: PitchBendSensitivity, ccNumber: Int, ccValue: Int): PitchBendSensitivity = {
    if (ccNumber == MidiCc.DataEntryMsb) current.copy(semitones = ccValue)
    else current.copy(cents = ccValue)
  }

  /**
   * Applies a Pitch Bend Sensitivity update to its Zone and emits a complete Pitch Bend Sensitivity sequence on
   * `channel` alone, the sender being responsible for every Member Channel. A member sensitivity change also moves the
   * High Expression Pitch Bend threshold, which may drop notes, and retunes the Zone.
   *
   * The sequence is rebuilt from the Zone rather than relayed, so that it carries both halves and the receiver's
   * sensitivity matches the one the Tuner encodes its Pitch Bend against (the paper's "Configuration" section).
   */
  private def applyPbsUpdate(buffer: mutable.Buffer[MidiMsg], channel: Int,
                             ccNumber: Int, ccValue: Int,
                             updatedZone: MpeZone, isMaster: Boolean): Unit = {
    val previousZone = if (updatedZone.zoneType == MpeZoneType.Lower) lowerZone else upperZone
    _zones = _zones.update(updatedZone)

    if (logger.underlying.isInfoEnabled) {
      val channelRole = if (isMaster) "master" else "member"
      val pbsField = if (ccNumber == MidiCc.DataEntryMsb) "semitones" else "cents"
      logger.info(s"PBS updated on $channelRole channel $channel of ${updatedZone.zoneType} zone: $pbsField = $ccValue")
    }

    val sensitivity = if (isMaster) updatedZone.masterPitchBendSensitivity else updatedZone.memberPitchBendSensitivity
    emitPbsSequence(buffer, channel, sensitivity)

    // A member sensitivity change reinterprets every held Expression Pitch Bend, so it can make notes High Expression
    // Pitch Bend notes with no message arriving. Master sensitivity doesn't affect the Member Channels.
    if (!isMaster) {
      val alloc = if (updatedZone.zoneType == MpeZoneType.Lower) lowerAllocator else upperAllocator
      alloc.foreach(applyExpressionPitchBendThreshold(buffer, _))

      // Only on a change, since a sender repeats the same sensitivity on every Member Channel of the Zone
      if (updatedZone.memberPitchBendSensitivity != previousZone.memberPitchBendSensitivity) {
        warnIfCannotTune()
      }
    }
  }

  /**
   * The channels whose Zone assignment an MCM changes, the paper's channels "entering or leaving MPE control".
   * Comparing assignments, rather than differencing sets, also counts a channel moving from one Zone to the other.
   */
  private def channelsAffectedByMcm(before: MpeZones, after: MpeZones): Set[Int] = (0 until MidiChannelCount).filter(
    ch => assignmentOf(before, ch) != assignmentOf(after, ch)).toSet

  /**
   * A channel's Zone assignment: its Zone's type and whether it is that Zone's Master Channel. It is read in MPE Input
   * Mode whatever the current mode, since Non-MPE Input Mode gives every channel the same role.
   */
  private def assignmentOf(zones: MpeZones, channel: Int): Option[(MpeZoneType, Boolean)] =
    MpeMessageRouting.roleOf(MpeInputMode.Mpe, zones, channel) match {
      case MpeChannelRole.Master(zone) => Some((zone.zoneType, true))
      case MpeChannelRole.Member(zone) => Some((zone.zoneType, false))
      case MpeChannelRole.NonMpeInput(_) | MpeChannelRole.Outside => None
    }

  /**
   * Emits a Note Off for every note active on `channels`: the allocators' notes whose output or input channel is among
   * them and, in MPE Input Mode, the Master Channel notes the tracker holds. A note whose input channel leaves MPE
   * control must stop even if its output channel stays, or the performer's Note Off would be discarded. Each note gets
   * one Note Off per Note On (the paper's "Note Identity and Reference Counting" section).
   */
  private def stopNotesOn(buffer: mutable.Buffer[MidiMsg], channels: Set[Int]): Unit = {
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      (noteIdentity, outChannel) <- alloc.activeAllocations
      if channels.contains(outChannel) || channels.contains(noteIdentity.inputChannel)
      _ <- 1 to alloc.referenceCountOf(noteIdentity)
    } {
      buffer += NoteOffMidiMsg(outChannel, noteIdentity.midiNote)
    }

    if (_inputMode == MpeInputMode.Mpe) {
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled && channels.contains(zone.masterChannel)
        midiNote <- tracker.activeNotes(zone.masterChannel)
        _ <- 1 to tracker.referenceCount(zone.masterChannel, midiNote)
      } {
        buffer += NoteOffMidiMsg(zone.masterChannel, midiNote)
      }
    }
  }

  /**
   * Returns the forwarded controls the input left away from their defaults, the Sustain and Sostenuto pedals and the
   * Pitch Bend, to their defaults, routing each as if the input channel holding it had sent it. All pedals go before
   * any Pitch Bend, so that the notes they hold stop before their pitch changes.
   */
  private def releaseForwardedControls(buffer: mutable.Buffer[MidiMsg]): Unit = {
    val heldControlReleases = (0 until MidiChannelCount).flatMap(pedalReleasesOn) ++
      (0 until MidiChannelCount).flatMap(pitchBendReleaseOn)
    val releases = for {
      release <- heldControlReleases
      outputChannel <- forwardingChannelOf(release)
    } yield release.mapChannel(_ => outputChannel)

    buffer ++= releases.distinct
  }

  /**
   * Returns CC #74 and Channel Pressure to their defaults on each Member Channel where the allocators record another
   * value, before a reset recreates them. A Member Channel's Pitch Bend, sent ahead of every note there, is centered
   * only where the restated Zones make the channel a Master Channel, where it would bend every note of its Zone.
   */
  private def releaseMemberChannelControls(buffer: mutable.Buffer[MidiMsg]): Unit = {
    val restatedMasterChannels = Seq(initialZones.lower, initialZones.upper).filter(_.isEnabled).map(_.masterChannel)

    // TODO #305 This relies on a tuner resetting its output when detached from it, which none does yet. Until then, a
    //  tuner that preceded this one on the same output, e.g. before a tracks file was loaded, may leave values that
    //  this one does not know about.
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      channel <- currentZone(alloc).memberChannels
    } {
      if (restatedMasterChannels.contains(channel)) {
        buffer += PitchBendMidiMsg(channel, 0)
      }

      val expression = alloc.channelExpression(channel)
      if (expression.slide != MpeExpression.DefaultSlide) {
        buffer += CcMidiMsg(channel, MidiCc.MpeSlide, MpeExpression.DefaultSlide)
      }
      if (expression.pressure != MpeExpression.DefaultPressure) {
        buffer += ChannelPressureMidiMsg(channel, MpeExpression.DefaultPressure)
      }
    }
  }

  private def pedalReleasesOn(channel: Int): Seq[ChannelMidiMsg] = for {
    pedal <- Tuner.NoteHoldingPedals if tracker.cc(channel, pedal) > 0
  } yield CcMidiMsg(channel, pedal, 0)

  private def pitchBendReleaseOn(channel: Int): Option[ChannelMidiMsg] =
    Option.when(tracker.pitchBend(channel) != 0)(PitchBendMidiMsg(channel, 0))

  private def forwardingChannelOf(message: ChannelMidiMsg): Option[Int] = {
    val role = MpeMessageRouting.roleOf(_inputMode, _zones, message.channel)
    MpeMessageRouting.route(role, message, RpnSelector.None) match {
      case MpeRoutingVerdict.ForwardOn(outputChannel) => Some(outputChannel)
      case _ => None
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMsg], msg: ChannelPressureMidiMsg,
                                     role: MpeChannelRole): Unit = {
    // It belongs to every note of the input channel, whatever output channel each went to.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updatePressure(msg.channel, msg.value), alloc, DropReason.NotExpected)
    }
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMsg], msg: PolyPressureMidiMsg,
                                  role: MpeChannelRole): Unit = {
    // Non-MPE input only: converted to Channel Pressure on the note's Member Channel, MPE forbidding Polyphonic Key
    // Pressure there.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer,
        alloc.updatePressure(MpeNoteIdentity(msg.channel, msg.midiNote), msg.value), alloc, DropReason.NotExpected)
    }
  }

  /**
   * The Pitch Bend emitted on an output Member Channel: the Tuning Pitch Bend of its pitch class plus its Expression
   * Pitch Bend, in raw 14-bit units. Only the tuning term is converted from cents, clamped first since
   * [[PitchBendMidiMsg.convertCentsToValue]] requires a value within the sensitivity; the sum is clamped too.
   */
  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val pbs = zone.memberPitchBendSensitivity
    val tuningValue = PitchBendMidiMsg.convertCentsToValue(
      clampValue(tuningOffsetCents, -pbs.totalCents, pbs.totalCents), pbs)
    clampValue(tuningValue + alloc.channelExpression(channel).pitchBend,
      PitchBendMidiMsg.MinValue, PitchBendMidiMsg.MaxValue)
  }

  /** Emits one Note Off per Note On forwarded for each dropped note. */
  private def emitDroppedNoteOffs(buffer: mutable.Buffer[MidiMsg], droppedNotes: MpeDroppedNotes,
                                  reason: DropReason): Unit = {
    logger.trace(s"Dropping notes ${droppedNotes.notes.map(_.noteIdentity.midiNote)} " +
      s"on channel ${droppedNotes.channel} (${reason.message})")
    for {
      droppedNote <- droppedNotes.notes
      _ <- 1 to droppedNote.referenceCount
    } {
      buffer += NoteOffMidiMsg(droppedNotes.channel, droppedNote.noteIdentity.midiNote)
    }
  }

  private def emitSlide(buffer: mutable.Buffer[MidiMsg], channel: Int, update: MpeExpressionUpdate): Unit =
    update.slide.foreach { value => buffer += CcMidiMsg(channel, MidiCc.MpeSlide, value) }

  private def emitPressure(buffer: mutable.Buffer[MidiMsg], channel: Int, update: MpeExpressionUpdate): Unit =
    update.pressure.foreach { value => buffer += ChannelPressureMidiMsg(channel, value) }

  private def emitExpressionUpdate(buffer: mutable.Buffer[MidiMsg], channel: Int,
                                   update: MpeExpressionUpdate, alloc: MpeChannelAllocator): Unit = {
    if (update.pitchBend.isDefined) emitPitchBend(buffer, channel, alloc, tuning)
    emitSlide(buffer, channel, update)
    emitPressure(buffer, channel, update)
  }

  /**
   * Emits the Note Offs of the notes an Expression Value update dropped, then the new values of each channel it
   * changed.
   *
   * @param dropReason The reason logged for each dropped note; only an Expression Pitch Bend update drops any.
   */
  private def emitExpressionUpdateResult(buffer: mutable.Buffer[MidiMsg], result: MpeExpressionUpdateResult,
                                         alloc: MpeChannelAllocator, dropReason: DropReason): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, dropReason))
    result.channelUpdates.foreach { channelUpdate =>
      emitExpressionUpdate(buffer, channelUpdate.channel, channelUpdate.update, alloc)
    }
  }

  /**
   * Re-derives a Zone's High Expression Pitch Bend threshold from its member sensitivity, after a Pitch Bend
   * Sensitivity message, and emits what the allocator reports. An MCM reaches the allocator through
   * [[rebuildAllocator]] instead.
   */
  private def applyExpressionPitchBendThreshold(buffer: mutable.Buffer[MidiMsg],
                                                alloc: MpeChannelAllocator): Unit = {
    val threshold = expressionPitchBendThresholdOf(currentZone(alloc).memberPitchBendSensitivity)
    emitZoneConfigurationResult(buffer, alloc.setExpressionPitchBendThreshold(threshold), alloc)
  }

  /**
   * Emits the consequences of a Zone configuration change in the order of the paper's "Message Ordering" section: the
   * dropped notes' Note Offs, a Pitch Bend on every occupied Member Channel, then CC #74 and Channel Pressure where a
   * drop moved them. The result's own Pitch Bends are left out, [[updateTuningOnZone]] re-emitting one on every
   * occupied channel.
   */
  private def emitZoneConfigurationResult(buffer: mutable.Buffer[MidiMsg], result: MpeExpressionUpdateResult,
                                          alloc: MpeChannelAllocator): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnMemberPbsChange))
    updateTuningOnZone(buffer, alloc, tuning)
    result.channelUpdates.foreach { channelUpdate =>
      emitSlide(buffer, channelUpdate.channel, channelUpdate.update)
      emitPressure(buffer, channelUpdate.channel, channelUpdate.update)
    }
  }

  /** Emits the Pitch Bend of a channel holding notes, for `tuning`. */
  private def emitPitchBend(buffer: mutable.Buffer[MidiMsg], channel: Int,
                            alloc: MpeChannelAllocator, tuning: Tuning): Unit = {
    val zone = currentZone(alloc)
    alloc.channelPitchClass(channel).foreach { pc =>
      val tuningOffset = tuning(pc)
      val totalPitchBend = computeOutputPitchBend(channel, alloc, zone, tuningOffset)
      buffer += PitchBendMidiMsg(channel, totalPitchBend)
    }
  }

  private def updateTuningOnZone(buffer: mutable.Buffer[MidiMsg],
                                 alloc: MpeChannelAllocator, tuning: Tuning): Unit = {
    val zone = currentZone(alloc)
    // Only occupied channels have a pitch class assigned
    for (ch <- zone.memberChannels) {
      emitPitchBend(buffer, ch, alloc, tuning)
    }
  }

  private def currentZone(alloc: MpeChannelAllocator): MpeZone = alloc.zoneType match {
    case MpeZoneType.Lower => lowerZone
    case MpeZoneType.Upper => upperZone
  }

  /**
   * Emits a Zone's MPE Configuration Message on its Master Channel, recording that its closing RPN Null deselects the
   * channel. Every MCM and Pitch Bend Sensitivity sequence goes through here or [[emitPbsSequence]], so that
   * [[outputRpnSelectors]] stays exact.
   */
  private def emitMcmSequence(buffer: mutable.Buffer[MidiMsg], zone: MpeZone): Unit = {
    // `RpnMessages.select` renders the selector and the Null, deciding their transmission order.
    val sequence = RpnMessages.select(zone.masterChannel, RpnMessages.MpeConfigurationMessageSelector) :+
      CcMidiMsg(zone.masterChannel, MidiCc.DataEntryMsb, zone.memberCount)
    buffer ++= (sequence ++ RpnMessages.select(zone.masterChannel, RpnSelector.None))

    outputRpnSelectors(zone.masterChannel) = RpnSelector.None
  }

  /**
   * Emits a Pitch Bend Sensitivity sequence on `channel`, recording that its closing RPN Null deselects the channel;
   * see [[emitMcmSequence]].
   */
  private def emitPbsSequence(buffer: mutable.Buffer[MidiMsg], channel: Int,
                              sensitivity: PitchBendSensitivity): Unit = {
    outputRpnSelectors(channel) = RpnSelector.None
    buffer ++= PitchBendSensitivityMessages.create(channel, sensitivity)
  }

  /** The parameter the Tuner last left selected on `channel`, or `RpnSelector.None` when it does not know. */
  private def latchedSelectorOn(channel: Int): RpnSelector =
    outputRpnSelectors.getOrElse(channel, RpnSelector.None)

  /** Emits the Pitch Bend Sensitivity of an enabled Zone's Master and Member Channels. */
  private def emitZonePbsSequences(buffer: mutable.Buffer[MidiMsg], zone: MpeZone): Unit = {
    if (zone.isEnabled) {
      emitPbsSequence(buffer, zone.masterChannel, zone.masterPitchBendSensitivity)

      zone.memberChannels.foreach { ch =>
        emitPbsSequence(buffer, ch, zone.memberPitchBendSensitivity)
      }
    }
  }

  private def createAllocator(zone: MpeZone): Option[MpeChannelAllocator] = {
    if (zone.isEnabled) {
      Some(MpeChannelAllocator(zone, expressionPitchBendThresholdOf(zone.memberPitchBendSensitivity)))
    } else {
      None
    }
  }

  /**
   * The allocator of the Zone a note arriving with this role is allocated to: `Some` for every role
   * [[MpeMessageRouting.route]] sends to an allocating handler.
   */
  private def allocatorFor(role: MpeChannelRole): Option[MpeChannelAllocator] = role match {
    case MpeChannelRole.Member(zone) => allocatorOf(zone)
    case MpeChannelRole.NonMpeInput(zone) => allocatorOf(zone)
    case MpeChannelRole.Master(_) | MpeChannelRole.Outside => None
  }

  private def allocatorOf(zone: MpeZone): Option[MpeChannelAllocator] = zone.zoneType match {
    case MpeZoneType.Lower => lowerAllocator
    case MpeZoneType.Upper => upperAllocator
  }
}

object MpeTuner {
  /** The `Tuner` plugin type name this tuner is (de)serialized under. */
  val TypeName: String = "mpe"

  private val MidiChannelCount: Int = 16

  private val AllChannels: Set[Int] = (0 until MidiChannelCount).toSet

  /** The paper's High Expression Pitch Bend threshold `t`: an absolute pitch deviation of half a semitone. */
  private val HighExpressionPitchBendThresholdCents: Double = 50.0

  /**
   * The threshold for a Member Pitch Bend Sensitivity no wider than `t`: the largest raw magnitude, so that the strict
   * `>` of the classification holds for no value, `MinValue` included.
   */
  private val UnreachableExpressionPitchBendThreshold: Int = -PitchBendMidiMsg.MinValue

  /**
   * The raw Expression Pitch Bend magnitude above which a note has a High Expression Pitch Bend, for a Member Pitch
   * Bend Sensitivity. One threshold serves both signs, the asymmetry of [[PitchBendMidiMsg.convertCentsToValue]] being
   * under one raw unit.
   */
  private def expressionPitchBendThresholdOf(pbs: PitchBendSensitivity): Int =
    if (HighExpressionPitchBendThresholdCents >= pbs.totalCents) UnreachableExpressionPitchBendThreshold
    else PitchBendMidiMsg.convertCentsToValue(HighExpressionPitchBendThresholdCents, pbs)
}

/**
 * Why the Tuner ended a note by its own decision, logged alongside the notes it dropped.
 *
 * @param message The human-readable reason written to the log.
 */
private enum DropReason(val message: String) {
  /** A Note On freed a channel, or a High Expression Pitch Bend made the notes of a channel diverge. */
  case OnNoteOn extends DropReason("channel freed, or High Expression Pitch Bend, on a new Note On")

  /** An Expression Pitch Bend made a note diverge from the others sharing its channel. */
  case OnPitchBend extends DropReason("High Expression Pitch Bend diverging on a shared channel")

  /** For the slide and pressure updates, which never drop notes. */
  case NotExpected extends DropReason("unreachable: slide/pressure updates never drop notes")

  /** A member Pitch Bend Sensitivity change, explicit or by an MCM, moved the threshold and reclassified the note. */
  case OnMemberPbsChange extends DropReason("member Pitch Bend Sensitivity change reclassified the note")
}

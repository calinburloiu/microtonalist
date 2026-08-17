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

  /**
   * Per-input-channel state derived from incoming MIDI messages: Channel Pressure, CC #74 (MPE Slide /
   * timbre), and the RPN selector state machine. Used to seed the output Member Channel at Note On
   * (MPE input mode only) and to drive the RPN-based protocol for MCM and PBS.
   */
  private val tracker: ScMidiChannelStateTracker = ScMidiChannelStateTracker()

  /**
   * What the Tuner last left selected on each output channel, so that a relayed uninterpreted sequence spends its
   * selector only when the parameter changes. [[MpeMessageRouting]] discards every selector CC the input sends and
   * never relays a value message raw, so every RPN or NRPN message an output channel receives is one the Tuner
   * composed itself, and this can be kept exact rather than guessed — provided it is maintained on both sides.
   *
   * Every sequence emitted on a channel must be recorded here, the closing RPN Null of the Tuner's own MCM and
   * Pitch Bend Sensitivity sequences included, which is why they go through `emitMcmSequence` and `emitPbsSequence`.
   * And every relayed message that deselects at the receiver must remove the entry: the Tuner authors the parameter
   * selected on its output channels, but not every message that changes it — see
   * [[MpeMessageRouting.deselectsOnRelay]] and the System Reset case in `processShortMessage`.
   *
   * An absent entry means "not known", and re-emits.
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
   * @return current tuning
   */
  def tuning: Tuning = _tuning

  override def reset(): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // Emit Note Off for every active note before switching input mode / zone layout,
    // so downstream receivers are never left with hanging notes (MPE spec Section 2.1.4).
    stopNotesOn(buffer, AllChannels)
    _zones = initialZones
    _inputMode = initialInputMode
    // Full re-initialization restores the Standard Tuning; an in-band Zone reconfiguration must not, since
    // nothing in the paper sanctions discarding the performer's active Tuning.
    _tuning = Tuning.Standard
    resetState()
    warnOnNonMpeInputWithBothZones()
    emitConfiguration(buffer)
    buffer.toSeq
  }

  override def tune(tuning: Tuning): Seq[MidiMessage] = {
    _tuning = tuning
    val buffer = mutable.Buffer[MidiMessage]()

    // Update pitch bend on all occupied member channels
    lowerAllocator.foreach(updateTuningOnZone(buffer, _))
    upperAllocator.foreach(updateTuningOnZone(buffer, _))

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
   * routed to a single Zone — the Lower Zone when it is enabled, otherwise the Upper Zone — so with both
   * enabled the Upper Zone is unreachable and its Member Channels are wasted. Logged at construction and
   * again on `reset()`, where the initial configuration is re-applied.
   */
  private def warnOnNonMpeInputWithBothZones(): Unit = {
    if (_inputMode == MpeInputMode.NonMpe && lowerZone.isEnabled && upperZone.isEnabled) {
      logger.warn("MpeTuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is " +
        "routed to a single Zone, the Lower Zone taking precedence, so the Upper Zone's Member Channels " +
        s"are unreachable. Consider disabling the Upper Zone or switching to MPE Input Mode. Zones: ${_zones}")
    }
  }

  /**
   * Clears internal channel-tracking state and recreates allocators from `currentZones`. Does not touch the active
   * Tuning; see `reset()` for full re-initialization.
   */
  private def resetState(): Unit = {
    tracker.reset()
    // Forget what each output channel held selected. The configuration messages that follow a reset re-record it
    // for every Master Channel anyway; clearing keeps the "only skip a selector when we know" rule from depending
    // on which channels those messages happen to reach, and a channel may be changing role entirely.
    outputRpnSelectors.clear()

    lowerAllocator = createAllocator(lowerZone)
    upperAllocator = createAllocator(upperZone)
  }

  /**
   * Emits the MCM and Pitch Bend Sensitivity sequences of both Zones. Only an enabled Zone contributes Pitch Bend
   * Sensitivity sequences; see [[emitZonePbsSequences]].
   */
  private def emitConfiguration(buffer: mutable.Buffer[MidiMessage]): Unit = {
    emitMcmSequence(buffer, lowerZone)
    emitZonePbsSequences(buffer, lowerZone)
    emitMcmSequence(buffer, upperZone)
    emitZonePbsSequences(buffer, upperZone)
  }

  private def processShortMessage(message: ShortMessage): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // A Note On with velocity 0 is a Note Off per the MIDI Specification. Normalizing it here, ahead of both the
    // tracker and the router, keeps every downstream decision — routing included — reading a single note-off shape.
    val scMessage = message.asScala match {
      case msg: NoteOnScMidiMessage if msg.velocity == NoteOnScMidiMessage.NoteOffVelocity =>
        NoteOffScMidiMessage(msg.channel, msg.midiNote)
      case other => other
    }
    tracker.send(scMessage)

    scMessage match {
      case msg: ChannelScMidiMessage =>
        val role = MpeMessageRouting.roleOf(_inputMode, _zones, msg.channel)
        val rpnSelector = tracker.rpnSelector(msg.channel)
        MpeMessageRouting.route(role, msg, rpnSelector) match {
          case MpeRoutingVerdict.Discard =>
            // Out-of-zone and wrong-level traffic is normal in a mixed rig, so this stays at trace level.
            logger.trace(s"Discarding $msg received on a channel with role $role")
          case MpeRoutingVerdict.ForwardOn(channel) =>
            buffer += msg.mapChannel(_ => channel).asJava
            // A relayed Reset All Controllers deselects the parameter at the receiver, so what the Tuner recorded
            // for that channel has stopped being a fact about it.
            if (MpeMessageRouting.deselectsOnRelay(msg)) outputRpnSelectors.remove(channel)
          case MpeRoutingVerdict.ForwardRpnSequenceOn(channel) => msg match {
            case cc: CcScMidiMessage =>
              val (messages, latchedSelector) =
                MpeMessageRouting.rpnSequence(rpnSelector, cc, channel, latchedSelectorOn(channel))
              buffer ++= messages.map(_.asJava)
              outputRpnSelectors(channel) = latchedSelector
            case _ =>
              // `route` returns this verdict only for a Data Entry, Data Increment or Data Decrement CC.
              logger.error(s"Unexpected RPN sequence verdict for $msg")
          }
          case MpeRoutingVerdict.Interpret =>
            interpret(buffer, msg, role, rpnSelector)
        }
      case _ =>
        // System Exclusive, System Common and System Real-Time messages affect the whole system and pass through.
        buffer += message
        // A System Reset returns every receiving channel to its power-up state, parameter selection included.
        if (scMessage == SystemResetScMidiMessage) outputRpnSelectors.clear()
    }

    buffer.toSeq
  }

  /**
   * Handles the messages [[MpeMessageRouting.route]] decided the Tuner acts upon itself: note allocation, the three
   * Expression Value dimensions at note level, the Non-MPE Polyphonic Key Pressure conversion, the MCM, and Pitch
   * Bend Sensitivity. Every handler receives the role rather than re-deriving it.
   */
  private def interpret(buffer: mutable.Buffer[MidiMessage], msg: ChannelScMidiMessage,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg match {
    case m: NoteOnScMidiMessage => processNoteOn(buffer, m, role)
    case m: NoteOffScMidiMessage => processNoteOff(buffer, m, role)
    case m: PitchBendScMidiMessage => processPitchBend(buffer, m, role)
    case m: ChannelPressureScMidiMessage => processChannelPressure(buffer, m, role)
    case m: PolyPressureScMidiMessage => processPolyPressure(buffer, m, role)
    case m: CcScMidiMessage => processCc(buffer, m, role, rpnSelector)
    case m =>
      // `route` never asks for a Program Change — the only other concrete channel message class — to be
      // interpreted; it is forwarded or discarded.
      logger.error(s"Unexpected request to interpret $m")
  }

  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage,
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
      // In MPE Input Mode the note's Expression Values are initialized from the state remembered for its
      // input Member Channel; in Non-MPE Input Mode there are none to take, and the allocator's defaults
      // apply — which is also what keeps CC #74 off the Member Channel in that mode.
      val expression = Option.when(isMpeInput)(inputExpressionOf(inputChannel))
      val preferredChannel = Option.when(isMpeInput && zone.memberChannels.contains(inputChannel))(inputChannel)

      val result = alloc.allocate(MpeNoteIdentity(inputChannel, midiNote), expression, preferredChannel)
      val outChannel = result.channel

      // Dropped notes are released before every message emitted for the new note: emitting the setup
      // messages first would retune the notes being dropped on their way out.
      result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnNoteOn))

      // Pitch Bend, CC #74, Channel Pressure, then the Note On. Pitch Bend is emitted unconditionally on
      // a fresh allocation: what goes on the wire is Tuning Pitch Bend + Expression Pitch Bend, and the
      // tuning half is invisible to the allocator — a channel that was unoccupied retains the bend of a
      // note of a different pitch class and has missed every tune() that ran while it was empty. A
      // duplicate Note On changes nothing at all, so it is emitted alone.
      if (!result.isDuplicate) {
        emitPitchBend(buffer, outChannel, alloc)
      }
      emitSlide(buffer, outChannel, result.update)
      emitPressure(buffer, outChannel, result.update)

      buffer += NoteOnScMidiMessage(outChannel, midiNote, velocity).asJava
    }
  }

  /**
   * The Expression Values a note arriving on an input Member Channel starts with, taken from the control
   * state remembered for that channel — the state-tracking obligation the MPE Specification places on
   * receivers, so that a Pitch Bend, CC #74 or Channel Pressure sent before the Note On is not lost.
   *
   * The Pitch Bend is taken exactly as the tracker holds it, with no conversion and no reference to the Zone's
   * Pitch Bend Sensitivity, which is what keeps it from disagreeing with the value already stored for the notes
   * active on the same input channel (see [[MpeExpression.pitchBend]]).
   */
  private def inputExpressionOf(inputChannel: Int): MpeExpression = ImmutableMpeExpression(
    pitchBend = tracker.pitchBend(inputChannel),
    pressure = tracker.channelPressure(inputChannel),
    slide = tracker.cc(inputChannel, ScMidiCc.MpeSlide))

  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], msg: NoteOffScMidiMessage,
                             role: MpeChannelRole): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    allocatorFor(role).foreach { alloc =>
      // The Channel Pressure reset applies in Non-MPE Input Mode only: there the Tuner is the controller
      // that synthesized the value, whereas in MPE Input Mode the dimension passes through from the
      // sender and a conforming sender's own pre-release reset reaches the output as an ordinary update.
      val resetPressureOnEmpty = role match {
        case MpeChannelRole.NonMpeInput(_) => true
        case _ => false
      }

      alloc.release(MpeNoteIdentity(inputChannel, midiNote), resetPressureOnEmpty) match {
        case Some(result) =>
          val outChannel = result.channel

          // The reset is the sole control message emitted before the Note Off; every other recomputed
          // value follows it, so that the released note's control state is final at the moment of release.
          if (result.pressureWasReset) emitPressure(buffer, outChannel, result.update)

          buffer += NoteOffScMidiMessage(outChannel, midiNote, velocity).asJava

          if (result.update.pitchBend.isDefined) emitPitchBend(buffer, outChannel, alloc)
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

  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], msg: PitchBendScMidiMessage,
                               role: MpeChannelRole): Unit = {
    // Per-note Pitch Bend on an input Member Channel: the note's Expression Pitch Bend, stored as received. The
    // allocator fans the update out by itself to every output channel holding a note of this input channel.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(msg.channel, msg.value),
        alloc, DropReason.OnPitchBend)
    }
  }

  private def processCc(buffer: mutable.Buffer[MidiMessage], msg: CcScMidiMessage,
                        role: MpeChannelRole, rpnSelector: RpnSelector): Unit = msg.number match {
    case ScMidiCc.MpeSlide =>
      allocatorFor(role).foreach { alloc =>
        emitExpressionUpdateResult(buffer, alloc.updateSlide(msg.channel, msg.value), alloc, DropReason.NotExpected)
      }
    case ScMidiCc.DataEntryMsb if MpeMessageRouting.isMcm(rpnSelector) =>
      processMcm(buffer, msg.channel, msg.value)
    case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb if MpeMessageRouting.isPbs(rpnSelector) =>
      processPbs(buffer, msg.channel, msg.number, msg.value, role)
    case _ =>
      // The arms above are the three CC shapes `MpeMessageRouting.route` returns `Interpret` for; matching them
      // here rather than trusting a catch-all keeps a future routing table row from silently rewriting a Zone's
      // Pitch Bend Sensitivity through `applyPbsUpdate`.
      logger.error(s"Unexpected request to interpret $msg")
  }

  /**
   * Processes an incoming MPE Configuration Message (MCM).
   *
   * Reconfigures zones (with overlap resolution) and outputs the new configuration messages downstream: for each
   * Zone whose configuration changed, its MCM followed by the Pitch Bend Sensitivity sequences that restate what
   * that Zone holds.
   *
   * The addressed Zone has both its Master and its Member Pitch Bend Sensitivity '''reset to the specification's
   * defaults''' — ±2 and ±48 semitones — because that is what the MCM does at a conforming receiver (MPE Spec
   * §2.4); the reconfigured Zone is built afresh, so it carries those defaults by construction. A Zone that
   * overlap resolution merely shrinks in consequence was not addressed by the MCM and keeps its sensitivities.
   *
   * Only the channels entering or leaving MPE control by the reconfiguration have their notes stopped and their
   * tracked state reset; a Zone untouched by the reconfiguration keeps its notes and state, as the paper's
   * Zone-configuration section requires.
   */
  private def processMcm(buffer: mutable.Buffer[MidiMessage], channel: Int, memberCount: Int): Unit = {
    assert(channel == 0 || channel == 15, "MCM messages are only sent to channel 0 or 15!")
    assert(MpeZone.isValidMemberCount(memberCount),
      s"An invalid MCM member count of $memberCount reached the MpeTuner!")
    // Per MPE spec Section 2.4, receiving MCM resets PBS to defaults
    val (zoneType, newZone) = if (channel == 0)
      (MpeZoneType.Lower, MpeZone(MpeZoneType.Lower, memberCount))
    else
      (MpeZoneType.Upper, MpeZone(MpeZoneType.Upper, memberCount))

    logger.info(s"MCM received on channel $channel: configuring $zoneType zone with $memberCount member channel(s)...")

    val zonesBefore = _zones
    val zonesAfter = zonesBefore.update(newZone)

    // Remember the other zone before update to detect overlap resolution changes. Read from `zonesBefore`,
    // not from the `_zones`-backed `lowerZone`/`upperZone` getters, so this holds the pre-update view
    // regardless of where `_zones` is reassigned below.
    val otherZoneBefore = if (channel == 0) zonesBefore.upper else zonesBefore.lower

    // Leaving Non-MPE Input Mode is treated as affecting every channel, deliberately conservatively rather
    // than out of strict necessity: an input Member Channel becoming a Lower/Upper Member keeps resolving
    // through the same allocator, so strictly only input channels becoming Master Channels strand. But the
    // Expression Values on Member Channels were synthesized under different (Non-MPE) semantics, and the
    // Channel-Pressure-reset rule differs by mode, so a wholesale reset is still the safer call.
    //
    // Within MPE Input Mode only the channels whose Zone assignment actually changes are affected. No note is
    // bound outside the Zone structure for that comparison to miss: `MpeMessageRouting.route` discards a Note On
    // arriving on a channel under no Zone's control, and `allocatorFor` would have no allocator to offer it in
    // any case.
    val affected =
      if (_inputMode == MpeInputMode.NonMpe) AllChannels else channelsAffectedByMcm(zonesBefore, zonesAfter)

    // Stop the affected notes while the old Zone structure and allocators are still in place. This must emit a
    // Note Off for exactly the notes `rebuildAllocator` drops below *for the same reason* — the ones it leaves
    // behind with the channels it does not retain, plus the ones it takes off the channels it does because their
    // input channel departed — both passes reading the same `affected` set: if they ever disagree on which notes
    // are affected, the result is either a hanging note (dropped without a Note Off) or an unmatched Note Off
    // (stopped without being dropped).
    //
    // The rebuild's other drops are not this pass's to emit. Re-applying the divergence rule against the moved
    // threshold drops notes `affected` does not name; those come back in the rebuild's `MpeExpressionUpdateResult`
    // and are sounded off by `emitZoneConfigurationResult`. The two sets are disjoint by construction — the
    // departed-input-channel drop runs first and removes its notes, so the divergence rule can only reach notes
    // whose input and output channels are both unaffected — which is what keeps a note from taking two Note Offs.
    stopNotesOn(buffer, affected)

    _zones = zonesAfter
    affected.foreach(tracker.reset)

    // Forward MCM for the updated zone, and restate the Pitch Bend Sensitivity the Zone holds afterwards on every
    // one of its channels. A conforming receiver resets it to the defaults on the MCM itself (MPE spec Section
    // 2.4) — which is exactly what a freshly configured Zone carries — so the restatement is idempotent there, and
    // it repairs a receiver that does not perform that reset. It must follow the MCM: a receiver that does reset
    // would otherwise overwrite it. And it must precede the retuning pass below, whose Pitch Bends are encoded
    // against this sensitivity.
    val updatedZone = if (channel == 0) lowerZone else upperZone
    logger.info(s"$zoneType zone updated: $updatedZone")
    emitMcmSequence(buffer, updatedZone)
    emitZonePbsSequences(buffer, updatedZone)

    // Forward MCM for the other zone only if it was changed by overlap resolution
    val otherZoneAfter = if (channel == 0) upperZone else lowerZone
    if (otherZoneAfter != otherZoneBefore) {
      val otherZoneType = if (channel == 0) MpeZoneType.Upper else MpeZoneType.Lower
      // This Zone keeps its Pitch Bend Sensitivity: the received MCM addressed the other Zone, and the MPE
      // Specification does not say whether the Zone that overlap resolution shrinks in response loses its
      // sensitivity along with its channels. The Tuner resolves it as JUCE's `MPEZoneLayout` does, narrowing the
      // yielding Zone's channel range while leaving its sensitivities untouched — see the paper's "Zones"
      // section. Restating the kept sensitivity after this Zone's own MCM is what makes that reading safe
      // against a receiver that took the other one and reset it.
      logger.info(s"$otherZoneType zone adjusted by overlap resolution: $otherZoneAfter")
      emitMcmSequence(buffer, otherZoneAfter)
      emitZonePbsSequences(buffer, otherZoneAfter)
    }

    // Rebuild each Zone's allocator against the new Zone structure and emit what the rebuild moved: the retained
    // notes are reclassified against the Zone's Pitch Bend Sensitivity, the ones whose input channel left MPE
    // control are dropped, and the channels that kept notes are retuned. The addressed Zone's sensitivity has just
    // been reset to the defaults, so a retained channel's Pitch Bend would otherwise be read against the wrong
    // range, and the threshold has moved under its notes.
    //
    // The rebuild sits here, rather than beside the `_zones` assignment above, so that mutating the allocators and
    // telling the receiver about it stay adjacent: nothing between the two can then read a Zone's notes in a state
    // the buffer does not yet describe. Its emissions must in any case follow the MCMs above, whose Pitch Bends
    // are encoded against the sensitivity they restate, and nothing between the assignment and here touches an
    // allocator.
    //
    // Lower before Upper, as `tune()` already orders them. An allocator built fresh by `createAllocator` already
    // holds the right threshold and holds no notes, so it reports nothing and needs no condition. The other Zone
    // runs the pass just the same, although its sensitivity — and so its threshold — did not move, whether or not
    // overlap resolution shrank it: its occupied channels take a redundant, bit-identical Pitch Bend on every MCM.
    // That is deliberate, in keeping with the paper's commitment to redundant messages for robustness against
    // receivers that do not fully conform, and not an unguarded case.
    val lowerRebuild = rebuildAllocator(lowerAllocator, lowerZone, affected)
    val upperRebuild = rebuildAllocator(upperAllocator, upperZone, affected)
    lowerAllocator = lowerRebuild.map(_.allocator)
    upperAllocator = upperRebuild.map(_.allocator)
    Seq(lowerRebuild, upperRebuild).flatten.foreach { rebuild =>
      emitZoneConfigurationResult(buffer, rebuild.settlement, rebuild.allocator)
    }

    // Switch to MPE input mode
    _inputMode = MpeInputMode.Mpe
  }

  /**
   * Rebuilds a Zone's allocator after a reconfiguration, transplanting the state of every Member Channel the
   * reconfiguration left untouched and settling the result against the reconfigured Zone. A Zone that is now
   * disabled loses its allocator altogether.
   *
   * @return the rebuilt allocator and what the caller must emit for it, or `None` for a Zone left with no
   *         allocator. A Zone whose allocator is built fresh has nothing to settle, so its settlement is empty;
   *         the threshold [[createAllocator]] gives it is the same one the transplanting branch injects.
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
   * Processes an incoming Pitch Bend Sensitivity RPN Data Entry MSB (semitones) or LSB (cents).
   *
   * The destination and the half of the Zone's Pitch Bend Sensitivity being written both follow from the role: a
   * non-MPE input has no Master Channel of its own, so its Pitch Bend Sensitivity configures the routing Zone's
   * Master Channel — the channel its Pitch Bend is redirected to.
   */
  private def processPbs(buffer: mutable.Buffer[MidiMessage], channel: Int, ccNumber: Int, ccValue: Int,
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
   * Applies a PBS update: updates the internal zone configuration, emits a complete Pitch Bend Sensitivity RPN
   * sequence on the target channel, and recomputes pitch bends on occupied member channels if needed.
   *
   * A ''member'' sensitivity change does more than recompute. It moves the High Expression Pitch Bend threshold
   * under every note the Zone holds, so it can also drop the notes that reclassification leaves diverging on a
   * shared channel — emitting their Note Offs — and emit CC #74 and Channel Pressure for a channel whose average
   * such a drop moved. [[emitZoneConfigurationResult]] renders all of that, in the order the paper's "Message
   * Ordering" section prescribes.
   *
   * The sequence is emitted only on `channel` — not broadcast to all member channels.
   * Per the MPE Specification, the sender is responsible for sending PBS to all member channels;
   * the tuner emits one sequence per received Data Entry on the destination channel 1:1.
   *
   * It follows the shape the paper's "Configuration" preamble gives every re-emitted RPN sequence — selector,
   * Data Entry, closing RPN Null — and is built by [[PitchBendSensitivityMessages.create]] from the Zone's
   * updated sensitivity rather than relayed byte-for-byte. It therefore always carries *both* Data Entry halves
   * (CC #6 semitones, CC #38 cents), which keeps the receiver's Pitch Bend Sensitivity equal to the value this
   * Tuner encodes its output Pitch Bend against; forwarding only the half that arrived would leave the other at
   * whatever the receiver happened to hold. The selector is likewise re-emitted rather than relayed, guarding
   * against another device having changed the active RPN on this channel between the sender's selector and its
   * Data Entry, and the closing RPN Null protects Pitch Bend Sensitivity from a later stray Data Entry. The
   * sender's own selector CCs are consumed upstream in [[MpeMessageRouting.route]], so re-emitting the selector
   * here cannot duplicate them.
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

    // Emit the complete sequence on the destination channel only, built from the Zone's updated sensitivity
    // rather than relayed byte-for-byte, so that both Data Entry halves are always sent.
    val sensitivity = if (isMaster) updatedZone.masterPitchBendSensitivity else updatedZone.memberPitchBendSensitivity
    emitPbsSequence(buffer, channel, sensitivity)

    // A member sensitivity change reinterprets every held Expression Pitch Bend at once, so it can turn sounding
    // notes into High Expression Pitch Bend notes with no note or Pitch Bend message arriving. Re-derive the
    // threshold and re-apply the divergence rule before the Zone's occupied channels are retuned. Master
    // sensitivity does not affect Member Channel interpretation, and in Non-MPE Input Mode all Pitch Bend
    // Sensitivity input is treated as master, so neither reaches this path.
    if (!isMaster) {
      val alloc = if (updatedZone.zoneType == MpeZoneType.Lower) lowerAllocator else upperAllocator
      alloc.foreach(applyExpressionPitchBendThreshold(buffer, _))
    }
  }

  /**
   * The channels whose Zone assignment an MCM changes — the paper's channels "entering or leaving MPE control" —
   * given the Zone configuration before and after that MCM was applied.
   *
   * Assignments are compared rather than the sets of MPE-controlled channels differenced: a channel handed from one
   * Zone's Member Channels to the other's has both left and entered MPE control, and a set difference would miss it.
   */
  private def channelsAffectedByMcm(before: MpeZones, after: MpeZones): Set[Int] =
    (0 until MidiChannelCount).filter(ch => assignmentOf(before, ch) != assignmentOf(after, ch)).toSet

  /**
   * A channel's Zone assignment: its Zone's type and whether it is that Zone's Master Channel.
   *
   * The role is asked for in MPE Input Mode explicitly, whatever the Tuner's current mode is: in Non-MPE Input Mode
   * [[MpeMessageRouting.roleOf]] gives every channel the same Zone-routing role irrespective of the Zone layout,
   * which would make every comparison in [[channelsAffectedByMcm]] trivially equal. Nothing is lost by it —
   * [[processMcm]] treats a reconfiguration in Non-MPE Input Mode as affecting every channel and never consults this.
   */
  private def assignmentOf(zones: MpeZones, channel: Int): Option[(MpeZoneType, Boolean)] =
    MpeMessageRouting.roleOf(MpeInputMode.Mpe, zones, channel) match {
      case MpeChannelRole.Master(zone) => Some((zone.zoneType, true))
      case MpeChannelRole.Member(zone) => Some((zone.zoneType, false))
      case MpeChannelRole.NonMpeInput(_) | MpeChannelRole.Outside => None
    }

  /**
   * Emits a Note Off for every note the Tuner currently considers active on the given channels: the allocators' own
   * bindings for Member Channel notes, and — in MPE Input Mode — the Master Channel notes the tracker holds, which
   * are forwarded on the channel they arrived on.
   *
   * A Member Channel note counts as being on `channels` when either its output channel or the input channel it
   * arrived on is among them. A note whose input channel leaves MPE control must go even if its output channel
   * stays: the performer's Note Off would arrive on a channel that is no longer under any Zone's control and be
   * discarded, leaving the note hanging.
   *
   * Member Channel notes get one Note Off per Note On forwarded for them, as [[emitDroppedNoteOffs]] does,
   * discharging the one-Note-Off-per-Note-On obligation of the paper's "Note Identity and Reference Counting"
   * section. Master Channel notes get exactly one each: they bypass the allocator, and
   * [[ScMidiChannelStateTracker]] models a channel's active notes as a set, so no count is available for them.
   */
  private def stopNotesOn(buffer: mutable.Buffer[MidiMessage], channels: Set[Int]): Unit = {
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      (noteIdentity, outChannel) <- alloc.activeAllocations
      if channels.contains(outChannel) || channels.contains(noteIdentity.inputChannel)
      _ <- 1 to alloc.referenceCountOf(noteIdentity)
    } {
      buffer += NoteOffScMidiMessage(outChannel, noteIdentity.midiNote).asJava
    }

    if (_inputMode == MpeInputMode.Mpe) {
      // TODO #254 One Note Off per active note, not one per forwarded Note On: the tracker holds a set of
      //   active notes with no reference count, so a Master Channel note struck twice leaves one unmatched
      //   Note On downstream.
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled && channels.contains(zone.masterChannel)
        midiNote <- tracker.activeNotes(zone.masterChannel)
      } {
        buffer += NoteOffScMidiMessage(zone.masterChannel, midiNote).asJava
      }
    }
  }

  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage], msg: ChannelPressureScMidiMessage,
                                     role: MpeChannelRole): Unit = {
    // Per-note pressure on an input Member Channel: it belongs to every note active on that channel, wherever the
    // pitch-class invariant placed them.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updatePressure(msg.channel, msg.value), alloc, DropReason.NotExpected)
    }
  }

  private def processPolyPressure(buffer: mutable.Buffer[MidiMessage], msg: PolyPressureScMidiMessage,
                                  role: MpeChannelRole): Unit = {
    // Non-MPE input only: converted to Channel Pressure on the allocated Member Channel, since MPE forbids
    // Polyphonic Key Pressure there. The value is the addressed note's own Expression Value and is averaged with
    // those of the other notes on its output channel.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer,
        alloc.updatePressure(MpeNoteIdentity(msg.channel, msg.midiNote), msg.value), alloc, DropReason.NotExpected)
    }
  }

  /**
   * The Pitch Bend value emitted on an output Member Channel: the Tuning Pitch Bend of the channel's pitch class
   * plus the channel's aggregated Expression Pitch Bend, summed in raw signed 14-bit units.
   *
   * Only the tuning term is converted, [[Tuning]] defining its offsets in cents. It is clamped in the cents domain
   * first, [[PitchBendScMidiMessage.convertCentsToValue]] carrying a `require` that rejects a value beyond the
   * sensitivity, and the sum is then clamped to the same interval expressed in raw units. The expression term is
   * never converted in either direction: the allocator already holds it in the units the wire carries.
   */
  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val pbs = zone.memberPitchBendSensitivity
    val tuningValue = PitchBendScMidiMessage.convertCentsToValue(
      clampValue(tuningOffsetCents, -pbs.totalCents, pbs.totalCents), pbs)
    clampValue(tuningValue + alloc.channelExpression(channel).pitchBend,
      PitchBendScMidiMessage.MinValue, PitchBendScMidiMessage.MaxValue)
  }

  /**
   * Emits Note Off messages for dropped notes: one per Note On forwarded for each note, at the neutral
   * release velocity 64 that a note ended by the Tuner's own decision receives.
   */
  private def emitDroppedNoteOffs(buffer: mutable.Buffer[MidiMessage], droppedNotes: MpeDroppedNotes,
                                  reason: DropReason): Unit = {
    logger.trace(s"Dropping notes ${droppedNotes.notes.map(_.noteIdentity.midiNote)} " +
      s"on channel ${droppedNotes.channel} (${reason.message})")
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
    if (update.pitchBend.isDefined) emitPitchBend(buffer, channel, alloc)
    emitSlide(buffer, channel, update)
    emitPressure(buffer, channel, update)
  }

  /**
   * Applies an Expression Value update received on an input Member Channel: emits the Note Offs of any
   * notes the update dropped first, then the recomputed Expression Values of each affected output channel.
   *
   * @param dropReason The reason logged for each dropped note. Only an Expression Pitch Bend update can
   *                   actually produce drops, so it is the sole path that passes a meaningful reason;
   *                   callers on the slide and pressure paths pass [[DropReason.NotExpected]] instead.
   */
  private def emitExpressionUpdateResult(buffer: mutable.Buffer[MidiMessage], result: MpeExpressionUpdateResult,
                                         alloc: MpeChannelAllocator, dropReason: DropReason): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, dropReason))
    result.channelUpdates.foreach { channelUpdate =>
      emitExpressionUpdate(buffer, channelUpdate.channel, channelUpdate.update, alloc)
    }
  }

  /**
   * Re-derives a Zone's High Expression Pitch Bend threshold from its current member Pitch Bend Sensitivity, hands
   * it to the allocator — which re-applies the divergence rule as part of the assignment — and emits the result.
   *
   * This is the path of a member sensitivity change that leaves the Zone's channels where they are: an explicit
   * Pitch Bend Sensitivity message. The reset an MPE Configuration Message performs is a member sensitivity change
   * like any other, but its Zone is rebuilt rather than kept, so it reaches the allocator through
   * [[rebuildAllocator]] instead — one call that also drops the notes the reconfiguration stranded, so that both
   * are measured against the aggregate the receiver holds. Either way the result is rendered by
   * [[emitZoneConfigurationResult]].
   */
  private def applyExpressionPitchBendThreshold(buffer: mutable.Buffer[MidiMessage],
                                                alloc: MpeChannelAllocator): Unit = {
    val threshold = expressionPitchBendThresholdOf(currentZone(alloc).memberPitchBendSensitivity)
    emitZoneConfigurationResult(buffer, alloc.setExpressionPitchBendThreshold(threshold), alloc)
  }

  /**
   * Emits the consequences of a Zone configuration change — a member Pitch Bend Sensitivity message, or the
   * rebuild an MPE Configuration Message forces — in the relative order the paper's "Message Ordering" section
   * gives the control dimensions after a Note Off: Note Offs, Pitch Bends, CC #74, Channel Pressure.
   *
   * The Pitch Bend dimension of `result` is deliberately not emitted here. [[updateTuningOnZone]] re-emits one
   * Pitch Bend on every occupied Member Channel of the Zone, which both subsumes it and is required in its own
   * right, a sensitivity change re-encoding the tuning term on ''every'' occupied channel rather than only on the
   * ones a drop touched; emitting both would duplicate the message on exactly the channels that changed.
   *
   * CC #74 and Channel Pressure stay `diff`-driven and therefore conditional: neither a sensitivity change nor a
   * Zone reconfiguration moves them by itself, but a drop removes a note's term from all three of its channel's
   * averages, and notes sharing a channel may come from different input channels with different values. When
   * nothing is dropped the result is empty and neither is emitted.
   *
   * Only the divergence rule's drops reach `result.droppedNotes`, and only they get a Note Off here: the notes
   * dropped for their input channel leaving MPE control were already sounded off by [[stopNotesOn]], before the
   * allocators were rebuilt.
   */
  private def emitZoneConfigurationResult(buffer: mutable.Buffer[MidiMessage], result: MpeExpressionUpdateResult,
                                          alloc: MpeChannelAllocator): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnMemberPbsChange))
    updateTuningOnZone(buffer, alloc)
    result.channelUpdates.foreach { channelUpdate =>
      emitSlide(buffer, channelUpdate.channel, channelUpdate.update)
      emitPressure(buffer, channelUpdate.channel, channelUpdate.update)
    }
  }

  /**
   * Emits a Pitch Bend message for a channel based on the current tuning offset, if the channel has active notes.
   * The zone used for pitch bend computation is resolved from `_zones` based on the allocator's zone type.
   */
  private def emitPitchBend(buffer: mutable.Buffer[MidiMessage], channel: Int,
                            alloc: MpeChannelAllocator): Unit = {
    val zone = currentZone(alloc)
    alloc.channelPitchClass(channel).foreach { pc =>
      val tuningOffset = _tuning(pc)
      val totalPitchBend = computeOutputPitchBend(channel, alloc, zone, tuningOffset)
      buffer += PitchBendScMidiMessage(channel, totalPitchBend).asJava
    }
  }

  private def updateTuningOnZone(buffer: mutable.Buffer[MidiMessage],
                                 alloc: MpeChannelAllocator): Unit = {
    val zone = currentZone(alloc)
    // Only occupied channels have a pitch class assigned
    for (ch <- zone.memberChannels) {
      emitPitchBend(buffer, ch, alloc)
    }
  }

  private def currentZone(alloc: MpeChannelAllocator): MpeZone = alloc.zoneType match {
    case MpeZoneType.Lower => lowerZone
    case MpeZoneType.Upper => upperZone
  }

  /**
   * Emits the MPE Configuration Message sequence for a Zone on its Master Channel, recording that its closing RPN
   * Null leaves that channel with no parameter selected.
   *
   * Every MCM sequence the Tuner emits goes through here, and every Pitch Bend Sensitivity one through
   * [[emitPbsSequence]], so that [[outputRpnSelectors]] can never claim a parameter one of their Nulls has cleared.
   */
  private def emitMcmSequence(buffer: mutable.Buffer[MidiMessage], zone: MpeZone): Unit = {
    // MCM: RPN 00 06 on the Master Channel with Data Entry MSB = memberCount, closed by an RPN Null. The selector and
    // the Null are rendered by `RpnMessages.select`, which decides their transmission order.
    val sequence = RpnMessages.select(zone.masterChannel, RpnMessages.MpeConfigurationMessageSelector) :+
      CcScMidiMessage(zone.masterChannel, ScMidiCc.DataEntryMsb, zone.memberCount)
    buffer ++= (sequence ++ RpnMessages.select(zone.masterChannel, RpnSelector.None)).map(_.asJava)

    outputRpnSelectors(zone.masterChannel) = RpnSelector.None
  }

  /**
   * Emits the Pitch Bend Sensitivity sequence for one output channel — selector, both Data Entry halves, closing RPN
   * Null — recording that the Null leaves the channel with no parameter selected.
   *
   * Every Pitch Bend Sensitivity sequence the Tuner emits goes through here, and every MCM one through
   * [[emitMcmSequence]], so that [[outputRpnSelectors]] can never claim a parameter one of their Nulls has cleared.
   */
  private def emitPbsSequence(buffer: mutable.Buffer[MidiMessage], channel: Int,
                              sensitivity: PitchBendSensitivity): Unit = {
    outputRpnSelectors(channel) = RpnSelector.None
    buffer ++= PitchBendSensitivityMessages.create(channel, sensitivity)
  }

  /** The parameter the Tuner last left selected on `channel`, or `RpnSelector.None` when it does not know. */
  private def latchedSelectorOn(channel: Int): RpnSelector =
    outputRpnSelectors.getOrElse(channel, RpnSelector.None)

  /**
   * Emits the Pitch Bend Sensitivity sequences of an enabled Zone: one for its Master Channel and one for each of
   * its Member Channels. A disabled Zone emits nothing.
   */
  private def emitZonePbsSequences(buffer: mutable.Buffer[MidiMessage], zone: MpeZone): Unit = {
    if (zone.isEnabled) {
      // Master channel PBS
      emitPbsSequence(buffer, zone.masterChannel, zone.masterPitchBendSensitivity)

      // Member channel PBS
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
   * The allocator of the Zone whose Member Channels a note arriving with this role is allocated to.
   *
   * `Member` and `NonMpeInput` both carry an enabled Zone, and an enabled Zone always has an allocator, so the
   * result is `Some` for every role that [[MpeMessageRouting.route]] sends to an allocating handler. The `None`
   * cases emit nothing, which is the correct behaviour should the invariant ever be broken.
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

  /** The number of MIDI channels. */
  private val MidiChannelCount: Int = 16

  /** Every MIDI channel, the scope of the state reset performed by a full re-initialization. */
  private val AllChannels: Set[Int] = (0 until MidiChannelCount).toSet

  /** The paper's High Expression Pitch Bend threshold `t`: an absolute pitch deviation of half a semitone. */
  private val HighExpressionPitchBendThresholdCents: Double = 50.0

  /**
   * The threshold used when the threshold in cents is not below the Member Channel Pitch Bend Sensitivity range —
   * a sender configuring, say, ±0 semitones 20 cents, where no bend the Pitch Bend range can express deviates by
   * more than `t`. Its value ''is'' the largest magnitude a signed 14-bit Pitch Bend can take — `MinValue` being
   * -8192 against `MaxValue`'s 8191 — so the strict `>` of the classification is false for every value, `MinValue`
   * included: nothing is a High Expression Pitch Bend at such a range, and
   * [[PitchBendScMidiMessage.convertCentsToValue]] — whose `require` rejects a value beyond the sensitivity — is
   * never called with one.
   */
  private val UnreachableExpressionPitchBendThreshold: Int = -PitchBendScMidiMessage.MinValue

  /**
   * The raw Expression Pitch Bend magnitude an [[MpeChannelAllocator]] classifies a High Expression Pitch Bend
   * against, for a given Member Channel Pitch Bend Sensitivity.
   *
   * A single threshold serves both signs even though [[PitchBendScMidiMessage.convertCentsToValue]] scales
   * negatives by 8192 and positives by 8191: the discrepancy is below one raw unit — 85.32 against 85.33 at ±48
   * semitones — and so invisible after rounding at every sensitivity of practical interest.
   */
  private def expressionPitchBendThresholdOf(pbs: PitchBendSensitivity): Int =
    if (HighExpressionPitchBendThresholdCents >= pbs.totalCents) UnreachableExpressionPitchBendThreshold
    else PitchBendScMidiMessage.convertCentsToValue(HighExpressionPitchBendThresholdCents, pbs)
}

/**
 * Why the Tuner ended a note by its own decision, logged alongside the notes it dropped.
 *
 * @param message The human-readable reason written to the log.
 */
private enum DropReason(val message: String) {
  /**
   * A Note On dropped notes. The allocation algorithm freeing an occupied channel is the common cause, but
   * a new note assigned to a channel holding a High Expression Pitch Bend note — or one whose own bend is
   * high — drops its co-residents too.
   */
  case OnNoteOn extends DropReason("channel freed, or High Expression Pitch Bend, on a new Note On")

  /** An Expression Pitch Bend made a note diverge from the others sharing its channel. */
  case OnPitchBend extends DropReason("High Expression Pitch Bend diverging on a shared channel")

  /**
   * For callers of `emitExpressionUpdateResult` whose update can never actually produce drops:
   * `result.droppedNotes` is always empty for slide and pressure updates (see
   * [[MpeExpressionUpdateResult]]), so this reason is never surfaced in a log line.
   */
  case NotExpected extends DropReason("unreachable: slide/pressure updates never drop notes")

  /**
   * A member Pitch Bend Sensitivity change — an explicit Pitch Bend Sensitivity message, or the reset an MPE
   * Configuration Message performs — moved the High Expression Pitch Bend threshold and reclassified the note.
   */
  case OnMemberPbsChange extends DropReason("member Pitch Bend Sensitivity change reclassified the note")
}

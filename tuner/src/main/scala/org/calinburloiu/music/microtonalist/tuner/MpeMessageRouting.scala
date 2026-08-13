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

import org.calinburloiu.music.scmidi.ScMidiChannelStateTracker.RpnSelector
import org.calinburloiu.music.scmidi.message.*

/**
 * The part a MIDI channel plays in the Tuner's Zone structure, as seen by the message router.
 *
 * The role is a total classification: every channel has exactly one in either input mode. It carries the Zone it
 * belongs to, so that a routing decision needs nothing else to name its destination channel.
 */
private[tuner] enum MpeChannelRole {
  /** Non-MPE Input Mode, with a Zone enabled to route this input's Zone-level messages to. */
  case NonMpeInput(routingZone: MpeZone)

  /** MPE Input Mode, the Master Channel of an enabled Zone. */
  case Master(zone: MpeZone)

  /** MPE Input Mode, a Member Channel of an enabled Zone. */
  case Member(zone: MpeZone)

  /**
   * Under no Zone's control, in either input mode: an MPE input channel outside every enabled Zone, and — when no
   * Zone is enabled at all — every channel in both input modes. The paper's "Messages Outside the Zone Structure"
   * section discards everything received here, an MCM on MIDI Channel 1 or 16 excepted.
   */
  case Outside
}

/**
 * What the Tuner does with a received channel message, as decided by [[MpeMessageRouting.route]].
 */
private[tuner] enum MpeRoutingVerdict {
  /** Emit nothing. The message is outside the Zone structure, or at the wrong level for its class. */
  case Discard

  /** Relay the message unmodified, on the given output channel — its own, or a Zone's Master Channel. */
  case ForwardOn(channel: Int)

  /** The Tuner acts on the message itself: note allocation, an Expression Value, the MCM, or Pitch Bend Sensitivity. */
  case Interpret
}

/**
 * The MPE Tuner's MIDI message routing and filtering rules, as pure functions of the channel's role, the message
 * and the channel's currently selected Registered or Non-Registered Parameter.
 *
 * This object holds no state: everything it needs is passed in, which is what lets the paper's message-handling
 * table be read straight off [[route]].
 */
private[tuner] object MpeMessageRouting {

  /**
   * Classifies a channel within a Zone configuration.
   *
   * In Non-MPE Input Mode the input carries no Zone structure of its own, so every channel takes the same role,
   * naming the Zone its Zone-level messages are routed to: the Lower Zone when enabled, otherwise the Upper Zone.
   *
   * @param inputMode The Tuner's current input mode.
   * @param zones     The Tuner's current Zone configuration.
   * @param channel   The 0-indexed MIDI channel to classify.
   */
  def roleOf(inputMode: MpeInputMode, zones: MpeZones, channel: Int): MpeChannelRole = inputMode match {
    case MpeInputMode.NonMpe =>
      if (zones.lower.isEnabled) MpeChannelRole.NonMpeInput(zones.lower)
      else if (zones.upper.isEnabled) MpeChannelRole.NonMpeInput(zones.upper)
      else MpeChannelRole.Outside
    case MpeInputMode.Mpe =>
      roleInZone(zones.lower, channel)
        .orElse(roleInZone(zones.upper, channel))
        .getOrElse(MpeChannelRole.Outside)
  }

  private def roleInZone(zone: MpeZone, channel: Int): Option[MpeChannelRole] = {
    if (!zone.isEnabled) None
    else if (channel == zone.masterChannel) Some(MpeChannelRole.Master(zone))
    else if (zone.memberChannels.contains(channel)) Some(MpeChannelRole.Member(zone))
    else None
  }

  /**
   * Decides what to do with a channel message, implementing the paper's message-handling table row by row: the
   * message supplies the row, the role the column.
   *
   * @param role        The role of the channel the message arrived on, from [[roleOf]].
   * @param message     The received message.
   * @param rpnSelector The parameter currently selected on the arrival channel, which is what distinguishes an MCM
   *                    Data Entry from a Pitch Bend Sensitivity one from uninterpreted parameter traffic. It must
   *                    already account for the message being routed — [[MpeTuner]] feeds every message to its
   *                    tracker before dispatching it.
   */
  def route(role: MpeChannelRole,
            message: ChannelScMidiMessage,
            rpnSelector: RpnSelector): MpeRoutingVerdict = message match {
    case msg: CcScMidiMessage => routeCc(role, msg, rpnSelector)
    case _: NoteScMidiMessage => role match {
      case MpeChannelRole.Member(_) | MpeChannelRole.NonMpeInput(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    // The first two of the three per-note control dimensions; CC #74 is the third, in `routeCc`.
    case _: PitchBendScMidiMessage | _: ChannelPressureScMidiMessage => routeControlDimension(role, message)
    case _: PolyPressureScMidiMessage => role match {
      // Forbidden on a Member Channel by the MPE Specification; converted to Channel Pressure for a non-MPE input.
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.NonMpeInput(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    case _: ProgramChangeScMidiMessage => routeZoneLevel(role, message)
  }

  /** One of Pitch Bend, Channel Pressure and CC #74: the note's own Expression Value at Member level. */
  private def routeControlDimension(role: MpeChannelRole, message: ChannelScMidiMessage): MpeRoutingVerdict =
    role match {
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
      case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }

  /**
   * A Zone-level message: forwarded unmodified on a Master Channel, redirected to the routing Zone's Master Channel
   * for a non-MPE input, and discarded on a Member Channel — the receiver obligation the paper's message-handling
   * section states — or outside every Zone.
   */
  private def routeZoneLevel(role: MpeChannelRole, message: ChannelScMidiMessage): MpeRoutingVerdict = role match {
    case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
    case MpeChannelRole.Master(_) => MpeRoutingVerdict.ForwardOn(message.channel)
    case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
    case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
  }

  private def routeCc(role: MpeChannelRole,
                      msg: CcScMidiMessage,
                      rpnSelector: RpnSelector): MpeRoutingVerdict = msg.number match {
    // The MIDI Mode messages are discarded at every role in both input modes: the Tuner is fixed-mode on both
    // sides, and a Mono On reaching an output Member Channel would turn every shared allocation into a note drop.
    case ScMidiCc.OmniModeOff | ScMidiCc.OmniModeOn | ScMidiCc.MonoModeOn | ScMidiCc.PolyModeOn =>
      MpeRoutingVerdict.Discard

    case ScMidiCc.MpeSlide => routeControlDimension(role, msg)

    // The selector of a parameter the Tuner interprets is consumed: `MpeTuner` re-emits a complete sequence of its
    // own for the MCM and for Pitch Bend Sensitivity, and relaying the sender's selector would duplicate it.
    case ScMidiCc.RpnMsb | ScMidiCc.RpnLsb if isInterpreted(rpnSelector) => MpeRoutingVerdict.Discard

    case ScMidiCc.DataEntryMsb if isMcm(rpnSelector) && isMcmChannel(msg.channel) => MpeRoutingVerdict.Interpret

    case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb if isPbs(rpnSelector) => role match {
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
      case _ => MpeRoutingVerdict.Interpret
    }

    case _ => routeZoneLevel(role, msg)
  }

  /** Whether an MCM received on this channel is valid: MIDI Channel 1 or 16, whatever the channel's current role. */
  private def isMcmChannel(channel: Int): Boolean = channel == 0 || channel == 15

  private def isMcm(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnSelector.Rpn(ScMidiRpn.MpeConfigurationMessageMsb, ScMidiRpn.MpeConfigurationMessageLsb)

  private def isPbs(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnSelector.Rpn(ScMidiRpn.PitchBendSensitivityMsb, ScMidiRpn.PitchBendSensitivityLsb)

  private def isInterpreted(rpnSelector: RpnSelector): Boolean = isMcm(rpnSelector) || isPbs(rpnSelector)
}

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

import org.calinburloiu.music.scmidi.{RpnMessages, ScMidiChannelStateTracker}
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
  case NonMpeInput(zone: MpeZone)

  /** MPE Input Mode, the Master Channel of an enabled Zone. */
  case Master(zone: MpeZone)

  /** MPE Input Mode, a Member Channel of an enabled Zone. */
  case Member(zone: MpeZone)

  /**
   * Under no Zone's control, in either input mode: an MPE input channel outside every enabled Zone, and — when no
   * Zone is enabled at all — every channel in both input modes. The paper's "Messages Outside the Zone Structure"
   * section discards everything received here, an MCM on MIDI Channel 1 or 16 excepted (1-based).
   */
  case Outside
}

/**
 * What the Tuner does with a received channel message, as decided by [[MpeMessageRouting.route]].
 */
private[tuner] enum MpeRoutingVerdict {
  /** Emit nothing. The message is outside the Zone structure, or at the wrong level for its class. */
  case Discard

  /**
   * Relay the message unmodified, on the given output channel: the Master Channel of the Zone the deciding role
   * belongs to, which for a [[MpeChannelRole.Master]] is the arrival channel itself.
   */
  case ForwardOn(channel: Int)

  /**
   * Re-emit a Registered or Non-Registered Parameter sequence of the Tuner's own — the value message, preceded by
   * the selector whenever the parameter selected on the output channel changes, via
   * [[MpeMessageRouting.rpnSequence]] — for a value message of a parameter the Tuner does not interpret. The
   * sender's own selector CCs are discarded rather than relayed, which is what keeps interleaved RPN/NRPN streams
   * from different input channels from being merged into one another on a shared output channel.
   */
  case ForwardRpnSequenceOn(channel: Int)

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
   * Decides what to do with a channel message, implementing the rows of the paper's message-handling table, row by
   * row: the message supplies the row, the role the column. The table's RPN/NRPN rows discard every selector CC,
   * re-emit a value message of an uninterpreted parameter as a sequence of the Tuner's own via [[rpnSequence]], and
   * ignore an invalid MCM's parameter traffic in its entirety.
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
      case MpeChannelRole.Master(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    // The first two of the three per-note control dimensions; CC #74 is the third, in `routeCc`.
    case _: PitchBendScMidiMessage | _: ChannelPressureScMidiMessage => routeControlDimension(role)
    case _: PolyPressureScMidiMessage => role match {
      // Forbidden on a Member Channel by the MPE Specification; converted to Channel Pressure for a non-MPE input.
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
      case MpeChannelRole.Master(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
      case MpeChannelRole.NonMpeInput(_) => MpeRoutingVerdict.Interpret
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
    case _: ProgramChangeScMidiMessage => routeZoneLevel(role)
  }

  /** One of Pitch Bend, Channel Pressure and CC #74: the note's own Expression Value at Member level. */
  private def routeControlDimension(role: MpeChannelRole): MpeRoutingVerdict = role match {
    case MpeChannelRole.Member(_) => MpeRoutingVerdict.Interpret
    case MpeChannelRole.Master(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
    case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
    case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
  }

  /**
   * A Zone-level message: forwarded on the Master Channel of the Zone the role belongs to — its own channel for a
   * Master, the routing Zone's for a non-MPE input — and discarded on a Member Channel, the receiver obligation the
   * paper's message-handling section states, or outside every Zone.
   */
  private def routeZoneLevel(role: MpeChannelRole): MpeRoutingVerdict = role match {
    case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
    case MpeChannelRole.Master(zone) => MpeRoutingVerdict.ForwardOn(zone.masterChannel)
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

    case ScMidiCc.MpeSlide => routeControlDimension(role)

    // Every selector is consumed, never relayed: the Tuner decides for itself what each value message it re-emits
    // needs ahead of it, which is what keeps interleaved RPN/NRPN streams from different input channels from being
    // merged into one another on a shared output channel.
    case ScMidiCc.RpnMsb | ScMidiCc.RpnLsb | ScMidiCc.NrpnMsb | ScMidiCc.NrpnLsb => MpeRoutingVerdict.Discard

    case ScMidiCc.DataEntryMsb | ScMidiCc.DataEntryLsb | ScMidiCc.DataIncrement | ScMidiCc.DataDecrement =>
      routeDataValue(role, msg, rpnSelector)

    case _ => routeZoneLevel(role)
  }

  /**
   * Routes a Data Entry, Data Increment or Data Decrement, to which the currently selected parameter gives meaning.
   *
   * Two parameters get special treatment. The MCM is accepted only as a Data Entry MSB on MIDI Channel 1 or 16
   * (1-based) — the MPE Specification does not use its LSB — carrying a Member Channel count a Zone can hold, and
   * an MCM that fails any of those tests is ignored in its entirety, its selector having already been consumed above.
   * Pitch Bend Sensitivity is accepted at every role but `Outside`. A Data Increment or Decrement of either is
   * discarded: neither the paper nor the MPE Specification covers it, and relaying one would desync the Tuner's
   * stored value from the receiver's, since the Tuner does not interpret the increment.
   *
   * A value message is also discarded when no complete parameter is selected. `RpnSelector.None` is the plain case;
   * a half-set selector — one whose MSB or LSB is still Null — is treated the same way, because
   * [[ScMidiChannelStateTracker]] itself refuses to record a value for one, and relaying a value with no parameter
   * to apply it to is precisely what the closing RPN Null exists to prevent.
   */
  private def routeDataValue(role: MpeChannelRole,
                             msg: CcScMidiMessage,
                             rpnSelector: RpnSelector): MpeRoutingVerdict = rpnSelector match {
    case selector if isMcm(selector) =>
      if (msg.number == ScMidiCc.DataEntryMsb && isValidMcm(msg)) MpeRoutingVerdict.Interpret
      else MpeRoutingVerdict.Discard
    case selector if isPbs(selector) =>
      val isDataEntry = msg.number == ScMidiCc.DataEntryMsb || msg.number == ScMidiCc.DataEntryLsb
      role match {
        case MpeChannelRole.Member(_) | MpeChannelRole.Master(_) | MpeChannelRole.NonMpeInput(_) =>
          if (isDataEntry) MpeRoutingVerdict.Interpret else MpeRoutingVerdict.Discard
        case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
      }
    case selector if !isComplete(selector) => MpeRoutingVerdict.Discard
    case _ => role match {
      case MpeChannelRole.Member(_) => MpeRoutingVerdict.Discard
      case MpeChannelRole.Master(zone) => MpeRoutingVerdict.ForwardRpnSequenceOn(zone.masterChannel)
      case MpeChannelRole.NonMpeInput(zone) => MpeRoutingVerdict.ForwardRpnSequenceOn(zone.masterChannel)
      case MpeChannelRole.Outside => MpeRoutingVerdict.Discard
    }
  }

  /**
   * Whether both halves of the selected parameter have been set, which is what a value message needs to apply.
   *
   * An unset half is indistinguishable from a genuine half of 127: [[ScMidiChannelStateTracker]]'s `RpnSelector`
   * reuses the Null value (127) as its "not yet received" sentinel, so an RPN or NRPN whose MSB or LSB is
   * genuinely 127 reads the same as a half-set selector. Such a parameter is therefore treated as incomplete here,
   * and its value messages are discarded rather than relayed.
   */
  // TODO #267 The real fix is to model the unset halves explicitly in `sc-midi`'s `RpnSelector` rather than by
  //  sentinel, so a genuine half of 127 can be told apart from "not yet received".
  private def isComplete(rpnSelector: RpnSelector): Boolean = rpnSelector match {
    case RpnSelector.None => false
    case RpnSelector.Rpn(msb, lsb) => msb != ScMidiRpn.NullMsb && lsb != ScMidiRpn.NullLsb
    case RpnSelector.Nrpn(msb, lsb) => msb != ScMidiNrpn.NullMsb && lsb != ScMidiNrpn.NullLsb
  }

  /**
   * Whether the MCM this Data Entry MSB carries is valid: received on MIDI Channel 1 or 16 (1-based), whatever the
   * channel's current role, and requesting a number of Member Channels a Zone can hold.
   *
   * The count is checked here rather than left to [[MpeZone]]'s own `require`, which would throw out of the Tuner
   * and into the MIDI transmitter's thread for a value the input is free to send.
   */
  private def isValidMcm(msg: CcScMidiMessage): Boolean =
    (msg.channel == 0 || msg.channel == 15) && MpeZone.isValidMemberCount(msg.value)

  /** Whether `rpnSelector` currently selects the MPE Configuration Message RPN. */
  private[tuner] def isMcm(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnMessages.MpeConfigurationMessageSelector

  /** Whether `rpnSelector` currently selects the Pitch Bend Sensitivity RPN. */
  private[tuner] def isPbs(rpnSelector: RpnSelector): Boolean =
    rpnSelector == RpnMessages.PitchBendSensitivitySelector

  /**
   * Whether relaying `msg` unmodified deselects the parameter the receiving channel holds, obliging the caller to
   * forget what it last left selected there.
   *
   * Reset All Controllers is the case that arises in ordinary traffic: the paper forwards it unmodified onto the
   * very output Master Channel a relayed sequence latches its selector on, and a receiver responds to it by
   * returning its parameter selection to Null — as [[ScMidiChannelStateTracker]] does for the channels it tracks.
   * The Tuner therefore authors the parameter selected on its output channels without authoring every message that
   * changes it, which is what this predicate exists to catch.
   *
   * All Sound Off, All Notes Off and Local Control leave the selection alone and are not included. A System Reset
   * does deselect, on every channel at once; it carries no channel of its own, so its caller handles it rather than
   * this predicate.
   */
  private[tuner] def deselectsOnRelay(msg: ChannelScMidiMessage): Boolean = msg match {
    case cc: CcScMidiMessage => cc.number == ScMidiCc.ResetAllControllers
    case _ => false
  }

  /**
   * Renders a complete Registered or Non-Registered Parameter sequence on an output channel: the selector, then the
   * value message.
   *
   * The selector is rendered by [[RpnMessages.select]], which fixes the transmission order of the pair for every
   * sequence Microtonalist emits, and is omitted when `latchedSelector` says the output channel already holds this
   * parameter selected: selection latches, so a run of value messages of one parameter needs one selector, while two
   * senders sharing an output channel still get a selector each, their parameters differing.
   *
   * Exactly one value message goes out per value message received. Unlike `PitchBendSensitivityMessages.create`,
   * which owns the value it sends and therefore always sends both Data Entry halves, the Tuner has no reading of an
   * uninterpreted parameter from which to supply a half the sender did not send.
   *
   * No closing RPN Null is appended. The paper's Null rule governs the sequences the Tuner ''originates''; appending
   * one to a relayed sequence would invent protocol the sender never sent, and would have to be an NRPN Null for
   * NRPN traffic.
   *
   * @param selector        The parameter selected on the input channel, from
   *                        [[ScMidiChannelStateTracker.rpnSelector]].
   * @param ccNumber        The value CC number: Data Entry MSB or LSB, Data Increment or Data Decrement.
   * @param ccValue         The value CC value.
   * @param outputChannel   The channel the whole sequence is emitted on.
   * @param latchedSelector The parameter the Tuner last left selected on `outputChannel`, or [[RpnSelector.None]]
   *                        when it does not know. Only a caller that records ''every'' sequence it emits on that
   *                        channel — the closing RPN Nulls of the Tuner's own MCM and Pitch Bend Sensitivity
   *                        sequences included — may pass anything else, a stale value being what would let a value
   *                        message ride a selection the Null has since cleared.
   * @return the sequence, or empty when no parameter is selected and no sequence can be formed.
   */
  def rpnSequence(selector: RpnSelector, ccNumber: Int, ccValue: Int, outputChannel: Int,
                  latchedSelector: RpnSelector): Seq[CcScMidiMessage] = {
    val valueMessage = CcScMidiMessage(outputChannel, ccNumber, ccValue)
    selector match {
      case RpnSelector.None => Seq.empty
      case _ if selector == latchedSelector => Seq(valueMessage)
      case _ => RpnMessages.select(outputChannel, selector) :+ valueMessage
    }
  }
}

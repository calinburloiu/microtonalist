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

/**
 * The parameter a MIDI channel currently holds selected, which gives meaning to the Data Entry, Data Increment and
 * Data Decrement messages that follow it.
 *
 * Selection latches: it persists until another selector or a Null Function replaces it, so a run of value messages
 * for one parameter needs a single selector ahead of it. This is the vocabulary shared by the two sides of MIDI 1.0's
 * parameter procedure — [[ScMidiChannelStateTracker]] derives it from an incoming stream, and [[RpnMessages]] renders
 * it back into the Control Change pair that selects it.
 *
 * It carries only ''complete'' parameters, both of whose selector CCs have arrived. [[PartialRpnSelector]] is the
 * finer-grained counterpart that also shows a half still pending, for the callers that need to tell that apart from
 * holding no selection at all.
 */
enum RpnSelector {
  /**
   * No parameter is currently selected, which is what the Null Function of MIDI 1.0 leaves behind and what
   * [[RpnMessages.select]] renders as that Null. Data Entry and Increment/Decrement messages are ignored — as they
   * are for a parameter with a selector CC still to arrive, which reads as this case too; see
   * [[ScMidiChannelStateTracker.partialRpnSelector]] to distinguish the two.
   */
  case None

  /** An RPN with the given MSB and LSB is selected. */
  case Rpn(msb: Int, lsb: Int)

  /** An NRPN with the given MSB and LSB is selected. */
  case Nrpn(msb: Int, lsb: Int)
}

/**
 * The parameter a channel is assembling from its selector CCs, half by half: [[RpnSelector]] with each half optional,
 * MIDI 1.0 letting the two CCs of a parameter arrive in either order.
 *
 * A pending half distinguishes a selector CC that has not arrived from one that carried the Null value (127), a
 * parameter number like any other — the distinction [[ScMidiChannelStateTracker]] needs in order to record a value
 * for an RPN or NRPN with a 127 half, and to recognize the Null ''pair'' whichever of its two CCs completes it.
 *
 * It is the finer-grained counterpart of [[RpnSelector]]: a parameter with a half still pending selects nothing, so
 * [[ScMidiChannelStateTracker.rpnSelector]] reports it as [[RpnSelector.None]], as it does an absent selection.
 * Read it through [[ScMidiChannelStateTracker.partialRpnSelector]] when those two need telling apart, and
 * [[ScMidiChannelStateTracker.rpnSelector]] when only a parameter complete enough to take a value matters.
 *
 * At least one half of an [[PartialRpnSelector.Rpn]] or [[PartialRpnSelector.Nrpn]] the tracker reports is always
 * defined: a parameter with neither half is no parameter at all and is reported as [[PartialRpnSelector.None]].
 * `Rpn(None, None)` and `Nrpn(None, None)` are constructible but never produced, so a consumer need not give them a
 * meaning of their own.
 */
enum PartialRpnSelector {
  /** No parameter is selected and no half of one is pending. */
  case None

  /** An RPN is being assembled, each half either received or still pending. */
  case Rpn(msb: Option[Int], lsb: Option[Int])

  /** An NRPN is being assembled, each half either received or still pending. */
  case Nrpn(msb: Option[Int], lsb: Option[Int])
}

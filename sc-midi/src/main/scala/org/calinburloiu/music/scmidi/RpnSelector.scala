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
 */
enum RpnSelector {
  /** No parameter is currently selected. Data Entry and Increment/Decrement messages are ignored. */
  case None

  /** An RPN with the given MSB and LSB is selected. */
  case Rpn(msb: Int, lsb: Int)

  /** An NRPN with the given MSB and LSB is selected. */
  case Nrpn(msb: Int, lsb: Int)
}

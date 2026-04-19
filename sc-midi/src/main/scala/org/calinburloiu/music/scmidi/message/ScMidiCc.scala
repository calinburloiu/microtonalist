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

package org.calinburloiu.music.scmidi.message

/**
 * Constants for MIDI Control Change (CC) controller numbers.
 */
object ScMidiCc {
  /** Registered Parameter Number (RPN) MSB controller number (#101). */
  val RpnMsb: Int = 101
  /** Registered Parameter Number (RPN) LSB controller number (#100). */
  val RpnLsb: Int = 100
  /** Non-Registered Parameter Number (NRPN) MSB controller number (#99). */
  val NrpnMsb: Int = 99
  /** Non-Registered Parameter Number (NRPN) LSB controller number (#98). */
  val NrpnLsb: Int = 98
  /** Data Entry MSB controller number (#6). */
  val DataEntryMsb: Int = 6
  /** Data Entry LSB controller number (#38). */
  val DataEntryLsb: Int = 38
  /** Data Increment controller number (#96). */
  val DataIncrement: Int = 96
  /** Data Decrement controller number (#97). */
  val DataDecrement: Int = 97
  /** All Sound Off controller number (#120). */
  val AllSoundOff: Int = 120
  /** Reset All Controllers controller number (#121). */
  val ResetAllControllers: Int = 121
  /** All Notes Off controller number (#123). */
  val AllNotesOff: Int = 123

  /** Bank Select MSB controller number (#0). */
  val BankSelectMsb: Int = 0
  /** Bank Select LSB controller number (#32). */
  val BankSelectLsb: Int = 32

  /** Modulation Wheel controller number (#1). */
  val Modulation: Int = 1
  /** Channel Volume controller number (#7). */
  val Volume: Int = 7
  /** Pan controller number (#10). */
  val Pan: Int = 10
  /** Expression controller number (#11). */
  val Expression: Int = 11
  /** Sustain Pedal (Damper) controller number (#64). */
  val SustainPedal: Int = 64
  /** Sostenuto Pedal controller number (#66). */
  val SostenutoPedal: Int = 66
  /** Soft Pedal controller number (#67). */
  val SoftPedal: Int = 67

  /**
   * MPE (MIDI Polyphonic Expression) Slide controller number (#74), also known as Timbre or Brightness.
   */
  val MpeSlide: Int = 74
}

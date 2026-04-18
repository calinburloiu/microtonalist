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
 * MIDI Registered Parameter Numbers (RPN) utilities and constants.
 */
object ScMidiRpn {
  /** Pitch Bend Sensitivity RPN MSB (#0). */
  val PitchBendSensitivityMsb: Int = 0x00
  /** Pitch Bend Sensitivity RPN LSB (#0). */
  val PitchBendSensitivityLsb: Int = 0x00

  /** Coarse Tuning RPN MSB (#0). */
  val CoarseTuningMsb: Int = 0x00
  /** Coarse Tuning RPN LSB (#2). */
  val CoarseTuningLsb: Int = 0x02

  /** Fine Tuning RPN MSB (#0). */
  val FineTuningMsb: Int = 0x00
  /** Fine Tuning RPN LSB (#1). */
  val FineTuningLsb: Int = 0x01

  /** Tuning Bank Select RPN MSB (#0). */
  val TuningBankSelectMsb: Int = 0x00
  /** Tuning Bank Select RPN LSB (#4). */
  val TuningBankSelectLsb: Int = 0x04

  /** Tuning Program Select RPN MSB (#0). */
  val TuningProgramSelectMsb: Int = 0x00
  /** Tuning Program Select RPN LSB (#3). */
  val TuningProgramSelectLsb: Int = 0x03

  val MpeConfigurationMessageLsb: Int = 0x06

  val MpeConfigurationMessageMsb: Int = 0x00

  /** Null RPN MSB (#127). */
  val NullMsb: Int = 0x7F
  /** Null RPN LSB (#127). */
  val NullLsb: Int = 0x7F
}

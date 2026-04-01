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

import org.calinburloiu.music.scmidi.PitchBendSensitivity

/**
 * Type of MPE Zone as defined by the MPE Specification.
 *
 * @see [[MpeZone]]
 */
enum MpeZoneType {
  /** Lower Zone uses Channel 1 (first) as its Master Channel. */
  case Lower
  /** Upper Zone uses Channel 16 (last) as its Master Channel. */
  case Upper
}

/**
 * Represents an MPE Zone consisting of a Master Channel and one or more Member Channels.
 *
 * MPE organizes MIDI Channels into one or two Zones.
 *
 * @param zoneType                   Whether this is a Lower or Upper Zone.
 * @param memberCount                The number of Member Channels in this Zone (0 to 15).
 * @param masterPitchBendSensitivity The Pitch Bend Sensitivity for the Master Channel.
 * @param memberPitchBendSensitivity The Pitch Bend Sensitivity for all Member Channels within this Zone.
 */
case class MpeZone(zoneType: MpeZoneType,
                   memberCount: Int,
                   masterPitchBendSensitivity: PitchBendSensitivity = MpeZone.DefaultMasterPitchBendSensitivity,
                   memberPitchBendSensitivity: PitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity) {
  require(memberCount >= 0 && memberCount <= 15,
    s"memberCount must be between 0 and 15; got $memberCount")

  /** The 0-indexed MIDI channel number of the Master Channel for this Zone. */
  val masterChannel: Int = zoneType match {
    case MpeZoneType.Lower => 0
    case MpeZoneType.Upper => 15
  }

  /** The 0-indexed MIDI channel numbers of the Member Channels for this Zone. */
  val memberChannels: Range = zoneType match {
    case MpeZoneType.Lower => 1 to memberCount
    case MpeZoneType.Upper => (15 - memberCount) to 14
  }

  /** Whether the Zone is enabled (has at least one Member Channel). */
  val isEnabled: Boolean = memberCount > 0

  /**
   * The number of Member Channels allocated to the Expression Group.
   *
   * The Expression Group is used for notes whose pitch class is already represented in the
   * Pitch Class Group, or for notes that cannot be accommodated in the Pitch Class Group
   * because all its channels are occupied.
   *
   * @see [[pitchClassGroupSize]]
   */
  val expressionGroupSize: Int = memberCount match {
    case n if n >= 10 => 3
    case n if 3 <= n && n < 10 => 2
    case 2 => 1
    case _ => 0
  }

  /**
   * The number of Member Channels allocated to the Pitch Class Group.
   *
   * The Pitch Class Group is used for notes of distinct pitch classes, ensuring each
   * pitch class can have its own independent tuning offset.
   *
   * @see [[expressionGroupSize]]
   */
  val pitchClassGroupSize: Int = memberCount - expressionGroupSize
}

/**
 * Companion object for [[MpeZone]].
 */
object MpeZone {
  /** The default Master Channel Pitch Bend Sensitivity as defined by the MPE Specification: ±2 semitones. */
  val DefaultMasterPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(2)
  /** The default Member Channel Pitch Bend Sensitivity as defined by the MPE Specification: ±48 semitones. */
  val DefaultMemberPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(48)
}

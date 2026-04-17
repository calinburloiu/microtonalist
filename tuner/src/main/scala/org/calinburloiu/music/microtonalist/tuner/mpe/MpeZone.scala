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
 * Structural properties of an MPE Zone: zone type, channel layout, and group sizes.
 *
 * This trait captures the immutable structural aspects of an MPE Zone that do not change after zone configuration.
 * It is separated from [[MpeZone]] to allow components that only need the zone structure (e.g.,
 * [[MpeChannelAllocator]]) to depend on this trait without holding a reference to Pitch Bend Sensitivity
 * configuration which may change through the lifetime of an [[MpeTuner]].
 */
trait MpeZoneStructure {

  /** Whether this is a Lower or Upper Zone. */
  def zoneType: MpeZoneType

  /** The number of Member Channels in this Zone (0 to 15). */
  def memberCount: Int

  /** The 0-indexed MIDI channel number of the Master Channel for this Zone. */
  def masterChannel: Int = zoneType match {
    case MpeZoneType.Lower => 0
    case MpeZoneType.Upper => 15
  }

  /** The 0-indexed MIDI channel numbers of the Member Channels for this Zone. */
  val memberChannels: Range = zoneType match {
    case MpeZoneType.Lower => 1 to memberCount
    case MpeZoneType.Upper => (15 - memberCount) to 14
  }

  /** Whether the Zone is enabled (has at least one Member Channel). */
  def isEnabled: Boolean = memberCount > 0

  /**
   * The number of Member Channels allocated to the Expression Group.
   *
   * The Expression Group is used for notes whose pitch class is already represented in the
   * Pitch Class Group or for notes that cannot be accommodated in the Pitch Class Group
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
                   memberPitchBendSensitivity: PitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity)
  extends MpeZoneStructure {
  require(memberCount >= 0 && memberCount <= 15,
    s"memberCount must be between 0 and 15; got $memberCount")
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

/**
 * Immutable configuration of up to two MPE Zones (Lower and Upper), handling overlap resolution
 * as specified by the MPE Specification.
 *
 * On construction, if the given zones overlap, the lower zone is shrunk to resolve the conflict
 * (the upper zone is treated as the later arrival). The `update` method returns a new instance
 * where the updated zone takes precedence and the other zone is shrunk if necessary.
 *
 * @param lower The Lower Zone configuration.
 * @param upper The Upper Zone configuration.
 */
case class MpeZones private(lower: MpeZone, upper: MpeZone) {

  /**
   * Returns a new [[MpeZones]] with the given zone replacing the existing zone of the same type.
   *
   * The updated zone takes precedence: if the update causes overlap, the ''other'' zone is shrunk
   * to resolve the conflict. Pitch Bend Sensitivity of the shrunk zone is preserved.
   *
   * @param zone The new zone configuration (Lower or Upper).
   * @return A new [[MpeZones]] with the update applied and any overlap resolved.
   */
  def update(zone: MpeZone): MpeZones = zone.zoneType match {
    case MpeZoneType.Lower =>
      val adjustedUpper = if (MpeZones.wouldOverlap(zone, upper)) {
        upper.copy(memberCount = Math.max(0, 14 - zone.memberCount))
      } else {
        upper
      }
      new MpeZones(zone, adjustedUpper)
    case MpeZoneType.Upper =>
      val adjustedLower = if (MpeZones.wouldOverlap(lower, zone)) {
        lower.copy(memberCount = Math.max(0, 14 - zone.memberCount))
      } else {
        lower
      }
      new MpeZones(adjustedLower, zone)
  }
}

/**
 * Companion object for [[MpeZones]].
 */
object MpeZones {

  /** The default zone configuration: Lower Zone with 15 Member Channels, Upper Zone disabled. */
  val DefaultZones: MpeZones = MpeZones(MpeZone(MpeZoneType.Lower, 15), MpeZone(MpeZoneType.Upper, 0))

  /**
   * Creates an [[MpeZones]] instance, resolving any overlap by shrinking the lower zone.
   *
   * The upper zone is treated as the later arrival per the MPE Specification: "the most recent
   * message takes precedence (those MIDI Channels are reassigned to the newer Zone)".
   *
   * @param lower The Lower Zone. Must have `zoneType == MpeZoneType.Lower`.
   * @param upper The Upper Zone. Must have `zoneType == MpeZoneType.Upper`.
   * @return A new [[MpeZones]] with overlap resolved.
   */
  def apply(lower: MpeZone, upper: MpeZone): MpeZones = {
    validateLowerAndUpperParams(lower, upper)

    val adjustedLower = if (wouldOverlap(lower, upper)) {
      lower.copy(memberCount = Math.max(0, 14 - upper.memberCount))
    } else {
      lower
    }
    new MpeZones(adjustedLower, upper)
  }

  /**
   * Returns whether the given Lower and Upper zones would overlap in their Member Channel ranges.
   *
   * @param lower The Lower Zone. Must have `zoneType == MpeZoneType.Lower`.
   * @param upper The Upper Zone. Must have `zoneType == MpeZoneType.Upper`.
   * @return `true` if both zones are enabled and their combined member counts exceed 14.
   */
  def wouldOverlap(lower: MpeZone, upper: MpeZone): Boolean = {
    validateLowerAndUpperParams(lower, upper)

    lower.isEnabled && upper.isEnabled && lower.memberCount + upper.memberCount > 14
  }

  private def validateLowerAndUpperParams(lower: MpeZone, upper: MpeZone): Unit = {
    require(lower.zoneType == MpeZoneType.Lower,
      s"lower zone must have zoneType Lower; got ${lower.zoneType}")
    require(upper.zoneType == MpeZoneType.Upper,
      s"upper zone must have zoneType Upper; got ${upper.zoneType}")
  }
}


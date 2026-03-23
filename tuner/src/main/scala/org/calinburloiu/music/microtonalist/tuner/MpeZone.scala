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

sealed trait MpeZoneType

object MpeZoneType {
  case object Lower extends MpeZoneType

  case object Upper extends MpeZoneType
}

case class MpeZone(zoneType: MpeZoneType,
                   memberCount: Int,
                   masterPitchBendSensitivity: PitchBendSensitivity = MpeZone.DefaultMasterPitchBendSensitivity,
                   memberPitchBendSensitivity: PitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity) {
  require(memberCount >= 0 && memberCount <= 15,
    s"memberCount must be between 0 and 15; got $memberCount")

  val masterChannel: Int = zoneType match {
    case MpeZoneType.Lower => 0
    case MpeZoneType.Upper => 15
  }

  val memberChannels: Range = zoneType match {
    case MpeZoneType.Lower => 1 to memberCount
    case MpeZoneType.Upper => (15 - memberCount) to 14
  }

  val isEnabled: Boolean = memberCount > 0

  val expressionGroupSize: Int = {
    if (memberCount == 0) 0
    else if (memberCount == 1) 0
    else if (memberCount == 2) 1
    else if (memberCount <= 9) 2
    else 3
  }

  val pitchClassGroupSize: Int = memberCount - expressionGroupSize
}

object MpeZone {
  val DefaultMasterPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(2)
  val DefaultMemberPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(48)
}

/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.calinburloiu.music.scmidi.MidiDeviceId

trait TrackChannelIOSupport {
  /**
   * [[Some]] MIDI channel number if that particular channel must be used, or [[None]] if ''any'' channel can be used.
   *
   * The channel number must be between 0 and 15, inclusive.
   */
  val channel: Option[Int]

  require(channel.forall(ch => ch >= 0 && ch <= 15), "Channel must be between 0 and 15, inclusive.")
}

trait TrackDeviceIOSupport extends TrackChannelIOSupport {
  val midiDeviceId: MidiDeviceId
}

trait InterTrackIOSupport extends TrackChannelIOSupport {
  val trackId: TrackSpec.Id
}

trait TrackInput

trait TrackOutput

trait TrackIO extends TrackInput with TrackOutput


case class DeviceTrackIO(override val midiDeviceId: MidiDeviceId,
                         override val channel: Option[Int]) extends TrackIO with TrackDeviceIOSupport

case class InterTrackIO(override val trackId: TrackSpec.Id,
                        override val channel: Option[Int]) extends TrackIO with InterTrackIOSupport

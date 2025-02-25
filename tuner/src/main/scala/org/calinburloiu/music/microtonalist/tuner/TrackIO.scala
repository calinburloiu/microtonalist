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

import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.scmidi.MidiDeviceId

/**
 * A trait that marks support for the input/output of a track.
 *
 * This trait is intended to be mixed into classes specify MIDI tracks configuration.
 */
trait TrackIOSupport

/**
 * A trait that extends [[TrackIOSupport]] and provides optional MIDI channel configuration for a track.
 *
 * This trait specifies the ability to define a particular MIDI channel or allow any channel to be used.
 * The channel must be specified within the range of valid MIDI channel numbers (0-15).
 */
trait TrackChannelIOSupport extends TrackIOSupport {
  /**
   * [[Some]] MIDI channel number if that particular channel must be used, or [[None]] if ''any'' channel can be used.
   *
   * The channel number must be between 0 and 15, inclusive.
   *
   * The semantics of the channel depends on the direction of the MIDI flow:
   *
   *   - In the context of ''input'', if the channel is defined, it marks a ''filtering'' of all incoming MIDI
   *     messages that have that channel number. If it's not defined, all messages will pass.
   *   - In the context of ''output'', if the channel is defined, it marks a ''mapping'' of all outgoing MIDI
   *     messages such that all are transformed to have that channel number. If it's not defined, message pass without
   *     modification.
   */
  val channel: Option[Int]

  require(channel.forall(ch => ch >= 0 && ch <= 15), "Channel must be between 0 and 15, inclusive.")
}

/**
 * A trait that extends [[TrackChannelIOSupport]] and provides support for associating
 * a track with a MIDI device, either as input or as output.
 *
 * This trait allows specifying a particular MIDI device represented by its unique
 * [[MidiDeviceId]], enabling device-specific track operations.
 */
trait TrackDeviceIOSupport extends TrackChannelIOSupport {
  /**
   * The unique identifier of the associated MIDI device.
   */
  val midiDeviceId: MidiDeviceId
}

/**
 * A trait extending [[TrackChannelIOSupport]] that provides support for connecting a track's input or output with
 * another track, providing inter-track communication.
 */
trait InterTrackIOSupport extends TrackChannelIOSupport {
  /**
   * Identifier of another track that should be connected to a given track.
   */
  val trackId: TrackSpec.Id
}

/**
 * Plugin that provides the configuration of a track's input.
 *
 * This trait extends [[TrackIOSupport]] to provide capabilities specific to MIDI track configuration.
 * Additionally, it is a [[Plugin]] that belongs to the `trackInput` family, allowing integration with pluggable
 * components for various track inputs.
 */
trait TrackInputSpec extends TrackIOSupport with Plugin {
  override val familyName: String = TrackInputSpec.FamilyName
}

object TrackInputSpec {
  val FamilyName: String = "trackInput"
}

/**
 * Plugin that provides the configuration of a track's output.
 *
 * This trait extends [[TrackIOSupport]] to provide capabilities specific to MIDI track configuration.
 * Additionally, it is a [[Plugin]] that belongs to the `trackOutput` family, allowing integration with pluggable
 * components for various track outputs.
 */
trait TrackOutputSpec extends TrackIOSupport with Plugin {
  override val familyName: String = TrackOutputSpec.FamilyName
}

object TrackOutputSpec {
  val FamilyName: String = "trackOutput"
}


/**
 * Plugin used as a configuration of a track input that uses a MIDI device.
 *
 * @param midiDeviceId Unique identifier of the associated MIDI device.
 * @param channel      Optional MIDI channel to be used for this track input. If the channel is defined, it marks a
 *                     ''filtering'' of all incoming MIDI messages that have that channel number. If it's not
 *                     defined, all messages will pass.
 */
case class DeviceTrackInputSpec(override val midiDeviceId: MidiDeviceId,
                                override val channel: Option[Int]) extends TrackInputSpec with TrackDeviceIOSupport {
  override val typeName: String = DeviceTrackInputSpec.TypeName
}

object DeviceTrackInputSpec {
  val TypeName: String = "device"
}

/**
 * Plugin that specifies the input for a track by connecting it to another track.
 *
 * @param trackId Identifier of the track that this input is connected to.
 * @param channel Optional MIDI channel to be used for this track input. If the channel is defined, it marks a
 *                ''filtering'' of all incoming MIDI messages that have that channel number. If it's not
 *                defined, all messages will pass.
 */
case class FromTrackInputSpec(override val trackId: TrackSpec.Id,
                              override val channel: Option[Int]) extends TrackInputSpec with InterTrackIOSupport {
  override val typeName: String = FromTrackInputSpec.TypeName
}

object FromTrackInputSpec {
  val TypeName: String = "track"
}


/**
 * Plugin used as a configuration of a track output that uses a MIDI device.
 *
 * @param midiDeviceId Unique identifier of the associated MIDI device.
 * @param channel      Optional MIDI channel to be used for this track output. If the channel is defined, it marks a
 *                     ''mapping'' of all outgoing MIDI messages such that all are transformed to have that channel
 *                     number. If it's not defined, message pass without modification.
 */
case class DeviceTrackOutputSpec(override val midiDeviceId: MidiDeviceId,
                                 override val channel: Option[Int]) extends TrackOutputSpec with TrackDeviceIOSupport {
  override val typeName: String = DeviceTrackOutputSpec.TypeName
}

object DeviceTrackOutputSpec {
  val TypeName: String = "device"
}

/**
 * Plugin that specifies the output for a track by connecting it to another track.
 *
 * @param trackId Identifier of the track that this output is connected to.
 * @param channel Optional MIDI channel to be used for this track output. If the channel is defined, it marks a
 *                ''mapping'' of all outgoing MIDI messages such that all are transformed to have that channel
 *                number. If it's not defined, message pass without modification.
 */
case class ToTrackOutputSpec(override val trackId: TrackSpec.Id,
                             override val channel: Option[Int]) extends TrackOutputSpec with InterTrackIOSupport {
  override val typeName: String = ToTrackOutputSpec.TypeName
}

object ToTrackOutputSpec {
  val TypeName: String = "track"
}

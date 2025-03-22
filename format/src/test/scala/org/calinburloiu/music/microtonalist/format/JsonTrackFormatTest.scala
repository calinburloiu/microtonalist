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

package org.calinburloiu.music.microtonalist.format

import com.fasterxml.jackson.core.JsonParseException
import org.calinburloiu.music.microtonalist.format.FormatTestUtils.readTracksFromResources
import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class JsonTrackFormatTest extends AnyFlatSpec with Matchers {
  private val trackFormat: TrackFormat = new JsonTrackFormat(NoJsonPreprocessor)
  private val trackRepo: TrackRepo = {
    val fileTrackRepo = new FileTrackRepo(trackFormat)

    new DefaultTrackRepo(Some(fileTrackRepo), None, None)
  }

  it should "read a basic tracks file" in {
    val tracks = readTracksFromResources("format/tracks/basic.mtlist.tracks", trackRepo)

    tracks.size shouldEqual 5

    tracks(0) shouldEqual TrackSpec(
      id = "piano",
      name = "# Piano",
      input = Some(DeviceTrackInputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - IAC 1", "Apple Inc."),
        channel = None
      )),
      tuningChangers = Seq(
        PedalTuningChanger(
          triggers = PedalTuningChanger.DefaultTriggers,
          threshold = PedalTuningChanger.DefaultThreshold,
          triggersThru = PedalTuningChanger.DefaultTriggersThru
        )
      ),
      tuner = Some(MtsOctave1ByteNonRealTimeTuner(thru = false, altTuningOutput = None)),
      output = Some(DeviceTrackOutputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - Roland Digital Piano", "Roland"),
        channel = None
      )),
      muted = false
    )
    tracks(1) shouldEqual TrackSpec(
      id = "piano-tuning-pedals",
      name = "# Piano Tuning Pedals",
      input = Some(DeviceTrackInputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - Roland Digital Piano", "Roland"),
        channel = None
      )),
      tuningChangers = Seq(
        PedalTuningChanger(
          triggers = PedalTuningChanger.DefaultTriggers,
          threshold = PedalTuningChanger.DefaultThreshold,
          triggersThru = PedalTuningChanger.DefaultTriggersThru
        )
      ),
      tuner = None,
      output = None,
      muted = false
    )
    tracks(2) shouldEqual TrackSpec(
      id = "synthA",
      name = "# Synth A",
      input = Some(DeviceTrackInputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard BLOCK M", "ROLI Ltd."),
        channel = None
      )),
      tuningChangers = Seq.empty,
      tuner = Some(MonophonicPitchBendTuner(
        outputChannel = 0,
        pitchBendSensitivity = PitchBendSensitivity(48, 0)
      )),
      output = Some(DeviceTrackOutputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."),
        channel = None
      )),
      muted = false
    )
    tracks(3) shouldEqual TrackSpec(
      id = "neova",
      name = "# Neova Ring",
      input = Some(DeviceTrackInputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - Neova", "Enhancia"),
        channel = None
      )),
      tuningChangers = Seq.empty,
      tuner = None,
      output = Some(ToTrackOutputSpec(
        trackId = "synthB",
        channel = None
      )),
      muted = true
    )
    tracks(4) shouldEqual TrackSpec(
      id = "synthB",
      name = "# Synth B",
      input = Some(DeviceTrackInputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - Roland Digital Piano", "Roland"),
        channel = None
      )),
      tuningChangers = Seq.empty,
      tuner = Some(MonophonicPitchBendTuner(
        outputChannel = 0,
        pitchBendSensitivity = PitchBendSensitivity(2, 0)
      )),
      output = Some(DeviceTrackOutputSpec(
        midiDeviceId = MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."),
        channel = None
      )),
      muted = true
    )
  }

  it should "fail to read a file that does not exist" in assertThrows[TracksNotFoundException] {
    trackRepo.readTracks(new URI("file:///does/not/exist.mtlist.tracks"))
  }

  it should "fail to read a file that is not a JSON" in assertThrows[JsonParseException] {
    readTracksFromResources("format/scales/chromatic.scl", trackRepo)
  }

  it should "fail to read a JSON file with another format" in assertThrows[InvalidTrackFormatException] {
    readTracksFromResources("format/scales/minor-just.jscl", trackRepo)
  }

  it should "fail to read a file that misses a mandatory property" in assertThrows[InvalidTrackFormatException] {
    readTracksFromResources("format/tracks/missing-mandatory-prop.mtlist.tracks", trackRepo)
  }

  it should "fail to read a file that has an invalid property value" in assertThrows[InvalidTrackFormatException] {
    readTracksFromResources("format/tracks/missing-mandatory-prop.mtlist.tracks", trackRepo)
  }

  it should "fail to read a file from an unsupported scheme" in assertThrows[BadTracksRequestException] {
    trackRepo.readTracks(new URI("xyz://example.com/file.mtlist.tracks"))
  }
}

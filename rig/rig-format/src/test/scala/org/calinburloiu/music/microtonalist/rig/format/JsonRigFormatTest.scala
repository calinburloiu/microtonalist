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

package org.calinburloiu.music.microtonalist.rig.format

import org.calinburloiu.music.microtonalist.format.NoJsonPreprocessor
import org.calinburloiu.music.microtonalist.tuner.changer.format.JsonTuningChangerPluginFormat
import org.calinburloiu.music.microtonalist.tuner.format.JsonTunerPluginFormat
import com.fasterxml.jackson.core.JsonParseException
import org.calinburloiu.music.microtonalist.rig.format.FormatTestUtils.readRigFromResources
import org.calinburloiu.music.microtonalist.rig.*
import org.calinburloiu.music.microtonalist.tuner.mpb.MonophonicPitchBendTuner
import org.calinburloiu.music.microtonalist.tuner.mts.MtsOctave1ByteNonRealTimeTuner
import org.calinburloiu.music.microtonalist.tuner.changer.pedal.PedalTuningChanger
import org.calinburloiu.music.microtonalist.tuner.Tuner
import org.calinburloiu.music.microtonalist.tuner.changer.TuningChanger
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{Format, JsString, Json, Reads, Writes}

import java.net.URI

class JsonRigFormatTest extends AnyWordSpec with Matchers {
  private val rigFormat: RigFormat = new JsonRigFormat(NoJsonPreprocessor, JsonTunerPluginFormat.format,
    JsonTuningChangerPluginFormat.format)
  private val rigRepo: RigRepo = {
    val fileRigRepo = new FileRigRepo(rigFormat)

    new DefaultRigRepo(Some(fileRigRepo), None, None)
  }

  private val givenTuner: Tuner = MonophonicPitchBendTuner(outputChannel = 5)
  private val givenTuningChanger: TuningChanger = PedalTuningChanger(threshold = 42)
  private val rigFormatWithGivenFormats: JsonRigFormat = JsonRigFormat(
    NoJsonPreprocessor,
    Format[Tuner](Reads.pure(givenTuner), Writes(_ => JsString("givenTuner"))),
    Format[TuningChanger](Reads.pure(givenTuningChanger), Writes(_ => JsString("givenTuningChanger")))
  )

  "JsonRigFormat" should {
    "read a basic tracks file" in {
      val tracks = readRigFromResources("format/tracks/basic.mtlist.tracks", rigRepo)

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
          defaultPitchBendSensitivity = PitchBendSensitivity(48, 0)
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
          defaultPitchBendSensitivity = PitchBendSensitivity(2, 0)
        )),
        output = Some(DeviceTrackOutputSpec(
          midiDeviceId = MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."),
          channel = None
        )),
        muted = true
      )
    }

    "fail to read a file that does not exist" in assertThrows[RigNotFoundException] {
      rigRepo.readRig(new URI("file:///does/not/exist.mtlist.tracks"))
    }

    "fail to read a file that is not a JSON" in assertThrows[JsonParseException] {
      readRigFromResources("format/scales/chromatic.scl", rigRepo)
    }

    "fail to read a JSON file with another format" in assertThrows[InvalidRigFormatException] {
      readRigFromResources("format/scales/minor-just.jscl", rigRepo)
    }

    "fail to read a file that misses a mandatory property" in assertThrows[InvalidRigFormatException] {
      readRigFromResources("format/tracks/missing-mandatory-prop.mtlist.tracks", rigRepo)
    }

    "fail to read a file that has an invalid property value" in assertThrows[InvalidRigFormatException] {
      readRigFromResources("format/tracks/invalid-prop-value.mtlist.tracks", rigRepo)
    }

    "fail to read a file from an unsupported scheme" in assertThrows[BadRigRequestException] {
      rigRepo.readRig(new URI("xyz://example.com/file.mtlist.tracks"))
    }

    "read a track's tuner and tuning changers with the formats it is given" in {
      // Given
      val json = Json.obj("id" -> "piano", "name" -> "Piano", "tuner" -> "unknownTuner",
        "tuningChangers" -> Json.arr("unknownTuningChanger"))

      // When
      val trackSpec = rigFormatWithGivenFormats.readTrack(json, None)

      // Then
      trackSpec.tuner shouldEqual Some(givenTuner)
      trackSpec.tuningChangers shouldEqual Seq(givenTuningChanger)
    }

    "write a track's tuner and tuning changers with the formats it is given" in {
      // Given
      val trackSpec = TrackSpec(id = "piano", name = "Piano", tuningChangers = Seq(PedalTuningChanger()),
        tuner = Some(MtsOctave1ByteNonRealTimeTuner()))

      // When
      val json = rigFormatWithGivenFormats.writeTrackAsJsValue(trackSpec)

      // Then
      (json \ "tuner").get shouldEqual JsString("givenTuner")
      (json \ "tuningChangers").get shouldEqual Json.arr("givenTuningChanger")
    }
  }
}

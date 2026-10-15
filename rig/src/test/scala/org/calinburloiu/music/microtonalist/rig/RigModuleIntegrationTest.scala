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

package org.calinburloiu.music.microtonalist.rig

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.tuner.{
  MtsMessageGenerator, MtsOctave1ByteNonRealTimeTuner, PedalTuningChanger, TunerModule, Tuning
}
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg}
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiDirection, MidiManager, MidiSplitter}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

class RigModuleIntegrationTest extends AnyWordSpec with Matchers with Stubs {

  /** How long a test waits for the tracks to open; the repo reads them at once. */
  private val OpenTimeout: FiniteDuration = 1.second

  private val tracksUri: URI = URI("file:///compositions/test.mtlist.tracks")

  private val keyboardId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
  private val pianoId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  private val controllerId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Keystation", "M-Audio")
  private val synthId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Minilogue", "KORG")

  /** The message an [[MtsOctave1ByteNonRealTimeTuner]] sends to tune its output to `tuning`. */
  private def mtsTuningMessage(tuning: Tuning): MidiMsg = MtsMessageGenerator.Octave1ByteNonRealTime.generate(tuning)

  /** A [[RigModule]] around a real [[TunerModule]] and bus, with only its MIDI devices and tracks file faked. */
  private trait Fixture {
    val businessync: Businessync = Businessync(EventBus())
    val tunerModule: TunerModule = TunerModule(businessync)

    val deviceReceivers: Map[MidiDeviceId, RecordingMidiReceiver] =
      Seq(keyboardId, pianoId, controllerId, synthId).map(_ -> RecordingMidiReceiver()).toMap
    val deviceHandles: Map[MidiDeviceId, FakeMidiDeviceHandle] =
      deviceReceivers.map { (deviceId, receiver) => deviceId -> FakeMidiDeviceHandle(deviceId, receiver) }

    val midiManager: Stub[MidiManager] = stub[MidiManager]
    midiManager.openDevice.returns { (deviceId, _) => deviceHandles(deviceId) }
    midiManager.closeDevice.returns(_ => ())

    val rigSpec: RigSpec = RigSpec(Seq(
      TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)),
        tuningChangers = Seq(PedalTuningChanger(nextTuningCcTrigger = MidiCc.SostenutoPedal)),
        tuner = Some(MtsOctave1ByteNonRealTimeTuner()),
        output = Some(DeviceTrackOutputSpec(pianoId, None))),
      TrackSpec("synth", "Synth", input = Some(DeviceTrackInputSpec(controllerId, None)),
        tuner = Some(MtsOctave1ByteNonRealTimeTuner()),
        output = Some(DeviceTrackOutputSpec(synthId, None)))
    ))
    val rigRepo: Stub[RigRepo] = stub[RigRepo]
    rigRepo.readRigAsync.returns(_ => Future.successful(rigSpec))

    val rigModule: RigModule = RigModule(businessync, rigRepo, midiManager, tunerModule.tuningService)

    def pianoMessages: Seq[MidiMsg] = deviceReceivers(pianoId).messages

    def synthMessages: Seq[MidiMsg] = deviceReceivers(synthId).messages

    def openTracks(): Unit = Await.result(rigModule.rigService.open(tracksUri), OpenTimeout)

    def clearMessages(): Unit = deviceReceivers.values.foreach(_.clear())

    /** Sends `message` from the input device `deviceId` to the tracks it feeds. */
    def play(deviceId: MidiDeviceId, message: MidiMsg): Unit = MidiSplitter(deviceHandles(deviceId).transmitter)
      .send(message)
  }

  "a RigModule" should {
    "open the tracks read from its repo through its rig service" in new Fixture {
      // When
      openTracks()

      // Then
      rigRepo.readRigAsync.calls shouldEqual List(tracksUri)
      midiManager.openDevice.calls should contain theSameElementsAs Seq(
        (keyboardId, MidiDirection.Input), (pianoId, MidiDirection.Output),
        (controllerId, MidiDirection.Input), (synthId, MidiDirection.Output))
      pianoMessages shouldEqual Seq(mtsTuningMessage(Tuning.Standard))
      synthMessages shouldEqual Seq(mtsTuningMessage(Tuning.Standard))
    }

    "retune its tracks when the tunings of the tuning session change" in new Fixture {
      // Given
      openTracks()
      clearMessages()

      // When
      tunerModule.tuningSession.tunings = Seq(TestTunings.justCMaj, TestTunings.justCRast)

      // Then
      pianoMessages shouldEqual Seq(mtsTuningMessage(TestTunings.justCMaj))
      synthMessages shouldEqual Seq(mtsTuningMessage(TestTunings.justCMaj))
    }

    "switch its tracks to the next tuning when the pedal of a track's pedal tuning changer is pressed" in new Fixture {
      // Given
      openTracks()
      tunerModule.tuningSession.tunings = Seq(TestTunings.justCMaj, TestTunings.justCRast)
      clearMessages()

      // When
      play(keyboardId, CcMidiMsg(0, MidiCc.SostenutoPedal, 127))

      // Then
      pianoMessages shouldEqual Seq(mtsTuningMessage(TestTunings.justCRast))
      synthMessages shouldEqual Seq(mtsTuningMessage(TestTunings.justCRast))
    }

    "close its tracks, switching their outputs back to 12-EDO and releasing their devices, when closed" in
      new Fixture {
        // Given
        openTracks()
        clearMessages()

        // When
        rigModule.close()

        // Then
        pianoMessages shouldEqual Seq(mtsTuningMessage(Tuning.Standard))
        synthMessages shouldEqual Seq(mtsTuningMessage(Tuning.Standard))
        midiManager.closeDevice.calls should contain theSameElementsAs Seq(
          (keyboardId, MidiDirection.Input), (pianoId, MidiDirection.Output),
          (controllerId, MidiDirection.Input), (synthId, MidiDirection.Output))
      }
  }
}

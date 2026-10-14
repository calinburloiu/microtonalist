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
import org.calinburloiu.music.microtonalist.tuner.{TunerModule, Tuning}
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, PitchBendMidiMsg}
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiDirection, MidiManager}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

class RigModuleTest extends AnyWordSpec with Matchers with Stubs {

  /** How long a test waits for the tracks to open; the repo reads them at once. */
  private val OpenTimeout: FiniteDuration = 1.second

  private val tracksUri: URI = URI("file:///compositions/test.mtlist.tracks")

  private val keyboardId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
  private val pianoId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

  private val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)

  /** What the fake tuner sends when it is tuned to 12-EDO. */
  private val standardTuningMessage: MidiMsg = PitchBendMidiMsg(1, -100)

  /** What the fake tuner sends when it is tuned to Just C Major. */
  private val justCMajTuningMessage: MidiMsg = PitchBendMidiMsg(1, 100)

  /**
   * A [[RigModule]] on a real bus, given the tuning service of a [[TunerModule]] on the same bus. Its stubbed
   * [[MidiManager]] opens every device as a [[FakeMidiDeviceHandle]] whose receiver records what the device gets, and
   * its stubbed [[RigRepo]] reads a rig of one track, "piano", which plays the keyboard on the piano.
   */
  private trait Fixture {
    val businessync: Businessync = Businessync(EventBus())
    val tunerModule: TunerModule = TunerModule(businessync)

    val deviceReceivers: Map[MidiDeviceId, RecordingMidiReceiver] =
      Seq(keyboardId, pianoId).map(_ -> RecordingMidiReceiver()).toMap

    val midiManager: Stub[MidiManager] = stub[MidiManager]
    midiManager.openDevice.returns { (deviceId, _) => FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId)) }
    midiManager.closeDevice.returns(_ => ())

    val rigSpec: RigSpec = RigSpec(Seq(
      TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)),
        tuner = Some(FakeTuner(resetMessages = Seq(initMessage),
          tuningMessages = Map(
            Tuning.Standard -> Seq(standardTuningMessage), TestTunings.justCMaj -> Seq(justCMajTuningMessage)))),
        output = Some(DeviceTrackOutputSpec(pianoId, None)))
    ))
    val rigRepo: Stub[RigRepo] = stub[RigRepo]
    rigRepo.readRigAsync.returns(_ => Future.successful(rigSpec))

    val rigModule: RigModule = RigModule(businessync, rigRepo, midiManager, tunerModule.tuningService)

    def pianoMessages: Seq[MidiMsg] = deviceReceivers(pianoId).messages

    def openTracks(): Unit = Await.result(rigModule.rigService.open(tracksUri), OpenTimeout)
  }

  "a RigModule" should {
    "open the tracks read from its repo through its rig service" in new Fixture {
      // When
      openTracks()

      // Then
      rigRepo.readRigAsync.calls shouldEqual List(tracksUri)
      midiManager.openDevice.calls should contain theSameElementsAs Seq(
        (keyboardId, MidiDirection.Input), (pianoId, MidiDirection.Output))
      pianoMessages shouldEqual Seq(initMessage, standardTuningMessage)
    }

    "retune its tracks when the tunings of the tuning session change" in new Fixture {
      // Given
      openTracks()
      deviceReceivers(pianoId).clear()

      // When
      tunerModule.tuningSession.tunings = Seq(TestTunings.justCMaj, TestTunings.justCRast)

      // Then
      pianoMessages shouldEqual Seq(justCMajTuningMessage)
    }

    "close its tracks, switching their outputs back to 12-EDO and releasing their devices, when closed" in
      new Fixture {
        // Given
        openTracks()
        deviceReceivers(pianoId).clear()

        // When
        rigModule.close()

        // Then
        pianoMessages shouldEqual Seq(standardTuningMessage)
        midiManager.closeDevice.calls should contain theSameElementsAs Seq(
          (keyboardId, MidiDirection.Input), (pianoId, MidiDirection.Output))
      }

    "close without releasing any device when no tracks were opened" in new Fixture {
      // When
      rigModule.close()

      // Then
      midiManager.openDevice.times shouldBe 0
      midiManager.closeDevice.times shouldBe 0
    }
  }
}

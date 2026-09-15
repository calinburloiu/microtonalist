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

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.message.{AllNotesOffMidiMsg, CcMidiMsg, MidiCc, MidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TrackManagerTest extends AnyFlatSpec with Matchers with Stubs {

  private val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)

  private val allNotesOff: Seq[MidiMsg] = (0 until 16).map(AllNotesOffMidiMsg(_))

  private val keyboardId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
  private val pianoId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  private val controllerId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Keystation", "M-Audio")
  private val synthId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Minilogue", "KORG")

  /** A tuner whose reset sends [[initMessage]], which tunes nothing and lets every message through. */
  private class ResetTuner extends Tuner {
    override val typeName: String = "reset"

    override def reset(): Seq[MidiMsg] = Seq(initMessage)

    override def tune(tuning: Tuning): Seq[MidiMsg] = Seq.empty

    override def process(message: MidiMsg): Seq[MidiMsg] = Seq(message)
  }

  /**
   * A [[TrackManager]] registered on a real bus, over a stubbed [[MidiManager]] that opens every device as a
   * [[FakeMidiDeviceHandle]] whose receiver records what the device gets. Its two tracks are built, and the messages
   * that building them sent are forgotten: "piano" plays the keyboard on the piano, and "synth" plays the controller on
   * the synth.
   */
  private trait Fixture {
    val businessync: Businessync = Businessync(EventBus())

    val deviceReceivers: Map[MidiDeviceId, RecordingMidiReceiver] =
      Seq(keyboardId, pianoId, controllerId, synthId).map(_ -> RecordingMidiReceiver()).toMap

    /** Called by the stubbed manager whenever a track opens an output device; a test may replace it. */
    var onOpenOutput: MidiDeviceId => Unit = _ => ()

    val midiManager: Stub[MidiManager] = stub[MidiManager]
    midiManager.openInput.returns(deviceId => FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId)))
    midiManager.openOutput.returns { deviceId =>
      onOpenOutput(deviceId)
      FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId))
    }
    midiManager.closeInput.returns(_ => ())
    midiManager.closeOutput.returns(_ => ())

    val trackSpecs: TrackSpecs = TrackSpecs(Seq(
      TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)), tuner = Some(ResetTuner()),
        output = Some(DeviceTrackOutputSpec(pianoId, None))),
      TrackSpec("synth", "Synth", input = Some(DeviceTrackInputSpec(controllerId, None)), tuner = Some(ResetTuner()),
        output = Some(DeviceTrackOutputSpec(synthId, None)))
    ))

    val trackManager: TrackManager =
      TrackManager(midiManager, TuningService(TuningSession(businessync), businessync))
    businessync.register(trackManager)
    trackManager.replaceAllTracks(trackSpecs)
    deviceReceivers.values.foreach(_.clear())
  }

  behavior of "a MIDI device event"

  it should "reset the tuner of the tracks whose output device got opened, and of no other track" in new Fixture {
    // When
    businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiEndpointType.Output))

    // Then
    deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
    deviceReceivers(synthId).messages shouldBe empty
  }

  it should "ignore the opening of an input device" in new Fixture {
    // When
    businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiEndpointType.Input))
    businessync.publish(MidiDeviceOpenedEvent(keyboardId, MidiEndpointType.Input))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }

  it should "release the output of the tracks whose input device got disconnected, and of no other track" in
    new Fixture {
      // When
      businessync.publish(MidiDeviceDisconnectedEvent(keyboardId, MidiEndpointType.Input))

      // Then
      deviceReceivers(pianoId).messages shouldEqual allNotesOff :+ initMessage
      deviceReceivers(synthId).messages shouldBe empty
    }

  it should "release the output of the tracks whose input device failed to disconnect" in new Fixture {
    // When
    businessync.publish(
      MidiDeviceFailedToDisconnectEvent(keyboardId, MidiEndpointType.Input, IllegalStateException("Cannot close")))

    // Then
    deviceReceivers(pianoId).messages shouldEqual allNotesOff :+ initMessage
    deviceReceivers(synthId).messages shouldBe empty
  }

  it should "ignore the disconnection of an output device" in new Fixture {
    // When
    businessync.publish(MidiDeviceDisconnectedEvent(keyboardId, MidiEndpointType.Output))
    businessync.publish(MidiDeviceDisconnectedEvent(pianoId, MidiEndpointType.Output))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }

  it should "ignore the other MIDI events" in new Fixture {
    // When
    businessync.publish(MidiEnvironmentChangedEvent)
    businessync.publish(MidiDeviceConnectedEvent(pianoId, MidiEndpointType.Output))
    businessync.publish(MidiDeviceClosedEvent(keyboardId, MidiEndpointType.Input))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }

  behavior of "replaceAllTracks"

  it should "not deliver an event published while it builds the new tracks to the tracks it closed" in new Fixture {
    // Given
    onOpenOutput = deviceId => businessync.publish(MidiDeviceOpenedEvent(deviceId, MidiEndpointType.Output))

    // When
    trackManager.replaceAllTracks(trackSpecs)

    // Then
    // Only the new track, connecting its device receiver when it is built, resets the tuner
    deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
    deviceReceivers(synthId).messages shouldEqual Seq(initMessage)
  }
}

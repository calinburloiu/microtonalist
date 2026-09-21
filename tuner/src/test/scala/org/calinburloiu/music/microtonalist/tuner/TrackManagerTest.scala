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
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class TrackManagerTest extends AnyWordSpec with Matchers with Stubs {

  private val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)

  /**
   * What releasing the input of a track sends straight to its output, on each of the 16 channels: the pedals
   * released first, then All Notes Off.
   */
  private val inputRelease: Seq[MidiMsg] = (0 until MidiChannelCount).flatMap { channel =>
    Seq(CcMidiMsg(channel, MidiCc.SustainPedal, 0), CcMidiMsg(channel, MidiCc.SostenutoPedal, 0),
      AllNotesOffMidiMsg(channel))
  }

  private val keyboardId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
  private val pianoId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  private val controllerId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Keystation", "M-Audio")
  private val synthId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Minilogue", "KORG")

  /** The message the reset of a second tuner on the same output device sends, to tell the two resets apart. */
  private val otherInitMessage: MidiMsg = CcMidiMsg(1, MidiCc.DataEntryMsb, 2)

  /** A tuner whose reset sends `resetMessage`, which tunes nothing and lets every message through. */
  private class ResetTuner(resetMessage: MidiMsg = initMessage) extends Tuner {
    override val typeName: String = "reset"

    override def reset(): Seq[MidiMsg] = Seq(resetMessage)

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
    midiManager.openDevice.returns { (deviceId, direction) =>
      if (direction == MidiDirection.Output) {
        onOpenOutput(deviceId)
      }

      FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId))
    }
    midiManager.closeDevice.returns(_ => ())

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

  "a MIDI device event" should {
    "reset the tuner of the tracks whose output device got opened, and of no other track" in new Fixture {
      // When
      businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiDirection.Output))

      // Then
      deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
      deviceReceivers(synthId).messages shouldBe empty
    }

    "reset the tuner of every track whose output device got opened, when two tracks share it" in new Fixture {
      // Given
      trackManager.replaceAllTracks(TrackSpecs(Seq(
        TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)), tuner = Some(ResetTuner()),
          output = Some(DeviceTrackOutputSpec(pianoId, None))),
        TrackSpec("controller", "Controller", input = Some(DeviceTrackInputSpec(controllerId, None)),
          tuner = Some(ResetTuner(otherInitMessage)), output = Some(DeviceTrackOutputSpec(pianoId, None)))
      )))
      deviceReceivers.values.foreach(_.clear())

      // When
      businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiDirection.Output))

      // Then
      deviceReceivers(pianoId).messages shouldEqual Seq(initMessage, otherInitMessage)
      deviceReceivers(synthId).messages shouldBe empty
    }

    "ignore the opening of an input device" in new Fixture {
      // When
      businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiDirection.Input))
      businessync.publish(MidiDeviceOpenedEvent(keyboardId, MidiDirection.Input))

      // Then
      deviceReceivers.values.flatMap(_.messages) shouldBe empty
    }

    "release the output of the tracks whose input device became unavailable, and of no other track" in new Fixture {
      // When
      businessync.publish(MidiDeviceUnavailableEvent(keyboardId, MidiDirection.Input))

      // Then
      deviceReceivers(pianoId).messages shouldEqual inputRelease :+ initMessage
      deviceReceivers(synthId).messages shouldBe empty
    }

    "release the output of every track whose input device became unavailable, when two tracks share it" in new Fixture {
      // Given
      trackManager.replaceAllTracks(TrackSpecs(Seq(
        TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)), tuner = Some(ResetTuner()),
          output = Some(DeviceTrackOutputSpec(pianoId, None))),
        TrackSpec("synth", "Synth", input = Some(DeviceTrackInputSpec(keyboardId, None)), tuner = Some(ResetTuner()),
          output = Some(DeviceTrackOutputSpec(synthId, None)))
      )))
      deviceReceivers.values.foreach(_.clear())

      // When
      businessync.publish(MidiDeviceUnavailableEvent(keyboardId, MidiDirection.Input))

      // Then
      deviceReceivers(pianoId).messages shouldEqual inputRelease :+ initMessage
      deviceReceivers(synthId).messages shouldEqual inputRelease :+ initMessage
    }

    "release the output of the tracks whose input device failed to become unavailable" in new Fixture {
      // When
      businessync.publish(MidiDeviceFailedToBecomeUnavailableEvent(
        keyboardId, MidiDirection.Input, IllegalStateException("Cannot close")))

      // Then
      deviceReceivers(pianoId).messages shouldEqual inputRelease :+ initMessage
      deviceReceivers(synthId).messages shouldBe empty
    }

    "ignore an output device becoming unavailable" in new Fixture {
      // When
      businessync.publish(MidiDeviceUnavailableEvent(keyboardId, MidiDirection.Output))
      businessync.publish(MidiDeviceUnavailableEvent(pianoId, MidiDirection.Output))

      // Then
      deviceReceivers.values.flatMap(_.messages) shouldBe empty
    }

    "ignore the other MIDI events" in new Fixture {
      // When
      businessync.publish(MidiEnvironmentChangedEvent)
      businessync.publish(MidiDeviceAvailableEvent(pianoId, MidiDirection.Output))
      businessync.publish(MidiDeviceClosedEvent(keyboardId, MidiDirection.Input))

      // Then
      deviceReceivers.values.flatMap(_.messages) shouldBe empty
    }
  }

  "replaceAllTracks" should {
    "not deliver an event published while it builds the new tracks to the tracks it closed" in new Fixture {
      // Given
      // A receiver on the output of the track about to be closed. Track.close() detaches only the output device
      // receiver, so this one stays attached and records what a tuner reset reaching the closed track would send —
      // which the device receiver, detached by then, could not show.
      val closedTrackOutput: RecordingMidiReceiver = RecordingMidiReceiver()
      trackManager.tracks.head.transmitter.addReceiver(closedTrackOutput)
      closedTrackOutput.clear()
      onOpenOutput = deviceId => businessync.publish(MidiDeviceOpenedEvent(deviceId, MidiDirection.Output))

      // When
      trackManager.replaceAllTracks(trackSpecs)

      // Then
      closedTrackOutput.messages shouldBe empty
      // Only the new track, attaching its device receiver when it is built, resets the tuner
      deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
      deviceReceivers(synthId).messages shouldEqual Seq(initMessage)
    }
  }
}

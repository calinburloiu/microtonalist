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

import org.calinburloiu.music.scmidi.message.{AllNotesOffMidiMsg, CcMidiMsg, MidiCc, MidiMsg, NoteOnMidiMsg,
  PitchBendMidiMsg}
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiDirection, MidiManager, MidiNote, MidiReceiver, MidiSplitter}
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class TrackTest extends AnyWordSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)
  val inputMessage: MidiMsg = NoteOnMidiMsg(0, MidiNote.C4, 64)
  val outputMessage: MidiMsg = PitchBendMidiMsg(0, 100)
  /** What the fake tuner sends when it is tuned to 12-EDO. */
  val standardTuningMessage: MidiMsg = PitchBendMidiMsg(1, -100)
  /** What the fake tuner sends when it is tuned to Just C Major. */
  val justCMajTuningMessage: MidiMsg = PitchBendMidiMsg(1, 100)

  private def createTuner(): FakeTuner = FakeTuner(
    resetMessages = Seq(initMessage),
    tuningMessages = Map(Tuning.Standard -> Seq(standardTuningMessage),
      TestTunings.justCMaj -> Seq(justCMajTuningMessage)),
    processMessages = Map(inputMessage -> Seq(outputMessage))
  )

  trait Fixture {
    val tuner: FakeTuner = createTuner()

    val tuningService: TuningService = stub[TuningService]
    val spec: TrackSpec = TrackSpec("track", "Track", tuner = Some(tuner))
    // The spec has no device input and no device output, so the track never touches the manager.
    val midiManager: MidiManager = stub[MidiManager]
    val track: Track = Track(spec = spec, midiManager = midiManager, tuningService = tuningService)

    val receiver: MidiReceiver = stub[MidiReceiver]
  }

  /** A track over an input device and an output device, both opened through a stubbed manager. */
  trait DeviceFixture {
    val inputDeviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
    val outputDeviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    val tuner: FakeTuner = createTuner()

    val inputHandle: FakeMidiDeviceHandle = FakeMidiDeviceHandle(inputDeviceId)
    val outputReceiver: RecordingMidiReceiver = RecordingMidiReceiver()
    val midiManager: MidiManager = stub[MidiManager]
    midiManager.openDevice.when(inputDeviceId, MidiDirection.Input).returns(inputHandle)
    midiManager.openDevice.when(outputDeviceId, MidiDirection.Output)
      .returns(FakeMidiDeviceHandle(outputDeviceId, outputReceiver))

    val tuningChanger: TuningChanger = stub[TuningChanger]
    tuningChanger.decide.when(*).returns(NoTuningChange)

    val tuningService: TuningService = stub[TuningService]
    val spec: TrackSpec = TrackSpec("track", "Track", input = Some(DeviceTrackInputSpec(inputDeviceId, None)),
      tuningChangers = Seq(tuningChanger), tuner = Some(tuner),
      output = Some(DeviceTrackOutputSpec(outputDeviceId, None)))
    val track: Track = Track(spec = spec, midiManager = midiManager, tuningService = tuningService)
  }

  "transmitter" should {
    "deliver the tuner's reset messages to a receiver added after the track was built" in new Fixture {
      // When
      track.transmitter.addReceiver(receiver)

      // Then
      receiver.send.verify(initMessage, -1L).once()
    }

    "forward the tuner's output for a message sent to the track's receiver" in new Fixture {
      // Given
      track.transmitter.addReceiver(receiver)

      // When
      track.receiver.send(inputMessage, 7L)

      // Then
      receiver.send.verify(outputMessage, *).once()
    }
  }

  "close" should {
    "release its input and output devices through the MIDI manager" in new DeviceFixture {
      // When
      track.close()

      // Then
      midiManager.closeDevice.verify(inputDeviceId, MidiDirection.Input).once()
      midiManager.closeDevice.verify(outputDeviceId, MidiDirection.Output).once()
    }

    "stop forwarding the messages of its input device to its output" in new DeviceFixture {
      // Given
      track.close()
      outputReceiver.clear()

      // When
      MidiSplitter(inputHandle.transmitter).send(inputMessage, 7L)

      // Then
      inputHandle.transmitter.receivers shouldBe empty
      outputReceiver.messages shouldBe empty
    }

    "stop sending the messages it receives to its output device" in new DeviceFixture {
      // Given
      track.close()
      outputReceiver.clear()

      // When
      track.receiver.send(inputMessage, 7L)

      // Then
      outputReceiver.messages shouldBe empty
    }

    "switch its output device back to 12-EDO exactly once" in new DeviceFixture {
      // Given
      outputReceiver.clear()

      // When
      track.close()

      // Then
      outputReceiver.messages shouldEqual Seq(standardTuningMessage)
    }

    "switch the tracks it feeds back to 12-EDO exactly once" in new Fixture {
      // Given
      // A tuning other than 12-EDO, which the tuner restates when the receiver attaches
      track.tune(TestTunings.justCMaj)
      track.transmitter.addReceiver(receiver)

      // When
      track.close()

      // Then
      receiver.send.verify(standardTuningMessage, -1L).once()
    }

    "release nothing through the MIDI manager when it has no device" in new Fixture {
      // When
      track.close()

      // Then
      midiManager.closeDevice.verify(*, *).never()
    }

    "release nothing through the MIDI manager when its input and output are other tracks" in new Fixture {
      // Given
      val trackSpecWithTrackIO: TrackSpec = spec.copy(
        input = Some(FromTrackInputSpec("upstream", None)), output = Some(ToTrackOutputSpec("downstream", None)))
      val trackWithTrackIO: Track = Track(spec = trackSpecWithTrackIO, midiManager = midiManager,
        tuningService = tuningService)

      // When
      trackWithTrackIO.close()

      // Then
      midiManager.closeDevice.verify(*, *).never()
    }
  }

  "resetTuner" should {
    "send the tuner's reset messages to the output, followed by the ones restoring the current tuning" in
      new DeviceFixture {
        // Given
        track.tune(TestTunings.justCMaj)
        outputReceiver.clear()

        // When
        track.resetTuner()

        // Then
        outputReceiver.messages shouldEqual Seq(initMessage, justCMajTuningMessage)
      }
  }

  "releaseInput" should {
    "release the pedals and send All Notes Off on every channel straight to the output, then reset the " +
      "tuning changers and tuner" in new DeviceFixture {
        // Given
        track.tune(TestTunings.justCMaj)
        outputReceiver.clear()

        // When
        track.releaseInput()

        // Then
        // The pedals are released first: a latched Hold or Sostenuto takes priority over All Notes Off, so a note held
        // by one would keep sounding otherwise.
        val expectedRelease: Seq[MidiMsg] = (0 until 16).flatMap { channel =>
          Seq(CcMidiMsg(channel, MidiCc.SustainPedal, 0), CcMidiMsg(channel, MidiCc.SostenutoPedal, 0),
            AllNotesOffMidiMsg(channel))
        }
        // The tuner reset restores the current tuning
        outputReceiver.messages shouldEqual expectedRelease ++ Seq(initMessage, justCMajTuningMessage)
        tuner.processedMessages shouldBe empty
        (() => tuningChanger.reset()).verify().once()
      }
  }
}

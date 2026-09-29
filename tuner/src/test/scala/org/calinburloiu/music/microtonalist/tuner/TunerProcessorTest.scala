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

import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiMsg, NoteOnMidiMsg, PitchBendMidiMsg}
import org.calinburloiu.music.scmidi.{MidiNote, MidiReceiver}
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class TunerProcessorTest extends AnyWordSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, 67, 0)

  val tuneMessage1: MidiMsg = PitchBendMidiMsg(0, 100)
  val tuneMessage2: MidiMsg = PitchBendMidiMsg(0, 0)
  val tuneMessage3: MidiMsg = PitchBendMidiMsg(0, 200)

  val processMessage1: MidiMsg = NoteOnMidiMsg(0, MidiNote(60), 64)
  val processMessage2: MidiMsg = PitchBendMidiMsg(0, 101)

  /**
   * A processor over a tuner in Just C Major, which it restates on each reset, so that the 12-EDO messages a receiver
   * gets can only come from detaching it.
   */
  abstract class Fixture(shouldAttach: Boolean = true) {
    val tuner: FakeTuner = FakeTuner(
      resetMessages = Seq(initMessage),
      tuningMessages = Map(
        TestTunings.justCMaj -> Seq(tuneMessage1),
        Tuning.Standard -> Seq(tuneMessage2),
        TestTunings.justCRast -> Seq(tuneMessage3)
      ),
      processMessages = Map(processMessage1 -> Seq(processMessage1, processMessage2))
    )
    tuner.tune(TestTunings.justCMaj)

    val receiver: MidiReceiver = stub[MidiReceiver]
    val processor: TunerProcessor = TunerProcessor(tuner)

    if (shouldAttach) {
      processor.transmitter.addReceiver(receiver)
    }
  }

  "onAttach" should {
    "send init message after attaching" in new Fixture(shouldAttach = false) {
      // When
      processor.transmitter.addReceiver(receiver)
      // Then
      receiver.send.verify(initMessage, -1L).once()
    }

    "not send init message before attaching" in new Fixture(shouldAttach = false) {
      receiver.send.verify(*, *).never()
    }

    "send init message to every receiver newly attached in one change" in new Fixture(shouldAttach = false) {
      // Given
      val anotherReceiver: MidiReceiver = stub[MidiReceiver]
      // When
      processor.transmitter.receivers = Seq(receiver, anotherReceiver)
      // Then
      receiver.send.verify(initMessage, -1L).once()
      anotherReceiver.send.verify(initMessage, -1L).once()
    }

    "send init message only to the receiver newly added, not again to one already attached" in new Fixture {
      // Given
      val anotherReceiver: MidiReceiver = stub[MidiReceiver]
      // When
      processor.transmitter.addReceiver(anotherReceiver)
      // Then
      anotherReceiver.send.verify(initMessage, -1L).once()
      receiver.send.verify(initMessage, -1L).once()
    }
  }

  "tune" should {
    "send the tune messages returned by the tuner" in new Fixture {
      // When
      processor.tune(TestTunings.justCRast)
      // Then
      tuner.tuning shouldEqual TestTunings.justCRast
      receiver.send.verify(tuneMessage3, -1L).once()
    }
  }

  "send" should {
    "send the message processed by the tuner" in new Fixture {
      // Given
      val timeStamp: Long = 3L
      // When
      processor.receiver.send(processMessage1, timeStamp)
      // Then
      tuner.processedMessages shouldEqual Seq(processMessage1)
      receiver.send.verify(processMessage1, timeStamp).once()
      receiver.send.verify(processMessage2, timeStamp).once()
    }
  }

  "onDetach" should {
    "reset tuning to 12-EDO on the receivers still in place when they are cleared" in new Fixture {
      // When
      processor.transmitter.clearReceivers()
      // Then
      receiver.send.verify(tuneMessage2, -1L).once()
    }

    "reset tuning to 12-EDO only on the receiver being removed, leaving the one that remains untouched" in
      new Fixture {
        // Given
        val anotherReceiver: MidiReceiver = stub[MidiReceiver]
        processor.transmitter.addReceiver(anotherReceiver)

        // When
        processor.transmitter.removeReceiver(receiver)

        // Then
        receiver.send.verify(tuneMessage2, -1L).once()
        anotherReceiver.send.verify(tuneMessage2, -1L).never()
      }
  }

  "reset" should {
    "reset the tuner and send its reset messages to every receiver" in new Fixture {
      // Given
      val anotherReceiver: MidiReceiver = stub[MidiReceiver]
      processor.transmitter.addReceiver(anotherReceiver)

      // When
      processor.reset()

      // Then
      // Once when each receiver attached, and once more on reset
      receiver.send.verify(initMessage, -1L).repeated(2)
      anotherReceiver.send.verify(initMessage, -1L).repeated(2)
    }

    "restore the current tuning after the reset messages" in new Fixture(shouldAttach = false) {
      // Given
      val recordingReceiver: RecordingMidiReceiver = RecordingMidiReceiver()
      processor.transmitter.addReceiver(recordingReceiver)
      processor.tune(TestTunings.justCRast)
      recordingReceiver.clear()

      // When
      processor.reset()

      // Then
      recordingReceiver.messages shouldEqual Seq(initMessage, tuneMessage3)
    }
  }
}

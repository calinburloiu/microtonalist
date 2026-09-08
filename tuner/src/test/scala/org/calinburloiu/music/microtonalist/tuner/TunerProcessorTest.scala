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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TunerProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, 67, 0)

  val tuneMessage1: MidiMsg = PitchBendMidiMsg(0, 100)
  val tuneMessage2: MidiMsg = PitchBendMidiMsg(0, 0)

  val processMessage1: MidiMsg = NoteOnMidiMsg(0, MidiNote(60), 64)
  val processMessage2: MidiMsg = PitchBendMidiMsg(0, 101)

  abstract class Fixture(shouldConnect: Boolean = true) {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(TestTunings.justCMaj).returns(Seq(tuneMessage1))
    tuner.tune.when(Tuning.Standard).returns(Seq(tuneMessage2))
    tuner.process.when(processMessage1).returns(Seq(processMessage1, processMessage2))

    val receiver: MidiReceiver = stub[MidiReceiver]
    val processor: TunerProcessor = TunerProcessor(tuner)

    if (shouldConnect) {
      processor.transmitter.addReceiver(receiver)
    }
  }

  "onConnect" should "send init message after connecting" in new Fixture(shouldConnect = false) {
    // When
    processor.transmitter.addReceiver(receiver)
    // Then
    receiver.send.verify(initMessage, -1L).once()
  }

  it should "not send init message before connecting" in new Fixture(shouldConnect = false) {
    receiver.send.verify(*, *).never()
  }

  it should "send init message to every receiver" in new Fixture(shouldConnect = false) {
    // Given
    val anotherReceiver: MidiReceiver = stub[MidiReceiver]
    // When
    processor.transmitter.receivers = Seq(receiver, anotherReceiver)
    // Then
    receiver.send.verify(initMessage, -1L).once()
    anotherReceiver.send.verify(initMessage, -1L).once()
  }

  "tune" should "send the tune messages returned by the tuner" in new Fixture {
    // When
    processor.tune(TestTunings.justCMaj)
    // Then
    tuner.tune.verify(TestTunings.justCMaj).once()
    receiver.send.verify(tuneMessage1, -1L).once()
  }

  "send" should "send the message processed by the tuner" in new Fixture {
    // Given
    val timeStamp: Long = 3L
    // When
    processor.receiver.send(processMessage1, timeStamp)
    // Then
    tuner.process.verify(processMessage1).once()
    receiver.send.verify(processMessage1, timeStamp).once()
    receiver.send.verify(processMessage2, timeStamp).once()
  }

  "onDisconnect" should "reset tuning to 12-EDO and the internal state of the tuner" in new Fixture {
    // When
    processor.close()
    // Then
    tuner.tune.verify(Tuning.Standard).once()
    receiver.send.verify(tuneMessage2, -1L).once()
  }
}

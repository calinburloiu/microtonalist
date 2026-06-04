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

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcScMidiMessage, NoteOnScMidiMessage, PitchBendScMidiMessage}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver}
import scala.collection.mutable

class TunerProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMessage = CcScMidiMessage(0, 67, 0).asJava

  val tuneMessage1: MidiMessage = PitchBendScMidiMessage(0, 100).asJava
  val tuneMessage2: MidiMessage = PitchBendScMidiMessage(0, 0).asJava

  val processMessage1: MidiMessage = NoteOnScMidiMessage(0, MidiNote(60), 64).asJava
  val processMessage2: MidiMessage = PitchBendScMidiMessage(0, 101).asJava

  /**
   * Concrete test double for [[Tuner]]. Because [[Tuner]] now carries mutable state (the stored tuning), it is no
   * longer a pure interface and cannot be created with ScalaMock's `stub`; this fake records the calls forwarded by
   * [[TunerProcessor]] and replays canned responses keyed by argument.
   */
  class FakeTuner extends Tuner {
    override val typeName: String = "fake"

    val tuneArgs: mutable.Buffer[Tuning] = mutable.Buffer.empty
    val processArgs: mutable.Buffer[MidiMessage] = mutable.Buffer.empty

    private val tuneResponses: Map[Tuning, Seq[MidiMessage]] = Map(
      TestTunings.justCMaj -> Seq(tuneMessage1),
      Tuning.Standard -> Seq(tuneMessage2)
    )
    private val processResponses: Map[MidiMessage, Seq[MidiMessage]] = Map(
      processMessage1 -> Seq(processMessage1, processMessage2)
    )

    override def reset(): Seq[MidiMessage] = Seq(initMessage)

    override protected def onTune(previousTuning: Tuning, tuning: Tuning): Seq[MidiMessage] = {
      tuneArgs += tuning
      tuneResponses.getOrElse(tuning, Seq.empty)
    }

    override def process(message: MidiMessage): Seq[MidiMessage] = {
      processArgs += message
      processResponses.getOrElse(message, Seq.empty)
    }
  }

  abstract class Fixture(shouldConnect: Boolean = true) {
    val tuner: FakeTuner = FakeTuner()

    val receiver: Receiver = stub[Receiver]
    val processor: TunerProcessor = new TunerProcessor(tuner)

    if (shouldConnect) {
      processor.transmitter.receiver = Some(receiver)
    }
  }

  "onConnect" should "send init message after connecting" in new Fixture(shouldConnect = false) {
    // When
    processor.transmitter.receiver = Some(receiver)
    // Then
    receiver.send.verify(initMessage, -1).once()
  }

  it should "not send init message before connecting" in new Fixture(shouldConnect = false) {
    receiver.send.verify(*, *).never()
  }

  "tune" should "send the tune messages returned by the tuner" in new Fixture {
    // When
    processor.tune(TestTunings.justCMaj)
    // Then
    tuner.tuneArgs shouldEqual Seq(TestTunings.justCMaj)
    receiver.send.verify(tuneMessage1, -1).once()
  }

  "send" should "send the message processed by the tuner" in new Fixture {
    // Given
    val timeStamp: Long = 3L
    // When
    processor.receiver.send(processMessage1, timeStamp)
    // Then
    tuner.processArgs shouldEqual Seq(processMessage1)
    receiver.send.verify(processMessage1, timeStamp).once()
    receiver.send.verify(processMessage2, timeStamp).once()
  }

  "onDisconnect" should "reset tuning to 12-EDO and the internal state of the tuner" in new Fixture {
    // When
    processor.close()
    // Then
    tuner.tuneArgs shouldEqual Seq(Tuning.Standard)
    receiver.send.verify(tuneMessage2, -1).once()
  }
}

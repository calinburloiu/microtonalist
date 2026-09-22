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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.BeforeAndAfter
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class MidiSerialProcessorTest extends AnyWordSpec, Matchers, BeforeAndAfter, Stubs {

  /**
   * Outputs of the [[TestMidiProcessor]] instances returned by [[MidiSerialProcessor.process]] expressed as (factor,
   * velocity) pairs.
   */
  val processedVelocities: mutable.ArrayBuffer[(Int, Int)] = mutable.ArrayBuffer()

  /**
   * Output values of the [[MidiSerialProcessor]] expressed as velocities.
   */
  val outputVelocities: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer()

  class TestMidiProcessor(val factor: Int) extends MidiProcessor {

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = message match {
      case NoteOnMidiMsg(channel, midiNote, velocity) =>
        val newVelocity = Math.min(factor * velocity, 127)
        processedVelocities += Tuple2(factor, newVelocity)

        Seq(NoteOnMidiMsg(channel, midiNote, newVelocity))
      case _ => Seq(message)
    }
  }

  abstract class Fixture(shouldSetOutputReceiverOnSend: Boolean = false) {
    val processor2x: TestMidiProcessor = new TestMidiProcessor(2)
    val processor3x: TestMidiProcessor = new TestMidiProcessor(3)
    val processor5x: TestMidiProcessor = new TestMidiProcessor(5)
    val processor7x: TestMidiProcessor = new TestMidiProcessor(7)

    val outputReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
    outputReceiver.send.returns {
      case (msg, ts) => msg match {
        case NoteOnMidiMsg(_, _, velocity) => outputVelocities += velocity
        case _ =>
      }
    }

    val midiSerialProcessor: MidiSerialProcessor

    def send(velocity: Int): Unit = {
      if (shouldSetOutputReceiverOnSend) midiSerialProcessor.transmitter.receivers = Seq(outputReceiver)

      midiSerialProcessor.receiver.send(NoteOnMidiMsg(0, MidiNote.C4, velocity), 123L)
    }
  }

  def reset(): Unit = {
    processedVelocities.clear()
    outputVelocities.clear()
  }

  after {
    reset()
  }

  "constructor" should {
    "configure some initial processors and some output receiver" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))

      // When
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (5, 30))
      outputVelocities should contain theSameElementsAs Seq(30)
    }

    "configure no initial processors and some output receiver" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq(outputReceiver))

      // When
      send(1)

      // Then
      processedVelocities shouldBe empty
      outputVelocities should contain theSameElementsAs Seq(1)
    }

    "configure no initial processors and no output receiver" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq.empty)
      midiSerialProcessor.transmitter.receivers = Seq(outputReceiver)

      // When
      send(1)

      // Then
      processedVelocities shouldBe empty
      outputVelocities should contain theSameElementsAs Seq(1)
    }

    "configure some initial processors and no output receiver" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor3x, processor5x), Seq.empty)

      // When
      send(1)
      // Then
      processedVelocities shouldBe empty

      // When
      midiSerialProcessor.transmitter.receivers = Seq(outputReceiver)
      midiSerialProcessor.receiver.send(NoteOnMidiMsg(0, MidiNote.C4, 1), 123L)
      // Then
      outputVelocities should contain theSameElementsAs Seq(15)
    }
  }

  "transmitter" should {
    "unwire the last processor when its receivers are cleared" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x), Seq(outputReceiver))
      processor3x.transmitter.receivers shouldEqual Seq(outputReceiver)

      // When
      midiSerialProcessor.transmitter.clearReceivers()

      // Then
      processor3x.transmitter.receivers shouldBe empty
      processor2x.transmitter.receivers shouldEqual Seq(processor3x.receiver)
    }

    "rewire the last processor when its receivers are replaced" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x), Seq(outputReceiver))
      val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
      anotherReceiver.send.returns(_ => ())

      // When
      midiSerialProcessor.transmitter.receivers = Seq(anotherReceiver)

      // Then
      processor3x.transmitter.receivers shouldEqual Seq(anotherReceiver)
    }

    "keep the other receivers wired when one of them is removed" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x), Seq(outputReceiver))
      val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
      anotherReceiver.send.returns(_ => ())
      midiSerialProcessor.transmitter.addReceiver(anotherReceiver)

      // When
      midiSerialProcessor.transmitter.removeReceiver(anotherReceiver)
      send(1)

      // Then
      processor3x.transmitter.receivers shouldEqual Seq(outputReceiver)
      outputVelocities should contain theSameElementsAs Seq(6)
      anotherReceiver.send.calls shouldBe empty
    }

    "mirror the order of its receivers onto the last processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x), Seq(outputReceiver))
      val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
      anotherReceiver.send.returns(_ => ())
      midiSerialProcessor.transmitter.addReceiver(anotherReceiver)

      // When
      midiSerialProcessor.transmitter.receivers = Seq(anotherReceiver, outputReceiver)

      // Then
      processor3x.transmitter.receivers shouldEqual Seq(anotherReceiver, outputReceiver)
    }

    "fan the output out to every receiver" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x), Seq(outputReceiver))
      val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
      anotherReceiver.send.returns(_ => ())
      midiSerialProcessor.transmitter.addReceiver(anotherReceiver)

      // When
      send(1)

      // Then
      outputVelocities should contain theSameElementsAs Seq(2)
      // The time-stamp is not matched: the chain re-stamps with -1 (see MidiSerialProcessor#process).
      anotherReceiver.send.calls.map { case (message, _) => message } shouldEqual Seq(NoteOnMidiMsg(0, MidiNote.C4, 2))
    }
  }

  "processors" should {
    "set a new sequence of chained processors" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))
      midiSerialProcessor.processors = Seq(processor3x, processor5x)

      // When
      midiSerialProcessor.receiver.send(NoteOnMidiMsg(0, MidiNote.C4, 7), 12L)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor3x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
      outputVelocities should contain theSameElementsAs Seq(105)

      processor2x.transmitter.receivers shouldBe empty
    }

    "set a new sequence of chained processors before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq.empty)
        midiSerialProcessor.processors = Seq(processor3x, processor5x)

        // When
        send(7)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor3x, processor5x)
        processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
        outputVelocities should contain theSameElementsAs Seq(105)

        processor2x.transmitter.receivers shouldBe empty
      }
  }

  "insert" should {
    "add a processor into an empty chain" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq(outputReceiver))

      // When
      midiSerialProcessor.insert(0, processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
      processedVelocities should contain theSameElementsAs Seq((5, 25))
      outputVelocities should contain theSameElementsAs Seq(25)
    }

    "add a processor into an empty chain before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq.empty)

        // When
        midiSerialProcessor.insert(0, processor5x)
        send(5)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
        processedVelocities should contain theSameElementsAs Seq((5, 25))
        outputVelocities should contain theSameElementsAs Seq(25)
      }

    "prepend a processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // When
      midiSerialProcessor.insert(0, processor5x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x)
      processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10))
      outputVelocities should contain theSameElementsAs Seq(10)
    }

    "append a processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor5x, processor2x),
        Seq(outputReceiver))

      // When
      midiSerialProcessor.insert(2, processor3x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10), (3, 30))
      outputVelocities should contain theSameElementsAs Seq(30)
    }

    "append a processor before setting an output receiver" in new Fixture(shouldSetOutputReceiverOnSend =
      true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor =
        MidiSerialProcessor(Seq(processor5x, processor2x), Seq.empty)

      // When
      midiSerialProcessor.insert(2, processor3x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10), (3, 30))
      outputVelocities should contain theSameElementsAs Seq(30)
    }

    "add a processor in between" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor5x, processor2x,
        processor3x), Seq(outputReceiver))

      // When
      midiSerialProcessor.insert(1, processor7x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor7x, processor2x,
        processor3x)
      processedVelocities should contain theSameElementsAs Seq((5, 5), (7, 35), (2, 70), (3, 127))
      outputVelocities should contain theSameElementsAs Seq(127)
    }

    "append if the index exceeds the upper bounds" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // Then
      midiSerialProcessor.insert(10, processor5x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
      outputVelocities should contain theSameElementsAs Seq(10)
    }

    "fail if the index is negative" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // Then
      assertThrows[IllegalArgumentException] {
        midiSerialProcessor.insert(-1, processor5x)
      }
    }
  }

  "append" should {
    "add a processor into an empty chain" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq(outputReceiver))

      // When
      midiSerialProcessor.append(processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
      processedVelocities should contain theSameElementsAs Seq((5, 25))
      outputVelocities should contain theSameElementsAs Seq(25)
    }

    "add a processor into an empty chain before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq.empty)

        // When
        midiSerialProcessor.append(processor5x)
        send(5)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
        processedVelocities should contain theSameElementsAs Seq((5, 25))
        outputVelocities should contain theSameElementsAs Seq(25)
      }

    "add a processor at the end of the chain" in new Fixture() {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // When
      midiSerialProcessor.append(processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 10), (5, 50))
      outputVelocities should contain theSameElementsAs Seq(50)
    }

    "add a processor at the end of the chain before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq.empty)

        // When
        midiSerialProcessor.append(processor5x)
        send(5)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
        processedVelocities should contain theSameElementsAs Seq((2, 10), (5, 50))
        outputVelocities should contain theSameElementsAs Seq(50)
      }
  }

  "update" should {
    "replace a processor at a given index" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))

      // When
      midiSerialProcessor.update(1, processor7x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor7x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (7, 14), (5, 70))
      outputVelocities should contain theSameElementsAs Seq(70)
    }

    "replace the last processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))

      // When
      midiSerialProcessor.update(2, processor7x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor7x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (7, 42))
      outputVelocities should contain theSameElementsAs Seq(42)
    }

    "replace the last processor before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
          Seq(processor2x, processor3x, processor5x), Seq.empty)

        // When
        midiSerialProcessor.update(2, processor7x)
        send(1)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor7x)
        processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (7, 42))
        outputVelocities should contain theSameElementsAs Seq(42)
      }
  }

  "remove" should {
    "delete a processor in the middle of the chain" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))
      processor3x.transmitter.receivers should not be empty

      // When
      midiSerialProcessor.remove(processor3x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
      outputVelocities should contain theSameElementsAs Seq(10)

      processor3x.transmitter.receivers shouldBe empty
    }

    "delete the last processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))
      processor5x.transmitter.receivers should not be empty

      // When
      midiSerialProcessor.remove(processor5x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
      outputVelocities should contain theSameElementsAs Seq(6)

      processor5x.transmitter.receivers shouldBe empty
    }

    "delete the last processor before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
          Seq(processor2x, processor3x, processor5x), Seq.empty)

        // When
        midiSerialProcessor.remove(processor5x)
        send(1)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
        processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
        outputVelocities should contain theSameElementsAs Seq(6)

        processor5x.transmitter.receivers shouldBe empty
      }

    "do nothing if the processor to be removed is not found" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // When
      midiSerialProcessor.remove(processor3x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x)
      processedVelocities should contain theSameElementsAs Seq((2, 2))
      outputVelocities should contain theSameElementsAs Seq(2)
    }
  }

  "removeAt" should {
    "delete a processor in the middle of the chain" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))
      processor3x.transmitter.receivers should not be empty

      // When
      midiSerialProcessor.removeAt(1)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
      outputVelocities should contain theSameElementsAs Seq(10)

      processor3x.transmitter.receivers shouldBe empty
    }

    "delete the last processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), Seq(outputReceiver))
      processor5x.transmitter.receivers should not be empty

      // When
      midiSerialProcessor.removeAt(2)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
      outputVelocities should contain theSameElementsAs Seq(6)

      processor5x.transmitter.receivers shouldBe empty
    }

    "delete the last processor before setting an output receiver" in
      new Fixture(shouldSetOutputReceiverOnSend = true) {
        // Given
        override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
          Seq(processor2x, processor3x, processor5x), Seq.empty)

        // When
        midiSerialProcessor.removeAt(2)
        send(1)

        // Then
        midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
        processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
        outputVelocities should contain theSameElementsAs Seq(6)

        processor5x.transmitter.receivers shouldBe empty
      }

    "do nothing if the index exceeds the upper bounds" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // When
      midiSerialProcessor.removeAt(1)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x)
      processedVelocities should contain theSameElementsAs Seq((2, 2))
      outputVelocities should contain theSameElementsAs Seq(2)
    }

    "fail if the index is negative" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x), Seq(outputReceiver))

      // Then
      assertThrows[IllegalArgumentException] {
        midiSerialProcessor.removeAt(-1)
      }
    }
  }

  "clear" should {
    "remove all processors" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x, processor5x),
        Seq(outputReceiver))

      // When
      midiSerialProcessor.clear()
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq.empty
      processedVelocities shouldBe empty
      outputVelocities should contain theSameElementsAs Seq(1)

      processor2x.transmitter.receivers shouldBe empty
      processor5x.transmitter.receivers shouldBe empty
    }

    "remove all processors before setting an output receiver" in new Fixture(shouldSetOutputReceiverOnSend =
      true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor =
        MidiSerialProcessor(Seq(processor2x, processor5x), Seq.empty)

      // When
      midiSerialProcessor.clear()
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq.empty
      processedVelocities shouldBe empty
      outputVelocities should contain theSameElementsAs Seq(1)

      processor2x.transmitter.receivers shouldBe empty
      processor5x.transmitter.receivers shouldBe empty
    }
  }

  "size" should {
    "tell that there is no processor" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq.empty, Seq.empty)

      // Then
      midiSerialProcessor.size shouldBe 0
    }

    "tell how many processors are in the chain" in new Fixture {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(Seq(processor2x, processor5x),
        Seq(outputReceiver))

      // Then
      midiSerialProcessor.size shouldBe 2
    }
  }
}

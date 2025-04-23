/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver}
import scala.collection.mutable

class MidiSerialProcessorTest extends AnyFlatSpec, Matchers, BeforeAndAfter, Stubs {

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

    override protected def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = message match {
      case ScNoteOnMidiMessage(channel, midiNote, velocity) =>
        val newVelocity = Math.min(factor * velocity, 127)
        processedVelocities += Tuple2(factor, newVelocity)

        Seq(ScNoteOnMidiMessage(channel, midiNote, newVelocity).javaMidiMessage)
      case otherMessage => Seq(otherMessage)
    }

    override def close(): Unit = {}
  }

  abstract class Fixture(shouldSetOutputReceiverOnSend: Boolean = false) {
    val processor2x: TestMidiProcessor = new TestMidiProcessor(2)
    val processor3x: TestMidiProcessor = new TestMidiProcessor(3)
    val processor5x: TestMidiProcessor = new TestMidiProcessor(5)
    val processor7x: TestMidiProcessor = new TestMidiProcessor(7)

    val outputReceiver: Stub[Receiver] = stub[Receiver]
    outputReceiver.send.returns {
      case (ScNoteOnMidiMessage(_, _, velocity), ts) => outputVelocities += velocity
    }

    val midiSerialProcessor: MidiSerialProcessor

    def send(velocity: Int): Unit = {
      if (shouldSetOutputReceiverOnSend) midiSerialProcessor.transmitter.receiver = Some(outputReceiver)

      midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, velocity).javaMidiMessage, 123L)
    }
  }

  def reset(): Unit = {
    processedVelocities.clear()
    outputVelocities.clear()
  }

  after {
    reset()
  }

  behavior of "constructor"

  it should "configure some initial processors and some output receiver" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))

    // When
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (5, 30))
    outputVelocities should contain theSameElementsAs Seq(30)
  }

  it should "configure no initial processors and some output receiver" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, Some(outputReceiver))

    // When
    send(1)

    // Then
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)
  }

  it should "configure no initial processors and no output receiver" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, None)
    midiSerialProcessor.transmitter.receiver = Some(outputReceiver)

    // When
    send(1)

    // Then
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)
  }

  it should "configure some initial processors and no output receiver" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor3x, processor5x), None)

    // When
    send(1)
    // Then
    processedVelocities shouldBe empty

    // When
    midiSerialProcessor.transmitter.receiver = Some(outputReceiver)
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)
    // Then
    outputVelocities should contain theSameElementsAs Seq(15)
  }

  it should "not send messages after the receiver was closed" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))
    midiSerialProcessor.receiver.close()

    // When
    send(1)

    // Then
    processedVelocities shouldBe empty
    outputVelocities shouldBe empty
  }

  behavior of "processors"

  "processors" should "set a new sequence of chained processors" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))
    midiSerialProcessor.processors = Seq(processor3x, processor5x)

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 7).javaMidiMessage, 12L)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor3x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
    outputVelocities should contain theSameElementsAs Seq(105)

    processor2x.transmitter.receiver shouldBe empty
  }

  it should "set a new sequence of chained processors before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), None)
      midiSerialProcessor.processors = Seq(processor3x, processor5x)

      // When
      send(7)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor3x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
      outputVelocities should contain theSameElementsAs Seq(105)

      processor2x.transmitter.receiver shouldBe empty
    }

  behavior of "insert"

  it should "add a processor into an empty chain" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, Some(outputReceiver))

    // When
    midiSerialProcessor.insert(0, processor5x)
    send(5)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
    processedVelocities should contain theSameElementsAs Seq((5, 25))
    outputVelocities should contain theSameElementsAs Seq(25)
  }

  it should "add a processor into an empty chain before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, None)

      // When
      midiSerialProcessor.insert(0, processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
      processedVelocities should contain theSameElementsAs Seq((5, 25))
      outputVelocities should contain theSameElementsAs Seq(25)
    }

  it should "prepend a processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // When
    midiSerialProcessor.insert(0, processor5x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x)
    processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10))
    outputVelocities should contain theSameElementsAs Seq(10)
  }

  it should "append a processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor5x, processor2x),
      Some(outputReceiver))

    // When
    midiSerialProcessor.insert(2, processor3x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x, processor3x)
    processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10), (3, 30))
    outputVelocities should contain theSameElementsAs Seq(30)
  }

  it should "append a processor before setting an output receiver" in new Fixture(shouldSetOutputReceiverOnSend =
    true) {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor5x, processor2x),
      None)

    // When
    midiSerialProcessor.insert(2, processor3x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor2x, processor3x)
    processedVelocities should contain theSameElementsAs Seq((5, 5), (2, 10), (3, 30))
    outputVelocities should contain theSameElementsAs Seq(30)
  }

  it should "add a processor in between" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor5x, processor2x,
      processor3x), Some(outputReceiver))

    // When
    midiSerialProcessor.insert(1, processor7x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x, processor7x, processor2x,
      processor3x)
    processedVelocities should contain theSameElementsAs Seq((5, 5), (7, 35), (2, 70), (3, 127))
    outputVelocities should contain theSameElementsAs Seq(127)
  }

  it should "append if the index exceeds the upper bounds" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // Then
    midiSerialProcessor.insert(10, processor5x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
    outputVelocities should contain theSameElementsAs Seq(10)
  }

  it should "fail if the index is negative" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // Then
    assertThrows[IllegalArgumentException] {
      midiSerialProcessor.insert(-1, processor5x)
    }
  }

  behavior of "append"

  it should "add a processor into an empty chain" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, Some(outputReceiver))

    // When
    midiSerialProcessor.append(processor5x)
    send(5)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
    processedVelocities should contain theSameElementsAs Seq((5, 25))
    outputVelocities should contain theSameElementsAs Seq(25)
  }

  it should "add a processor into an empty chain before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, None)

      // When
      midiSerialProcessor.append(processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor5x)
      processedVelocities should contain theSameElementsAs Seq((5, 25))
      outputVelocities should contain theSameElementsAs Seq(25)
    }

  it should "add a processor at the end of the chain" in new Fixture() {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // When
    midiSerialProcessor.append(processor5x)
    send(5)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 10), (5, 50))
    outputVelocities should contain theSameElementsAs Seq(50)
  }

  it should "add a processor at the end of the chain before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), None)

      // When
      midiSerialProcessor.append(processor5x)
      send(5)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
      processedVelocities should contain theSameElementsAs Seq((2, 10), (5, 50))
      outputVelocities should contain theSameElementsAs Seq(50)
    }

  behavior of "update"

  it should "replace a processor at a given index" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))

    // When
    midiSerialProcessor.update(1, processor7x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor7x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (7, 14), (5, 70))
    outputVelocities should contain theSameElementsAs Seq(70)
  }

  it should "replace the last processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))

    // When
    midiSerialProcessor.update(2, processor7x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor7x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (7, 42))
    outputVelocities should contain theSameElementsAs Seq(42)
  }

  it should "replace the last processor before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), None)

      // When
      midiSerialProcessor.update(2, processor7x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x, processor7x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (7, 42))
      outputVelocities should contain theSameElementsAs Seq(42)
    }

  behavior of "remove"

  it should "delete a processor in the middle of the chain" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))
    processor3x.transmitter.receiver should not be empty

    // When
    midiSerialProcessor.remove(processor3x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
    outputVelocities should contain theSameElementsAs Seq(10)

    processor3x.transmitter.receiver shouldBe empty
  }

  it should "delete the last processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))
    processor5x.transmitter.receiver should not be empty

    // When
    midiSerialProcessor.remove(processor5x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
    outputVelocities should contain theSameElementsAs Seq(6)

    processor5x.transmitter.receiver shouldBe empty
  }

  it should "delete the last processor before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), None)

      // When
      midiSerialProcessor.remove(processor5x)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
      outputVelocities should contain theSameElementsAs Seq(6)

      processor5x.transmitter.receiver shouldBe empty
    }

  it should "do nothing if the processor to be removed is not found" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // When
    midiSerialProcessor.remove(processor3x)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x)
    processedVelocities should contain theSameElementsAs Seq((2, 2))
    outputVelocities should contain theSameElementsAs Seq(2)
  }

  behavior of "removeAt"

  it should "delete a processor in the middle of the chain" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))
    processor3x.transmitter.receiver should not be empty

    // When
    midiSerialProcessor.removeAt(1)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (5, 10))
    outputVelocities should contain theSameElementsAs Seq(10)

    processor3x.transmitter.receiver shouldBe empty
  }

  it should "delete the last processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))
    processor5x.transmitter.receiver should not be empty

    // When
    midiSerialProcessor.removeAt(2)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
    outputVelocities should contain theSameElementsAs Seq(6)

    processor5x.transmitter.receiver shouldBe empty
  }

  it should "delete the last processor before setting an output receiver" in
    new Fixture(shouldSetOutputReceiverOnSend = true) {
      // Given
      override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
        Seq(processor2x, processor3x, processor5x), None)

      // When
      midiSerialProcessor.removeAt(2)
      send(1)

      // Then
      midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x, processor3x)
      processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6))
      outputVelocities should contain theSameElementsAs Seq(6)

      processor5x.transmitter.receiver shouldBe empty
    }

  it should "do nothing if the index exceeds the upper bounds" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // When
    midiSerialProcessor.removeAt(1)
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq(processor2x)
    processedVelocities should contain theSameElementsAs Seq((2, 2))
    outputVelocities should contain theSameElementsAs Seq(2)
  }

  it should "fail if the index is negative" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some
      (outputReceiver))

    // Then
    assertThrows[IllegalArgumentException] {
      midiSerialProcessor.removeAt(-1)
    }
  }

  behavior of "clear"

  it should "remove all processors" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x, processor5x),
      Some(outputReceiver))

    // When
    midiSerialProcessor.clear()
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq.empty
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)

    processor2x.transmitter.receiver shouldBe empty
    processor5x.transmitter.receiver shouldBe empty
  }

  it should "remove all processors before setting an output receiver" in new Fixture(shouldSetOutputReceiverOnSend =
    true) {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x, processor5x), None)

    // When
    midiSerialProcessor.clear()
    send(1)

    // Then
    midiSerialProcessor.processors should contain theSameElementsAs Seq.empty
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)

    processor2x.transmitter.receiver shouldBe empty
    processor5x.transmitter.receiver shouldBe empty
  }

  behavior of "size"

  it should "tell that there is no processor" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, None)

    // Then
    midiSerialProcessor.size shouldBe 0
  }

  it should "tell how many processors are in the chain" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x, processor5x),
      Some(outputReceiver))

    // Then
    midiSerialProcessor.size shouldBe 2
  }

  behavior of "close"

  it should "clear the receiver of transmitters of all processors" in new Fixture {
    // Given
    val processors: Seq[TestMidiProcessor] = Seq(processor2x, processor5x)
    override val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(processors, Some(outputReceiver))
    processors.foreach(_.transmitter.receiver should not be empty)

    // When
    midiSerialProcessor.close()

    // Then
    processors.foreach(_.transmitter.receiver should be(empty))
  }
}

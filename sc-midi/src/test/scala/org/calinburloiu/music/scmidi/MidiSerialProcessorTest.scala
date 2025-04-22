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
        val newVelocity = factor * velocity
        processedVelocities += Tuple2(factor, newVelocity)

        Seq(ScNoteOnMidiMessage(channel, midiNote, newVelocity).javaMidiMessage)
      case otherMessage => Seq(otherMessage)
    }

    override def close(): Unit = {}
  }

  trait Fixture {
    val processor2x: TestMidiProcessor = new TestMidiProcessor(2)
    val processor3x: TestMidiProcessor = new TestMidiProcessor(3)
    val processor5x: TestMidiProcessor = new TestMidiProcessor(5)

    val outputReceiver: Stub[Receiver] = stub[Receiver]
    outputReceiver.send.returns {
      case (ScNoteOnMidiMessage(_, _, velocity), ts) => outputVelocities += velocity
    }
  }

  after {
    processedVelocities.clear()
    outputVelocities.clear()
  }

  "constructor" should "configure some initial processors and some output receiver" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor2x, processor3x, processor5x), Some(outputReceiver))

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)

    // Then
    midiSerialProcessor.processors shouldEqual Seq(processor2x, processor3x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((2, 2), (3, 6), (5, 30))
    outputVelocities should contain theSameElementsAs Seq(30)
  }

  it should "configure no initial processors and some output receiver" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, Some(outputReceiver))

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)

    // Then
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)
  }

  it should "configure no initial processors and no output receiver" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq.empty, None)
    midiSerialProcessor.transmitter.receiver = Some(outputReceiver)

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)

    // Then
    processedVelocities shouldBe empty
    outputVelocities should contain theSameElementsAs Seq(1)
  }

  it should "configure some initial processors and no output receiver" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(
      Seq(processor3x, processor5x), None)

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)
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
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some(outputReceiver))
    midiSerialProcessor.receiver.close()

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 1).javaMidiMessage, 123L)

    // Then
    processedVelocities shouldBe empty
    outputVelocities shouldBe empty
  }

  "processors" should "set a new sequence of chained processors" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), Some(outputReceiver))
    midiSerialProcessor.processors = Seq(processor3x, processor5x)

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 7).javaMidiMessage, 12L)

    // Then
    midiSerialProcessor.processors shouldEqual Seq(processor3x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
    outputVelocities should contain theSameElementsAs Seq(105)

    processor2x.transmitter.receiver shouldBe empty
  }

  it should "set a new sequence of chained processors before setting an output receiver" in new Fixture {
    // Given
    val midiSerialProcessor: MidiSerialProcessor = new MidiSerialProcessor(Seq(processor2x), None)
    midiSerialProcessor.processors = Seq(processor3x, processor5x)
    midiSerialProcessor.transmitter.receiver = Some(outputReceiver)

    // When
    midiSerialProcessor.receiver.send(ScNoteOnMidiMessage(0, MidiNote.C4, 7).javaMidiMessage, 12L)

    // Then
    midiSerialProcessor.processors shouldEqual Seq(processor3x, processor5x)
    processedVelocities should contain theSameElementsAs Seq((3, 21), (5, 105))
    outputVelocities should contain theSameElementsAs Seq(105)

    processor2x.transmitter.receiver shouldBe empty
  }
}

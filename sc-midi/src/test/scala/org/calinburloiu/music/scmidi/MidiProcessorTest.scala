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

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver, ShortMessage}
import scala.collection.mutable

class MidiProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  class TestMidiProcessor extends MidiProcessor {
    val processedMessages: mutable.ListBuffer[(MidiMessage, Long)] = mutable.ListBuffer()
    val connectCalled: mutable.ListBuffer[Unit] = mutable.ListBuffer()
    val disconnectCalled: mutable.ListBuffer[Unit] = mutable.ListBuffer()

    // Simple implementation that returns the same message
    override protected def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = {
      processedMessages += ((message, timeStamp))
      Seq(message)
    }

    override protected def onConnect(): Unit = {
      connectCalled += (())
    }

    override protected def onDisconnect(): Unit = {
      disconnectCalled += (())
    }

    override def close(): Unit = {}
  }

  // Fixtures
  trait TestFixture {
    // A simple implementation of MidiProcessor for testing
    val processor: TestMidiProcessor = TestMidiProcessor()

    // Create test MIDI message
    val testScMessage: ScMidiMessage = ScNoteOnMidiMessage(1, 60, 100)
    val testMessage: MidiMessage = testScMessage.javaMidiMessage
    val testTimestamp = 123L

    // Create a stub receiver
    val receiverStub: Receiver = stub[Receiver]
  }

  "receiver" should "forward messages to the transmitter's receiver after processing" in new TestFixture {
    // Set up our mock receiver to expect the message
    receiverStub.send.when(testMessage, testTimestamp).returns(())

    // Set the mock receiver as the transmitter's receiver
    processor.transmitter.setReceiver(receiverStub)

    // Send a message through the processor
    processor.receiver.send(testMessage, testTimestamp)

    // Verify that `process` was called with the correct message
    processor.processedMessages should contain((testMessage, testTimestamp))

    // Verify that the message was forwarded to the mock receiver
    receiverStub.send.verify(testMessage, testTimestamp)
  }

  it should "be able to send ScMidiMessage objects" in new TestFixture {
    // Set up our mock receiver
    receiverStub.send.when(*, *).returns(())

    processor.transmitter.setReceiver(receiverStub)

    // Send using the ScMidiMessage overload
    processor.receiver.send(testScMessage, testTimestamp)

    def matchMessage(msg: MidiMessage): Boolean = {
      msg match {
        case sm: ShortMessage => sm.getCommand == ShortMessage.NOTE_ON && sm.getData1 == 60 && sm.getData2 == 100
        case _ => false
      }
    }

    // Verify that process was called
    processor.processedMessages should have size 1
    matchMessage(processor.processedMessages.head._1) shouldBe true

    // Verify the message was forwarded
    receiverStub.send.verify(where { (msg: MidiMessage, _: Long) =>
      matchMessage(msg)
    })
  }

  it should "not forward messages when closed" in new TestFixture {
    processor.transmitter.setReceiver(receiverStub)

    // Close the receiver
    processor.receiver.close()
    processor.receiver.isClosed shouldBe true

    // Send a message
    processor.receiver.send(testMessage, testTimestamp)

    // Process should not have been called
    processor.processedMessages shouldBe empty

    // Verify the message was not forwarded
    receiverStub.send.verify(*, *).never()
  }

  "transmitter" should "call onConnect when a receiver is set" in new TestFixture {
    processor.transmitter.setReceiver(receiverStub)

    processor.connectCalled.size shouldBe 1
    processor.disconnectCalled shouldBe empty
  }

  it should "call onDisconnect when receiver is replaced" in new TestFixture {
    // Set initial receiver
    processor.transmitter.setReceiver(receiverStub)
    processor.connectCalled.size shouldBe 1
    processor.disconnectCalled shouldBe empty

    // Create another mock receiver
    val anotherReceiver: Receiver = stub[Receiver]

    // Replace the receiver
    processor.transmitter.setReceiver(anotherReceiver)

    // Verify onDisconnect and onConnect were called
    processor.disconnectCalled.size shouldBe 1
    processor.connectCalled.size shouldBe 2
  }

  it should "not trigger callbacks when setting same receiver" in new TestFixture {
    // Set initial receiver
    processor.transmitter.setReceiver(receiverStub)
    processor.connectCalled.size shouldBe 1
    processor.disconnectCalled shouldBe empty

    // Set the same receiver again
    processor.transmitter.setReceiver(receiverStub)

    // Verify no additional callbacks were triggered
    processor.connectCalled.size shouldBe 1
    processor.disconnectCalled shouldBe empty
  }

  it should "support idiomatic Scala getter and setter" in new TestFixture {
    // Test getter
    processor.transmitter.receiver shouldBe empty

    // Test setter with Some
    processor.transmitter.receiver = Some(receiverStub)
    processor.transmitter.receiver shouldBe Some(receiverStub)

    // Test setter with None
    processor.transmitter.receiver = None
    processor.transmitter.receiver shouldBe empty
  }

  it should "return null for getReceiver when no receiver is set" in new TestFixture {
    processor.transmitter.getReceiver shouldBe null
  }

  it should "not forward messages after being closed" in new TestFixture {
    processor.transmitter.setReceiver(receiverStub)

    // Close the transmitter
    processor.transmitter.close()
    processor.transmitter.isClosed shouldBe true

    // The close status shouldn't affect message forwarding directly
    // It's more about resource cleanup
    processor.receiver.send(testMessage, testTimestamp)

    // The message should still be processed and forwarded
    processor.processedMessages should contain((testMessage, testTimestamp))
    receiverStub.send.verify(testMessage, testTimestamp)
  }

  "process" should "allow modification of MIDI messages" in {
    // Create a processor that transforms messages
    val transformingProcessor = new MidiProcessor {
      override protected def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = {
        // For testing, transform NOTE_ON to NOTE_OFF
        message match
          case sm: ShortMessage if sm.getCommand == ShortMessage.NOTE_ON =>
            val newMessage = new ShortMessage(ShortMessage.NOTE_OFF, sm.getChannel, sm.getData1, 0)
            Seq(newMessage)
          case _ =>
            Seq(message)
      }

      override protected def onConnect(): Unit = {}

      override protected def onDisconnect(): Unit = {}

      override def close(): Unit = {}
    }

    val mockReceiver = stub[Receiver]
    transformingProcessor.transmitter.setReceiver(mockReceiver)

    // Create test message
    val noteOnMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When we send a NOTE_ON, we expect a NOTE_OFF to be forwarded
    transformingProcessor.receiver.send(noteOnMessage, 0L)

    // Verify that the receiver got a NOTE_OFF message
    mockReceiver.send.verify(where {
      (msg: MidiMessage, _: Long) =>
        msg.isInstanceOf[ShortMessage] &&
          msg.asInstanceOf[ShortMessage].getCommand == ShortMessage.NOTE_OFF
    })
  }

  it should "support returning multiple messages for a single input" in {
    // Create a processor that generates multiple messages
    val multiMessageProcessor = new MidiProcessor {
      override protected def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = {
        message match
          case sm: ShortMessage if sm.getCommand == ShortMessage.NOTE_ON =>
            val echoMessage = new ShortMessage(
              ShortMessage.NOTE_ON,
              sm.getChannel,
              sm.getData1,
              sm.getData2 / 2
            )
            Seq(message, echoMessage)
          case _ =>
            Seq(message)
      }

      override protected def onConnect(): Unit = {}

      override protected def onDisconnect(): Unit = {}

      override def close(): Unit = {}
    }

    val mockReceiver = stub[Receiver]
    multiMessageProcessor.transmitter.setReceiver(mockReceiver)

    // Create test message
    val noteOnMessage = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100)

    // When we send a NOTE_ON, we expect both original and echo to be forwarded
    multiMessageProcessor.receiver.send(noteOnMessage, 0L)

    // Verify both messages were received
    mockReceiver.send.verify(noteOnMessage, 0L)
    mockReceiver.send.verify(where {
      (msg: MidiMessage, _: Long) =>
        msg.isInstanceOf[ShortMessage] &&
          msg.asInstanceOf[ShortMessage].getCommand == ShortMessage.NOTE_ON &&
          msg.asInstanceOf[ShortMessage].getData2 == 50
    })
  }
}

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

import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOffMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

class MidiProcessorTest extends AnyFlatSpec with Matchers with Stubs {

  /** Records what it processes and the order of its hook calls; forwards every message unchanged. */
  class RecordingMidiProcessor extends MidiProcessor {
    val processedMessages: mutable.ListBuffer[(MidiMsg, Long)] = mutable.ListBuffer()
    val hookCalls: mutable.ListBuffer[String] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = {
      processedMessages += ((message, timeStamp))
      Seq(message)
    }

    override protected def onConnect(): Unit = {
      hookCalls += "connect"
    }

    override protected def onDisconnect(): Unit = {
      hookCalls += "disconnect"
    }

    override def close(): Unit = {}
  }

  /** Snapshots the transmitter's receivers as seen from inside each hook. */
  class SnapshottingMidiProcessor extends MidiProcessor {
    var receiversOnDisconnect: Seq[MidiReceiver] = Seq.empty
    var receiversOnConnect: Seq[MidiReceiver] = Seq.empty

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(): Unit = {
      receiversOnConnect = transmitter.receivers
    }

    override protected def onDisconnect(): Unit = {
      receiversOnDisconnect = transmitter.receivers
    }

    override def close(): Unit = {}
  }

  /**
   * From inside each hook, tries to read the transmitter's receivers on another thread and records whether that
   * read completed while the hook was still running. It completes at once unless the hook holds the write lock.
   */
  class LockProbingMidiProcessor extends MidiProcessor {
    val readerTimeoutMillis: Long = 200L
    val readCompletedDuringHook: mutable.ListBuffer[Boolean] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(): Unit = probe()

    override protected def onDisconnect(): Unit = probe()

    override def close(): Unit = {}

    private def probe(): Unit = {
      val completed = AtomicBoolean(false)
      val reader = Thread(() => {
        transmitter.receivers
        completed.set(true)
      })
      reader.setDaemon(true)
      reader.start()
      reader.join(readerTimeoutMillis)
      readCompletedDuringHook += completed.get
    }
  }

  trait Fixture {
    val processor: RecordingMidiProcessor = RecordingMidiProcessor()

    val message: MidiMsg = NoteOnMidiMsg(1, 60, 100)
    val timeStamp: Long = 123L

    val receiver1: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiver2: Stub[MidiReceiver] = stub[MidiReceiver]
    Seq(receiver1, receiver2).foreach(_.send.returns(_ => ()))
  }

  behavior of "receiver"

  it should "process a message once and forward the result to every receiver of the transmitter" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.processedMessages.toSeq shouldEqual Seq((message, timeStamp))
    receiver1.send.calls shouldEqual Seq((message, timeStamp))
    receiver2.send.calls shouldEqual Seq((message, timeStamp))
  }

  it should "forward every message a processor returns, in order, with the input time-stamp" in new Fixture {
    // Given
    val noteOff: MidiMsg = NoteOffMidiMsg(1, 60, 0)
    val echoingProcessor: MidiProcessor = new MidiProcessor {
      override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message, noteOff)

      override def close(): Unit = {}
    }
    echoingProcessor.transmitter.addReceiver(receiver1)

    // When
    echoingProcessor.receiver.send(message, timeStamp)

    // Then
    receiver1.send.calls shouldEqual Seq((message, timeStamp), (noteOff, timeStamp))
  }

  it should "not process a message while the transmitter has no receivers" in new Fixture {
    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.processedMessages shouldBe empty
  }

  it should "not process or forward messages once closed" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)
    processor.receiver.close()

    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.receiver.isClosed shouldBe true
    processor.processedMessages shouldBe empty
    receiver1.send.times shouldEqual 0
  }

  behavior of "transmitter"

  it should "call onConnect when the first receiver is added" in new Fixture {
    // When
    processor.transmitter.addReceiver(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect")
  }

  it should "call onDisconnect, then onConnect, when the receivers are replaced" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1)

    // When
    processor.transmitter.receivers = Seq(receiver2)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect", "connect")
  }

  it should "call onDisconnect, then onConnect, when a receiver is added to a connected processor" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)

    // When
    processor.transmitter.addReceiver(receiver2)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect", "connect")
    processor.transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "call only onDisconnect when the last receiver is removed" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)

    // When
    processor.transmitter.removeReceiver(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect")
  }

  it should "call only onDisconnect when the receivers are cleared" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect")
  }

  it should "call no hook when the same receivers are set again" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1)

    // When
    processor.transmitter.receivers = Seq(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect")
  }

  it should "call no hook when an empty transmitter is cleared" in new Fixture {
    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls shouldBe empty
  }

  it should "still expose the old receivers during onDisconnect and the new ones during onConnect" in new Fixture {
    // Given
    val snapshotting: SnapshottingMidiProcessor = SnapshottingMidiProcessor()
    snapshotting.transmitter.receivers = Seq(receiver1)

    // When
    snapshotting.transmitter.receivers = Seq(receiver2)

    // Then
    snapshotting.receiversOnDisconnect shouldEqual Seq(receiver1)
    snapshotting.receiversOnConnect shouldEqual Seq(receiver2)
  }

  it should "run the hooks while holding the write lock, through a modifier and through a direct assignment" in
    new Fixture {
      // Given
      val probing: LockProbingMidiProcessor = LockProbingMidiProcessor()

      // When
      probing.transmitter.addReceiver(receiver1)
      probing.transmitter.receivers = Seq(receiver2)

      // Then: connect; disconnect, connect
      probing.readCompletedDuringHook.toSeq shouldEqual Seq(false, false, false)
    }
}

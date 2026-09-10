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

  /**
   * Records what it processes and the receivers passed to each hook call, in order; forwards every message as is.
   *
   * The two membership hooks and the sequence hook are recorded separately, so that a test of one is not disturbed by
   * the other; [[OrderingMidiProcessor]] is the one that records all three in a single log.
   */
  class RecordingMidiProcessor extends MidiProcessor {
    val processedMessages: mutable.ListBuffer[(MidiMsg, Long)] = mutable.ListBuffer()
    val hookCalls: mutable.ListBuffer[(String, Seq[MidiReceiver])] = mutable.ListBuffer()
    val receiversChangedCalls: mutable.ListBuffer[Seq[MidiReceiver]] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = {
      processedMessages += ((message, timeStamp))
      Seq(message)
    }

    override protected def onConnect(receivers: Seq[MidiReceiver]): Unit = {
      hookCalls += (("connect", receivers))
    }

    override protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = {
      hookCalls += (("disconnect", receivers))
    }

    override protected def onReceiversChanged(receivers: Seq[MidiReceiver]): Unit = {
      receiversChangedCalls += receivers
    }
  }

  /** Records the name of every hook called, in one log, so that their relative order can be asserted. */
  class OrderingMidiProcessor extends MidiProcessor {
    val hookNames: mutable.ListBuffer[String] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(receivers: Seq[MidiReceiver]): Unit = hookNames += "connect"

    override protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = hookNames += "disconnect"

    override protected def onReceiversChanged(receivers: Seq[MidiReceiver]): Unit = hookNames += "changed"
  }

  /** Snapshots the transmitter's receivers as seen from inside each hook. */
  class SnapshottingMidiProcessor extends MidiProcessor {
    var receiversOnDisconnect: Seq[MidiReceiver] = Seq.empty
    var receiversOnConnect: Seq[MidiReceiver] = Seq.empty

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(receivers: Seq[MidiReceiver]): Unit = {
      receiversOnConnect = transmitter.receivers
    }

    override protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = {
      receiversOnDisconnect = transmitter.receivers
    }
  }

  /**
   * From inside each hook, tries to read the transmitter's receivers on another thread and records whether that
   * read completed while the hook was still running. It completes at once unless the hook holds the write lock.
   */
  class LockProbingMidiProcessor extends MidiProcessor {
    val readerTimeoutMillis: Long = 200L
    val readCompletedDuringHook: mutable.ListBuffer[Boolean] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(receivers: Seq[MidiReceiver]): Unit = probe()

    override protected def onDisconnect(receivers: Seq[MidiReceiver]): Unit = probe()

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

  behavior of "transmitter"

  it should "call onConnect with the receiver when the first receiver is added" in new Fixture {
    // When
    processor.transmitter.addReceiver(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1)))
  }

  it should "call onDisconnect with the old receiver, then onConnect with the new one, when the receivers are " +
    "fully replaced" in new Fixture {
      // Given
      processor.transmitter.receivers = Seq(receiver1)

      // When
      processor.transmitter.receivers = Seq(receiver2)

      // Then
      processor.hookCalls.toSeq shouldEqual Seq(
        ("connect", Seq(receiver1)),
        ("disconnect", Seq(receiver1)),
        ("connect", Seq(receiver2))
      )
    }

  it should "call only onConnect, with the receiver being added, when a receiver is added to a connected " +
    "processor" in new Fixture {
      // Given
      processor.transmitter.addReceiver(receiver1)

      // When
      processor.transmitter.addReceiver(receiver2)

      // Then
      processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1)), ("connect", Seq(receiver2)))
      processor.transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

  it should "call only onDisconnect, with the receiver being removed, when the last receiver is removed" in
    new Fixture {
      // Given
      processor.transmitter.addReceiver(receiver1)

      // When
      processor.transmitter.removeReceiver(receiver1)

      // Then
      processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1)), ("disconnect", Seq(receiver1)))
    }

  it should "call only onDisconnect, with one receiver that remains untouched, when one of two receivers is " +
    "removed" in new Fixture {
      // Given
      processor.transmitter.receivers = Seq(receiver1, receiver2)

      // When
      processor.transmitter.removeReceiver(receiver1)

      // Then
      processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1, receiver2)), ("disconnect", Seq(receiver1)))
    }

  it should "call only onDisconnect with every receiver when the receivers are cleared" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls.toSeq shouldEqual Seq(
      ("connect", Seq(receiver1, receiver2)),
      ("disconnect", Seq(receiver1, receiver2))
    )
  }

  it should "call no hook when the same receivers are set again" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1)

    // When
    processor.transmitter.receivers = Seq(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1)))
  }

  it should "call no hook when an empty transmitter is cleared" in new Fixture {
    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls shouldBe empty
    processor.receiversChangedCalls shouldBe empty
  }

  it should "call onReceiversChanged with the whole new sequence on every change" in new Fixture {
    // When
    processor.transmitter.addReceiver(receiver1)
    processor.transmitter.addReceiver(receiver2)
    processor.transmitter.removeReceiver(receiver1)
    processor.transmitter.clearReceivers()

    // Then
    processor.receiversChangedCalls.toSeq shouldEqual Seq(
      Seq(receiver1),
      Seq(receiver1, receiver2),
      Seq(receiver2),
      Seq.empty
    )
  }

  it should "call only onReceiversChanged when the receivers are reordered" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.transmitter.receivers = Seq(receiver2, receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq(("connect", Seq(receiver1, receiver2)))
    processor.receiversChangedCalls.toSeq shouldEqual Seq(Seq(receiver1, receiver2), Seq(receiver2, receiver1))
  }

  it should "call onReceiversChanged after onDisconnect and onConnect" in new Fixture {
    // Given
    val ordering: OrderingMidiProcessor = OrderingMidiProcessor()
    ordering.transmitter.receivers = Seq(receiver1)

    // When
    ordering.transmitter.receivers = Seq(receiver2)

    // Then
    ordering.hookNames.toSeq shouldEqual Seq("connect", "changed", "disconnect", "connect", "changed")
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

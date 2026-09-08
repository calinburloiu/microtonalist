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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class ConcurrentMidiTransmitterTest extends AnyFlatSpec with Matchers with MutableMidiTransmitterBehaviors {

  /**
   * Records, for every call of `receivers_=`, whether the calling thread held the write lock at that moment. A
   * subclass overriding the setter (as `MidiProcessorTransmitter` will) relies on this being `true` for every change
   * that arrives through a modifier. Not `private`: a fixture exposes a value of this type. The two `lock…` accessors
   * let a test look at the `protected` lock without reaching into it from outside the class.
   */
  class WriteLockProbingTransmitter(initialReceivers: Seq[MidiReceiver] = Seq.empty)
    extends ConcurrentMidiTransmitter(initialReceivers) {

    val writeLockHeldOnSet: mutable.ListBuffer[Boolean] = mutable.ListBuffer()

    override def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = {
      writeLockHeldOnSet += lock.isWriteLockedByCurrentThread
      super.receivers_=(newReceivers)
    }

    def lockIsWriteLocked: Boolean = lock.isWriteLocked

    def lockReadLockCount: Int = lock.getReadLockCount
  }

  trait ProbeFixture {
    val receiver1: MidiReceiver = NoOpMidiReceiver()
    val receiver2: MidiReceiver = NoOpMidiReceiver()

    val probe: WriteLockProbingTransmitter = WriteLockProbingTransmitter()
  }

  /**
   * Fixture for the multi-thread test: `writerCount` writer threads each own `receiversPerWriter` distinct receivers,
   * add them all, then remove the first half; `readerCount` reader threads read snapshots in a loop meanwhile. All
   * threads start on one latch so that they overlap, and any exception thrown on a thread is collected and asserted on.
   */
  trait ConcurrencyFixture {
    val writerCount: Int = 8
    val receiversPerWriter: Int = 250
    val readerCount: Int = 4

    val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
    val receiversByWriter: Seq[Seq[MidiReceiver]] =
      Seq.fill(writerCount)(Seq.fill(receiversPerWriter)(NoOpMidiReceiver()))
    val expectedFinalReceivers: Seq[MidiReceiver] = receiversByWriter.flatMap(_.drop(receiversPerWriter / 2))

    val start: CountDownLatch = CountDownLatch(1)
    val readersRunning: AtomicBoolean = AtomicBoolean(true)
    val failures: ConcurrentLinkedQueue[Throwable] = ConcurrentLinkedQueue()

    def startThread(body: => Unit): Thread = {
      // Typed as Runnable explicitly: the catch branch returns a Boolean, so an untyped lambda would not SAM-convert.
      val runnable: Runnable = () => {
        start.await()
        try {
          body
        } catch {
          case NonFatal(e) => failures.add(e)
        }
      }
      val thread = Thread(runnable)
      thread.start()
      thread
    }

    val writers: Seq[Thread] = receiversByWriter.map { ownReceivers =>
      startThread {
        ownReceivers.foreach(transmitter.addReceiver)
        ownReceivers.take(receiversPerWriter / 2).foreach(transmitter.removeReceiver)
      }
    }

    val readers: Seq[Thread] = Seq.fill(readerCount) {
      startThread {
        while (readersRunning.get()) {
          val snapshot = transmitter.receivers
          snapshot.size should be <= writerCount * receiversPerWriter
          snapshot.distinct.size shouldEqual snapshot.size
        }
      }
    }

    def runAll(): Unit = {
      start.countDown()
      writers.foreach(_.join(30000L))
      readersRunning.set(false)
      readers.foreach(_.join(30000L))
    }
  }

  behavior of "ConcurrentMidiTransmitter"

  it should behave like mutableMidiTransmitter(initialReceivers => ConcurrentMidiTransmitter(initialReceivers))

  it should "not call receivers_= from its constructor" in {
    // Given
    val receiver = NoOpMidiReceiver()

    // When
    val probe = WriteLockProbingTransmitter(Seq(receiver))

    // Then
    probe.writeLockHeldOnSet shouldBe empty
    probe.receivers shouldEqual Seq(receiver)
  }

  it should "reach receivers_= from every modifier while holding the write lock" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)
    probe.addReceivers(Seq(receiver2))
    probe.removeReceiver(receiver1)
    probe.clearReceivers()

    // Then
    probe.writeLockHeldOnSet.toSeq shouldEqual Seq(true, true, true, true)
    probe.receivers shouldBe empty
  }

  it should "release the write lock after a modifier returns" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)

    // Then
    probe.lockIsWriteLocked shouldBe false
    probe.lockReadLockCount shouldEqual 0
  }

  it should "not lose updates when several threads add and remove receivers while others read" in
    new ConcurrencyFixture {
      // When
      runAll()

      // Then
      writers.map(_.isAlive) should contain only false
      readers.map(_.isAlive) should contain only false
      failures.asScala shouldBe empty
      // The size check comes first so that a lost update fails with a readable count, not a dump of 1000 receivers.
      transmitter.receivers should have size expectedFinalReceivers.size
      transmitter.receivers should contain theSameElementsAs expectedFinalReceivers
    }
}

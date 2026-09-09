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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class ConcurrentMidiTransmitterTest extends AnyFlatSpec with Matchers with MutableMidiTransmitterBehaviors {

  /**
   * Records, for every call of `receivers_=`, whether the calling thread held the write lock at that moment. A
   * subclass overriding the setter (as `MidiProcessorTransmitter` will) relies on this being `true` for every change
   * that arrives through a modifier. Not `private`: a fixture exposes a value of this type. The two `lock…` accessors
   * let a test look at the `protected` lock without reaching into it from outside the class.
   *
   * This override does not wrap its body in `withWriteLock`, which is safe here only because it is exercised solely
   * through modifiers, which already hold the lock by the time they reach it. A **production** subclass overriding
   * `receivers_=` must wrap its own body in `withWriteLock`, because a direct assignment reaches the override before
   * `ConcurrentMidiTransmitter` takes the lock.
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

    def lockHasQueuedThreads: Boolean = lock.hasQueuedThreads

    /** Runs `body` on the calling thread while holding the write lock, so that a test can block a reader out. */
    def holdingWriteLock[R](body: => R): R = withWriteLock {
      body
    }
  }

  /**
   * Polls `condition` until it holds or `timeoutMillis` expires, and returns whether it held. Used instead of a fixed
   * sleep, so that the assertion is decided by the state under test rather than by how fast the machine is.
   */
  private def awaitCondition(condition: => Boolean, timeoutMillis: Long = 30000L): Boolean = {
    val deadlineNanos = System.nanoTime() + timeoutMillis * 1000000L
    while (!condition && System.nanoTime() < deadlineNanos) {
      Thread.sleep(1)
    }
    condition
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
   *
   * Reading is a checked precondition, not a hope. `readersRunning` is only cleared once every reader has completed a
   * first snapshot, and `runAll` records in `allReadersRead` whether that happened within `joinTimeoutMillis`, for the
   * test to assert on. Without that barrier a reader starved until after the writers finished would find the flag
   * already `false`, never enter its loop, and silently reduce this to a writers-only test that still passes green.
   * The barrier pins that every reader read during the run; it does not, and cannot without distorting the timings
   * under test, pin that any individual read interleaved with an individual write.
   */
  trait ConcurrencyFixture {
    val writerCount: Int = 8
    val receiversPerWriter: Int = 250
    val readerCount: Int = 4
    val joinTimeoutMillis: Long = 30000L

    val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
    val receiversByWriter: Seq[Seq[MidiReceiver]] =
      Seq.fill(writerCount)(Seq.fill(receiversPerWriter)(NoOpMidiReceiver()))
    val expectedFinalReceivers: Seq[MidiReceiver] = receiversByWriter.flatMap(_.drop(receiversPerWriter / 2))

    val start: CountDownLatch = CountDownLatch(1)
    val readersRunning: AtomicBoolean = AtomicBoolean(true)
    /** Counted down by each reader once it has completed its first snapshot, successfully or not. */
    val firstReads: CountDownLatch = CountDownLatch(readerCount)
    /** Whether every reader reached its first snapshot before `runAll` stopped the readers. */
    val allReadersRead: AtomicBoolean = AtomicBoolean(false)
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
      thread.setDaemon(true)
      thread.start()
      thread
    }

    val writers: Seq[Thread] = receiversByWriter.map { ownReceivers =>
      startThread {
        ownReceivers.foreach(transmitter.addReceiver)
        ownReceivers.take(receiversPerWriter / 2).foreach(transmitter.removeReceiver)
      }
    }

    def readSnapshot(): Unit = {
      val snapshot = transmitter.receivers
      snapshot.size should be <= writerCount * receiversPerWriter
      snapshot.distinct.size shouldEqual snapshot.size
    }

    val readers: Seq[Thread] = Seq.fill(readerCount) {
      startThread {
        // Counting down in a `finally` keeps a reader whose first snapshot fails from stalling `runAll` for the whole
        // timeout: the failure it threw is collected in `failures` and asserted on there instead.
        try {
          readSnapshot()
        } finally {
          firstReads.countDown()
        }

        while (readersRunning.get()) {
          readSnapshot()
        }
      }
    }

    def runAll(): Unit = {
      start.countDown()
      // Awaited before the readers are stopped, so that a reader starved past the end of the run fails the test
      // instead of silently reducing it to a writers-only one.
      allReadersRead.set(firstReads.await(joinTimeoutMillis, TimeUnit.MILLISECONDS))
      writers.foreach(_.join(joinTimeoutMillis))
      readersRunning.set(false)
      readers.foreach(_.join(joinTimeoutMillis))
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

  it should "reach a subclass receivers_= outside the write lock on a direct assignment" in new ProbeFixture {
    // When
    probe.receivers = Seq(receiver1)

    // Then
    probe.writeLockHeldOnSet.toSeq shouldEqual Seq(false)
    probe.receivers shouldEqual Seq(receiver1)
  }

  it should "release the write lock after a modifier returns" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)

    // Then
    probe.lockIsWriteLocked shouldBe false
  }

  it should "release the read lock after a read returns" in new ProbeFixture {
    // Given
    probe.addReceiver(receiver1)

    // When
    probe.receivers shouldEqual Seq(receiver1)

    // Then
    probe.lockReadLockCount shouldEqual 0
  }

  it should "block a read while another thread holds the write lock" in new ProbeFixture {
    // Given
    probe.addReceiver(receiver1)
    val readDone: CountDownLatch = CountDownLatch(1)
    val readReceivers: AtomicReference[Seq[MidiReceiver]] = AtomicReference(Seq.empty)
    val reader: Thread = Thread(() => {
      readReceivers.set(probe.receivers)
      readDone.countDown()
    })
    reader.setDaemon(true)

    // When
    // The reader parks in the lock's queue rather than returning, which is what pins that `receivers` takes the read
    // lock: without it the read would complete straight away and never queue.
    val blockedOnTheLock: Boolean = probe.holdingWriteLock {
      reader.start()
      awaitCondition(probe.lockHasQueuedThreads) && readDone.getCount == 1
    }

    // Then
    blockedOnTheLock shouldBe true
    readDone.await(30000L, TimeUnit.MILLISECONDS) shouldBe true
    readReceivers.get() shouldEqual Seq(receiver1)
  }

  it should "not lose updates when several threads add and remove receivers while others read" in
    new ConcurrencyFixture {
      // When
      runAll()

      // Then
      failures.asScala shouldBe empty
      allReadersRead.get() shouldBe true
      writers.map(_.isAlive) should contain only false
      readers.map(_.isAlive) should contain only false
      // The size check comes first so that a lost update fails with a readable count, not a dump of 1000 receivers.
      transmitter.receivers should have size expectedFinalReceivers.size
      transmitter.receivers should contain theSameElementsAs expectedFinalReceivers
    }
}

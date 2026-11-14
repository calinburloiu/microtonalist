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

package org.calinburloiu.music.microtonalist.messaging.pekko

import ch.qos.logback.classic.Level
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.failuresAt
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, MessagingRuntime, Urgent}
import org.calinburloiu.music.microtonalist.messaging.pekko.PekkoTestSupport.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CompletableFuture, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Tests [[PekkoMessagingRuntime]] on one shared test kit.
 */
class PekkoMessagingRuntimeTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import PekkoMessagingRuntimeTest.*

  private val testKit: ActorTestKit = PekkoTestSupport.startTestKit("PekkoMessagingRuntimeTest")
  private val runtime: PekkoMessagingRuntime = PekkoMessagingRuntime(testKit.system)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /**
   * A case's mailbox, whose handler records each [[Probe]] it runs, with its thread, in [[runs]].
   *
   * @param start starts the mailbox with the given handler
   */
  abstract class RecordingFixture(start: (Message => Unit) => Mailbox[Message]) {
    /** The name of each probe run, with the thread it ran on, in the order run. */
    val runs: LinkedBlockingQueue[(String, Thread)] = LinkedBlockingQueue()

    /** The mailbox under test. */
    val mailbox: Mailbox[Message] = start {
      case Probe(probeName) => runs.put(probeName -> Thread.currentThread())
      case UrgentProbe(probeName) => runs.put(probeName -> Thread.currentThread())
      case CheckThread(result) => result.complete(mailbox.isCurrentThread)
      case Fail(text) => throw IllegalStateException(text)
      case Gate(entered, release) =>
        entered.countDown()
        release.await(Timeout.toMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Occupies the mailbox's thread with a [[Gate]], once it has entered it, so that later messages queue up.
     *
     * @return the latch that frees the thread
     */
    def hold(): CountDownLatch = {
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      mailbox.enqueue(Gate(entered, release))
      entered.await(Timeout.toMillis, TimeUnit.MILLISECONDS) shouldBe true
      release
    }

    /** Takes the next run from [[runs]], waiting up to [[PekkoTestSupport.Timeout]]; fails if none comes. */
    def nextRun(): (String, Thread) =
      Option(runs.poll(Timeout.toMillis, TimeUnit.MILLISECONDS)).getOrElse(fail(s"No message ran within $Timeout."))
  }

  /**
   * A case's pinned mailbox, which records as [[RecordingFixture]] does.
   *
   * @param name the name of the mailbox
   * @param priority the priority of the mailbox's thread
   */
  class Fixture(name: String = "Pinned", priority: Int = Thread.NORM_PRIORITY)
    extends RecordingFixture(handler => runtime.startMailbox[Message](name, priority)(handler))

  /**
   * A case's UI mailbox, which records as [[RecordingFixture]] does.
   */
  class UiFixture extends RecordingFixture(handler => runtime.startUiMailbox[Message](handler))

  "a pinned mailbox" should {
    "run its messages on one thread, named after the mailbox, at the given priority" in
      new Fixture("Track-1", Thread.NORM_PRIORITY + 2) {
        // When
        mailbox.enqueue(Probe("a"))
        mailbox.enqueue(Probe("b"))

        // Then
        private val (_, first) = nextRun()
        private val (_, second) = nextRun()
        second should be theSameInstanceAs first
        first.getName shouldEqual "Track-1"
        first.getPriority shouldEqual Thread.NORM_PRIORITY + 2
      }

    "run its messages in arrival order" in new Fixture() {
      // When
      (1 to 100).foreach(index => mailbox.enqueue(Probe(index.toString)))

      // Then
      Seq.fill(100)(nextRun()._1) shouldEqual (1 to 100).map(_.toString)
    }

    "run each mailbox on a thread of its own, also two of the same name" in {
      // Given
      val business1 = new Fixture("Business") {}
      val business2 = new Fixture("Business") {}

      // When
      business1.mailbox.enqueue(Probe("1"))
      business2.mailbox.enqueue(Probe("2"))

      // Then
      val (_, thread1) = business1.nextRun()
      val (_, thread2) = business2.nextRun()
      thread1 should not be theSameInstanceAs(thread2)
      Seq(thread1.getName, thread2.getName) shouldEqual Seq("Business", "Business")
    }

    "name its thread with a name that isn't a valid actor name" in new Fixture("Track-Piano 1/2") {
      // When
      mailbox.enqueue(Probe("a"))

      // Then
      nextRun()._2.getName shouldEqual "Track-Piano 1/2"
    }

    "count its own thread as current from its first message, and no other thread" in new Fixture() {
      // Given
      private val inFirst = CompletableFuture[Boolean]()

      // When
      mailbox.enqueue(CheckThread(inFirst))

      // Then
      inFirst.get(Timeout.toMillis, TimeUnit.MILLISECONDS) shouldBe true
      mailbox.isCurrentThread shouldBe false
    }

    "throw IllegalArgumentException on the caller's thread for a priority out of range" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy
        runtime.startMailbox[Message]("Low", Thread.MIN_PRIORITY - 1)(_ => ())
      an[IllegalArgumentException] should be thrownBy
        runtime.startMailbox[Message]("High", Thread.MAX_PRIORITY + 1)(_ => ())
    }

    "log a message whose handler throws, and go on with the next" in new Fixture() {
      // Given
      mailbox.enqueue(Probe("first"))
      private val (_, thread) = nextRun()

      // When
      private val ((next, _), events) = LogCapture.capturing(classOf[PekkoMailbox[?]].getName, _ eq thread) {
        mailbox.enqueue(Fail("boom"))
        mailbox.enqueue(Probe("next"))
        nextRun()
      }

      // Then
      next shouldEqual "next"
      events.failuresAt(Level.ERROR) shouldEqual Seq(("A message of mailbox Pinned failed.", Some("boom")))
    }

    "run the urgent messages queued before the normal ones, each kind in arrival order" in new Fixture() {
      // Given
      private val release = hold()

      // When
      mailbox.enqueue(Probe("n1"))
      mailbox.enqueue(UrgentProbe("u1"))
      mailbox.enqueue(Probe("n2"))
      mailbox.enqueue(UrgentProbe("u2"))
      release.countDown()

      // Then
      Seq.fill(4)(nextRun()._1) shouldEqual Seq("u1", "u2", "n1", "n2")
    }

    "run the messages queued before stop, then complete its Future" in new Fixture() {
      // Given
      private val release = hold()
      mailbox.enqueue(Probe("a"))
      mailbox.enqueue(Probe("b"))

      // When
      private val stopped = mailbox.stop()

      // Then
      stopped.isCompleted shouldBe false

      // When
      release.countDown()

      // Then
      Await.result(stopped, Timeout)
      runs.asScala.toSeq.map(_._1) shouldEqual Seq("a", "b")
    }

    "drop a message enqueued after stop, before it reaches Pekko's dead letters" in new Fixture() {
      // Given
      private val deadLetters = testKit.createDeadLetterProbe()

      // When
      private val stopped = mailbox.stop()
      mailbox.enqueue(Probe("late"))

      // Then
      Await.result(stopped, Timeout)
      deadLetters.expectNoMessage()
      runs.isEmpty shouldBe true
    }

    "return the same Future when stopped twice" in new Fixture() {
      // When / Then
      mailbox.stop() should be theSameInstanceAs mailbox.stop()
    }
  }

  "the UI mailbox" should {
    "be named UI" in new UiFixture {
      // Then
      mailbox.name shouldEqual MessagingRuntime.UiMailboxName
    }

    "count the UI dispatcher's thread as its own once started, in its messages and outside them" in new UiFixture {
      // When
      private val (outside, toolkitThread) =
        PekkoTestSupport.onUiDispatcher(testKit.system)(mailbox.isCurrentThread -> Thread.currentThread())
      mailbox.enqueue(Probe("a"))

      // Then
      outside shouldBe true
      nextRun()._2 should be theSameInstanceAs toolkitThread
      mailbox.isCurrentThread shouldBe false
    }

    "throw IllegalStateException when started on the UI dispatcher's thread, which it waits for" in {
      // Given
      val impatient = PekkoMessagingRuntime(testKit.system, uiStartTimeout = ShortUiStartTimeout)

      // When
      val attempt = PekkoTestSupport.onUiDispatcher(testKit.system)(Try(impatient.startUiMailbox[Message](_ => ())))

      // Then
      attempt.failed.get shouldBe an[IllegalStateException]
    }
  }
}

/**
 * The messages of [[PekkoMessagingRuntimeTest]].
 */
object PekkoMessagingRuntimeTest {
  /** The start timeout of a runtime whose UI mailbox can't start, which its case waits out. */
  val ShortUiStartTimeout: FiniteDuration = 200.millis

  /** A message of the test mailboxes. */
  sealed trait Message

  /** A message that the mailbox records, by `name`, in its fixture's runs. */
  case class Probe(name: String) extends Message

  /** A message that completes `result` with whether the mailbox counts its running thread as its own. */
  case class CheckThread(result: CompletableFuture[Boolean]) extends Message

  /** A message whose handler throws an exception with `text` as its message. */
  case class Fail(text: String) extends Message

  /** An [[Urgent]] message that the mailbox records, by `name`, in its fixture's runs as a [[Probe]] is. */
  case class UrgentProbe(name: String) extends Message with Urgent

  /** A message that counts `entered` down, then holds the mailbox's thread until `release` is counted down. */
  case class Gate(entered: CountDownLatch, release: CountDownLatch) extends Message
}

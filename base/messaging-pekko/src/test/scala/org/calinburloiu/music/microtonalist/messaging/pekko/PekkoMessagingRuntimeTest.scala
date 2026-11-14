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

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.calinburloiu.music.microtonalist.messaging.Mailbox
import org.calinburloiu.music.microtonalist.messaging.pekko.PekkoTestSupport.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}

/**
 * Tests [[PekkoMessagingRuntime]] on one shared test kit.
 */
class PekkoMessagingRuntimeTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import PekkoMessagingRuntimeTest.*

  private val testKit: ActorTestKit = PekkoTestSupport.startTestKit("PekkoMessagingRuntimeTest")
  private val runtime: PekkoMessagingRuntime = PekkoMessagingRuntime(testKit.system)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /**
   * A case's pinned mailbox, whose handler records each [[Probe]] it runs, with its thread, in [[runs]].
   *
   * @param name the name of the mailbox
   * @param priority the priority of the mailbox's thread
   */
  class Fixture(name: String = "Pinned", priority: Int = Thread.NORM_PRIORITY) {
    /** The name of each probe run, with the thread it ran on, in the order run. */
    val runs: LinkedBlockingQueue[(String, Thread)] = LinkedBlockingQueue()

    /** The mailbox under test. */
    val mailbox: Mailbox[Message] = runtime.startMailbox[Message](name, priority) {
      case Probe(probeName) => runs.put(probeName -> Thread.currentThread())
      case CheckThread(result) => result.complete(mailbox.isCurrentThread)
    }

    /** Takes the next run from [[runs]], waiting up to [[PekkoTestSupport.Timeout]]; fails if none comes. */
    def nextRun(): (String, Thread) =
      Option(runs.poll(Timeout.toMillis, TimeUnit.MILLISECONDS)).getOrElse(fail(s"No message ran within $Timeout."))
  }

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
  }
}

/**
 * The messages of [[PekkoMessagingRuntimeTest]].
 */
object PekkoMessagingRuntimeTest {
  /** A message of the test mailboxes. */
  sealed trait Message

  /** A message that the mailbox records, by `name`, in its fixture's runs. */
  case class Probe(name: String) extends Message

  /** A message that completes `result` with whether the mailbox counts its running thread as its own. */
  case class CheckThread(result: CompletableFuture[Boolean]) extends Message
}

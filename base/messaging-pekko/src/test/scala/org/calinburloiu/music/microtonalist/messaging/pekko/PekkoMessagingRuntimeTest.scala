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
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.failuresAt
import org.calinburloiu.music.microtonalist.messaging.{Mailbox, MessagingRuntime, SynchronizedBuffer, Urgent}
import org.calinburloiu.music.microtonalist.messaging.pekko.PekkoTestSupport.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
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

  /**
   * A case's own runtime, on an actor system of its own, since its shutdown terminates that system. Each case shuts it
   * down.
   *
   * @param config the configuration of the actor system
   */
  class ShutdownFixture(config: Config = ConfigFactory.load("application-test")) {
    /** The runtime under test. */
    val started: PekkoMessagingRuntime = PekkoMessagingRuntime.start(config)
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

  "starting and shutting down" should {
    "start an ActorSystem whose mailboxes run, and terminate it on shutdown" in new ShutdownFixture {
      // Given
      private val ran = CompletableFuture[String]()

      // When
      started.startMailbox[String]("Business", Thread.NORM_PRIORITY)(text => ran.complete(text)).enqueue("hello")

      // Then
      ran.get(Timeout.toMillis, TimeUnit.MILLISECONDS) shouldEqual "hello"

      // When
      Await.result(started.shutdown(), Timeout)

      // Then
      Await.result(started.system.whenTerminated, Timeout)
    }

    "run the FinishWork tasks, then the ReleaseResources ones, then stop the mailboxes" in
      new ShutdownFixture(BeforeTerminationConfig) {
        // Given
        private val steps = SynchronizedBuffer[String]()
        private val mailbox = started.startMailbox[Promise[Unit]]("Business", Thread.NORM_PRIORITY) { done =>
          steps += "mailbox"
          done.success(())
        }
        started.addShutdownTask(ShutdownPhase.ReleaseResources, "release") { () =>
          steps += "release"
          Future.unit
        }
        started.addShutdownTask(ShutdownPhase.FinishWork, "finish") { () =>
          steps += "finish"
          val done = Promise[Unit]()
          mailbox.enqueue(done)
          done.future
        }
        // Only the runtime can have stopped the mailboxes before the actor system's termination begins.
        private val runningBeforeTermination = Promise[Seq[Mailbox[?]]]()
        CoordinatedShutdown(started.system).addTask(BeforeTerminationPhase, "check-mailboxes") { () =>
          runningBeforeTermination.success(started.runningMailboxes)
          Future.successful(Done)
        }

        // When
        Await.result(started.shutdown(), Timeout)

        // Then
        steps.toSeq shouldEqual Seq("finish", "mailbox", "release")
        Await.result(runningBeforeTermination.future, Timeout) shouldBe empty
        mailbox.stop().isCompleted shouldBe true
      }

    "run each task once when shut down twice" in new ShutdownFixture {
      // Given
      private val runs = AtomicInteger()
      started.addShutdownTask(ShutdownPhase.FinishWork, "count") { () =>
        runs.incrementAndGet()
        Future.unit
      }

      // When
      private val first = started.shutdown()
      private val second = started.shutdown()

      // Then
      Await.result(first, Timeout)
      Await.result(second, Timeout)
      runs.get shouldEqual 1
    }

    "forget a pinned mailbox once it has stopped" in new ShutdownFixture {
      // Given
      private val mailbox = started.startMailbox[String]("Business", Thread.NORM_PRIORITY)(_ => ())

      // Then
      started.runningMailboxes shouldEqual Seq(mailbox)

      // When
      Await.result(mailbox.stop(), Timeout)

      // Then
      started.runningMailboxes shouldBe empty
      Await.result(started.shutdown(), Timeout)
    }

    "stop the mailboxes last started first, each once the one after it has stopped" in {
      // Given
      val stops = SynchronizedBuffer[String]()
      val first = FakeMailbox("first", stops)
      val second = FakeMailbox("second", stops)

      // When
      val all = PekkoMessagingRuntime.stopLastStartedFirst(Seq(first, second))(using ExecutionContext.parasitic)

      // Then
      stops.toSeq shouldEqual Seq("second")

      // When
      second.stopped.success(())

      // Then
      stops.toSeq shouldEqual Seq("second", "first")
      all.isCompleted shouldBe false

      // When
      first.stopped.success(())

      // Then
      all.isCompleted shouldBe true
    }
  }
}

/**
 * The messages of [[PekkoMessagingRuntimeTest]].
 */
object PekkoMessagingRuntimeTest {
  /** The start timeout of a runtime whose UI mailbox can't start, which its case waits out. */
  val ShortUiStartTimeout: FiniteDuration = 200.millis

  /** A shutdown phase that runs after the runtime has stopped the mailboxes and before the actor system terminates. */
  val BeforeTerminationPhase: String = "test-before-termination"

  /** The tests' configuration, with [[BeforeTerminationPhase]] added to the shutdown. */
  val BeforeTerminationConfig: Config = ConfigFactory.parseString(
    s"""pekko.coordinated-shutdown.phases {
       |  $BeforeTerminationPhase.depends-on = [${CoordinatedShutdown.PhaseBeforeActorSystemTerminate}]
       |  ${CoordinatedShutdown.PhaseActorSystemTerminate}.depends-on = [$BeforeTerminationPhase]
       |}""".stripMargin).withFallback(ConfigFactory.load("application-test"))

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

  /**
   * A mailbox that only records its stops: [[stop]] adds its name to `stops` and returns the `Future` of [[stopped]],
   * which the case completes.
   *
   * @param name the name of the mailbox
   * @param stops records the name of each mailbox stopped, in the order stopped
   */
  class FakeMailbox(override val name: String, stops: SynchronizedBuffer[String]) extends Mailbox[AnyRef] {
    /** Completes the `Future` that [[stop]] returns. */
    val stopped: Promise[Unit] = Promise()

    override def enqueue(message: AnyRef): Unit = ()

    override def isCurrentThread: Boolean = false

    override def stop(): Future[Unit] = {
      stops += name
      stopped.future
    }
  }
}

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

package org.calinburloiu.music.microtonalist.messaging

import ch.qos.logback.classic.Level
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutorService, Executors}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Success, Try}

object MessageBusTest {
  /** How long an `Await` in a test waits, which happens only when the bus fails to refuse it. */
  private val RefusedAwaitTimeout: FiniteDuration = 1.second

  /** How many threads dispatch at once in the concurrent-id test. */
  private val DispatcherCount: Int = 8

  /** How many commands each of those threads dispatches, kept under `ManualMessagingRuntime.MaxDrainedMessages`. */
  private val DispatchesPerThread: Int = 500

  sealed trait TunerCommand extends Command

  case class Retune(index: Int) extends TunerCommand

  case class Reset() extends TunerCommand

  trait RigCommand extends Command

  case class TuneRig() extends TunerCommand with RigCommand

  case class Unhandled() extends Command

  case class Explode() extends Command

  case class Echo(text: String) extends Request[String]

  case class Later() extends Request[String]

  abstract class Animal

  trait Pet

  case class Dog(name: String) extends Animal with Pet

  case class Wolf(name: String) extends Animal

  /** The messages of the exceptions that the exception of the failed `reply` suppresses, in the order added. */
  def suppressedMessages(reply: Future[?]): Seq[String] =
    reply.value.get.failed.get.getSuppressed.toSeq.map(_.getMessage)

  abstract class Fixture {
    val runtime: ManualMessagingRuntime = ManualMessagingRuntime()
    val messageBus: MessageBus = MessageBus(runtime)
    given MessageBus = messageBus
    val log: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
  }

  /**
   * A [[MessagingRuntime]] over a [[ManualMessagingRuntime]] whose UI mailbox also counts `toolkitThread` as its thread
   * outside its messages, as a production runtime counts the UI toolkit's thread.
   */
  final class ToolkitRuntime(manualRuntime: ManualMessagingRuntime, toolkitThread: Thread) extends MessagingRuntime {
    override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M] =
      manualRuntime.startMailbox(name, priority)(handler)

    override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M] = {
      val mailbox = manualRuntime.startUiMailbox(handler)
      new Mailbox[M] {
        override def name: String = mailbox.name

        override def enqueue(message: M): Unit = mailbox.enqueue(message)

        override def isCurrentThread: Boolean = mailbox.isCurrentThread || Thread.currentThread() == toolkitThread

        override def stop(): Future[Unit] = mailbox.stop()
      }
    }
  }

  /**
   * A fixture whose bus runs on a [[ToolkitRuntime]], with the thread of the single-thread executor `toolkit` as the
   * toolkit's thread; its messages run when the test drains `runtime`.
   */
  final class ToolkitFixture {
    val toolkit: ExecutorService = Executors.newSingleThreadExecutor()
    private val toolkitThread: Thread = CompletableFuture.supplyAsync(() => Thread.currentThread(), toolkit).join()
    val runtime: ManualMessagingRuntime = ManualMessagingRuntime()
    val messageBus: MessageBus = MessageBus(ToolkitRuntime(runtime, toolkitThread))
    given MessageBus = messageBus

    /**
     * Runs `body` on the toolkit's thread outside any message, as the UI toolkit runs UI code, and returns its result.
     */
    def onToolkit[T](body: => T): T = CompletableFuture.supplyAsync(() => body, toolkit).join()

    /**
     * Asks a request whose handler publishes an event to a UI subscriber and returns a `Future` that never completes,
     * drains, and returns the pending reply; the UI Thread's last message was thus caused by the request.
     */
    def askWithUiEvent(): Future[String] = {
      messageBus.handleRequest(classOf[Later]) { _ =>
        messageBus.publish(Dog("Rex"))
        Promise[String]().future
      }
      messageBus.subscribeOnUi(classOf[Dog])(_ => ())
      val reply = messageBus.ask(Later())
      runtime.drainAll()
      reply
    }
  }

  /** Runs `test` with a new [[ToolkitFixture]], then shuts its toolkit's thread down. */
  def withToolkitFixture(test: ToolkitFixture => Unit): Unit = {
    val fixture = ToolkitFixture()
    try {
      test(fixture)
    } finally {
      fixture.toolkit.shutdown()
    }
  }
}

class MessageBusTest extends AnyWordSpec with Matchers {

  import BusinessContinuations.*
  import LogCapture.*
  import MessageBusTest.*

  "a MessageBus" when {
    "dispatching a command" should {
      "start the Business mailbox at normal priority" in new Fixture {
        // When / Then
        runtime.mailbox("Business").priority shouldEqual Some(Thread.NORM_PRIORITY)
      }

      "run a command's handler on the Business Thread once the Business mailbox drains" in new Fixture {
        // Given
        private var onBusiness = false
        messageBus.handleCommand(classOf[Retune]) { retune =>
          log += s"retune ${retune.index}"
          onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
        }

        // When
        messageBus.dispatch(Retune(1))

        // Then
        log shouldBe empty

        // When
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune 1")
        onBusiness shouldBe true
      }

      "run the handler registered for a supertype of the command's class" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[TunerCommand])(command => log += command.toString)

        // When
        messageBus.dispatch(Retune(1))
        messageBus.dispatch(Reset())
        runtime.drainAll()

        // Then
        log shouldEqual Seq("Retune(1)", "Reset()")
      }

      "throw on the caller's thread when no handler matches the command" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.dispatch(Unhandled())
      }

      "throw on the caller's thread when several handlers match the command" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[TunerCommand])(_ => ())
        messageBus.handleCommand(classOf[RigCommand])(_ => ())

        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.dispatch(TuneRig())
      }

      "reject a handler for a class that already has one" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handleCommand(classOf[Retune])(_ => ())
      }

      "reject a handler for a subtype of a handled class" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[TunerCommand])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handleCommand(classOf[Retune])(_ => ())
      }

      "reject a handler for a supertype of a handled class" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handleCommand(classOf[TunerCommand])(_ => ())
      }

      "enqueue a command that a handler dispatches instead of running it inside the handler" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune]) { _ =>
          messageBus.dispatch(Reset())
          log += "retune done"
        }
        messageBus.handleCommand(classOf[Reset])(_ => log += "reset")

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune done", "reset")
      }

      "accept a command dispatched from another thread" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune])(retune => log += s"retune ${retune.index}")

        // When
        CompletableFuture.runAsync(() => messageBus.dispatch(Retune(2))).join()
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune 2")
      }

      "log a failing handler and go on with the next command" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Explode])(_ => throw RuntimeException("boom"))
        messageBus.handleCommand(classOf[Retune])(retune => log += s"retune ${retune.index}")

        // When
        private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          messageBus.dispatch(Explode())
          messageBus.dispatch(Retune(1))
          runtime.drainAll()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Explode() failed.", Some("boom")))
        log shouldEqual Seq("retune 1")
      }

      "throw from requireBusinessThread off the Business Thread" in new Fixture {
        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.requireBusinessThread()
      }
    }

    "publishing an event" should {
      "start the UI mailbox after the Business one" in new Fixture {
        // When / Then
        runtime.mailboxes.map(_.name) shouldEqual Seq("Business", "UI")
      }

      "deliver an event to a Business subscriber on the Business Thread once the Business mailbox drains" in
        new Fixture {
          // Given
          private var onBusiness = false
          messageBus.subscribeOnBusiness(classOf[Dog]) { dog =>
            log += dog.name
            onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
          }

          // When
          messageBus.publish(Dog("Rex"))

          // Then
          log shouldBe empty

          // When
          runtime.drainAll()

          // Then
          log shouldEqual Seq("Rex")
          onBusiness shouldBe true
        }

      "deliver an event to the subscribers of its class and of its supertypes, classes and traits" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Animal])(event => log += s"animal $event")
        messageBus.subscribeOnBusiness(classOf[Pet])(event => log += s"pet $event")
        messageBus.subscribeOnBusiness(classOf[Dog])(event => log += s"dog $event")
        messageBus.subscribeOnBusiness(classOf[Wolf])(event => log += s"wolf $event")

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("animal Dog(Rex)", "pet Dog(Rex)", "dog Dog(Rex)")
      }

      "deliver an event of any class" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[String])(event => log += event)

        // When
        messageBus.publish("plain")
        runtime.drainAll()

        // Then
        log shouldEqual Seq("plain")
      }

      "deliver an event once per matching subscription, in subscription order" in new Fixture {
        // Given
        private val record: AnyRef => Unit = event => log += s"twice $event"
        messageBus.subscribeOnBusiness(classOf[Animal])(event => log += s"animal $event")
        messageBus.subscribeOnBusiness(classOf[Pet])(record)
        messageBus.subscribeOnBusiness(classOf[Animal])(record)

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("animal Dog(Rex)", "twice Dog(Rex)", "twice Dog(Rex)")
      }

      "reach a subscription made after its event's class was first published, in subscription order" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Animal])(event => log += s"animal $event")
        messageBus.publish(Dog("a"))
        runtime.drainAll()
        log.clear()
        messageBus.subscribeOnBusiness(classOf[Dog])(event => log += s"dog $event")

        // When
        messageBus.publish(Dog("b"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("animal Dog(b)", "dog Dog(b)")
      }

      "not reach a subscription cancelled after its event's class was first published" in new Fixture {
        // Given
        private val subscription = messageBus.subscribeOnBusiness(classOf[Animal])(event => log += s"animal $event")
        messageBus.subscribeOnBusiness(classOf[Dog])(event => log += s"dog $event")
        messageBus.publish(Dog("a"))
        runtime.drainAll()
        log.clear()

        // When
        subscription.cancel()
        messageBus.publish(Dog("b"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("dog Dog(b)")
      }

      "deliver last to a subscriber that was cancelled and subscribed again" in new Fixture {
        // Given
        private val record: AnyRef => Unit = event => log += s"again $event"
        private val subscription = messageBus.subscribeOnBusiness(classOf[Animal])(record)
        messageBus.subscribeOnBusiness(classOf[Dog])(event => log += s"dog $event")
        subscription.cancel()
        messageBus.subscribeOnBusiness(classOf[Animal])(record)

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("dog Dog(Rex)", "again Dog(Rex)")
      }

      "deliver the events a handler publishes after it returns and before the next command" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune]) { retune =>
          messageBus.publish(Dog(s"d${retune.index}"))
          log += s"retune ${retune.index}"
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name)

        // When
        messageBus.dispatch(Retune(1))
        messageBus.dispatch(Retune(2))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune 1", "d1", "retune 2", "d2")
      }

      "deliver the events published while draining in the same drain" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.handleCommand(classOf[Reset])(_ => log += "reset")
        messageBus.subscribeOnBusiness(classOf[Dog]) { dog =>
          log += dog.name
          messageBus.publish(Wolf("Grey"))
        }
        messageBus.subscribeOnBusiness(classOf[Wolf])(wolf => log += wolf.name)

        // When
        messageBus.dispatch(Retune(1))
        messageBus.dispatch(Reset())
        runtime.drainAll()

        // Then
        log shouldEqual Seq("Rex", "Grey", "reset")
      }

      "enqueue an event to the UI mailbox as soon as it's published" in new Fixture {
        // Given
        private var uiQueued = false
        messageBus.subscribeOnUi(classOf[Dog])(_ => ())
        messageBus.handleCommand(classOf[Retune]) { _ =>
          messageBus.publish(Dog("Rex"))
          uiQueued = !runtime.mailbox("UI").isEmpty
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.mailbox("Business").drain()

        // Then
        uiQueued shouldBe true
      }

      "deliver events to UI subscribers on the UI Thread, in publish order" in new Fixture {
        // Given
        private var onUi = false
        messageBus.subscribeOnUi(classOf[Dog]) { dog =>
          log += dog.name
          onUi = Try(messageBus.requireUiThread()).isSuccess
        }
        messageBus.handleCommand(classOf[Retune]) { _ =>
          messageBus.publish(Dog("a"))
          messageBus.publish(Dog("b"))
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.mailbox("Business").drain()

        // Then
        log shouldBe empty

        // When
        runtime.mailbox("UI").drain()

        // Then
        log shouldEqual Seq("a", "b")
        onUi shouldBe true
      }

      "deliver an event to both its Business and UI subscribers" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += s"business ${dog.name}")
        messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"ui ${dog.name}")

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log should contain theSameElementsAs Seq("business Rex", "ui Rex")
      }

      "not deliver to a cancelled subscription" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name).cancel()

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldBe empty
      }

      "not deliver an event published before its subscription was cancelled" in new Fixture {
        // Given
        private val business = messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += s"business ${dog.name}")
        private val ui = messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"ui ${dog.name}")
        messageBus.publish(Dog("Rex"))

        // When
        business.cancel()
        ui.cancel()
        runtime.drainAll()

        // Then
        log shouldBe empty
      }

      "skip a subscriber that an earlier subscriber of the same event cancelled" in new Fixture {
        // Given
        private var later: Option[Subscription] = None
        messageBus.subscribeOnBusiness(classOf[Dog]) { dog =>
          log += s"first ${dog.name}"
          later.foreach(_.cancel())
        }
        later = Some(messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += s"later ${dog.name}"))

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("first Rex")
      }

      "give a subscription made while delivering an event the events published after it" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Wolf]) { _ =>
          messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += s"new ${dog.name}")
          messageBus.publish(Dog("after"))
        }

        // When
        messageBus.publish(Dog("before"))
        messageBus.publish(Wolf("Grey"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("new after")
      }

      "log a failing Business subscriber and still run the others" in new Fixture {
        // Given
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name)

        // When
        private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          messageBus.publish(Dog("Rex"))
          runtime.drainAll()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A subscriber of Dog(Rex) failed.", Some("bite")))
        log shouldEqual Seq("Rex")
      }

      "log a failing UI subscriber and still run the others" in new Fixture {
        // Given
        messageBus.subscribeOnUi(classOf[Dog])(_ => throw RuntimeException("bite"))
        messageBus.subscribeOnUi(classOf[Dog])(dog => log += dog.name)

        // When
        private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          messageBus.publish(Dog("Rex"))
          runtime.drainAll()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A subscriber of Dog(Rex) failed.", Some("bite")))
        log shouldEqual Seq("Rex")
      }

      "throw from requireUiThread off the UI Thread" in new Fixture {
        // Given
        private var inHandler: Option[Try[Unit]] = None
        messageBus.handleCommand(classOf[Retune])(_ => inHandler = Some(Try(messageBus.requireUiThread())))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        an[IllegalStateException] should be thrownBy messageBus.requireUiThread()
        inHandler.get.failed.get shouldBe an[IllegalStateException]
      }
    }

    "stamping the causing command" should {
      "give each dispatched command the next id of one counter" in new Fixture {
        // Given
        private val ids = mutable.ArrayBuffer[Option[Long]]()
        messageBus.handleCommand(classOf[Retune])(_ => ids += ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        (1 to 3).foreach(index => messageBus.dispatch(Retune(index)))
        runtime.drainAll()

        // Then
        ids shouldEqual Seq(Some(1L), Some(2L), Some(3L))
      }

      "give distinct ids to commands dispatched at once from several threads" in new Fixture {
        // Given
        private val ids = mutable.ArrayBuffer[Long]()
        messageBus.handleCommand(classOf[Retune])(_ =>
          ids ++= ManualMessagingRuntime.causingCommandId(using messageBus))
        private val dispatchers = Executors.newFixedThreadPool(DispatcherCount)
        private val start = CountDownLatch(1)
        private val dispatchAll: Runnable = () => {
          start.await()
          (1 to DispatchesPerThread).foreach(index => messageBus.dispatch(Retune(index)))
        }

        // When
        try {
          val dispatched = (1 to DispatcherCount).map(_ => CompletableFuture.runAsync(dispatchAll, dispatchers))
          start.countDown()
          dispatched.foreach(_.join())
        } finally {
          dispatchers.shutdown()
        }
        runtime.drainAll()

        // Then
        ids should contain theSameElementsAs (1L to (DispatcherCount * DispatchesPerThread).toLong)
      }

      "stamp the events a handler publishes with its command's id, for Business and UI subscribers" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handleCommand(classOf[Retune]) { _ =>
          stamps += "handler" -> ManualMessagingRuntime.causingCommandId(using messageBus)
          messageBus.publish(Dog("Rex"))
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ =>
          stamps += "business" -> ManualMessagingRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Dog])(_ =>
          stamps += "ui" -> ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("handler" -> Some(1L), "business" -> Some(1L), "ui" -> Some(1L))
      }

      "keep the stamp of the events that Business subscribers publish while draining" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handleCommand(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => messageBus.publish(Wolf("Grey")))
        messageBus.subscribeOnBusiness(classOf[Wolf])(_ =>
          stamps += "business" -> ManualMessagingRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Wolf])(_ =>
          stamps += "ui" -> ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> Some(1L), "ui" -> Some(1L))
      }

      "keep the stamp of the events that UI subscribers publish" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handleCommand(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribeOnUi(classOf[Dog])(_ => messageBus.publish(Wolf("Grey")))
        messageBus.subscribeOnBusiness(classOf[Wolf])(_ =>
          stamps += "business" -> ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> Some(1L))
      }

      "give a command that a handler dispatches an id of its own" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handleCommand(classOf[Retune]) { _ =>
          stamps += "retune" -> ManualMessagingRuntime.causingCommandId(using messageBus)
          messageBus.dispatch(Reset())
        }
        messageBus.handleCommand(classOf[Reset])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribeOnBusiness(classOf[Dog])(_ =>
          stamps += "dog" -> ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("retune" -> Some(1L), "dog" -> Some(2L))
      }

      "stamp nothing published outside the messages that a command caused" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.subscribeOnBusiness(classOf[Dog])(_ =>
          stamps += "business" -> ManualMessagingRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Dog])(_ =>
          stamps += "ui" -> ManualMessagingRuntime.causingCommandId(using messageBus))

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> None, "ui" -> None)
        ManualMessagingRuntime.causingCommandId(using messageBus) shouldBe None
      }

      "stamp nothing published off the Business Thread after a command's message" in new Fixture {
        // Given
        private var stamp: Option[Option[Long]] = None
        messageBus.handleCommand(classOf[Retune])(_ => ())
        messageBus.subscribeOnBusiness(classOf[Dog])(_ =>
          stamp = Some(ManualMessagingRuntime.causingCommandId(using messageBus)))
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // When
        CompletableFuture.runAsync(() => messageBus.publish(Dog("Rex"))).join()
        runtime.drainAll()

        // Then
        stamp shouldEqual Some(None)
      }

      "stamp nothing that the toolkit runs on the UI Thread after a command's message" in
        withToolkitFixture { fixture =>
          import fixture.*
          // Given
          // A request caused the UI Thread's last message, whose cause mustn't leak into the toolkit's code after it.
          askWithUiEvent()

          // When
          val onUi = onToolkit(Try(messageBus.requireUiThread()).isSuccess)
          val stamp = onToolkit(ManualMessagingRuntime.causingCommandId(using messageBus))

          // Then
          // The toolkit's thread counts as the UI Thread, so the stamp isn't None merely for being off both threads.
          onUi shouldBe true
          stamp shouldBe None
        }
    }

    "asking a request" should {
      "complete the reply with the result of the request's handler once the Business mailbox drains" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))

        // When
        private val reply = messageBus.ask(Echo("hi"))

        // Then
        reply.isCompleted shouldBe false

        // When
        runtime.drainAll()

        // Then
        reply.value shouldEqual Some(Success("hi"))
      }

      "complete the reply only after the events its handler published reached their Business subscribers" in
        new Fixture {
          // Given
          private var reply: Future[String] = Future.never
          private var completedAtDelivery: Option[Boolean] = None
          messageBus.handleRequest(classOf[Echo]) { echo =>
            messageBus.publish(Dog("Rex"))
            Future.successful(echo.text)
          }
          messageBus.subscribeOnBusiness(classOf[Dog])(_ => completedAtDelivery = Some(reply.isCompleted))

          // When
          reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()

          // Then
          completedAtDelivery shouldEqual Some(false)
          reply.value shouldEqual Some(Success("hi"))
        }

      "deliver the events a handler published before throwing, then fail the reply and log" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { _ =>
          messageBus.publish(Dog("Rex"))
          throw RuntimeException("boom")
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name)

        // When
        private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        log shouldEqual Seq("Rex")
        reply.value.get.failed.get.getMessage shouldEqual "boom"
        events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Echo(hi) failed.", Some("boom")))
      }

      "fail the reply without logging when the handler's Future fails" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo])(_ => Future.failed(RuntimeException("nope")))

        // When
        private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "nope"
        events.failuresAt(Level.ERROR) shouldBe empty
      }

      "fail the reply when a Business subscriber of its events throws" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { echo =>
          messageBus.publish(Dog("Rex"))
          Future.successful(echo.text)
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))

        // When
        private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "bite"
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A subscriber of Dog(Rex) failed.", Some("bite")))
      }

      "log the handler's Future that fails after a Business subscriber failed the reply" in new Fixture {
        // Given
        private val pending = Promise[String]()
        messageBus.handleRequest(classOf[Later]) { _ =>
          messageBus.publish(Dog("Rex"))
          pending.future
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))

        // When
        private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Later())
          runtime.drainAll()
          pending.failure(RuntimeException("late"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "bite"
        events.failuresAt(Level.ERROR) shouldEqual Seq(
          ("A subscriber of Dog(Rex) failed.", Some("bite")),
          ("The handler of Later() failed.", Some("late")))
      }

      "suppress in the reply's failure the failures of the other subscribers of an event" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { echo =>
          messageBus.publish(Dog("Rex"))
          Future.successful(echo.text)
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("growl"))

        // When
        private val (reply, _) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "bite"
        suppressedMessages(reply) shouldEqual Seq("growl")
      }

      "suppress in the reply's failure the failures of the subscribers of a later event" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { echo =>
          messageBus.publish(Dog("Rex"))
          messageBus.publish(Wolf("Grey"))
          Future.successful(echo.text)
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))
        messageBus.subscribeOnBusiness(classOf[Wolf])(_ => throw RuntimeException("howl"))

        // When
        private val (reply, _) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "bite"
        suppressedMessages(reply) shouldEqual Seq("howl")
      }

      "suppress a subscriber's failure in the failure of the handler that published its event" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { _ =>
          messageBus.publish(Dog("Rex"))
          throw RuntimeException("boom")
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))

        // When
        private val (reply, _) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "boom"
        suppressedMessages(reply) shouldEqual Seq("bite")
      }

      "suppress the handler's already failed Future in a subscriber's failure, and still log it" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { _ =>
          messageBus.publish(Dog("Rex"))
          Future.failed(RuntimeException("nope"))
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))

        // When
        private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "bite"
        suppressedMessages(reply) shouldEqual Seq("nope")
        events.failuresAt(Level.ERROR) shouldEqual Seq(
          ("A subscriber of Dog(Rex) failed.", Some("bite")),
          ("The handler of Echo(hi) failed.", Some("nope")))
      }

      "not suppress an exception in itself when two subscribers throw the same one" in new Fixture {
        // Given
        private val shared = RuntimeException("bite")
        messageBus.handleRequest(classOf[Echo]) { echo =>
          messageBus.publish(Dog("Rex"))
          Future.successful(echo.text)
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw shared)
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw shared)
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name)

        // When
        private val (reply, _) = LogCapture.capturing(classOf[MessageBus].getName) {
          val reply = messageBus.ask(Echo("hi"))
          runtime.drainAll()
          reply
        }

        // Then
        reply.value.get.failed.get should be theSameInstanceAs shared
        suppressedMessages(reply) shouldBe empty
        log shouldEqual Seq("Rex")
      }

      "keep the reply pending when the toolkit publishes an event whose Business subscriber throws" in
        withToolkitFixture { fixture =>
          import fixture.*
          // Given
          val reply = askWithUiEvent()
          messageBus.subscribeOnBusiness(classOf[Wolf])(_ => throw RuntimeException("bite"))

          // When
          val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
            onToolkit(messageBus.publish(Wolf("Grey")))
            runtime.drainAll()
          }

          // Then
          events.failuresAt(Level.ERROR) shouldEqual Seq(("A subscriber of Wolf(Grey) failed.", Some("bite")))
          reply.isCompleted shouldBe false
        }

      "accept a request asked from another thread" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))

        // When
        private val reply = CompletableFuture.supplyAsync(() => messageBus.ask(Echo("hi"))).join()
        runtime.drainAll()

        // Then
        reply.value shouldEqual Some(Success("hi"))
      }

      "give an asked request the next id of the dispatch counter" in new Fixture {
        // Given
        private val ids = mutable.ArrayBuffer[Option[Long]]()
        messageBus.handleCommand(classOf[Retune])(_ => ids += ManualMessagingRuntime.causingCommandId(using messageBus))
        messageBus.handleRequest(classOf[Echo]) { echo =>
          ids += ManualMessagingRuntime.causingCommandId(using messageBus)
          Future.successful(echo.text)
        }

        // When
        messageBus.dispatch(Retune(1))
        messageBus.ask(Echo("hi"))
        runtime.drainAll()

        // Then
        ids shouldEqual Seq(Some(1L), Some(2L))
      }

      "run a request's handler and drop its reply when the request is dispatched" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo]) { echo =>
          log += echo.text
          Future.successful(echo.text)
        }

        // When
        messageBus.dispatch(Echo("hi"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("hi")
      }

      "log the failure of a dispatched request's Future" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo])(_ => Future.failed(RuntimeException("nope")))

        // When
        private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
          messageBus.dispatch(Echo("hi"))
          runtime.drainAll()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Echo(hi) failed.", Some("nope")))
      }

      "throw on the caller's thread when asking a request whose handler was registered with handle" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Echo])(_ => ())

        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.ask(Echo("hi"))
      }

      "reject a request handler for a class that already has one" in new Fixture {
        // Given
        messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))

        // When / Then
        an[IllegalArgumentException] should be thrownBy
          messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))
      }
    }

    "awaiting a Future" should {
      "throw instead of blocking in a Business handler" in new Fixture {
        // Given
        private var attempt: Option[Try[Int]] = None
        messageBus.handleCommand(classOf[Retune]) { _ =>
          attempt = Some(Try(Await.result(Promise[Int]().future, RefusedAwaitTimeout)))
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "throw instead of deadlocking when a handler awaits an ask's reply" in new Fixture {
        // Given
        private var attempt: Option[Try[String]] = None
        messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))
        messageBus.handleCommand(classOf[Retune]) { _ =>
          attempt = Some(Try(Await.result(messageBus.ask(Echo("hi")), RefusedAwaitTimeout)))
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "throw instead of blocking in a Business subscriber" in new Fixture {
        // Given
        private var attempt: Option[Try[Int]] = None
        messageBus.handleCommand(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribeOnBusiness(classOf[Dog]) { _ =>
          attempt = Some(Try(Await.result(Promise[Int]().future, RefusedAwaitTimeout)))
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "throw instead of blocking in a Business continuation" in new Fixture {
        // Given
        private var attempt: Option[Try[Int]] = None
        messageBus.handleCommand(classOf[Retune]) { _ =>
          Future.unit.onCompleteOnBusiness { _ =>
            attempt = Some(Try(Await.result(Promise[Int]().future, RefusedAwaitTimeout)))
          }
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "throw instead of blocking in a UI subscriber" in new Fixture {
        // Given
        private var attempt: Option[Try[Int]] = None
        messageBus.subscribeOnUi(classOf[Dog]) { _ =>
          attempt = Some(Try(Await.result(Promise[Int]().future, RefusedAwaitTimeout)))
        }

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "return the result of a completed Future in a handler" in new Fixture {
        // Given
        private var attempt: Option[Try[Int]] = None
        messageBus.handleCommand(classOf[Retune]) { _ =>
          attempt = Some(Try(Await.result(Future.successful(1), RefusedAwaitTimeout)))
        }

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        attempt shouldEqual Some(Success(1))
      }
    }
  }
}

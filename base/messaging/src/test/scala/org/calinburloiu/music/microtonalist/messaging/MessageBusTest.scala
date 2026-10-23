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

import java.util.concurrent.CompletableFuture
import scala.collection.mutable
import scala.util.Try

object MessageBusTest {
  sealed trait TunerCommand extends Command

  case class Retune(index: Int) extends TunerCommand

  case class Reset() extends TunerCommand

  trait RigCommand extends Command

  case class TuneRig() extends TunerCommand with RigCommand

  case class Unhandled() extends Command

  case class Explode() extends Command

  abstract class Animal

  trait Pet

  case class Dog(name: String) extends Animal with Pet

  case class Wolf(name: String) extends Animal

  abstract class Fixture {
    val runtime: ManualRuntime = ManualRuntime()
    val messageBus: MessageBus = MessageBus(runtime)
    val log: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
  }
}

class MessageBusTest extends AnyWordSpec with Matchers {

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
        messageBus.handle(classOf[Retune]) { retune =>
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
        messageBus.handle(classOf[TunerCommand])(command => log += command.toString)

        // When
        messageBus.dispatch(Retune(1))
        messageBus.dispatch(Reset())
        runtime.drainAll()

        // Then
        log shouldEqual Seq("Retune(1)", "Reset()")
      }

      "throw on the caller's thread when no handler matches the command" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.dispatch(Unhandled())
      }

      "throw on the caller's thread when several handlers match the command" in new Fixture {
        // Given
        messageBus.handle(classOf[TunerCommand])(_ => ())
        messageBus.handle(classOf[RigCommand])(_ => ())

        // When / Then
        an[IllegalStateException] should be thrownBy messageBus.dispatch(TuneRig())
      }

      "reject a handler for a class that already has one" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handle(classOf[Retune])(_ => ())
      }

      "reject a handler for a subtype of a handled class" in new Fixture {
        // Given
        messageBus.handle(classOf[TunerCommand])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handle(classOf[Retune])(_ => ())
      }

      "reject a handler for a supertype of a handled class" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune])(_ => ())

        // When / Then
        an[IllegalArgumentException] should be thrownBy messageBus.handle(classOf[TunerCommand])(_ => ())
      }

      "enqueue a command that a handler dispatches instead of running it inside the handler" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune]) { _ =>
          messageBus.dispatch(Reset())
          log += "retune done"
        }
        messageBus.handle(classOf[Reset])(_ => log += "reset")

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune done", "reset")
      }

      "accept a command dispatched from another thread" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune])(retune => log += s"retune ${retune.index}")

        // When
        CompletableFuture.runAsync(() => messageBus.dispatch(Retune(2))).join()
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune 2")
      }

      "log a failing handler and go on with the next command" in new Fixture {
        // Given
        messageBus.handle(classOf[Explode])(_ => throw RuntimeException("boom"))
        messageBus.handle(classOf[Retune])(retune => log += s"retune ${retune.index}")

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
          messageBus.subscribe(classOf[Dog]) { dog =>
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
        messageBus.subscribe(classOf[Animal])(event => log += s"animal $event")
        messageBus.subscribe(classOf[Pet])(event => log += s"pet $event")
        messageBus.subscribe(classOf[Dog])(event => log += s"dog $event")
        messageBus.subscribe(classOf[Wolf])(event => log += s"wolf $event")

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("animal Dog(Rex)", "pet Dog(Rex)", "dog Dog(Rex)")
      }

      "deliver an event of any class" in new Fixture {
        // Given
        messageBus.subscribe(classOf[String])(event => log += event)

        // When
        messageBus.publish("plain")
        runtime.drainAll()

        // Then
        log shouldEqual Seq("plain")
      }

      "deliver an event once per matching subscription, in subscription order" in new Fixture {
        // Given
        private val record: AnyRef => Unit = event => log += s"twice $event"
        messageBus.subscribe(classOf[Animal])(event => log += s"animal $event")
        messageBus.subscribe(classOf[Pet])(record)
        messageBus.subscribe(classOf[Animal])(record)

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("animal Dog(Rex)", "twice Dog(Rex)", "twice Dog(Rex)")
      }

      "deliver the events a handler publishes after it returns and before the next command" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune]) { retune =>
          messageBus.publish(Dog(s"d${retune.index}"))
          log += s"retune ${retune.index}"
        }
        messageBus.subscribe(classOf[Dog])(dog => log += dog.name)

        // When
        messageBus.dispatch(Retune(1))
        messageBus.dispatch(Retune(2))
        runtime.drainAll()

        // Then
        log shouldEqual Seq("retune 1", "d1", "retune 2", "d2")
      }

      "deliver the events published while draining in the same drain" in new Fixture {
        // Given
        messageBus.handle(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.handle(classOf[Reset])(_ => log += "reset")
        messageBus.subscribe(classOf[Dog]) { dog =>
          log += dog.name
          messageBus.publish(Wolf("Grey"))
        }
        messageBus.subscribe(classOf[Wolf])(wolf => log += wolf.name)

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
        messageBus.handle(classOf[Retune]) { _ =>
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
        messageBus.handle(classOf[Retune]) { _ =>
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

      "not deliver to a cancelled subscription" in new Fixture {
        // Given
        messageBus.subscribe(classOf[Dog])(dog => log += dog.name).cancel()

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        log shouldBe empty
      }

      "not deliver an event published before its subscription was cancelled" in new Fixture {
        // Given
        private val business = messageBus.subscribe(classOf[Dog])(dog => log += s"business ${dog.name}")
        private val ui = messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"ui ${dog.name}")
        messageBus.publish(Dog("Rex"))

        // When
        business.cancel()
        ui.cancel()
        runtime.drainAll()

        // Then
        log shouldBe empty
      }

      "give a subscription made while delivering an event the events published after it" in new Fixture {
        // Given
        messageBus.subscribe(classOf[Wolf]) { _ =>
          messageBus.subscribe(classOf[Dog])(dog => log += s"new ${dog.name}")
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
        messageBus.subscribe(classOf[Dog])(_ => throw RuntimeException("bite"))
        messageBus.subscribe(classOf[Dog])(dog => log += dog.name)

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
        messageBus.handle(classOf[Retune])(_ => inHandler = Some(Try(messageBus.requireUiThread())))

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
        messageBus.handle(classOf[Retune])(_ => ids += ManualRuntime.causingCommandId(using messageBus))

        // When
        (1 to 3).foreach(index => messageBus.dispatch(Retune(index)))
        runtime.drainAll()

        // Then
        ids shouldEqual Seq(Some(1L), Some(2L), Some(3L))
      }

      "stamp the events a handler publishes with its command's id, for Business and UI subscribers" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handle(classOf[Retune]) { _ =>
          stamps += "handler" -> ManualRuntime.causingCommandId(using messageBus)
          messageBus.publish(Dog("Rex"))
        }
        messageBus.subscribe(classOf[Dog])(_ =>
          stamps += "business" -> ManualRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Dog])(_ => stamps += "ui" -> ManualRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("handler" -> Some(1L), "business" -> Some(1L), "ui" -> Some(1L))
      }

      "keep the stamp of the events that Business subscribers publish while draining" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handle(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribe(classOf[Dog])(_ => messageBus.publish(Wolf("Grey")))
        messageBus.subscribe(classOf[Wolf])(_ =>
          stamps += "business" -> ManualRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Wolf])(_ => stamps += "ui" -> ManualRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> Some(1L), "ui" -> Some(1L))
      }

      "keep the stamp of the events that UI subscribers publish" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handle(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribeOnUi(classOf[Dog])(_ => messageBus.publish(Wolf("Grey")))
        messageBus.subscribe(classOf[Wolf])(_ =>
          stamps += "business" -> ManualRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> Some(1L))
      }

      "give a command that a handler dispatches an id of its own" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.handle(classOf[Retune]) { _ =>
          stamps += "retune" -> ManualRuntime.causingCommandId(using messageBus)
          messageBus.dispatch(Reset())
        }
        messageBus.handle(classOf[Reset])(_ => messageBus.publish(Dog("Rex")))
        messageBus.subscribe(classOf[Dog])(_ => stamps += "dog" -> ManualRuntime.causingCommandId(using messageBus))

        // When
        messageBus.dispatch(Retune(1))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("retune" -> Some(1L), "dog" -> Some(2L))
      }

      "stamp nothing published off the Business and UI Threads" in new Fixture {
        // Given
        private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
        messageBus.subscribe(classOf[Dog])(_ =>
          stamps += "business" -> ManualRuntime.causingCommandId(using messageBus))
        messageBus.subscribeOnUi(classOf[Dog])(_ => stamps += "ui" -> ManualRuntime.causingCommandId(using messageBus))

        // When
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()

        // Then
        stamps shouldEqual Seq("business" -> None, "ui" -> None)
        ManualRuntime.causingCommandId(using messageBus) shouldBe None
      }
    }
  }
}

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
  }
}

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

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

class FutureContinuationsTest extends AnyWordSpec with Matchers {

  import LogCapture.*
  import MessageBusTest.*

  "the Business continuations" should {
    "run mapOnBusiness's function on the Business Thread once the Future completes" in new Fixture {
      // Given
      private val input = Promise[Int]()
      private var output: Future[Int] = Future.never
      private var onBusiness = false
      messageBus.handle(classOf[Retune]) { _ =>
        output = input.future.mapOnBusiness { value =>
          onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
          value * 2
        }
      }
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // When
      input.success(21)

      // Then
      output.isCompleted shouldBe false

      // When
      runtime.drainAll()

      // Then
      output.value shouldEqual Some(Success(42))
      onBusiness shouldBe true
    }

    "enqueue the continuation of a completed Future instead of running it in place" in new Fixture {
      // Given
      messageBus.handle(classOf[Retune]) { _ =>
        Future.successful(1).onCompleteOnBusiness(_ => log += "continuation")
        log += "handler"
      }

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      log shouldEqual Seq("handler", "continuation")
    }

    "deliver the events a continuation publishes after it returns" in new Fixture {
      // Given
      messageBus.handle(classOf[Retune]) { _ =>
        Future.unit.onCompleteOnBusiness { _ =>
          messageBus.publish(Dog("Rex"))
          log += "continuation"
        }
      }
      messageBus.subscribe(classOf[Dog])(dog => log += dog.name)

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      log shouldEqual Seq("continuation", "Rex")
    }

    "stamp the events a continuation publishes with the command that started it" in new Fixture {
      // Given
      private val input = Promise[Unit]()
      private var stamp: Option[Long] = None
      messageBus.handle(classOf[Retune])(_ => input.future.onCompleteOnBusiness(_ => messageBus.publish(Dog("Rex"))))
      messageBus.handle(classOf[Reset])(_ => ())
      messageBus.subscribe(classOf[Dog])(_ => stamp = ManualRuntime.causingCommandId)
      messageBus.dispatch(Retune(1))
      runtime.drainAll()
      messageBus.dispatch(Reset())
      runtime.drainAll()

      // When
      input.success(())
      runtime.drainAll()

      // Then
      stamp shouldEqual Some(1L)
    }

    "complete an ask's reply through the Business mailbox when the handler's Future completes later" in new Fixture {
      // Given
      private val input = Promise[String]()
      messageBus.handleRequest(classOf[Later])(_ => input.future)
      private val reply = messageBus.ask(Later())
      runtime.drainAll()

      // When
      input.success("late")

      // Then
      reply.isCompleted shouldBe false

      // When
      runtime.drainAll()

      // Then
      reply.value shouldEqual Some(Success("late"))
    }

    "complete an ask's reply after the drain of the continuation that completed the handler's Future" in new Fixture {
      // Given
      private val input = Promise[String]()
      private var reply: Future[String] = Future.never
      private var completedAtDelivery: Option[Boolean] = None
      messageBus.handleRequest(classOf[Later]) { _ =>
        input.future.mapOnBusiness { text =>
          messageBus.publish(Dog(text))
          text
        }
      }
      messageBus.subscribe(classOf[Dog])(_ => completedAtDelivery = Some(reply.isCompleted))
      reply = messageBus.ask(Later())
      runtime.drainAll()

      // When
      input.success("Rex")
      runtime.drainAll()

      // Then
      completedAtDelivery shouldEqual Some(false)
      reply.value shouldEqual Some(Success("Rex"))
    }

    "log the late failure of a dispatched request's Future" in new Fixture {
      // Given
      private val input = Promise[String]()
      messageBus.handleRequest(classOf[Echo])(_ => input.future)

      // When
      private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
        messageBus.dispatch(Echo("hi"))
        runtime.drainAll()
        input.failure(RuntimeException("late"))
        runtime.drainAll()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Echo(hi) failed.", Some("late")))
    }

    "continue with the Future that flatMapOnBusiness's function returns" in new Fixture {
      // Given
      private var output: Future[Int] = Future.never
      messageBus.handle(classOf[Retune]) { _ =>
        output = Future.successful(1).flatMapOnBusiness(value => Future.successful(value + 1))
      }

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      output.value shouldEqual Some(Success(2))
    }

    "fail the Future that mapOnBusiness returns when its function throws" in new Fixture {
      // Given
      private var output: Future[Int] = Future.never
      messageBus.handle(classOf[Retune]) { _ =>
        output = Future.successful(1).mapOnBusiness(_ => throw RuntimeException("boom"))
      }

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      output.value.get.failed.get.getMessage shouldEqual "boom"
    }

    "pass a failure to onCompleteOnBusiness, and skip mapOnBusiness's function" in new Fixture {
      // Given
      private val failure = RuntimeException("unreadable")
      private var completion: Option[Try[Int]] = None
      private var mapped: Future[Int] = Future.never
      messageBus.handle(classOf[Retune]) { _ =>
        val read = Future.failed[Int](failure)
        read.onCompleteOnBusiness(result => completion = Some(result))
        mapped = read.mapOnBusiness { value =>
          log += "mapped"
          value
        }
      }

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      completion shouldEqual Some(Failure(failure))
      mapped.value shouldEqual Some(Failure(failure))
      log shouldBe empty
    }

    "log an onCompleteOnBusiness function that throws, and fail the pending ask of its command" in new Fixture {
      // Given
      private val pending = Promise[String]()
      messageBus.handleRequest(classOf[Later]) { _ =>
        Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
        pending.future
      }

      // When
      private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
        val reply = messageBus.ask(Later())
        runtime.drainAll()
        reply
      }

      // Then
      reply.value.get.failed.get.getMessage shouldEqual "boom"
      events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
    }

    "log the handler's Future that fails after a continuation failed the reply" in new Fixture {
      // Given
      private val pending = Promise[String]()
      messageBus.handleRequest(classOf[Later]) { _ =>
        Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
        pending.future
      }

      // When
      private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
        val reply = messageBus.ask(Later())
        runtime.drainAll()
        pending.failure(RuntimeException("late"))
        runtime.drainAll()
        reply
      }

      // Then
      reply.value.get.failed.get.getMessage shouldEqual "boom"
      events.failuresAt(Level.ERROR) shouldEqual Seq(
        ("A continuation failed.", Some("boom")),
        ("The handler of Later() failed.", Some("late")))
    }

    "stay silent when the handler's Future succeeds after a continuation failed the reply" in new Fixture {
      // Given
      private val pending = Promise[String]()
      messageBus.handleRequest(classOf[Later]) { _ =>
        Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
        pending.future
      }

      // When
      private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
        messageBus.ask(Later())
        runtime.drainAll()
        pending.success("late")
        runtime.drainAll()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
    }

    "refuse a Business continuation off the Business Thread" in new Fixture {
      // Given
      private val completed = Future.successful(1)
      private var onUi: Option[Try[Future[Int]]] = None
      messageBus.subscribeOnUi(classOf[Dog])(_ => onUi = Some(Try(completed.mapOnBusiness(identity))))

      // When
      messageBus.publish(Dog("Rex"))
      runtime.drainAll()

      // Then
      an[IllegalStateException] should be thrownBy completed.mapOnBusiness(identity)
      an[IllegalStateException] should be thrownBy completed.flatMapOnBusiness(Future.successful)
      an[IllegalStateException] should be thrownBy completed.onCompleteOnBusiness(_ => ())
      onUi.get.failed.get shouldBe an[IllegalStateException]
    }
  }

  "the UI continuations" should {
    "run a mapOnUi continuation of an ask's reply after the UI subscribers of its events" in new Fixture {
      // Given
      messageBus.handleRequest(classOf[Echo]) { echo =>
        messageBus.publish(Dog(echo.text))
        Future.successful(echo.text)
      }
      messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"event ${dog.name}")
      messageBus.subscribeOnUi(classOf[Wolf]) { wolf =>
        messageBus.ask(Echo(wolf.name)).mapOnUi(text => log += s"reply $text")
      }

      // When
      messageBus.publish(Wolf("Grey"))
      runtime.drainAll()

      // Then
      log shouldEqual Seq("event Grey", "reply Grey")
    }

    "run mapOnUi's and onCompleteOnUi's functions on the UI Thread" in new Fixture {
      // Given
      private var output: Future[Int] = Future.never
      private var mappedOnUi = false
      private var completedOnUi = false
      messageBus.subscribeOnUi(classOf[Dog]) { _ =>
        output = Future.successful(1).mapOnUi { value =>
          mappedOnUi = Try(messageBus.requireUiThread()).isSuccess
          value * 2
        }
        Future.successful(1).onCompleteOnUi(_ => completedOnUi = Try(messageBus.requireUiThread()).isSuccess)
      }

      // When
      messageBus.publish(Dog("Rex"))
      runtime.drainAll()

      // Then
      output.value shouldEqual Some(Success(2))
      mappedOnUi shouldBe true
      completedOnUi shouldBe true
    }

    "log an onCompleteOnUi function that throws" in new Fixture {
      // Given
      messageBus.subscribeOnUi(classOf[Dog])(_ => Future.unit.onCompleteOnUi(_ => throw RuntimeException("boom")))

      // When
      private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
        messageBus.publish(Dog("Rex"))
        runtime.drainAll()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
    }

    "stamp nothing on a continuation that the toolkit starts outside a message" in withToolkitFixture { fixture =>
      import fixture.{given, *}
      // Given
      askWithUiEvent()
      var stamp: Option[Option[Long]] = None

      // When
      onToolkit(Future.unit.onCompleteOnUi(_ => stamp = Some(ManualRuntime.causingCommandId)))
      runtime.drainAll()

      // Then
      stamp shouldEqual Some(None)
    }

    "refuse a UI continuation off the UI Thread" in new Fixture {
      // Given
      private val completed = Future.successful(1)
      private var onBusiness: Option[Try[Future[Int]]] = None
      messageBus.handle(classOf[Retune])(_ => onBusiness = Some(Try(completed.mapOnUi(identity))))

      // When
      messageBus.dispatch(Retune(1))
      runtime.drainAll()

      // Then
      an[IllegalStateException] should be thrownBy completed.mapOnUi(identity)
      an[IllegalStateException] should be thrownBy completed.onCompleteOnUi(_ => ())
      onBusiness.get.failed.get shouldBe an[IllegalStateException]
    }
  }
}

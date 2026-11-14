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

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * The behaviors of the `Future` continuations of [[BusinessContinuations]] and [[UiContinuations]] that hold on any
 * [[MessagingRuntime]], for a suite that runs them on the [[MessagingTestRuntime]] its [[newTestRuntime]] makes.
 */
trait FutureContinuationsBehaviors extends MessagingFixtures {
  this: AnyWordSpec & Matchers =>

  import BusinessContinuations.*
  import LogCapture.*
  import MessagingFixtures.*
  import UiContinuations.*

  /**
   * Registers the cases of the Business and of the UI continuations, one `should` group each.
   */
  def futureContinuations(): Unit = {
    "the Business continuations" should {
      "run mapOnBusiness's function on the Business Thread once the Future completes" in new Fixture {
        // Given
        private val input = Promise[Int]()
        private var output: Future[Int] = Future.never
        private var onBusiness = false
        messageBus.handleCommand(classOf[Retune]) { _ =>
          output = input.future.mapOnBusiness { value =>
            onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
            value * 2
          }
        }
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // When
        input.success(21)
        awaitIdle()

        // Then
        output.value shouldEqual Some(Success(42))
        onBusiness shouldBe true
      }

      "enqueue the continuation of a completed Future instead of running it in place" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune]) { _ =>
          Future.successful(1).onCompleteOnBusiness(_ => log += "continuation")
          log += "handler"
        }

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        log.toSeq shouldEqual Seq("handler", "continuation")
      }

      "deliver the events a continuation publishes after it returns" in new Fixture {
        // Given
        messageBus.handleCommand(classOf[Retune]) { _ =>
          Future.unit.onCompleteOnBusiness { _ =>
            messageBus.publish(Dog("Rex"))
            log += "continuation"
          }
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += dog.name)

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        log.toSeq shouldEqual Seq("continuation", "Rex")
      }

      "stamp the events a continuation publishes with the command that started it" in new Fixture {
        // Given
        private val input = Promise[Unit]()
        private var stamp: Option[Long] = None
        messageBus.handleCommand(classOf[Retune])(_ =>
          input.future.onCompleteOnBusiness(_ => messageBus.publish(Dog("Rex"))))
        messageBus.handleCommand(classOf[Reset])(_ => ())
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => stamp = MessagingTestRuntime.causingCommandId)
        messageBus.dispatch(Retune(1))
        awaitIdle()
        messageBus.dispatch(Reset())
        awaitIdle()

        // When
        input.success(())
        awaitIdle()

        // Then
        stamp shouldEqual Some(1L)
      }

      "stamp the continuations of overlapping requests with their own commands, whichever completes first" in
        new Fixture {
          // Given
          private val inputs = Map("first" -> Promise[String](), "second" -> Promise[String]())
          private val stamps = SynchronizedBuffer[(String, Option[Long])]()
          messageBus.handleRequest(classOf[Echo]) { echo =>
            inputs(echo.text).future.mapOnBusiness { text =>
              messageBus.publish(Dog(text))
              text
            }
          }
          messageBus.handleCommand(classOf[Retune])(_ => ())
          messageBus.subscribeOnBusiness(classOf[Dog])(dog =>
            stamps += dog.name -> MessagingTestRuntime.causingCommandId)
          messageBus.subscribeOnUi(classOf[Dog])(dog =>
            stamps += s"${dog.name} on UI" -> MessagingTestRuntime.causingCommandId)
          private val first = messageBus.ask(Echo("first"))
          private val second = messageBus.ask(Echo("second"))
          messageBus.dispatch(Retune(3))
          awaitIdle()

          // When
          inputs("second").success("second")
          awaitIdle()
          inputs("first").success("first")
          awaitIdle()

          // Then
          stamps.toSeq should contain theSameElementsAs Seq(
            "second" -> Some(2L), "second on UI" -> Some(2L), "first" -> Some(1L), "first on UI" -> Some(1L))
          first.value shouldEqual Some(Success("first"))
          second.value shouldEqual Some(Success("second"))
        }

      "stamp a continuation with the command that started it when a later command completes its Future" in
        new Fixture {
          // Given
          private val input = Promise[String]()
          private var stamp: Option[Long] = None
          messageBus.handleRequest(classOf[Later]) { _ =>
            input.future.mapOnBusiness { text =>
              messageBus.publish(Dog(text))
              text
            }
          }
          messageBus.handleCommand(classOf[Retune])(retune => input.success(s"Rex ${retune.index}"))
          messageBus.subscribeOnBusiness(classOf[Dog])(_ => stamp = MessagingTestRuntime.causingCommandId)

          // When
          private val reply = messageBus.ask(Later())
          messageBus.dispatch(Retune(1))
          awaitIdle()

          // Then
          stamp shouldEqual Some(1L)
          reply.value shouldEqual Some(Success("Rex 1"))
        }

      "complete an ask's reply on the Business Thread when the handler's Future completes later" in new Fixture {
        // Given
        private val input = Promise[String]()
        private var completedOnBusiness = false
        messageBus.handleRequest(classOf[Later])(_ => input.future)
        private val reply = messageBus.ask(Later())
        awaitIdle()
        // `parasitic` runs the callback on the thread that completes the reply.
        reply.onComplete(_ => completedOnBusiness = Try(messageBus.requireBusinessThread()).isSuccess)(
          using ExecutionContext.parasitic)

        // When
        input.success("late")
        awaitIdle()

        // Then
        reply.value shouldEqual Some(Success("late"))
        completedOnBusiness shouldBe true
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
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => completedAtDelivery = Some(reply.isCompleted))
        reply = messageBus.ask(Later())
        awaitIdle()

        // When
        input.success("Rex")
        awaitIdle()

        // Then
        completedAtDelivery shouldEqual Some(false)
        reply.value shouldEqual Some(Success("Rex"))
      }

      "log the late failure of a dispatched request's Future" in new Fixture {
        // Given
        private val input = Promise[String]()
        messageBus.handleRequest(classOf[Echo])(_ => input.future)

        // When
        private val (_, events) = capturingLogs {
          messageBus.dispatch(Echo("hi"))
          awaitIdle()
          input.failure(RuntimeException("late"))
          awaitIdle()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Echo(hi) failed.", Some("late")))
      }

      "continue with the Future that flatMapOnBusiness's function returns" in new Fixture {
        // Given
        private var output: Future[Int] = Future.never
        messageBus.handleCommand(classOf[Retune]) { _ =>
          output = Future.successful(1).flatMapOnBusiness(value => Future.successful(value + 1))
        }

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        output.value shouldEqual Some(Success(2))
      }

      "fail the Future that mapOnBusiness returns when its function throws" in new Fixture {
        // Given
        private var output: Future[Int] = Future.never
        messageBus.handleCommand(classOf[Retune]) { _ =>
          output = Future.successful(1).mapOnBusiness(_ => throw RuntimeException("boom"))
        }

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        output.value.get.failed.get.getMessage shouldEqual "boom"
      }

      "pass a failure to onCompleteOnBusiness, and skip mapOnBusiness's function" in new Fixture {
        // Given
        private val failure = RuntimeException("unreadable")
        private var completion: Option[Try[Int]] = None
        private var mapped: Future[Int] = Future.never
        messageBus.handleCommand(classOf[Retune]) { _ =>
          val read = Future.failed[Int](failure)
          read.onCompleteOnBusiness(result => completion = Some(result))
          mapped = read.mapOnBusiness { value =>
            log += "mapped"
            value
          }
        }

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        completion shouldEqual Some(Failure(failure))
        mapped.value shouldEqual Some(Failure(failure))
        log.toSeq shouldBe empty
      }

      "log an onCompleteOnBusiness function that throws, and fail the pending ask of its command" in new Fixture {
        // Given
        private val pending = Promise[String]()
        messageBus.handleRequest(classOf[Later]) { _ =>
          Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
          pending.future
        }

        // When
        private val (reply, events) = capturingLogs {
          val reply = messageBus.ask(Later())
          awaitIdle()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "boom"
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
      }

      "fail each overlapping request's reply with the failure of its own continuation's subscriber" in new Fixture {
        // Given
        private val inputs = Map("first" -> Promise[String](), "second" -> Promise[String]())
        messageBus.handleRequest(classOf[Echo]) { echo =>
          inputs(echo.text).future.mapOnBusiness { text =>
            messageBus.publish(Dog(text))
            text
          }
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(dog => throw RuntimeException(s"bite ${dog.name}"))

        // When
        private val ((first, second), events) = capturingLogs {
          val first = messageBus.ask(Echo("first"))
          val second = messageBus.ask(Echo("second"))
          awaitIdle()
          inputs("second").success("second")
          inputs("first").success("first")
          awaitIdle()
          (first, second)
        }

        // Then
        first.value.get.failed.get.getMessage shouldEqual "bite first"
        second.value.get.failed.get.getMessage shouldEqual "bite second"
        suppressedMessages(first) shouldBe empty
        suppressedMessages(second) shouldBe empty
        events.failuresAt(Level.ERROR) shouldEqual Seq(
          ("A subscriber of Dog(second) failed.", Some("bite second")),
          ("A subscriber of Dog(first) failed.", Some("bite first")))
      }

      "suppress in a continuation's failure the failures of the subscribers of its events" in new Fixture {
        // Given
        private val pending = Promise[String]()
        messageBus.handleRequest(classOf[Later]) { _ =>
          Future.unit.onCompleteOnBusiness { _ =>
            messageBus.publish(Dog("Rex"))
            throw RuntimeException("boom")
          }
          pending.future
        }
        messageBus.subscribeOnBusiness(classOf[Dog])(_ => throw RuntimeException("bite"))

        // When
        private val (reply, _) = capturingLogs {
          val reply = messageBus.ask(Later())
          awaitIdle()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "boom"
        suppressedMessages(reply) shouldEqual Seq("bite")
      }

      "log the handler's Future that fails after a continuation failed the reply" in new Fixture {
        // Given
        private val pending = Promise[String]()
        messageBus.handleRequest(classOf[Later]) { _ =>
          Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
          pending.future
        }

        // When
        private val (reply, events) = capturingLogs {
          val reply = messageBus.ask(Later())
          awaitIdle()
          pending.failure(RuntimeException("late"))
          awaitIdle()
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
        private val (reply, events) = capturingLogs {
          val reply = messageBus.ask(Later())
          awaitIdle()
          pending.success("late")
          awaitIdle()
          reply
        }

        // Then
        reply.value.get.failed.get.getMessage shouldEqual "boom"
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
      }

      "only log a failure reported off the Business Thread, failing no reply" in new Fixture {
        // Given
        private val input = Promise[String]()
        messageBus.handleRequest(classOf[Later])(_ => input.future.mapOnBusiness(identity))
        private val reply = messageBus.ask(Later())
        awaitIdle()

        // When
        private val (_, events) = capturingLogs {
          // Scala reports a failure of the context's `execute` on the thread that completes the Future, such as this.
          messageBus.businessContinuationsExecutionContext.reportFailure(RuntimeException("elsewhere"))
          input.success("loaded")
          awaitIdle()
        }

        // Then
        reply.value shouldEqual Some(Success("loaded"))
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("elsewhere")))
      }

      "refuse the Business continuations on a thread that runs no message" in new Fixture {
        // Given
        private val completed = Future.successful(1)

        // When / Then
        // The test's thread runs no Business message here.
        an[IllegalStateException] should be thrownBy completed.mapOnBusiness(identity)
        an[IllegalStateException] should be thrownBy completed.flatMapOnBusiness(Future.successful)
        an[IllegalStateException] should be thrownBy completed.onCompleteOnBusiness(_ => ())
      }

      "refuse a Business continuation on the UI Thread" in new Fixture {
        // Given
        private var onUi: Option[Try[Future[Int]]] = None
        messageBus.subscribeOnUi(classOf[Dog])(_ => onUi = Some(Try(Future.successful(1).mapOnBusiness(identity))))

        // When
        messageBus.publish(Dog("Rex"))
        awaitIdle()

        // Then
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
        awaitIdle()

        // Then
        log.toSeq shouldEqual Seq("event Grey", "reply Grey")
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
        awaitIdle()

        // Then
        output.value shouldEqual Some(Success(2))
        mappedOnUi shouldBe true
        completedOnUi shouldBe true
      }

      "continue with the Future that flatMapOnUi's function returns, calling it on the UI Thread" in new Fixture {
        // Given
        private var output: Future[Int] = Future.never
        private var calledOnUi = false
        messageBus.subscribeOnUi(classOf[Dog]) { _ =>
          output = Future.successful(1).flatMapOnUi { value =>
            calledOnUi = Try(messageBus.requireUiThread()).isSuccess
            Future.successful(value + 1)
          }
        }

        // When
        messageBus.publish(Dog("Rex"))
        awaitIdle()

        // Then
        output.value shouldEqual Some(Success(2))
        calledOnUi shouldBe true
      }

      "log an onCompleteOnUi function that throws" in new Fixture {
        // Given
        messageBus.subscribeOnUi(classOf[Dog])(_ => Future.unit.onCompleteOnUi(_ => throw RuntimeException("boom")))

        // When
        private val (_, events) = capturingLogs {
          messageBus.publish(Dog("Rex"))
          awaitIdle()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
      }

      "stamp nothing on a continuation that the toolkit starts outside a message" in new Fixture {
        // Given
        askWithUiEvent()
        private var stamp: Option[Option[Long]] = None

        // When
        onToolkit(Future.unit.onCompleteOnUi(_ => stamp = Some(MessagingTestRuntime.causingCommandId)))
        awaitIdle()

        // Then
        stamp shouldEqual Some(None)
      }

      "refuse the UI continuations on a thread that runs no message" in new Fixture {
        // Given
        private val completed = Future.successful(1)

        // When / Then
        // The test's thread runs no UI message here.
        an[IllegalStateException] should be thrownBy completed.mapOnUi(identity)
        an[IllegalStateException] should be thrownBy completed.flatMapOnUi(Future.successful)
        an[IllegalStateException] should be thrownBy completed.onCompleteOnUi(_ => ())
      }

      "refuse a UI continuation on the Business Thread" in new Fixture {
        // Given
        private var onBusiness: Option[Try[Future[Int]]] = None
        messageBus.handleCommand(classOf[Retune])(_ => onBusiness = Some(Try(Future.successful(1).mapOnUi(identity))))

        // When
        messageBus.dispatch(Retune(1))
        awaitIdle()

        // Then
        onBusiness.get.failed.get shouldBe an[IllegalStateException]
      }
    }
  }
}

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

import ch.qos.logback.classic.spi.ILoggingEvent
import org.scalatest.{Outcome, TestSuite}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Future, Promise}

/**
 * The fixtures of the suites that run a [[MessageBus]] on a [[MessagingTestRuntime]], which closes each runtime that a
 * case made once the case ends.
 */
trait MessagingFixtures extends TestSuite {

  import MessagingFixtures.*

  private val testRuntimes: SynchronizedBuffer[MessagingTestRuntime] = SynchronizedBuffer()

  /**
   * Makes the fresh test runtime of a [[Fixture]]; the suite closes it after the case.
   */
  protected def newTestRuntime(): MessagingTestRuntime

  /**
   * Runs the case, then closes every test runtime made during it.
   */
  override def withFixture(test: NoArgTest): Outcome = {
    try {
      super.withFixture(test)
    } finally {
      val made = testRuntimes.toSeq
      testRuntimes.clear()
      made.foreach(_.close())
    }
  }

  /**
   * A case's bus, on a fresh test runtime, with a log that its messages may append to from any thread.
   */
  abstract class Fixture {
    /** The runtime under test, registered with the suite to be closed after the case. */
    val testRuntime: MessagingTestRuntime = {
      val made = newTestRuntime()
      testRuntimes += made
      made
    }

    /** The bus under test, on [[testRuntime]]. */
    val messageBus: MessageBus = MessageBus(testRuntime.runtime)

    given MessageBus = messageBus

    /** What the case's handlers and subscribers record, to be read after [[awaitIdle]]. */
    val log: SynchronizedBuffer[String] = SynchronizedBuffer()

    /** See [[MessagingTestRuntime.awaitIdle]]. */
    def awaitIdle(): Unit = testRuntime.awaitIdle()

    /** See [[MessagingTestRuntime.onToolkit]]. */
    def onToolkit[T](body: => T): T = testRuntime.onToolkit(body)

    /** Runs `body` while capturing the logs of [[MessageBus]]; see [[MessagingTestRuntime.capturingLogs]]. */
    def capturingLogs[R](body: => R): (R, Seq[ILoggingEvent]) =
      testRuntime.capturingLogs(classOf[MessageBus].getName)(body)

    /**
     * Asks a request whose handler publishes an event to a UI subscriber and returns a `Future` that never completes,
     * awaits idleness, and returns the pending reply; the UI Thread's last message was thus caused by the request.
     */
    def askWithUiEvent(): Future[String] = {
      messageBus.handleRequest(classOf[Later]) { _ =>
        messageBus.publish(Dog("Rex"))
        Promise[String]().future
      }
      messageBus.subscribeOnUi(classOf[Dog])(_ => ())
      val reply = messageBus.ask(Later())
      awaitIdle()
      reply
    }
  }
}

/**
 * The constants and messages of the suites that mix [[MessagingFixtures]] in.
 */
object MessagingFixtures {
  /** How long an `Await` in a test waits, which happens only when the bus fails to refuse it. */
  val RefusedAwaitTimeout: FiniteDuration = 1.second

  /** How many threads dispatch at once in the concurrent-id test. */
  val DispatcherCount: Int = 8

  /** How many commands each of those threads dispatches, kept under `ManualMessagingRuntime.MaxDrainedMessages`. */
  val DispatchesPerThread: Int = 500

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

  /** An event whose `toString` throws. */
  class Nameless {
    override def toString: String = throw RuntimeException("no name")
  }

  /** The messages of the exceptions that the exception of the failed `reply` suppresses, in the order added. */
  def suppressedMessages(reply: Future[?]): Seq[String] =
    reply.value.get.failed.get.getSuppressed.toSeq.map(_.getMessage)
}

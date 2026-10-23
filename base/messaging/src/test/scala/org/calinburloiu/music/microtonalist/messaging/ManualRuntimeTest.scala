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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.CompletableFuture
import scala.collection.mutable
import scala.util.Try

object ManualRuntimeTest {
  sealed trait Message {
    def name: String
  }

  case class Normal(name: String) extends Message

  case class UrgentMessage(name: String) extends Message with Urgent
}

class ManualRuntimeTest extends AnyWordSpec with Matchers {

  import ManualRuntimeTest.*

  abstract class Fixture {
    val runtime: ManualRuntime = ManualRuntime()
    val handled: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
    val business: ManualMailbox[Message] =
      runtime.startMailbox[Message]("Business", Thread.NORM_PRIORITY)(message => handled += message.name)
  }

  "a ManualRuntime" should {
    "record the name and priority of each mailbox it starts, in start order" in new Fixture {
      // When
      runtime.startUiMailbox[Message](_ => ())
      runtime.startMailbox[Message]("Track-1", Thread.NORM_PRIORITY + 2)(_ => ())

      // Then
      runtime.mailboxes.map(mailbox => (mailbox.name, mailbox.priority)) shouldEqual Seq(
        ("Business", Some(Thread.NORM_PRIORITY)), ("UI", None), ("Track-1", Some(Thread.NORM_PRIORITY + 2)))
      runtime.mailbox("Track-1").name shouldEqual "Track-1"
    }

    "fail to find a mailbox it didn't start" in new Fixture {
      // When / Then
      a[NoSuchElementException] should be thrownBy runtime.mailbox("Unknown")
    }

    "run nothing until drained, then the messages in arrival order" in new Fixture {
      // When
      business.enqueue(Normal("a"))
      business.enqueue(Normal("b"))

      // Then
      handled shouldBe empty
      business.isEmpty shouldBe false

      // When
      runtime.drainAll()

      // Then
      handled shouldEqual Seq("a", "b")
      business.isEmpty shouldBe true
    }

    "accept messages enqueued from another thread" in new Fixture {
      // Given
      CompletableFuture.runAsync(() => business.enqueue(Normal("a"))).join()

      // When
      runtime.drainAll()

      // Then
      handled shouldEqual Seq("a")
    }

    "let urgent messages overtake the queued ones, each lane keeping its order" in new Fixture {
      // Given
      Seq(Normal("a"), UrgentMessage("u1"), Normal("b"), UrgentMessage("u2")).foreach(business.enqueue)

      // When
      business.drain()

      // Then
      handled shouldEqual Seq("u1", "u2", "a", "b")
    }

    "drain the messages that messages enqueue, across mailboxes, until all are empty" in new Fixture {
      // Given
      private val track = runtime.startMailbox[Message]("Track-1", Thread.NORM_PRIORITY + 2) { message =>
        handled += message.name
        business.enqueue(Normal(s"${message.name}-ack"))
      }
      private val ui = runtime.startUiMailbox[Message] { message =>
        handled += message.name
        track.enqueue(Normal("from-ui"))
      }
      ui.enqueue(Normal("click"))

      // When
      runtime.drainAll()

      // Then
      handled shouldEqual Seq("click", "from-ui", "from-ui-ack")
    }

    "treat a mailbox's thread as current only while it runs one of its messages there" in new Fixture {
      // Given
      private var during: Option[(Boolean, Boolean, Boolean)] = None
      private val ui = runtime.startUiMailbox[Message](_ => ())
      private val probe: ManualMailbox[Message] = runtime.startMailbox[Message]("Probe", Thread.NORM_PRIORITY) { _ =>
        during = Some((probe.isCurrentThread, ui.isCurrentThread,
          CompletableFuture.supplyAsync(() => probe.isCurrentThread).join()))
      }
      probe.enqueue(Normal("a"))

      // When
      runtime.drainAll()

      // Then
      during shouldEqual Some((true, false, false))
      probe.isCurrentThread shouldBe false
    }

    "refuse to drain while one of its mailboxes runs a message" in new Fixture {
      // Given
      private var attempt: Option[Try[Unit]] = None
      private val reentrant = runtime.startMailbox[Message]("Reentrant", Thread.NORM_PRIORITY) { _ =>
        attempt = Some(Try(runtime.drainAll()))
      }
      reentrant.enqueue(Normal("a"))

      // When
      runtime.drainAll()

      // Then
      attempt.get.failed.get shouldBe an[IllegalStateException]
    }

    "fail a drain whose messages keep enqueuing others" in new Fixture {
      // Given
      private val loop: ManualMailbox[Message] =
        runtime.startMailbox[Message]("Loop", Thread.NORM_PRIORITY)(message => loop.enqueue(message))
      loop.enqueue(Normal("again"))

      // When / Then
      an[IllegalStateException] should be thrownBy runtime.drainAll()
    }

    "run the queued messages before completing a stop, and drop the later ones" in new Fixture {
      // Given
      business.enqueue(Normal("a"))

      // When
      private val stopped = business.stop()
      business.enqueue(Normal("late"))

      // Then
      stopped.isCompleted shouldBe false

      // When
      runtime.drainAll()

      // Then
      handled shouldEqual Seq("a")
      stopped.isCompleted shouldBe true
    }

    "complete a stop at once when nothing is queued" in new Fixture {
      // When / Then
      business.stop().isCompleted shouldBe true
    }
  }
}

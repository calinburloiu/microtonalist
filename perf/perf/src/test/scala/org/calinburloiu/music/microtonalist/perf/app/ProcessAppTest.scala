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

package org.calinburloiu.music.microtonalist.perf.app

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ProcessAppTest extends AnyWordSpec with Matchers {

  /** How long a test lets a process exit after asking it to. */
  private val StopTimeout: FiniteDuration = 200.millis

  private def logFile(): Path = Files.createTempDirectory("process-app").resolve("app.log")

  "ProcessApp" should {
    "capture the app's output in its log" in {
      // Given
      val log = logFile()
      val app = ProcessApp.start(Seq("sh", "-c", "echo started; echo failed >&2"), log)

      // When
      app.awaitExit(10.seconds)

      // Then
      Files.readString(log) should include("started")
      Files.readString(log) should include("failed")
      app.logFile shouldEqual Some(log)
    }

    "stop the app" in {
      // Given
      val app = ProcessApp.start(Seq("cat"), logFile())
      app.isAlive shouldBe true

      // When
      app.stop(StopTimeout)

      // Then
      app.isAlive shouldBe false
      app.exitCode shouldBe defined
    }

    "kill an app that ignores the request to stop" in {
      // Given
      val app = ProcessApp.start(Seq("sh", "-c", "trap '' TERM; exec sleep 60"), logFile())
      val start = System.nanoTime()

      // When
      app.stop(StopTimeout)

      // Then
      app.isAlive shouldBe false
      (System.nanoTime() - start) should be < 10.seconds.toNanos
    }

    "report the exit code of an app that exited" in {
      // Given
      val app = ProcessApp.start(Seq("sh", "-c", "exit 3"), logFile())

      // When
      app.awaitExit(10.seconds)

      // Then
      app.isAlive shouldBe false
      app.exitCode shouldEqual Some(3)
    }
  }
}

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

package org.calinburloiu.music.microtonalist.perf.midilatency.app

import org.calinburloiu.music.microtonalist.perf.midilatency.env.ProcessCommandRunner
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
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
      val dir = Files.createTempDirectory("process-app")
      val ready = dir.resolve("ready")
      ProcessCommandRunner.run(Seq("mkfifo", ready.toString)).exitCode shouldEqual 0
      // The app tells through the FIFO when it ignores TERM, which the process it execs inherits
      val app = ProcessApp.start(Seq("sh", "-c", s"trap '' TERM; echo ready > '$ready'; exec sleep 60"),
        dir.resolve("app.log"))
      CompletableFuture.supplyAsync(() => Files.readString(ready)).get(10, TimeUnit.SECONDS) shouldEqual "ready\n"
      val start = System.nanoTime()

      // When
      app.stop(StopTimeout)

      // Then
      app.isAlive shouldBe false
      // 128 + SIGKILL
      app.exitCode shouldEqual Some(137)
      (System.nanoTime() - start) should be < 10.seconds.toNanos
    }

    "kill the app when the JVM exits" in {
      // Given
      val app = ProcessApp.start(Seq("cat"), logFile())

      // When
      app.shutdownHook.run()

      // Then
      app.awaitExit(10.seconds) shouldBe true
      app.stop(StopTimeout)
    }

    "register its shutdown hook while the app runs, and forget it once stopped" in {
      // Given
      val app = ProcessApp.start(Seq("cat"), logFile())
      val runtime = Runtime.getRuntime
      runtime.removeShutdownHook(app.shutdownHook) shouldBe true
      runtime.addShutdownHook(app.shutdownHook)

      // When
      app.stop(StopTimeout)

      // Then
      runtime.removeShutdownHook(app.shutdownHook) shouldBe false
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

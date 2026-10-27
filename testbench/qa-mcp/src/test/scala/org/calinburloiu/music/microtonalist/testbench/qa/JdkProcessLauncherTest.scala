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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class JdkProcessLauncherTest extends AnyWordSpec with Matchers {

  /** How long a test waits for a process that should exit at once. */
  private val ExitTimeoutSeconds: Long = 10

  private trait Fixture {
    val launcher: JdkProcessLauncher = JdkProcessLauncher()
    val dir: Path = Files.createTempDirectory("qa")

    def exitCode(process: LaunchedProcess): Int = process.onExit.get(ExitTimeoutSeconds, TimeUnit.SECONDS)
  }

  "JdkProcessLauncher.start" should {
    "pipe the input and append the output" in new Fixture {
      // Given
      val output: Path = dir.resolve("cat.log")
      Files.writeString(output, "before\n")

      // When
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("cat"), Some(output), pipeInput = true))
      process.input.write("x\n".getBytes(UTF_8))
      process.input.close()

      // Then
      exitCode(process) shouldBe 0
      Files.readString(output) shouldBe "before\nx\n"
    }

    "append the standard error too" in new Fixture {
      // Given
      val output: Path = dir.resolve("sh.log")

      // When
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("sh", "-c", "echo out; echo err >&2"),
        Some(output)))

      // Then
      exitCode(process) shouldBe 0
      Files.readString(output) shouldBe "out\nerr\n"
    }

    "give an empty input, and discard the output, unless asked otherwise" in new Fixture {
      // When
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("cat")))

      // Then
      exitCode(process) shouldBe 0
      an[IllegalStateException] should be thrownBy process.input
    }

    "tell the process's PID and start instant" in new Fixture {
      // Given
      val before: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

      // When
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("sleep", "60")))

      // Then
      try {
        process.isAlive shouldBe true
        process.pid should be > 0L
        process.startInstant should be >= before
      } finally {
        process.kill()
      }
    }

    "terminate a process" in new Fixture {
      // Given
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("sleep", "60")))

      // When
      process.terminate()

      // Then
      exitCode(process) should not be 0
      process.isAlive shouldBe false
    }

    "kill a process" in new Fixture {
      // Given
      val process: LaunchedProcess = launcher.start(ProcessSpec(Seq("sleep", "60")))

      // When
      process.kill()

      // Then
      exitCode(process) should not be 0
    }

    "fail for a missing program" in new Fixture {
      // When / Then
      an[IOException] should be thrownBy launcher.start(ProcessSpec(Seq("microtonalist-missing-program")))
    }
  }

  "JdkProcessLauncher.run" should {
    "run a command" in new Fixture {
      // When / Then
      launcher.run(Seq("echo", "hi"), 5.seconds) shouldBe CommandResult(0, "hi\n", "")
    }

    "collect the standard error and the exit code" in new Fixture {
      // When / Then
      launcher.run(Seq("sh", "-c", "echo boom >&2; exit 3"), 5.seconds) shouldBe CommandResult(3, "", "boom\n")
    }

    "kill a command that doesn't finish in time" in new Fixture {
      // When / Then
      (the[QaException] thrownBy launcher.run(Seq("sleep", "60"), 100.millis)).getMessage shouldBe
        "sleep 60 didn't finish in 100 milliseconds"
    }

    "fail for a missing program" in new Fixture {
      // When / Then
      an[IOException] should be thrownBy launcher.run(Seq("microtonalist-missing-program"), 5.seconds)
    }
  }
}

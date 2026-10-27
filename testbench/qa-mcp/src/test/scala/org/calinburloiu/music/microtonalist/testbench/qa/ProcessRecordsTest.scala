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

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

class ProcessRecordsTest extends AnyWordSpec with Matchers {

  private val AwaitSeconds: Long = 5

  private abstract class Fixture {
    val baseDir: Path = Files.createTempDirectory("qa")
    val run: QaRun = QaRun.create(baseDir)

    private val launcher: JdkProcessLauncher = JdkProcessLauncher()
    private val started: ArrayBuffer[LaunchedProcess] = ArrayBuffer()

    /** Starts a real process that runs for a minute; [[killAll]] kills it. */
    def startSleep(): LaunchedProcess = {
      val process = launcher.start(ProcessSpec(Seq("sleep", "60")))
      started += process
      process
    }

    /** Records as the run's server a process that has exited. */
    def writeDeadServer(): Unit = {
      val server = startSleep()
      server.kill()
      server.onExit.get(AwaitSeconds, TimeUnit.SECONDS)
      ProcessRecords.writeServer(run, server.pid, server.startInstant)
    }

    def killAll(): Unit = started.foreach(_.kill())

    /** Runs a case, then kills the processes it started. */
    def cleaningUp(body: => Unit): Unit = {
      try {
        body
      } finally {
        killAll()
      }
    }
  }

  "ProcessRecords.sweep" should {
    "terminate the processes of a run whose server is gone" in new Fixture {
      cleaningUp {
        // Given
        writeDeadServer()
        val orphan: LaunchedProcess = startSleep()
        ProcessRecords.writeProcesses(run, Seq(orphan))

        // When
        val swept: Seq[Long] = ProcessRecords.sweep(baseDir)

        // Then
        swept shouldBe Seq(orphan.pid)
        orphan.onExit.get(AwaitSeconds, TimeUnit.SECONDS)
        orphan.isAlive shouldBe false
      }
    }

    "leave a run whose server is alive" in new Fixture {
      cleaningUp {
        // Given
        ProcessRecords.writeServer(run)
        val process: LaunchedProcess = startSleep()
        ProcessRecords.writeProcesses(run, Seq(process))

        // When
        val swept: Seq[Long] = ProcessRecords.sweep(baseDir)

        // Then
        swept shouldBe empty
        process.isAlive shouldBe true
      }
    }

    "leave a PID whose start instant differs" in new Fixture {
      cleaningUp {
        // Given
        writeDeadServer()
        val other: LaunchedProcess = startSleep()
        val recorded: FakeProcess = FakeProcess(ProcessSpec(Seq("sleep", "60")), other.pid,
          startInstant = other.startInstant.minusSeconds(1))
        ProcessRecords.writeProcesses(run, Seq(recorded))

        // When
        val swept: Seq[Long] = ProcessRecords.sweep(baseDir)

        // Then
        swept shouldBe empty
        other.isAlive shouldBe true
      }
    }

    "skip a run that has no server record" in new Fixture {
      cleaningUp {
        // Given
        val process: LaunchedProcess = startSleep()
        ProcessRecords.writeProcesses(run, Seq(process))

        // When
        val swept: Seq[Long] = ProcessRecords.sweep(baseDir)

        // Then
        swept shouldBe empty
        process.isAlive shouldBe true
      }
    }

    "sweep nothing when the base directory doesn't exist" in {
      // When / Then
      ProcessRecords.sweep(Path.of("/nonexistent/microtonalist-qa")) shouldBe empty
    }

    "skip a run whose server record is unreadable" in new Fixture {
      cleaningUp {
        // Given
        Files.writeString(run.file("server.json"), "{ not JSON")
        val process: LaunchedProcess = startSleep()
        ProcessRecords.writeProcesses(run, Seq(process))

        // When / Then
        ProcessRecords.sweep(baseDir) shouldBe empty
        process.isAlive shouldBe true
      }
    }
  }

  "ProcessRecords.writeServer" should {
    "record this JVM's PID and start instant" in new Fixture {
      // When
      ProcessRecords.writeServer(run)

      // Then
      Files.readString(run.file("server.json")) should include(s"\"pid\":${ProcessHandle.current().pid()}")
      Files.readString(run.file("server.json")) should
        include(ProcessHandle.current().info().startInstant().get().toString)
    }
  }

  "ProcessRecords.writeProcesses" should {
    "replace the run's process records" in new Fixture {
      // Given
      val instant: Instant = Instant.parse("2026-10-27T12:00:00Z")
      ProcessRecords.writeProcesses(run, Seq(FakeProcess(ProcessSpec(Seq("a")), 1001, instant)))

      // When
      ProcessRecords.writeProcesses(run, Seq(FakeProcess(ProcessSpec(Seq("b")), 1002, instant)))

      // Then
      Files.readString(run.file("processes.json")) shouldBe
        """[{"pid":1002,"startInstant":"2026-10-27T12:00:00Z"}]"""
    }
  }
}

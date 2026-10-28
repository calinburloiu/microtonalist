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

import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.CompletableFuture
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

/**
 * A [[ProcessLauncher]] that starts no process: it records what it's asked to start and run, and hands out
 * [[FakeProcess]]es, whose PIDs count up from [[FakeProcessLauncher.FirstPid]].
 */
class FakeProcessLauncher extends ProcessLauncher {
  /** Fails the start of a spec with the exception it returns, e.g. an `IOException` for a missing tool. */
  @volatile var startFailure: ProcessSpec => Option[Exception] = _ => None

  /** Creates the process of a spec that starts, given its PID; replace it to make processes behave otherwise. */
  @volatile var newProcess: (ProcessSpec, Long) => FakeProcess = (spec, pid) => FakeProcess(spec, pid)

  /** The result of each command run, or the exception it throws. */
  @volatile var runResult: Seq[String] => CommandResult = _ => CommandResult(0, "", "")

  private val _specs: ArrayBuffer[ProcessSpec] = ArrayBuffer()
  private val _processes: ArrayBuffer[FakeProcess] = ArrayBuffer()
  private val _runs: ArrayBuffer[Seq[String]] = ArrayBuffer()

  override def start(spec: ProcessSpec): LaunchedProcess = synchronized {
    _specs += spec
    startFailure(spec).foreach(e => throw e)
    val process = newProcess(spec, FakeProcessLauncher.FirstPid + _processes.size)
    _processes += process
    process
  }

  override def run(command: Seq[String], timeout: FiniteDuration): CommandResult = {
    synchronized {
      _runs += command
    }
    runResult(command)
  }

  /** Each spec it was asked to start, in order, the failed ones included. */
  def specs: Seq[ProcessSpec] = synchronized(_specs.toSeq)

  /** Each process it started, in order. */
  def processes: Seq[FakeProcess] = synchronized(_processes.toSeq)

  /** The process it started at `index`, counting from 0. */
  def process(index: Int): FakeProcess = synchronized(_processes(index))

  /** Each command it was asked to run, in order. */
  def runs: Seq[Seq[String]] = synchronized(_runs.toSeq)
}

object FakeProcessLauncher {
  /** The PID of the first process a [[FakeProcessLauncher]] starts. */
  val FirstPid: Long = 1000
}

/**
 * A process that runs until it's told to exit.
 *
 * Its input records what's written to it, and fails like a real pipe's once the process has exited or the input is
 * closed.
 *
 * @param ignoresTerminate whether it keeps running on [[terminate]], so that only [[kill]] stops it
 */
class FakeProcess(val spec: ProcessSpec, override val pid: Long,
                  override val startInstant: Instant = Instant.parse("2026-10-27T12:00:00Z"),
                  val ignoresTerminate: Boolean = false) extends LaunchedProcess {
  override val onExit: CompletableFuture[Integer] = CompletableFuture()

  private val writtenBytes = ByteArrayOutputStream()
  @volatile private var _inputClosed: Boolean = false
  @volatile private var _terminateCount: Int = 0
  @volatile private var _killCount: Int = 0

  override def isAlive: Boolean = !onExit.isDone

  override val input: OutputStream = new OutputStream {
    override def write(b: Int): Unit = FakeProcess.this.synchronized {
      if (_inputClosed || !isAlive) {
        throw IOException("Stream closed")
      }
      writtenBytes.write(b)
    }

    override def close(): Unit = {
      _inputClosed = true
    }
  }

  override def terminate(): Unit = {
    _terminateCount += 1
    if (!ignoresTerminate) {
      exit(FakeProcess.TerminatedExitCode)
    }
  }

  override def kill(): Unit = {
    _killCount += 1
    exit(FakeProcess.KilledExitCode)
  }

  /** Makes the process exit with `exitCode`, unless it already has. */
  def exit(exitCode: Int): Unit = onExit.complete(exitCode)

  /** What was written to the process's input, as UTF-8. */
  def written: String = synchronized(writtenBytes.toString(UTF_8))

  /** Whether the process's input was closed. */
  def inputClosed: Boolean = _inputClosed

  /** How many times [[terminate]] was called. */
  def terminateCount: Int = _terminateCount

  /** How many times [[kill]] was called. */
  def killCount: Int = _killCount
}

object FakeProcess {
  /** The exit code of a [[FakeProcess]] that [[FakeProcess.terminate]] stopped, as a process killed by `SIGTERM`. */
  val TerminatedExitCode: Int = 143

  /** The exit code of a [[FakeProcess]] that [[FakeProcess.kill]] stopped, as a process killed by `SIGKILL`. */
  val KilledExitCode: Int = 137
}

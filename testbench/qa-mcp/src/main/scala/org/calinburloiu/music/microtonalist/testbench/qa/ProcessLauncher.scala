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

import java.io.{InputStream, OutputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.{CompletableFuture, Executor, Executors, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/**
 * What to start as a process.
 *
 * @param command the program and its arguments
 * @param output the file where the process's standard output and error are appended, or `None` to discard them
 * @param pipeInput whether the server writes to the process's standard input; otherwise the input is empty
 */
final case class ProcessSpec(command: Seq[String], output: Option[Path] = None, pipeInput: Boolean = false)

/** A process that a [[ProcessLauncher]] started, which only its handle stops. */
trait LaunchedProcess {
  /** The process ID. */
  def pid: Long

  /** When the process started, which tells it apart from a later process with the same PID. */
  def startInstant: Instant

  /** Whether the process is still running. */
  def isAlive: Boolean

  /**
   * The process's standard input.
   *
   * @throws IllegalStateException if it was started without [[ProcessSpec.pipeInput]]
   */
  def input: OutputStream

  /** Completes with the exit code once the process has exited. */
  def onExit: CompletableFuture[Integer]

  /** Asks the process to exit, with `SIGTERM`. */
  def terminate(): Unit

  /** Kills the process, with `SIGKILL`. */
  def kill(): Unit
}

/** The result of a command that ran to its end. */
final case class CommandResult(exitCode: Int, stdout: String, stderr: String)

/** Starts external processes; tests fake it, since CI lacks the programs the QA server runs. */
trait ProcessLauncher {
  /**
   * Starts a process.
   *
   * @throws java.io.IOException if the program can't be started, e.g. because it isn't installed
   */
  def start(spec: ProcessSpec): LaunchedProcess

  /**
   * Runs a command to its end, with an empty standard input, and collects its output as UTF-8.
   *
   * @throws java.io.IOException if the program can't be started
   * @throws QaException if it doesn't finish within `timeout`, after killing it
   */
  def run(command: Seq[String], timeout: FiniteDuration): CommandResult
}

/** A [[ProcessLauncher]] that starts real processes through the JDK. */
final class JdkProcessLauncher extends ProcessLauncher {
  override def start(spec: ProcessSpec): LaunchedProcess = {
    val builder = ProcessBuilder(spec.command*)
    spec.output match {
      case Some(path) => builder.redirectErrorStream(true).redirectOutput(Redirect.appendTo(path.toFile))
      case None => builder.redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD)
    }

    val process = builder.start()
    if (!spec.pipeInput) {
      process.getOutputStream.close()
    }
    JdkLaunchedProcess(process, spec.pipeInput)
  }

  override def run(command: Seq[String], timeout: FiniteDuration): CommandResult = {
    val process = ProcessBuilder(command*).start()
    process.getOutputStream.close()
    // Both outputs are read while the command runs, since a full pipe would block it.
    val stdout = readAll(process.getInputStream)
    val stderr = readAll(process.getErrorStream)

    if (!process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)) {
      process.destroyForcibly()
      throw QaException(s"${command.mkString(" ")} didn't finish in $timeout")
    }
    CommandResult(process.exitValue(), stdout.join(), stderr.join())
  }

  private def readAll(stream: InputStream): CompletableFuture[String] = {
    CompletableFuture.supplyAsync(() => String(stream.readAllBytes(), UTF_8), JdkProcessLauncher.ReaderExecutor)
  }
}

private object JdkProcessLauncher {
  /** Runs the blocking reads of the commands' outputs, each on a virtual thread of its own. */
  private val ReaderExecutor: Executor = Executors.newVirtualThreadPerTaskExecutor()
}

private final class JdkLaunchedProcess(process: Process, pipeInput: Boolean) extends LaunchedProcess {
  override val startInstant: Instant = process.info().startInstant().orElseGet(() => Instant.now())

  override val onExit: CompletableFuture[Integer] = process.onExit().thenApply(_.exitValue())

  override def pid: Long = process.pid()

  override def isAlive: Boolean = process.isAlive

  override def input: OutputStream = {
    if (!pipeInput) {
      throw IllegalStateException(s"The process $pid was started without a piped input")
    }
    process.getOutputStream
  }

  override def terminate(): Unit = process.destroy()

  override def kill(): Unit = {
    process.destroyForcibly()
  }
}

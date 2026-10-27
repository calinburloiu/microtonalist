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

import play.api.libs.json.*

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Records the PIDs of a run's server and of the processes it started, each with its start instant, so that a later
 * server can stop the processes of a killed one.
 *
 * A start instant tells a recorded process apart from a later one that reuses its PID, so a PID is never signalled
 * unless its process still has the recorded start instant.
 */
object ProcessRecords {
  private val ServerFileName: String = "server.json"
  private val ProcessesFileName: String = "processes.json"

  /** How long a sweep waits for the processes it signalled to exit on `SIGTERM`, before killing them. */
  private val SweepStopTimeout: FiniteDuration = 5.seconds

  private final case class ProcessRecord(pid: Long, startInstant: Instant)

  private given Format[ProcessRecord] = Json.format[ProcessRecord]

  /**
   * Records this JVM, the run's server, in `<run>/server.json`.
   *
   * @throws IllegalStateException if the JVM's start instant is unknown, since a later server would then take this one
   *   for gone, and stop its processes
   */
  def writeServer(run: QaRun): Unit = {
    val server = ProcessHandle.current()
    writeServer(run, server.pid(), server.info())
  }

  /** Records a process as the run's server, in `<run>/server.json`, from its information. */
  private[qa] def writeServer(run: QaRun, pid: Long, info: ProcessHandle.Info): Unit = {
    val startInstant = info.startInstant().orElseThrow { () =>
      IllegalStateException(s"The start instant of the QA server, PID $pid, is unknown, so a later server would take " +
        "it for gone and stop its processes")
    }
    writeServer(run, pid, startInstant)
  }

  /** Records a process as the run's server, in `<run>/server.json`. */
  private[qa] def writeServer(run: QaRun, pid: Long, startInstant: Instant): Unit = {
    write(run.file(ServerFileName), Json.toJson(ProcessRecord(pid, startInstant)))
  }

  /** Records the processes that the run's server started, in `<run>/processes.json`, replacing the earlier records. */
  def writeProcesses(run: QaRun, processes: Seq[LaunchedProcess]): Unit = {
    val records = processes.map(process => ProcessRecord(process.pid, process.startInstant))
    write(run.file(ProcessesFileName), Json.toJson(records))
  }

  /**
   * Stops the recorded processes of each run in `baseDir` whose server is gone, i.e. no longer runs with its recorded
   * start instant, but only those that still run with their recorded start instant: sends them `SIGTERM`, then kills
   * with `SIGKILL` those that haven't exited within `stopTimeout`.
   *
   * A run without a readable server record is left alone, since its server may be starting.
   *
   * @return the PIDs of the processes stopped
   */
  def sweep(baseDir: Path, stopTimeout: FiniteDuration = SweepStopTimeout): Seq[Long] = {
    val orphans = if (Files.isDirectory(baseDir)) {
      Using.resource(Files.list(baseDir))(_.iterator().asScala.toSeq).sorted.flatMap(sweepRun)
    } else {
      Seq.empty
    }

    // Signalling the handles that were checked lets the JDK refuse a PID whose start instant has changed since.
    orphans.foreach(_.destroy())
    val deadline = System.nanoTime() + stopTimeout.toNanos
    orphans.foreach { orphan =>
      try {
        orphan.onExit().get(math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
      } catch {
        case _: TimeoutException => orphan.destroyForcibly()
      }
    }
    orphans.map(_.pid)
  }

  private def sweepRun(runDir: Path): Seq[ProcessHandle] = {
    read[ProcessRecord](runDir.resolve(ServerFileName)) match {
      case Some(server) if liveHandle(server).isEmpty =>
        read[Seq[ProcessRecord]](runDir.resolve(ProcessesFileName)).getOrElse(Seq.empty).flatMap(liveHandle)
      case _ => Seq.empty
    }
  }

  /** The handle of the recorded process if it still runs, with the same start instant, so that its PID wasn't reused. */
  private def liveHandle(record: ProcessRecord): Option[ProcessHandle] = {
    ProcessHandle.of(record.pid).filter(_.isAlive)
      .filter(_.info().startInstant().filter(_ == record.startInstant).isPresent).toScala
  }

  private def read[T: Reads](file: Path): Option[T] = {
    try {
      Json.parse(Files.readString(file)).asOpt[T]
    } catch {
      case _: IOException => None
    }
  }

  /**
   * Writes a file atomically, through a temporary file of its own, so that a server killed meanwhile leaves the earlier
   * records whole, and two writes at once don't share a temporary file.
   */
  private def write(file: Path, json: JsValue): Unit = {
    val temporary = Files.createTempFile(file.getParent, s"${file.getFileName}.", ".tmp")
    try {
      Files.writeString(temporary, Json.stringify(json), UTF_8)
      Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }
}

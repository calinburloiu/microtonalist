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

  private final case class ProcessRecord(pid: Long, startInstant: Instant)

  private given Format[ProcessRecord] = Json.format[ProcessRecord]

  /** Records this JVM, the run's server, in `<run>/server.json`. */
  def writeServer(run: QaRun): Unit = {
    val server = ProcessHandle.current()
    writeServer(run, server.pid(), server.info().startInstant().orElseGet(() => Instant.now()))
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
   * Sends `SIGTERM` to the recorded processes of each run in `baseDir` whose server is gone, i.e. no longer runs with
   * its recorded start instant, but only to those that still run with their recorded start instant.
   *
   * A run without a readable server record is left alone, since its server may be starting.
   *
   * @return the PIDs of the processes signalled
   */
  def sweep(baseDir: Path): Seq[Long] = {
    if (Files.isDirectory(baseDir)) {
      Using.resource(Files.list(baseDir))(_.iterator().asScala.toSeq).sorted.flatMap(sweepRun)
    } else {
      Seq.empty
    }
  }

  private def sweepRun(runDir: Path): Seq[Long] = {
    read[ProcessRecord](runDir.resolve(ServerFileName)) match {
      case Some(server) if !isRunning(server) =>
        read[Seq[ProcessRecord]](runDir.resolve(ProcessesFileName)).getOrElse(Seq.empty).filter(isRunning).map {
          record =>
            ProcessHandle.of(record.pid).ifPresent(_.destroy())
            record.pid
        }
      case _ => Seq.empty
    }
  }

  /** Whether the recorded process still runs, with the same start instant, so that its PID wasn't reused. */
  private def isRunning(record: ProcessRecord): Boolean = {
    ProcessHandle.of(record.pid).filter(_.isAlive).flatMap(_.info().startInstant())
      .filter(_ == record.startInstant).isPresent
  }

  private def read[T: Reads](file: Path): Option[T] = {
    try {
      Json.parse(Files.readString(file)).asOpt[T]
    } catch {
      case _: IOException => None
    }
  }

  /** Writes a file atomically, so that a server killed meanwhile leaves the earlier records whole. */
  private def write(file: Path, json: JsValue): Unit = {
    val temporary = file.resolveSibling(s"${file.getFileName}.tmp")
    Files.writeString(temporary, Json.stringify(json), UTF_8)
    Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
  }
}

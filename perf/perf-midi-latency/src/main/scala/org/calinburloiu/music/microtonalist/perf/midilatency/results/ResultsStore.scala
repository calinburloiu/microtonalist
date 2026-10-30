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

package org.calinburloiu.music.microtonalist.perf.midilatency.results

import play.api.libs.json.{JsObject, Json, OFormat}

import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.util.Using

/**
 * The committed directory of run files: one per run under a directory per machine, and `index.json`, which lists every
 * run with what the report needs to pick one without opening every file. Beside the run file of each machine's last
 * written run lie its raw samples, which git ignores.
 */
final class ResultsStore(dir: Path) {

  import ResultsStore.*

  /**
   * Writes the run's file and adds it to the index, returning the file; writes its raw samples too, in place of those
   * of the machine's previous run.
   */
  def write(run: RunResult): Path = {
    val path = relativePath(run)
    val file = dir.resolve(path)
    Files.createDirectories(file.getParent)
    Files.writeString(file, JsonRunResultFormat.write(run))

    val samplesFile = dir.resolve(samplesPath(run))
    // Written first, so that a failed write leaves the previous run's samples in place
    Files.writeString(samplesFile, JsonRunSamplesFormat.write(run))
    Using.resource(Files.list(samplesFile.getParent)) { files =>
      files.filter(file => file.getFileName.toString.endsWith(SamplesSuffix) && file != samplesFile)
        .forEach(file => Files.delete(file))
    }

    val entry = IndexEntry(path = path, name = run.name, machineId = run.machine.id, machineName = run.machine.name,
      startTime = run.startTime, appVersion = run.app.version, appCommit = run.app.commit)
    val entries = (readIndex().filterNot(_.path == path) :+ entry).sortBy(_.startTime)
    val index = Json.obj("schemaVersion" -> RunResult.SchemaVersion, "runs" -> entries)
    Files.writeString(dir.resolve(IndexFileName), Json.prettyPrint(index) + "\n")

    file
  }

  private def readIndex(): Seq[IndexEntry] = {
    val indexFile = dir.resolve(IndexFileName)
    if (Files.exists(indexFile)) (Json.parse(Files.readString(indexFile)).as[JsObject] \ "runs").as[Seq[IndexEntry]]
    else Seq.empty
  }
}

object ResultsStore {
  /** The index's file name in the results directory. */
  val IndexFileName: String = "index.json"

  /** `<machine id>/<UTC start time>-<app version>-<short commit>.json` */
  def relativePath(run: RunResult): String =
    s"${run.machine.id}/${FileTimestamp.format(run.startTime)}-${run.app.version}-${run.app.commit.take(8)}.json"

  /** `<machine id>/<UTC start time>-<app version>-<short commit>.samples.json`, beside the run file */
  def samplesPath(run: RunResult): String = relativePath(run).stripSuffix(".json") + SamplesSuffix

  private val SamplesSuffix: String = ".samples.json"

  private val FileTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    .withZone(ZoneOffset.UTC)

  /** A run as the index lists it; an unknown value is left out, as in the run files. */
  private case class IndexEntry(path: String,
                                name: Option[String],
                                machineId: String,
                                machineName: String,
                                startTime: Instant,
                                appVersion: String,
                                appCommit: String)

  private given OFormat[IndexEntry] = Json.format[IndexEntry]
}

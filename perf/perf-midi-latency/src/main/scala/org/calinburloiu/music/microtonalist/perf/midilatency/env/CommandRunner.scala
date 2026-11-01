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

package org.calinburloiu.music.microtonalist.perf.midilatency.env

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * What a command printed and how it exited.
 *
 * @param output The standard output and error, merged.
 */
case class CommandResult(exitCode: Int, output: String)

/** Runs a command to completion, which tests replace with canned results. */
trait CommandRunner {
  /** Runs `command` in `directory`, by default the current one. */
  def run(command: Seq[String], directory: Option[Path] = None): CommandResult
}

/** Runs commands in processes. */
object ProcessCommandRunner extends CommandRunner {
  override def run(command: Seq[String], directory: Option[Path] = None): CommandResult = {
    val builder = ProcessBuilder(command.asJava).redirectErrorStream(true)
    directory.foreach(dir => builder.directory(dir.toFile))
    val process = builder.start()
    process.getOutputStream.close()
    val output = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    CommandResult(process.waitFor(), output)
  }
}

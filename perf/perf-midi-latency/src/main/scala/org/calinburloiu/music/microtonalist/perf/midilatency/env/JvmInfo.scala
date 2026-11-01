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

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException

import java.nio.file.Path

/**
 * The JVM the app runs on.
 *
 * @param options The options the app is launched with.
 */
case class JvmInfo(vendor: String, version: String, options: Seq[String])

object JvmInfo {

  /**
   * Asks the `java` executable for its vendor and version.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException if it fails to run.
   */
  def probe(java: Path, options: Seq[String], runner: CommandRunner): JvmInfo = {
    val result = runner.run(Seq(java.toString, "-XshowSettings:properties", "-version"))
    if (result.exitCode != 0) {
      throw PerfSetupException(s"Failed to run $java (exit code ${result.exitCode}): ${result.output}")
    }
    val properties = result.output.linesIterator.collect {
      case PropertyLine(name, value) => name -> value.trim
    }.toMap

    def property(name: String): String =
      properties.getOrElse(name, throw PerfSetupException(s"$java didn't tell its $name"))

    JvmInfo(vendor = property("java.vendor"), version = property("java.runtime.version"), options = options)
  }

  /** A line of `-XshowSettings:properties`, e.g. `    java.vendor = Eclipse Adoptium`. */
  private val PropertyLine = """\s+([\w.]+) = (.*)""".r
}

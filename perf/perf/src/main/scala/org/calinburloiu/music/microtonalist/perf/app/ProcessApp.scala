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

package org.calinburloiu.music.microtonalist.perf.app

import org.calinburloiu.music.microtonalist.perf.run.RunningApp

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** The app running in a process of its own, its output going to a log file. */
final class ProcessApp private (process: Process, log: Path) extends RunningApp {

  override def isAlive: Boolean = process.isAlive

  override def exitCode: Option[Int] = Option.when(!process.isAlive)(process.exitValue())

  override def logFile: Option[Path] = Some(log)

  /** Sends the app the request to exit, which runs its shutdown hooks, and kills it if it doesn't exit in `timeout`. */
  override def stop(timeout: FiniteDuration): Unit = {
    process.destroy()
    if (!awaitExit(timeout)) {
      process.destroyForcibly()
      process.waitFor()
    }
  }

  /** Waits for the app to exit, up to `timeout`, telling whether it did. */
  def awaitExit(timeout: FiniteDuration): Boolean = process.waitFor(timeout.toNanos, TimeUnit.NANOSECONDS)
}

object ProcessApp {
  /** Starts `command`, with its standard output and error going to `logFile`. */
  def start(command: Seq[String], logFile: Path): ProcessApp = {
    Option(logFile.toAbsolutePath.getParent).foreach(dir => Files.createDirectories(dir))
    val process = ProcessBuilder(command.asJava)
      .redirectErrorStream(true)
      .redirectOutput(logFile.toFile)
      .start()
    new ProcessApp(process, logFile)
  }
}

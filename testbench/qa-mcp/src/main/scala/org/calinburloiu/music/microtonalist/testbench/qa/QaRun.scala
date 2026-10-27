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

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, InvalidPathException, LinkOption, Path}
import scala.util.Random

/**
 * The directory of one QA run, which holds every file the server's tools read or write.
 *
 * @param id the run's short unique ID, e.g. `qa-3f9c`, which also names its MIDI ports
 * @param dir the run directory's real path
 */
final class QaRun private (val id: String, val dir: Path) {

  /**
   * Resolves a path that a tool received, relative to the run directory or absolute, to the real path to use.
   *
   * Symbolic links are followed, so a link out of the run directory is refused. A path that doesn't exist yet is
   * resolved through its nearest existing ancestor.
   *
   * @throws QaException if the path is invalid, or not inside the run directory
   */
  def resolve(path: String): Path = {
    val resolved =
      try {
        QaRun.realPath(dir.resolve(path).normalize())
      } catch {
        case e: InvalidPathException => throw QaException(s"Invalid path \"$path\": ${e.getReason}")
        case e: IOException => throw QaException(s"Can't resolve the path \"$path\": $e")
      }

    if (!resolved.startsWith(dir)) {
      throw QaException(s"The path \"$path\" is outside the run directory $dir")
    }
    resolved
  }

  /** The Unix domain socket on which the server listens for the driver. */
  def socket: Path = dir.resolve(QaRun.SocketFileName)

  /** A file of the run directory with a name the server chose, e.g. `app.log`; never a name a tool received. */
  def file(name: String): Path = dir.resolve(name)
}

/** Creates the run directories. */
object QaRun {
  /** The default directory of the runs, `<java.io.tmpdir>/microtonalist-qa`. */
  val DefaultBaseDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "microtonalist-qa")

  /** The longest path, in UTF-8 bytes, of a Unix domain socket, as macOS's `sun_path` allows. */
  val MaxSocketPathBytes: Int = 103

  private val SocketFileName: String = "d.sock"
  private val IdCount: Int = 0x10000
  private val MaxAttempts: Int = 100

  /**
   * Creates a run directory, with a new ID, in `baseDir`, creating `baseDir` too if it's missing.
   *
   * @param random draws the run's ID; another ID is drawn while the run directory exists
   * @throws QaException if the socket's path would be longer than [[MaxSocketPathBytes]], or if no drawn ID is free
   * @throws IOException if a directory can't be created
   */
  def create(baseDir: Path, random: Random = Random()): QaRun = {
    val socketPath = realPath(baseDir.toAbsolutePath.normalize()).resolve(formatId(0)).resolve(SocketFileName)
    val socketPathBytes = socketPath.toString.getBytes(UTF_8).length
    if (socketPathBytes > MaxSocketPathBytes) {
      throw QaException(
        s"The socket path $socketPath would take $socketPathBytes bytes, more than $MaxSocketPathBytes: " +
          "choose a shorter base directory")
    }

    Files.createDirectories(baseDir)
    createRunDir(baseDir, random, MaxAttempts)
  }

  private def createRunDir(baseDir: Path, random: Random, attemptsLeft: Int): QaRun = {
    if (attemptsLeft == 0) {
      throw QaException(s"Found no free run ID in $baseDir after $MaxAttempts attempts")
    }

    val id = formatId(random.nextInt(IdCount))
    // `createDirectory`, unlike `createDirectories`, fails if the directory exists, so two runs never share one.
    val created =
      try {
        Some(Files.createDirectory(baseDir.resolve(id)))
      } catch {
        case _: FileAlreadyExistsException => None
      }
    created match {
      case Some(runDir) => QaRun(id, runDir.toRealPath())
      case None => createRunDir(baseDir, random, attemptsLeft - 1)
    }
  }

  private def formatId(number: Int): String = f"qa-$number%04x"

  /** The real path of an absolute, normalized `path`; for a missing file, its nearest existing ancestor's, extended. */
  private def realPath(path: Path): Path = {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      path.toRealPath()
    } else {
      realPath(path.getParent).resolve(path.getFileName)
    }
  }
}

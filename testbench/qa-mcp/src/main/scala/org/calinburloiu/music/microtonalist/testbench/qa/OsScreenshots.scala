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

import play.api.libs.json.{Json, Reads}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.util.{Try, Using}

/**
 * The on-screen windows of a process, as macOS lists them.
 *
 * @param granted whether the process that asked may record the screen, which it inherits from the app that runs it
 * @param windowIds the IDs of the process's on-screen windows
 */
final case class WindowList(granted: Boolean, windowIds: Seq[Long])

/** Takes PNGs of an app's windows with macOS's `screencapture`, which needs the Screen Recording permission. */
object OsScreenshots {

  private val Osascript: String = "/usr/bin/osascript"
  private val Screencapture: String = "/usr/sbin/screencapture"
  private val ScreenshotsDirName: String = "screenshots"
  private val CommandTimeout: FiniteDuration = 10.seconds

  /** The JXA script that prints the [[WindowList]] of the PID it takes, as JSON. */
  private val WindowIdsScript: String = {
    Using.resource(getClass.getResourceAsStream("window-ids.js"))(stream => String(stream.readAllBytes(), UTF_8))
  }

  private given Reads[WindowList] = Json.reads[WindowList]

  /** Reads the JSON that `window-ids.js` prints, e.g. `{"granted":true,"windowIds":[12,13]}`. */
  def parseWindowList(json: String): Either[String, WindowList] = {
    Try(Json.parse(json)).toEither.left.map(_ => s"not JSON: $json").flatMap { parsed =>
      parsed.validate[WindowList].asEither.left.map(errors => s"not a window list: $json; $errors")
    }
  }

  /**
   * Takes a PNG of each on-screen window of a process, saving it as `<run>/screenshots/os-<number>-<window ID>.png`.
   *
   * @param number the capture's sequence number in the run
   * @return each PNG's file and bytes, in the order macOS lists the windows, front to back
   * @throws QaException if Screen Recording isn't granted, if the process has no window on screen, or if a command
   *   fails
   */
  def capture(run: QaRun, launcher: ProcessLauncher, pid: Long, number: Int): Seq[(Path, Array[Byte])] = {
    val output = runCommand(launcher, Seq(Osascript, "-l", "JavaScript", "-e", WindowIdsScript, pid.toString))
    val windowList = parseWindowList(output).fold(error => throw QaException(s"Can't list the app's windows: $error"),
      identity)
    if (!windowList.granted) {
      throw QaException("Screen Recording isn't granted to the app that runs Claude Code: grant it in System " +
        "Settings → Privacy & Security → Screen & System Audio Recording, then restart that app")
    }
    if (windowList.windowIds.isEmpty) {
      throw QaException(s"the app, PID $pid, has no window on screen")
    }

    val dir = Files.createDirectories(run.file(ScreenshotsDirName))
    windowList.windowIds.map { windowId =>
      val file = dir.resolve(s"os-$number-$windowId.png")
      runCommand(launcher, Seq(Screencapture, "-x", "-o", "-l", windowId.toString, file.toString))
      file -> Files.readAllBytes(file)
    }
  }

  /** Runs a command and returns its standard output, or throws a [[QaException]] with its error if it fails. */
  private def runCommand(launcher: ProcessLauncher, command: Seq[String]): String = {
    val result = launcher.run(command, CommandTimeout)
    if (result.exitCode != 0) {
      throw QaException(s"${command.head} failed with exit code ${result.exitCode}: ${result.stderr.trim}")
    }
    result.stdout
  }
}

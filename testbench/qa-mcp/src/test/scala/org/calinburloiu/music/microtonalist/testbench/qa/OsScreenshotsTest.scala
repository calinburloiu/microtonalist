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

import org.calinburloiu.music.microtonalist.testbench.qa.OsScreenshots.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class OsScreenshotsTest extends AnyWordSpec with Matchers {

  private val Pid: Long = 4242

  private abstract class Fixture {
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"))
    val launcher: FakeProcessLauncher = FakeProcessLauncher()

    /** What `osascript` answers. */
    var windowList: CommandResult = CommandResult(0, """{"granted":true,"windowIds":[12,13]}""", "")

    /** What `screencapture` answers. */
    var captureResult: CommandResult = CommandResult(0, "", "")

    // Like the real one, `screencapture` writes a PNG, whose bytes are the window's ID, when it succeeds.
    launcher.runResult = command =>
      command.head match {
        case "/usr/bin/osascript" => windowList
        case "/usr/sbin/screencapture" =>
          if (captureResult.exitCode == 0) {
            Files.write(Path.of(command.last), pngOf(command(4)))
          }
          captureResult
        case program => fail(s"unexpected program $program")
      }

    def pngOf(windowId: String): Array[Byte] = windowId.getBytes

    def screenshot(name: String): Path = run.dir.resolve("screenshots").resolve(name)
  }

  "OsScreenshots" should {
    "parse the window list" in {
      // When / Then
      parseWindowList("""{"granted":true,"windowIds":[12,13]}""") shouldBe Right(WindowList(true, Seq(12, 13)))
    }

    "refuse an unreadable window list" in {
      // When / Then
      parseWindowList("""{"granted":true}""").left.toOption.get should include("windowIds")
      parseWindowList("not JSON").left.toOption.get should include("not JSON")
    }

    "capture each window of the app" in new Fixture {
      // When
      val screenshots: Seq[(Path, Array[Byte])] = capture(run, launcher, Pid, 1)

      // Then
      launcher.runs.head.take(4) shouldBe Seq("/usr/bin/osascript", "-l", "JavaScript", "-e")
      launcher.runs.head(4) should include("CGWindowListCopyWindowInfo")
      launcher.runs.head.drop(5) shouldBe Seq(Pid.toString)
      launcher.runs.tail shouldBe Seq(
        Seq("/usr/sbin/screencapture", "-x", "-o", "-l", "12", run.dir.resolve("screenshots/os-1-12.png").toString),
        Seq("/usr/sbin/screencapture", "-x", "-o", "-l", "13", run.dir.resolve("screenshots/os-1-13.png").toString))
      screenshots.map(_._1) shouldBe Seq(screenshot("os-1-12.png"), screenshot("os-1-13.png"))
      screenshots.map(_._2.toSeq) shouldBe Seq(pngOf("12").toSeq, pngOf("13").toSeq)
    }

    "name the screenshots by their number" in new Fixture {
      // Given
      windowList = CommandResult(0, """{"granted":true,"windowIds":[12]}""", "")

      // When
      val screenshots: Seq[(Path, Array[Byte])] = capture(run, launcher, Pid, 7)

      // Then
      screenshots.map(_._1) shouldBe Seq(screenshot("os-7-12.png"))
    }

    "say that Screen Recording isn't granted, capturing nothing" in new Fixture {
      // Given
      windowList = CommandResult(0, """{"granted":false,"windowIds":[12]}""", "")

      // When / Then
      (the[QaException] thrownBy capture(run, launcher, Pid, 1)).getMessage shouldBe
        "Screen Recording isn't granted to the app that runs Claude Code: grant it in System Settings → Privacy & " +
        "Security → Screen & System Audio Recording, then restart that app"
      launcher.runs should have size 1
    }

    "say that the app has no window" in new Fixture {
      // Given
      windowList = CommandResult(0, """{"granted":true,"windowIds":[]}""", "")

      // When / Then
      (the[QaException] thrownBy capture(run, launcher, Pid, 1)).getMessage shouldBe
        s"the app, PID $Pid, has no window on screen"
      launcher.runs should have size 1
    }

    "pass on osascript's error" in new Fixture {
      // Given
      windowList = CommandResult(1, "", "boom\n")

      // When / Then
      (the[QaException] thrownBy capture(run, launcher, Pid, 1)).getMessage should include("boom")
      launcher.runs should have size 1
    }

    "refuse osascript's unreadable output" in new Fixture {
      // Given
      windowList = CommandResult(0, "garbage", "")

      // When / Then
      (the[QaException] thrownBy capture(run, launcher, Pid, 1)).getMessage should include("garbage")
    }

    "pass on screencapture's error" in new Fixture {
      // Given
      captureResult = CommandResult(1, "", "could not create image from window\n")

      // When / Then
      (the[QaException] thrownBy capture(run, launcher, Pid, 1)).getMessage should
        include("could not create image from window")
      launcher.runs should have size 2
    }
  }
}

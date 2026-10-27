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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.*
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, JsValue, Json}

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.util.Random

class QaSessionTest extends AnyWordSpec with Matchers {

  private val AwaitSeconds: Long = 5

  /** How long the session waits for the app to exit on `SIGTERM`, before killing it. */
  private val ShortStopTimeout: FiniteDuration = 100.millis

  /** A composition with no `tracksUrl`, whose rig is the `.tracks` file next to it. */
  private val compositionJson: String = """{ "tunings": [] }"""

  /** The design's fixture rig, which the `gui-qa` skill's agent copies. */
  private val fixtureRig: String =
    """{
      |  "tracks": [
      |    {
      |      "id": "qa",
      |      "name": "QA",
      |      "input": { "midiDeviceId": { "name": "qa:keys" } },
      |      "tuningChangers": ["pedal"],
      |      "tuner": "mtsOctave1ByteNonRealTime",
      |      "output": { "midiDeviceId": { "name": "qa:synth" } }
      |    }
      |  ]
      |}""".stripMargin

  private val rewrittenFixtureRig: JsValue = Json.parse(
    """{
      |  "tracks": [
      |    {
      |      "id": "qa",
      |      "name": "QA",
      |      "input": { "midiDeviceId": { "name": "CoreMIDI4J - qa-3f9c-keys", "vendor": "<Unknown Manufacturer>" } },
      |      "tuningChangers": ["pedal"],
      |      "tuner": "mtsOctave1ByteNonRealTime",
      |      "output": { "midiDeviceId": { "name": "CoreMIDI4J - qa-3f9c-synth", "vendor": "<Unknown Manufacturer>" } }
      |    }
      |  ]
      |}""".stripMargin)

  private abstract class Fixture {
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"),
      new Random {
        override def nextInt(n: Int): Int = 0x3F9C
      })
    val launcher: FakeProcessLauncher = FakeProcessLauncher()
    val jars: QaJars = QaJars(Path.of("/jars/app.jar"), Path.of("/jars/driver.jar"))
    val java: Path = Path.of("/jdk/bin/java")
    val session: QaSession = QaSession(run, launcher, jars, java, ShortStopTimeout)
    val composition: Path = run.dir.resolve("qa.mtlist")
    val rigFile: Path = run.dir.resolve("qa.mtlist.tracks")

    /** The drivers that the app's processes connected, in order. */
    val drivers: ArrayBuffer[FakeDriver] = ArrayBuffer()

    Files.writeString(composition, compositionJson)
    Files.writeString(rigFile, fixtureRig)

    // By default, the app's process connects its driver as soon as it starts.
    launcher.newProcess = (spec, pid) => {
      if (isApp(spec)) {
        drivers += FakeDriver(run.socket, pid)
      }
      FakeProcess(spec, pid)
    }

    def isApp(spec: ProcessSpec): Boolean = spec.command.head == java.toString

    def driver: FakeDriver = drivers.last

    def startApp(unplugged: Seq[String] = Nil): StartAppResult = {
      session.startApp(StartAppArgs("qa.mtlist", unplugged = unplugged))
    }

    /** The process that runs a program, e.g. `sendmidi`, the last one if several did. */
    def processOf(program: String): FakeProcess = launcher.processes.filter(_.spec.command.head == program).last

    /** The PIDs that `processes.json` records. */
    def recordedPids: Seq[Long] = {
      Json.parse(Files.readString(run.file("processes.json"))).as[Seq[JsObject]].map(record =>
        (record \ "pid").as[Long])
    }
  }

  "QaSession.startApp" should {
    "start the app on the fixture rig" in new Fixture {
      // When
      val result: StartAppResult = startApp()

      // Then
      result.devices.map(d => (d.device, d.input, d.output, d.plugged)) shouldBe
        Seq(("keys", true, false, true), ("synth", false, true, true))
      result.devices.map(d => (d.port, d.midiIn, d.midiOut)) shouldBe Seq(
        ("qa-3f9c-keys", Some(run.dir.resolve("midi-in-keys.txt").toString), None),
        ("qa-3f9c-synth", None, Some(run.dir.resolve("midi-out-synth.txt").toString)))
      Json.parse(Files.readString(run.file("qa.mtlist.tracks"))) shouldBe rewrittenFixtureRig
      Files.readString(rigFile) shouldBe Json.prettyPrint(rewrittenFixtureRig)
      launcher.specs.map(_.command.head) shouldBe Seq("sendmidi", "receivemidi", java.toString)
      launcher.specs.last.command should contain(composition.toString)
      result shouldBe StartAppResult(processOf(java.toString).pid, GuiPlatform.Headless, result.devices,
        run.dir.resolve("app.log").toString, run.dir.resolve("qa.conf").toString, Some(rigFile.toString))
      recordedPids should contain theSameElementsAs launcher.processes.map(_.pid)
    }

    "leave a device unplugged, and plug it in on replug" in new Fixture {
      // When
      val result: StartAppResult = startApp(unplugged = Seq("synth"))

      // Then
      result.devices.map(d => (d.device, d.plugged)) shouldBe Seq(("keys", true), ("synth", false))
      launcher.specs.map(_.command.head) shouldBe Seq("sendmidi", java.toString)

      // When
      session.replug("synth")

      // Then
      launcher.specs.map(_.command.head) shouldBe Seq("sendmidi", java.toString, "receivemidi")
      recordedPids should contain theSameElementsAs launcher.processes.map(_.pid)

      // When
      session.unplug("synth")

      // Then
      processOf("receivemidi").isAlive shouldBe false
      recordedPids should contain theSameElementsAs Seq(processOf("sendmidi").pid, processOf(java.toString).pid)
    }

    "refuse to leave unplugged a device the rig doesn't name" in new Fixture {
      // When / Then
      (the[QaException] thrownBy startApp(unplugged = Seq("drums"))).getMessage shouldBe
        "unknown device drums; the QA devices are keys, synth"
      launcher.specs shouldBe empty
    }

    "refuse a rig with a real device, opening no port" in new Fixture {
      // Given
      val realRig: String = fixtureRig.replace("qa:keys", "Real Keys")
      Files.writeString(rigFile, realRig)

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage should include("isn't a QA device")
      launcher.specs shouldBe empty
      Files.readString(rigFile) shouldBe realRig
    }

    "refuse a rig that isn't JSON" in new Fixture {
      // Given
      Files.writeString(rigFile, "{ not JSON")

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage should startWith(s"Can't read the rig $rigFile as JSON")
      launcher.specs shouldBe empty
    }

    "refuse a composition it can't read" in new Fixture {
      // When / Then
      (the[QaException] thrownBy session.startApp(StartAppArgs("missing.mtlist"))).getMessage should
        startWith("Can't read the composition")
      launcher.specs shouldBe empty
    }

    "refuse a composition whose path the app would read otherwise" in new Fixture {
      // Given
      Files.writeString(run.dir.resolve("a#b.mtlist"), compositionJson)
      Files.writeString(run.dir.resolve("a#b.mtlist.tracks"), fixtureRig)

      // When / Then
      (the[QaException] thrownBy session.startApp(StartAppArgs("a#b.mtlist"))).getMessage shouldBe
        "The composition's path \"a#b.mtlist\" has a name with a character other than a letter, a digit, '.', '_' " +
        "or '-', which the app could resolve to another rig"
      launcher.specs shouldBe empty
    }

    "start with no devices when the composition has no rig" in new Fixture {
      // Given
      Files.delete(rigFile)

      // When
      val result: StartAppResult = startApp()

      // Then
      result.devices shouldBe empty
      result.rig shouldBe None
      launcher.specs.map(_.command.head) shouldBe Seq(java.toString)
      Files.exists(rigFile) shouldBe false
    }

    "refuse a second start while the app runs" in new Fixture {
      // Given
      startApp()

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage shouldBe "an app is running; call stop_app first"
      launcher.specs should have size 3
    }

    "stop the MIDI tools when the app fails to start" in new Fixture {
      // Given
      launcher.startFailure = spec => Option.when(isApp(spec))(IOException("no java"))

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage shouldBe "can't start the app: no java"
      launcher.processes.map(_.isAlive) shouldBe Seq(false, false)

      // When
      launcher.startFailure = _ => None
      startApp()

      // Then
      processOf("sendmidi").isAlive shouldBe true
    }
  }

  "QaSession" should {
    "stop the app before its MIDI tools" in new Fixture {
      // Given
      val terminationOrder: ArrayBuffer[String] = ArrayBuffer()
      launcher.newProcess = (spec, pid) => {
        if (isApp(spec)) {
          drivers += FakeDriver(run.socket, pid)
        }
        new FakeProcess(spec, pid) {
          override def terminate(): Unit = {
            terminationOrder.synchronized(terminationOrder += this.spec.command.head)
            super.terminate()
          }
        }
      }
      startApp()

      // When
      val result: StopResult = session.stopApp()

      // Then
      terminationOrder shouldBe Seq(java.toString, "sendmidi", "receivemidi")
      result shouldBe StopResult(FakeProcess.TerminatedExitCode, killed = false)
      processOf("sendmidi").inputClosed shouldBe true
    }

    "start the app again once stopped" in new Fixture {
      // Given
      startApp()
      session.stopApp()

      // When
      val result: StartAppResult = startApp()

      // Then
      result.pid shouldBe processOf(java.toString).pid
      launcher.specs.map(_.command.head) shouldBe
        Seq("sendmidi", "receivemidi", java.toString, "sendmidi", "receivemidi", java.toString)
      launcher.processes.drop(3).map(_.isAlive) shouldBe Seq(true, true, true)
    }

    "stop an app that is still starting" in new Fixture {
      // Given
      val refusedDriver: CompletableFuture[FakeDriver] = CompletableFuture()
      launcher.newProcess = (spec, pid) => {
        if (isApp(spec)) {
          // Its hello is refused only once the app's process is tracked, so this driver's disconnection says it is.
          refusedDriver.complete(FakeDriver(run.socket, pid, helloLine = Some("not a hello")))
        }
        FakeProcess(spec, pid)
      }
      val failed: CompletableFuture[Throwable] = CompletableFuture()
      Thread.ofVirtual().start { () =>
        try {
          startApp()
          failed.completeExceptionally(AssertionError("the app started"))
        } catch {
          case e: Throwable => failed.complete(e)
        }
      }
      refusedDriver.get(AwaitSeconds, TimeUnit.SECONDS).disconnected.get(AwaitSeconds, TimeUnit.SECONDS)

      // When
      val result: StopResult = session.stopApp()

      // Then
      result shouldBe StopResult(FakeProcess.TerminatedExitCode, killed = false)
      failed.get(AwaitSeconds, TimeUnit.SECONDS).getMessage should startWith("the app exited with code 143")
      launcher.processes.map(_.isAlive) shouldBe Seq(false, false, false)
    }

    "fail stop_app when no app runs" in new Fixture {
      // When / Then
      (the[QaException] thrownBy session.stopApp()).getMessage shouldBe "no app running; call start_app"
    }

    "fail a GUI request when no app runs" in new Fixture {
      // When / Then
      (the[QaException] thrownBy session.driver(Idle)).getMessage shouldBe "no app running; call start_app"
    }

    "pass a GUI request to the app's driver" in new Fixture {
      // Given
      startApp()
      driver.script = envelope => Protocol.encode(DriverResponse.Ok(envelope.id, Json.obj("text" -> "clip")))

      // When
      val result: JsObject = session.driver(ReadClipboard)

      // Then
      result shouldBe Json.obj("text" -> "clip")
      driver.requests shouldBe Seq(ReadClipboard)
    }

    "save a snapshot in the run directory" in new Fixture {
      // Given
      val png: Array[Byte] = Array[Byte](-119, 80, 78, 71)
      startApp()
      driver.script = envelope =>
        Protocol.encode(DriverResponse.Ok(envelope.id,
          Json.obj("png" -> Base64.getEncoder.encodeToString(png), "width" -> 640, "height" -> 480)))

      // When
      val first: SnapshotResult = session.snapshot(Some(1), Some("Play"), 2.0)
      val second: SnapshotResult = session.snapshot(None, None, 1.0)

      // Then
      first.file shouldBe run.dir.resolve("snapshots").resolve("1.png")
      Files.readAllBytes(first.file) shouldBe png
      first.png shouldBe png
      (first.width, first.height) shouldBe (640, 480)
      second.file shouldBe run.dir.resolve("snapshots").resolve("2.png")
      driver.requests shouldBe Seq(Snapshot(Some(1), Some("Play"), 2.0), Snapshot(None, None, 1.0))
    }

    "fail a snapshot whose answer has no PNG" in new Fixture {
      // Given
      startApp()

      // When / Then
      (the[QaException] thrownBy session.snapshot(None, None, 1.0)).getMessage should
        startWith("the driver's snapshot is unreadable")
    }

    "send MIDI to a device's sendmidi" in new Fixture {
      // Given
      startApp()

      // When
      val midiIn: Path = session.sendMidi("keys", Seq("on 60 100"))

      // Then
      midiIn shouldBe run.dir.resolve("midi-in-keys.txt")
      processOf("sendmidi").written shouldBe "on 60 100\n"
    }

    "refuse the MIDI tools' requests when no app runs" in new Fixture {
      // Given
      startApp()
      session.stopApp()

      // When / Then
      for (
        request <- Seq(() => session.sendMidi("keys", Seq("on 60 100")), () => session.unplug("keys"),
          () => session.replug("keys"))
      ) {
        (the[QaException] thrownBy request()).getMessage shouldBe "no app running; call start_app"
      }
      launcher.specs should have size 3
    }

    "terminate every process at once on shutdown" in new Fixture {
      // Given
      launcher.newProcess = (spec, pid) => {
        if (isApp(spec)) {
          drivers += FakeDriver(run.socket, pid)
        }
        FakeProcess(spec, pid, ignoresTerminate = true)
      }
      startApp()

      // When
      session.shutdown()

      // Then
      launcher.processes.map(_.terminateCount) shouldBe Seq(1, 1, 1)
      launcher.processes.map(_.killCount) shouldBe Seq(0, 0, 0)
      launcher.processes.map(_.isAlive) shouldBe Seq(true, true, true)
    }

    "stop every process, killing an app that ignores SIGTERM, when the agent finishes" in new Fixture {
      // Given
      launcher.newProcess = (spec, pid) => {
        if (isApp(spec)) {
          drivers += FakeDriver(run.socket, pid)
        }
        FakeProcess(spec, pid, ignoresTerminate = isApp(spec))
      }
      startApp()

      // When
      session.finish()

      // Then
      launcher.processes.map(_.isAlive) shouldBe Seq(false, false, false)
      processOf(java.toString).killCount shouldBe 1
      (the[QaException] thrownBy startApp()).getMessage shouldBe "the QA server is shutting down"
    }

    "terminate an app that is still starting on shutdown" in new Fixture {
      // Given
      launcher.newProcess = (spec, pid) => {
        if (isApp(spec)) {
          // The server shuts down after the app's process starts and before the session tracks it.
          session.shutdown()
        }
        FakeProcess(spec, pid)
      }

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage should startWith("the app exited with code 143")
      launcher.processes.map(_.isAlive) shouldBe Seq(false, false, false)
    }

    "refuse to start once shut down" in new Fixture {
      // Given
      session.shutdown()

      // When / Then
      (the[QaException] thrownBy startApp()).getMessage shouldBe "the QA server is shutting down"
      launcher.specs shouldBe empty
    }
  }

  "QaSession.osScreenshot" should {
    "take PNGs of the desktop app's windows, numbering each call's" in new Fixture {
      // Given
      session.startApp(StartAppArgs("qa.mtlist", GuiPlatform.Desktop))
      val appPid: Long = processOf(java.toString).pid
      launcher.runResult = command =>
        command.head match {
          case "/usr/bin/osascript" => CommandResult(0, """{"granted":true,"windowIds":[12]}""", "")
          case _ =>
            Files.write(Path.of(command.last), Array[Byte](1, 2))
            CommandResult(0, "", "")
        }

      // When
      val first: Seq[(Path, Array[Byte])] = session.osScreenshot()
      val second: Seq[(Path, Array[Byte])] = session.osScreenshot()

      // Then
      launcher.runs.head.last shouldBe appPid.toString
      first.map(_._1) shouldBe Seq(run.dir.resolve("screenshots").resolve("os-1-12.png"))
      second.map(_._1) shouldBe Seq(run.dir.resolve("screenshots").resolve("os-2-12.png"))
      first.head._2 shouldBe Array[Byte](1, 2)
    }

    "refuse the headless platform" in new Fixture {
      // Given
      startApp()

      // When / Then
      (the[QaException] thrownBy session.osScreenshot()).getMessage shouldBe
        "os_screenshot needs the desktop platform: start_app with platform desktop"
      launcher.runs shouldBe empty
    }

    "refuse when no app runs" in new Fixture {
      // When / Then
      (the[QaException] thrownBy session.osScreenshot()).getMessage shouldBe "no app running; call start_app"
      launcher.runs shouldBe empty
    }
  }
}

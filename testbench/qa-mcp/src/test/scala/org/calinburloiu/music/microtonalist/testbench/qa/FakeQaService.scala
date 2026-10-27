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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest
import play.api.libs.json.{JsObject, Json}

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/**
 * A [[QaService]] on a new run in a temporary directory, which starts no process: it records the calls and answers with
 * canned results.
 *
 * Its `sendMidi` validates the lines as `MidiPorts` does, so that a refused line throws its [[QaException]].
 */
class FakeQaService extends QaService {
  override val run: QaRun = QaRun.create(Files.createTempDirectory("qa"))

  /** The answer to each driver request. */
  @volatile var driverResult: DriverRequest => JsObject = _ => Json.obj()

  /** The PNG that each snapshot returns. */
  val png: Array[Byte] = Array[Byte](1, 2, 3)

  private val _requests: ArrayBuffer[DriverRequest] = ArrayBuffer()
  private val _calls: ArrayBuffer[(String, Any)] = ArrayBuffer()

  /** The requests passed to the driver, a snapshot's included, in order. */
  def requests: Seq[DriverRequest] = synchronized(_requests.toSeq)

  /** The other calls, each with its method's name and its arguments, in order. */
  def calls: Seq[(String, Any)] = synchronized(_calls.toSeq)

  override def startApp(args: StartAppArgs): StartAppResult = {
    record("startApp", args)
    StartAppResult(FakeQaService.Pid, args.platform, Seq(FakeQaService.Keys), run.file("app.log").toString,
      run.file("qa.conf").toString, None)
  }

  override def stopApp(): StopResult = {
    record("stopApp", ())
    StopResult(0, killed = false)
  }

  override def driver(request: DriverRequest): JsObject = {
    synchronized {
      _requests += request
    }
    driverResult(request)
  }

  override def snapshot(window: Option[Int], selector: Option[String], scale: Double): SnapshotResult = {
    driver(DriverRequest.Snapshot(window, selector, scale))
    SnapshotResult(run.file("snapshots").resolve("1.png"), png, 4, 3)
  }

  override def sendMidi(device: String, lines: Seq[String]): Path = {
    record("sendMidi", (device, lines))
    SendMidiLines.validate(lines).left.foreach(error => throw QaException(error))
    run.file(s"midi-in-$device.txt")
  }

  override def unplug(device: String): Unit = record("unplug", device)

  override def replug(device: String): Unit = record("replug", device)

  override def osScreenshot(): Seq[(Path, Array[Byte])] = Seq.empty

  override def shutdown(): Unit = record("shutdown", ())

  private def record(method: String, args: Any): Unit = synchronized {
    _calls += method -> args
  }
}

object FakeQaService {
  /** The PID of the app that [[FakeQaService.startApp]] reports. */
  val Pid: Long = 1000

  /** The device that [[FakeQaService.startApp]] reports. */
  val Keys: DeviceInfo = DeviceInfo("keys", "qa-0000-keys", input = true, output = false, plugged = true,
    Some("midi-in-keys.txt"), None)
}

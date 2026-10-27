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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, GuiPlatform}
import play.api.libs.json.{JsObject, JsValue, Json}

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The [[QaService]] of a QA run: it starts the app on the run's rig with the run's virtual MIDI ports, and stops them,
 * only through the handles of the processes it started. Thread-safe.
 *
 * @param java the `java` program that runs the app, e.g. [[AppLauncher.CurrentJava]]
 * @param appStopTimeout how long [[stopApp]] and [[finish]] wait for the app to exit on `SIGTERM`, before killing it
 */
final class QaSession(val run: QaRun, launcher: ProcessLauncher, jars: QaJars, java: Path,
                      appStopTimeout: FiniteDuration = QaSession.AppStopTimeout) extends QaService {

  import QaSession.*

  private val appLauncher: AppLauncher = AppLauncher(run, launcher, jars, java)
  private val ports: MidiPorts = MidiPorts(run, launcher)

  /** Whether a [[startApp]] is under way. */
  private var starting: Boolean = false
  /** The app's process, from its launch until it's stopped. */
  private var appProcess: Option[LaunchedProcess] = None
  /** The app, once it has shown a window, until it's stopped. */
  private var app: Option[RunningApp] = None
  private var isShutDown: Boolean = false
  private var snapshotCount: Int = 0
  private var osScreenshotCount: Int = 0

  override def startApp(args: StartAppArgs): StartAppResult = {
    synchronized {
      if (isShutDown) {
        throw QaException("the QA server is shutting down")
      }
      if (starting || app.isDefined) {
        throw QaException("an app is running; call stop_app first")
      }
      starting = true
    }

    try {
      start(args)
    } finally {
      synchronized {
        starting = false
      }
    }
  }

  private def start(args: StartAppArgs): StartAppResult = {
    val composition = resolveComposition(args.composition)
    val rigFile = RigLocator.locate(composition, run).fold(error => throw QaException(error), identity)
    val rig = Option.when(Files.exists(rigFile))(rewriteRig(rigFile))
    val devices = rig.fold(Seq.empty)(_.devices)

    ports.open(devices, args.unplugged.toSet)
    val running =
      try {
        val launched = appLauncher.start(composition, args.platform, track)
        synchronized {
          // A stop or a shutdown while the app started took its process.
          if (!appProcess.contains(launched.process)) {
            launched.terminate()
            throw QaException("the app was stopped while it started")
          }
          app = Some(launched)
        }
        launched
      } catch {
        case e @ (_: InterruptedException | NonFatal(_)) =>
          synchronized {
            appProcess = None
          }
          ports.stopAll()
          throw e
      }
    recordProcesses()

    StartAppResult(running.process.pid, running.platform, devices.map(deviceInfo), run.file(AppLogFileName).toString,
      run.file(ConfigFileName).toString, rig.map(_ => rigFile.toString))
  }

  /** Resolves the composition in the run, refusing a path that the app would resolve to another rig than the server. */
  private def resolveComposition(path: String): Path = {
    val composition = run.resolve(path)
    // The app finds the default rig through the composition's URI, which differs from its path for names with `#`, `?`
    // or `%`.
    if (!run.dir.relativize(composition).iterator().asScala.forall(_.toString.matches(SafeName))) {
      throw QaException(s"The composition's path \"$path\" has a name with a character other than a letter, a " +
        "digit, '.', '_' or '-', which the app could resolve to another rig")
    }
    composition
  }

  /** Rewrites the rig file in place for the run, as pretty-printed JSON. */
  private def rewriteRig(rigFile: Path): RewrittenRig = {
    val json: JsValue =
      try {
        Json.parse(Files.readString(rigFile))
      } catch {
        case e: IOException => throw QaException(s"Can't read the rig $rigFile as JSON: ${e.getMessage}")
      }
    val rewritten = RigRewriter.rewrite(json, run.id).fold(error => throw QaException(error), identity)
    Files.writeString(rigFile, Json.prettyPrint(rewritten.json), UTF_8)
    rewritten
  }

  /** Tracks the app's process as soon as it starts, so that it can be stopped while it starts. */
  private def track(process: LaunchedProcess): Unit = {
    val isCancelled = synchronized {
      appProcess = Some(process)
      isShutDown
    }
    if (isCancelled) {
      process.terminate()
    }
    recordProcesses()
  }

  private def deviceInfo(device: QaDevice): DeviceInfo = {
    DeviceInfo(device.name, device.port(run.id), device.input, device.output, ports.isPlugged(device.name),
      Option.when(device.input)(run.file(s"midi-in-${device.name}.txt").toString),
      Option.when(device.output)(run.file(s"midi-out-${device.name}.txt").toString))
  }

  override def stopApp(): StopResult = {
    val (stoppedApp, process) = synchronized {
      val process = appProcess.getOrElse(throw QaException(NoAppMessage))
      val stopped = (app, process)
      app = None
      appProcess = None
      stopped
    }

    val result = stoppedApp.fold(AppLauncher.stopProcess(process, appStopTimeout))(_.stop(appStopTimeout))
    // `stopAll` doesn't wait for the tools, so they stay recorded.
    val tools = ports.processes
    ports.stopAll()
    ProcessRecords.writeProcesses(run, tools)
    result
  }

  override def driver(request: DriverRequest): JsObject = runningApp.request(request)

  override def snapshot(window: Option[Int], selector: Option[String], scale: Double): SnapshotResult = {
    val result = driver(DriverRequest.Snapshot(window, selector, scale))
    val snapshot = for {
      png <- (result \ "png").asOpt[String].flatMap(decodeBase64)
      width <- (result \ "width").asOpt[Int]
      height <- (result \ "height").asOpt[Int]
    } yield (png, width, height)
    val (png, width, height) = snapshot.getOrElse(throw QaException(s"the driver's snapshot is unreadable: $result"))

    val number = synchronized {
      snapshotCount += 1
      snapshotCount
    }
    val file = Files.createDirectories(run.file(SnapshotsDirName)).resolve(s"$number.png")
    Files.write(file, png)
    SnapshotResult(file, png, width, height)
  }

  override def sendMidi(device: String, lines: Seq[String]): Path = {
    requireApp()
    ports.send(device, lines)
  }

  override def unplug(device: String): Unit = {
    requireApp()
    ports.unplug(device)
    recordProcesses()
  }

  override def replug(device: String): Unit = {
    requireApp()
    ports.replug(device)
    recordProcesses()
  }

  override def osScreenshot(): Seq[(Path, Array[Byte])] = {
    val app = runningApp
    if (app.platform == GuiPlatform.Headless) {
      throw QaException("os_screenshot needs the desktop platform: start_app with platform desktop")
    }

    val number = synchronized {
      osScreenshotCount += 1
      osScreenshotCount
    }
    OsScreenshots.capture(run, launcher, app.process.pid, number)
  }

  override def shutdown(): Unit = {
    val (stoppedApp, process) = takeForShutdown()
    stoppedApp.fold(process.foreach(_.terminate()))(_.terminate())
    ports.stopAll()
  }

  /** Refuses to start again, and takes the app and its process, if any, for a shutdown to stop. */
  private def takeForShutdown(): (Option[RunningApp], Option[LaunchedProcess]) = synchronized {
    isShutDown = true
    val stopped = (app, appProcess)
    app = None
    appProcess = None
    stopped
  }

  /**
   * Stops what the session started when the agent finishes, and refuses to start again: unlike [[shutdown]], it stops
   * the app as [[stopApp]] does, killing it if it hasn't exited within the stop timeout.
   */
  def finish(): Unit = {
    takeForShutdown() match {
      case (Some(stoppedApp), _) => stoppedApp.stop(appStopTimeout)
      case (None, Some(process)) => AppLauncher.stopProcess(process, appStopTimeout)
      case (None, None) => ()
    }
    ports.stopAll()
  }

  private def runningApp: RunningApp = synchronized {
    app.getOrElse(throw QaException(NoAppMessage))
  }

  private def requireApp(): Unit = synchronized {
    if (app.isEmpty) {
      throw QaException(NoAppMessage)
    }
  }

  /** Records the app's process and the MIDI tools, which a later server stops if this one is killed. */
  private def recordProcesses(): Unit = {
    val process = synchronized(appProcess)
    ProcessRecords.writeProcesses(run, process.toSeq ++ ports.processes)
  }

  private def decodeBase64(text: String): Option[Array[Byte]] = {
    try {
      Some(Base64.getDecoder.decode(text))
    } catch {
      case _: IllegalArgumentException => None
    }
  }
}

private object QaSession {
  private val NoAppMessage: String = "no app running; call start_app"
  private val AppLogFileName: String = "app.log"
  private val ConfigFileName: String = "qa.conf"
  private val SnapshotsDirName: String = "snapshots"
  /** The names that a composition's path can have, which its URI spells the same. */
  private val SafeName: String = "[A-Za-z0-9._-]+"
  /** How long [[QaSession.stopApp]] waits by default for the app to exit on `SIGTERM`, before killing it. */
  private val AppStopTimeout: FiniteDuration = 10.seconds
}

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
import play.api.libs.json.JsObject

import java.nio.file.Path

/**
 * The arguments of [[QaService.startApp]].
 *
 * @param composition the composition's path, inside the run directory
 * @param platform the JavaFX platform that the app runs on
 * @param unplugged the QA devices to leave unplugged at startup, which [[QaService.replug]] plugs in later
 */
final case class StartAppArgs(composition: String, platform: GuiPlatform = GuiPlatform.Headless,
                              unplugged: Seq[String] = Nil)

/**
 * A QA device of the app's rig.
 *
 * @param device the device's name, `<device>` in the rig's `qa:<device>`
 * @param port the name of its virtual MIDI port, `<run>-<device>`
 * @param input whether a track uses it as its input, so that MIDI can be sent to it
 * @param output whether a track uses it as its output, so that the MIDI it receives is recorded
 * @param plugged whether its port is open
 * @param midiIn the file that logs the MIDI sent to it, for an input device
 * @param midiOut the file that records the MIDI it receives, for an output device
 */
final case class DeviceInfo(device: String, port: String, input: Boolean, output: Boolean, plugged: Boolean,
                            midiIn: Option[String], midiOut: Option[String])

/**
 * The app that [[QaService.startApp]] started.
 *
 * @param pid the app's process ID
 * @param platform the JavaFX platform that it runs on
 * @param devices the QA devices of its rig
 * @param appLog the file that holds its output
 * @param config its configuration file
 * @param rig its rig file, as rewritten for the run, if the composition has one
 */
final case class StartAppResult(pid: Long, platform: GuiPlatform, devices: Seq[DeviceInfo], appLog: String,
                                config: String, rig: Option[String])

/**
 * A snapshot that [[QaService.snapshot]] took.
 *
 * @param file where the PNG is saved, in the run directory
 * @param png the PNG's bytes
 * @param width the image's width, in pixels
 * @param height the image's height, in pixels
 */
final case class SnapshotResult(file: Path, png: Array[Byte], width: Int, height: Int)

/** What the QA server's tools do, on the run of the server. */
trait QaService {
  /** The server's run. */
  def run: QaRun

  /**
   * Starts the app on a composition of the run, with its rig rewritten for the run's virtual MIDI ports, and opens
   * those ports, but the ones left unplugged.
   *
   * @throws QaException if an app runs, if the composition or its rig is refused, or if the app fails to start
   */
  def startApp(args: StartAppArgs): StartAppResult

  /**
   * Stops the app, waiting for it to exit, then its MIDI tools.
   *
   * @throws QaException if no app runs
   */
  def stopApp(): StopResult

  /**
   * Passes a request to the app's driver and returns its result.
   *
   * @throws QaException if no app runs, or if the request fails
   */
  def driver(request: DriverRequest): JsObject

  /**
   * Takes a PNG of a window or of a node through the driver, and saves it as `<run>/snapshots/<n>.png`, `<n>` counting
   * from 1.
   *
   * @throws QaException if no app runs, or if the request fails
   */
  def snapshot(window: Option[Int], selector: Option[String], scale: Double): SnapshotResult

  /**
   * Sends SendMIDI command lines to an input device of the app's rig.
   *
   * @return the file that logs the MIDI sent to the device
   * @throws QaException if no app runs, or as [[MidiPorts.send]] says
   */
  def sendMidi(device: String, lines: Seq[String]): Path

  /**
   * Stops the MIDI tools of a device, like pulling its cable.
   *
   * @throws QaException if no app runs, or for an unknown or unplugged device
   */
  def unplug(device: String): Unit

  /**
   * Starts the MIDI tools of an unplugged device again, like plugging its cable in.
   *
   * @throws QaException if no app runs, for an unknown or plugged device, or if a tool isn't installed
   */
  def replug(device: String): Unit

  /**
   * Takes a PNG of each on-screen window of the app, on the desktop platform, with macOS's `screencapture`, and saves
   * it as `<run>/screenshots/os-<n>-<window ID>.png`, `<n>` counting the calls from 1.
   *
   * @return each PNG's file and bytes
   * @throws QaException if no app runs, on the headless platform, or as [[OsScreenshots.capture]] says
   */
  def osScreenshot(): Seq[(Path, Array[Byte])]

  /**
   * Sends `SIGTERM` to the app and to every MIDI tool at once, without waiting for them, and refuses to start again.
   */
  def shutdown(): Unit
}

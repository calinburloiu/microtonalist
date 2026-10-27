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
import java.nio.file.StandardOpenOption.{APPEND, CREATE}
import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.{Clock, LocalTime}
import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.concurrent.duration.*

/**
 * The virtual MIDI ports of a run's QA devices, each opened by external tools that the server starts: `sendmidi` for a
 * device that a track uses as its input, through which the agent sends MIDI to the app, and `receivemidi` for one used
 * as an output, which records the MIDI the app sends in `midi-out-<device>.txt`.
 *
 * It stops only the processes it started, through their handles. Thread-safe.
 *
 * @param clock the clock of the times logged in `midi-in-<device>.txt`
 * @param toolExitTimeout how long [[unplug]] waits for a tool to exit after `SIGTERM`, before killing it
 */
final class MidiPorts(run: QaRun, launcher: ProcessLauncher, clock: Clock = Clock.systemDefaultZone(),
                      toolExitTimeout: FiniteDuration = 5.seconds) {

  import MidiPorts.*

  private var devices: Seq[QaDevice] = Seq.empty
  /** The tools of each plugged device. */
  private var tools: Map[String, DeviceTools] = Map.empty

  /**
   * Opens the ports of `devices`, but those named in `unplugged`, which [[replug]] can plug in later.
   *
   * If a tool fails to start, it stops the tools already started.
   *
   * @throws QaException if `unplugged` names an unknown device, or if a tool isn't installed
   * @throws IllegalStateException if ports are open, since the last [[stopAll]]
   */
  def open(devices: Seq[QaDevice], unplugged: Set[String]): Unit = synchronized {
    if (tools.nonEmpty) {
      throw IllegalStateException("The ports are open; stop them first")
    }
    this.devices = devices
    unplugged.toSeq.sorted.foreach(device)

    try {
      devices.filterNot(qaDevice => unplugged.contains(qaDevice.name)).foreach(plug)
    } catch {
      case e: QaException =>
        stopAll()
        throw e
    }
  }

  /**
   * Tells whether the tools of a device run.
   *
   * @throws QaException for an unknown device
   */
  def isPlugged(device: String): Boolean = synchronized {
    this.device(device)
    tools.contains(device)
  }

  /**
   * Stops both tools of a device, like pulling its cable, and waits for them to exit, killing a tool that doesn't exit
   * within the `toolExitTimeout`.
   *
   * @throws QaException for an unknown or an unplugged device
   */
  def unplug(device: String): Unit = synchronized {
    this.device(device)
    val deviceTools = tools.getOrElse(device, throw QaException(s"$device is already unplugged"))
    tools -= device

    stop(Seq(deviceTools))
    for (process <- deviceTools.processes) {
      try {
        process.onExit.get(toolExitTimeout.toMillis, TimeUnit.MILLISECONDS)
      } catch {
        case _: TimeoutException => process.kill()
      }
    }
  }

  /**
   * Starts the tools of an unplugged device again, like plugging its cable in. `receivemidi` keeps appending to the
   * same `midi-out-<device>.txt`.
   *
   * @throws QaException for an unknown or a plugged device, or if a tool isn't installed
   */
  def replug(device: String): Unit = synchronized {
    val qaDevice = this.device(device)
    if (tools.contains(device)) {
      throw QaException(s"$device is already plugged in")
    }
    plug(qaDevice)
  }

  /**
   * Sends SendMIDI command lines to a device's `sendmidi`, after appending each, with the time sent, to
   * `midi-in-<device>.txt`.
   *
   * @return the `midi-in-<device>.txt` file
   * @throws QaException for an unknown device, one that isn't an input or is unplugged, a line that
   *   [[SendMidiLines.validate]] refuses, and a `sendmidi` that has exited
   */
  def send(device: String, lines: Seq[String]): Path = synchronized {
    if (!this.device(device).input) {
      throw QaException(s"$device isn't an input device")
    }
    val sendMidi = tools.get(device).flatMap(_.sendMidi)
      .getOrElse(throw QaException(s"$device is unplugged; call replug first"))
    SendMidiLines.validate(lines).left.foreach(error => throw QaException(error))

    val midiIn = run.file(s"midi-in-$device.txt")
    val time = TimeFormat.format(LocalTime.now(clock))
    Files.writeString(midiIn, lines.map(line => s"$time $line\n").mkString, UTF_8, CREATE, APPEND)

    try {
      sendMidi.input.write(lines.map(line => s"$line\n").mkString.getBytes(UTF_8))
      sendMidi.input.flush()
    } catch {
      case _: IOException => throw QaException(s"sendmidi for $device exited; see $ToolsLogFileName")
    }
    midiIn
  }

  /** Closes the input of each `sendmidi`, then sends `SIGTERM` to every tool, without waiting for them to exit. */
  def stopAll(): Unit = synchronized {
    stop(devices.flatMap(qaDevice => tools.get(qaDevice.name)))
    tools = Map.empty
  }

  /** The tools that run, of the plugged devices. */
  def processes: Seq[LaunchedProcess] = synchronized {
    devices.flatMap(qaDevice => tools.get(qaDevice.name)).flatMap(_.processes)
  }

  private def device(name: String): QaDevice = {
    devices.find(_.name == name).getOrElse {
      val known = if (devices.isEmpty) "there are no QA devices"
      else
        s"the QA devices are ${devices.map(_.name).mkString(", ")}"
      throw QaException(s"unknown device $name; $known")
    }
  }

  /** Starts the tools of a device, stopping its `sendmidi` if its `receivemidi` fails to start. */
  private def plug(qaDevice: QaDevice): Unit = {
    val port = qaDevice.port(run.id)
    val sendMidi = Option.when(qaDevice.input) {
      startTool(ProcessSpec(Seq(SendMidi, "virt", port, "--"), Some(run.file(ToolsLogFileName)), pipeInput = true))
    }
    val receiveMidi =
      try {
        Option.when(qaDevice.output) {
          startTool(ProcessSpec(Seq(ReceiveMidi, "virt", port, "ts", "nn"),
            Some(run.file(s"midi-out-${qaDevice.name}.txt"))))
        }
      } catch {
        case e: QaException =>
          stop(Seq(DeviceTools(sendMidi, None)))
          throw e
      }
    tools += qaDevice.name -> DeviceTools(sendMidi, receiveMidi)
  }

  private def startTool(spec: ProcessSpec): LaunchedProcess = {
    try {
      launcher.start(spec)
    } catch {
      case _: IOException => throw QaException(s"${spec.command.head} isn't installed; see $InstallGuide")
    }
  }

  /** Closes the input of each `sendmidi`, letting it exit on its own, then sends `SIGTERM` to every tool. */
  private def stop(stopped: Seq[DeviceTools]): Unit = {
    for (sendMidi <- stopped.flatMap(_.sendMidi)) {
      // A `sendmidi` that has exited may fail to flush on close; it's being stopped anyway.
      try {
        sendMidi.input.close()
      } catch {
        case _: IOException =>
      }
    }
    stopped.flatMap(_.processes).foreach(_.terminate())
  }
}

private object MidiPorts {
  private val SendMidi: String = "sendmidi"
  private val ReceiveMidi: String = "receivemidi"
  private val ToolsLogFileName: String = "midi-tools.log"
  private val InstallGuide: String = "docs/development/gui-qa.md"
  private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  private final case class DeviceTools(sendMidi: Option[LaunchedProcess], receiveMidi: Option[LaunchedProcess]) {
    def processes: Seq[LaunchedProcess] = sendMidi.toSeq ++ receiveMidi
  }
}

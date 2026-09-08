/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.cli

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.javamidi.JavaMidiManager
import org.calinburloiu.music.scmidi.{MidiDeviceInfo, MidiManager}

/**
 * Entry point of the `microtonalist-cli` utilities tool, a command-line executable separate from the Microtonalist
 * desktop application.
 */
object MicrotonalistToolApp {

  /**
   * Runs the subcommand named by the first argument, printing a usage message for anything else.
   *
   * This is the composition root of the tool: it creates the [[JavaMidiManager]] that `midi-devices` lists through
   * and closes it when done.
   *
   * @param args Command-line arguments; only `midi-devices` is supported.
   */
  def main(args: Array[String]): Unit = {
    args match {
      case Array("midi-devices") =>
        val midiManager = JavaMidiManager(Businessync(EventBus()))
        printMidiDevices(midiManager)
        midiManager.close()
      case _ => println(
        """Usage:
          |midi-devices    prints all available MIDI devices
          |""".stripMargin
      )
    }
  }

  /**
   * Prints every input and output device of `midiManager`: its name, vendor, version and description, and how many
   * transmitters (inputs) or receivers (outputs) it can open.
   */
  private[cli] def printMidiDevices(midiManager: MidiManager): Unit = {
    // Endpoint is a term for input or output
    def printMidiDevicesByEndpoint(devicesInfo: Seq[MidiDeviceInfo], printLimit: MidiDeviceInfo => Unit): Unit = {
      devicesInfo.foreach { info =>
        println(
          s"""Name: ${info.name}
             |Vendor: ${info.vendor}
             |Version: ${info.version}
             |Description: ${info.description}""".stripMargin
        )
        printLimit(info)
        println()
      }
    }

    println("=== Input Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.inputDevicesInfo,
      info => println(s"Max. Transmitters: ${info.maxTransmitters}"))

    println("\n=== Output Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.outputDevicesInfo,
      info => println(s"Max. Receivers: ${info.maxReceivers}"))
  }
}

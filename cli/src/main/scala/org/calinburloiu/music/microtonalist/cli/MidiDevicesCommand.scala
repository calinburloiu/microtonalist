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

package org.calinburloiu.music.microtonalist.cli

import org.calinburloiu.music.scmidi.{MidiDeviceInfo, MidiManager}

/**
 * The `midi-devices` command of the `microtonalist-cli` tool, which lists the MIDI devices connected to the computer.
 *
 * @param midiManager Lists the devices. The caller owns it and closes it after running the command.
 */
class MidiDevicesCommand(midiManager: MidiManager) {

  /**
   * Prints every input and output device: its name, vendor, version and description, and how many transmitters
   * (inputs) or receivers (outputs) it can open.
   */
  def run(): Unit = {
    println("=== Input Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.inputDevicesInfo,
      info => println(s"Max. Transmitters: ${info.transmittersLimit}"))

    println("\n=== Output Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.outputDevicesInfo,
      info => println(s"Max. Receivers: ${info.receiversLimit}"))
  }

  // Endpoint is a term for input or output
  private def printMidiDevicesByEndpoint(devicesInfo: Seq[MidiDeviceInfo], printLimit: MidiDeviceInfo => Unit): Unit = {
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
}

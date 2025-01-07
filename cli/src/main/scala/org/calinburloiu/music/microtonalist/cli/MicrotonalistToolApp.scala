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
import org.calinburloiu.music.scmidi.MidiManager

import javax.sound.midi.{MidiDevice, MidiSystem}

object MicrotonalistToolApp {

  def main(args: Array[String]): Unit = {
    args match {
      case Array("midi-devices") => printMidiDevices()
      case _ => println(
        """Usage:
          |midi-devices    prints all available MIDI devices
          |""".stripMargin
      )
    }
  }

  private def printMidiDevices(): Unit = {
    val businessync = new Businessync(new EventBus())
    val midiManager = new MidiManager(businessync)

    // Endpoint is a term for input or output
    def printMidiDevicesByEndpoint(devicesInfo: Seq[MidiDevice.Info], printHandler: MidiDevice => Unit)
    : Unit = {
      devicesInfo.foreach { deviceInfo =>
        val midiDevice = MidiSystem.getMidiDevice(deviceInfo)

        println(
          s"""Name: ${deviceInfo.getName}
             |Vendor: ${deviceInfo.getVendor}
             |Version: ${deviceInfo.getVersion}
             |Description: ${deviceInfo.getDescription}""".stripMargin
        )
        printHandler(midiDevice)
        println()
      }
    }

    def printTransmitters(midiDevice: MidiDevice): Unit = {
      println(s"Max. Transmitters: ${fromHandlerCountToString(midiDevice.getMaxTransmitters)}")
    }

    def printReceivers(midiDevice: MidiDevice): Unit = {
      println(s"Max. Receivers: ${fromHandlerCountToString(midiDevice.getMaxReceivers)}")
    }

    println("=== Input Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.inputDevicesInfo, printTransmitters)

    println("\n=== Output Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.outputDevicesInfo, printReceivers)

    midiManager.close()
  }

  private def fromHandlerCountToString(handlerCount: Int): String = {
    if (handlerCount == -1) {
      "unlimited"
    } else {
      handlerCount.toString
    }
  }
}

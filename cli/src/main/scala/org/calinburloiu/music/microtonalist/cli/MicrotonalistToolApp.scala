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
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiManager}

import javax.sound.midi.MidiSystem

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

  def printMidiDevices(): Unit = {
    val businessync = new Businessync(new EventBus())
    val midiManager = new MidiManager(businessync)

    def printMidiDevicesByType(isInput: Boolean, deviceIds: Seq[MidiDeviceId], printHandler: MidiDeviceId => Unit)
    : Unit = {
      deviceIds.foreach { deviceId =>
        val deviceInfo = if (isInput)
          midiManager.inputDeviceInfoOf(deviceId)
        else
          midiManager.outputDeviceInfoOf(deviceId)
        println(
          s"""Name: ${deviceInfo.getName}
             |Vendor: ${deviceInfo.getVendor}
             |Version: ${deviceInfo.getVersion}
             |Description: ${deviceInfo.getDescription}""".stripMargin
        )
        printHandler(deviceId)
        println()
      }
    }

    println("=== Input Devices ===\n")

    def printTransmitters(deviceId: MidiDeviceId): Unit = {
      val deviceInfo = midiManager.inputDeviceInfoOf(deviceId)
      val midiDevice = MidiSystem.getMidiDevice(deviceInfo)
      println(s"Max. Transmitters: ${fromHandlerCountToString(midiDevice.getMaxTransmitters)}")
    }

    printMidiDevicesByType(isInput = true, midiManager.inputDeviceIds, printTransmitters)

    println("\n=== Output Devices ===\n")

    def printReceivers(deviceId: MidiDeviceId): Unit = {
      val deviceInfo = midiManager.outputDeviceInfoOf(deviceId)
      val midiDevice = MidiSystem.getMidiDevice(deviceInfo)
      println(s"Max. Receivers: ${fromHandlerCountToString(midiDevice.getMaxReceivers)}")
    }

    printMidiDevicesByType(isInput = false, midiManager.outputDeviceIds, printReceivers)

    midiManager.close()
  }

  private def fromHandlerCountToString(handlerCount: Int): String =
    if (handlerCount == -1)
      "unlimited"
    else
      handlerCount.toString
}

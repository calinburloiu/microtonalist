package org.calinburloiu.music.microtuner.tool

import org.calinburloiu.music.microtuner.midi.{MidiDeviceId, MidiManager}

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
    val midiManager = new MidiManager

    def printMidiDevicesByType(deviceIds: Seq[MidiDeviceId], printHandler: (MidiDeviceId) => Unit): Unit = {
      deviceIds.foreach { deviceId =>
        val deviceInfo = midiManager.deviceInfo(deviceId)
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
      midiManager.openInput(deviceId)
      val device = midiManager.inputDevice(deviceId)
      println(s"Max. Transmitters: ${fromHandlerCountToString(device.getMaxTransmitters)}")
    }
    printMidiDevicesByType(midiManager.inputDeviceIds, printTransmitters)

    println("\n=== Output Devices ===\n")
    def printReceivers(deviceId: MidiDeviceId): Unit = {
      midiManager.openOutput(deviceId)
      val device = midiManager.outputDevice(deviceId)
      println(s"Max. Receivers: ${fromHandlerCountToString(device.getMaxReceivers)}")
    }
    printMidiDevicesByType(midiManager.outputDeviceIds, printReceivers)

    midiManager.close()
  }

  private def fromHandlerCountToString(handlerCount: Int): String = if (handlerCount == -1)
    "unlimited"
  else
    handlerCount.toString
}

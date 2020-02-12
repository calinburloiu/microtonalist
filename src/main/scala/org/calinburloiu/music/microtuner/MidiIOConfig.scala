package org.calinburloiu.music.microtuner

import net.ceedubs.ficus.Ficus._
import com.typesafe.config.{Config, ConfigFactory}
import javax.sound.midi.MidiDevice

/**
  * MIDI Input read-only interface
  */
trait MidiIOConfigured {
  def devices: Seq[MidiDeviceId]
}

/**
  * MIDI Input read-write interface
  */
trait MidiIOConfigurable extends MidiIOConfigured {
  def devices_=(devices: Seq[MidiDeviceId]): Unit
}

/**
  * Concrete MIDI Input configuration implementation
  */
class MidiIOConfig(private[this] val hocon: Config) extends MidiIOConfigurable {

  override def devices_=(devices: Seq[MidiDeviceId]): Unit = ???

  override def devices: Seq[MidiDeviceId] = hocon.as[Seq[Config]]("devices").map { deviceHocon =>
    MidiDeviceId(deviceHocon.as[String]("name"), deviceHocon.as[String]("vendor"), deviceHocon.as[String]("version"))
  }
}

case class MidiDeviceId(
  name: String,
  vendor: String,
  version: String
)

object MidiDeviceId {
  def apply(midiDeviceInfo: MidiDevice.Info): MidiDeviceId =
    MidiDeviceId(midiDeviceInfo.getName, midiDeviceInfo.getVendor, midiDeviceInfo.getVersion)
}

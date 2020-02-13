package org.calinburloiu.music.microtuner

import com.typesafe.config.Config
import javax.sound.midi.MidiDevice
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

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

  private[this] implicit val midiDeviceIdValueReader: ValueReader[MidiDeviceId] = ValueReader.relative { conf =>
    MidiDeviceId(
      name = conf.as[String]("name"),
      vendor = conf.as[String]("vendor"),
      version = conf.as[String]("version"))
  }

  /*
  Writing is not implemented and was left were as a an example, because
  `MidiIOConfig` was the first configuration fragment to use HOCON in this project.
   */
  override def devices_=(devices: Seq[MidiDeviceId]): Unit = ???

  override def devices: Seq[MidiDeviceId] = hocon.as[Seq[MidiDeviceId]]("devices")
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

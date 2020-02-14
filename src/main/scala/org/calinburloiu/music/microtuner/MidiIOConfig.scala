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


trait MidiOutputConfigured extends MidiIOConfigured

trait MidiOutputConfigurable extends MidiIOConfigurable with MidiOutputConfigured

class MidiOutputConfig(private[this] val hocon: Config) extends MidiIOConfig(hocon) with MidiOutputConfigurable {

}

object MidiOutputConfig {
  val configRootPath = "output.midi"
}


trait MidiInputConfigured extends MidiIOConfigured {
  def enabled: Boolean
  def prevTuningCcTrigger: Int
  def nextTuningCcTrigger: Int
  def ccTriggerThreshold: Int
  def isFilteringCcTriggersInOutput: Boolean
}

trait MidiInputConfigurable extends MidiIOConfigurable with MidiInputConfigured {
  def enabled_=(enabled: Boolean): Unit
}

class MidiInputConfig(private[this] val hocon: Config) extends MidiIOConfig(hocon) with MidiInputConfigurable {

  override def enabled: Boolean = hocon.as[Boolean]("enabled")

  override def enabled_=(enabled: Boolean): Unit = ???

  override def prevTuningCcTrigger: Int = hocon.getAs[Int]("prevTuningCcTrigger").getOrElse(67)

  override def nextTuningCcTrigger: Int = hocon.getAs[Int]("nextTuningCcTrigger").getOrElse(66)

  override def ccTriggerThreshold: Int = hocon.getAs[Int]("ccTriggerThreshold").getOrElse(0)

  override def isFilteringCcTriggersInOutput: Boolean = hocon.getAs[Boolean]("isFilteringCcTriggersInOutput").getOrElse(true)
}

object MidiInputConfig {
  val configRootPath = "output.midi"
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

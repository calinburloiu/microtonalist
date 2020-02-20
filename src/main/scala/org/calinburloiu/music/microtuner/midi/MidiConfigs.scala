package org.calinburloiu.music.microtuner.midi

import com.typesafe.config.Config
import javax.sound.midi.MidiDevice
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.calinburloiu.music.microtuner.{Configured, MainConfigManager, SubConfigManager}

case class MidiOutputConfig(
  devices: Seq[MidiDeviceId],
  tuningFormat: MidiTuningFormat
) extends Configured

class MidiOutputConfigManager(mainConfigManager: MainConfigManager)
    extends SubConfigManager[MidiOutputConfig](MidiOutputConfigManager.configRootPath, mainConfigManager) {
  import org.calinburloiu.music.microtuner.ConfigSerDe._
  import MidiConfigSerDe._

  override def serialize(config: MidiOutputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devicesValue = config.devices.map { device =>
      Map("name" -> device.name, "vendor" -> device.vendor, "version" -> device.version)
    }
    hoconConfig
      .withAnyRefValue("devices", devicesValue)
      .withAnyRefValue("tuningFormat", config.tuningFormat.toString)
  }

  override def deserialize(hoconConfig: Config): MidiOutputConfig = MidiOutputConfig(
    devices = hoconConfig.as[Seq[MidiDeviceId]]("devices"),
    tuningFormat = MidiTuningFormat.withName(hoconConfig.as[String]("tuningFormat"))
  )
}

object MidiOutputConfigManager {
  val configRootPath = "output.midi"
}

case class CcTriggers(
  prevTuningCc: Int,
  nextTuningCc: Int,
  ccThreshold: Int,
  isFilteringInOutput: Boolean
)

case class MidiInputConfig(
  enabled: Boolean,
  devices: Seq[MidiDeviceId],
  ccTriggers: CcTriggers
) extends Configured

class MidiInputConfigManager(mainConfigManager: MainConfigManager)
    extends SubConfigManager[MidiInputConfig](MidiInputConfigManager.configRootPath, mainConfigManager) {
  import MidiConfigSerDe.{ccTriggersValueReader, midiDeviceIdValueReader}

  override protected def serialize(configured: MidiInputConfig): Config = ???

  override protected def deserialize(hoconConfig: Config): MidiInputConfig = MidiInputConfig(
    enabled = hoconConfig.as[Boolean]("enabled"),
    devices = hoconConfig.as[Seq[MidiDeviceId]]("devices"),
    ccTriggers = hoconConfig.as[CcTriggers]("ccTriggers")
  )
}

object MidiInputConfigManager {
  val configRootPath = "input.midi"
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

object MidiConfigSerDe {
  private[midi] implicit val midiDeviceIdValueReader: ValueReader[MidiDeviceId] = ValueReader.relative { hc =>
    MidiDeviceId(
      name = hc.as[String]("name"),
      vendor = hc.as[String]("vendor"),
      version = hc.as[String]("version"))
  }

  private[midi] implicit val ccTriggersValueReader: ValueReader[CcTriggers] = ValueReader.relative { hc =>
    CcTriggers(
      prevTuningCc = hc.getAs[Int]("prevTuningCcTrigger").getOrElse(67),
      nextTuningCc = hc.getAs[Int]("nextTuningCcTrigger").getOrElse(66),
      ccThreshold = hc.getAs[Int]("ccTriggerThreshold").getOrElse(0),
      isFilteringInOutput = hc.getAs[Boolean]("isFilteringCcTriggersInOutput").getOrElse(true)
    )
  }
}

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

  override def serialize(configured: MidiOutputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devicesValue = configured.devices.map { device =>
      Map("name" -> device.name, "vendor" -> device.vendor, "version" -> device.version)
    }
    hoconConfig
      .withAnyRefValue("devices", devicesValue)
      .withAnyRefValue("tuningFormat", configured.tuningFormat.toString)
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
  import org.calinburloiu.music.microtuner.ConfigSerDe._
  import MidiConfigSerDe.{ccTriggersValueReader, midiDeviceIdValueReader}

  override protected def serialize(configured: MidiInputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devicesValue = configured.devices.map { device =>
      Map("name" -> device.name, "vendor" -> device.vendor, "version" -> device.version)
    }
    val ccTriggersValue = Map(
      "prevTuningCc" -> configured.ccTriggers.prevTuningCc,
      "nextTuningCc" -> configured.ccTriggers.nextTuningCc,
      "ccThreshold" -> configured.ccTriggers.ccThreshold,
      "isFilteringInOutput" -> configured.ccTriggers.isFilteringInOutput
    )
    hoconConfig
      .withAnyRefValue("enabled", configured.enabled)
      .withAnyRefValue("devices", devicesValue)
      .withAnyRefValue("ccTriggers", ccTriggersValue)
  }

  override protected def deserialize(hc: Config): MidiInputConfig = MidiInputConfig(
    enabled = hc.as[Boolean]("enabled"),
    devices = hc.as[Seq[MidiDeviceId]]("devices"),
    ccTriggers = hc.as[CcTriggers]("ccTriggers")
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
      prevTuningCc = hc.getAs[Int]("prevTuningCc").getOrElse(67),
      nextTuningCc = hc.getAs[Int]("nextTuningCc").getOrElse(66),
      ccThreshold = hc.getAs[Int]("ccThreshold").getOrElse(0),
      isFilteringInOutput = hc.getAs[Boolean]("isFilteringInOutput").getOrElse(true)
    )
  }
}

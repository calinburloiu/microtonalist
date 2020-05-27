/*
 * Copyright 2020 Calin-Andrei Burloiu
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
  import MidiConfigSerDe._
  import org.calinburloiu.music.microtuner.ConfigSerDe._

  override protected def serialize(config: MidiOutputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devices = serializeDevices(config.devices)

    hoconConfig
      .withAnyRefValue("devices", devices)
      .withAnyRefValue("tuningFormat", config.tuningFormat.toString)
  }

  override protected def deserialize(hoconConfig: Config): MidiOutputConfig = MidiOutputConfig(
    devices = hoconConfig.as[Seq[MidiDeviceId]]("devices"),
    tuningFormat = MidiTuningFormat.withName(hoconConfig.as[String]("tuningFormat"))
  )
}

object MidiOutputConfigManager {
  val configRootPath = "output.midi"
}


case class MidiInputConfig(
  enabled: Boolean = false,
  devices: Seq[MidiDeviceId],
  thru: Boolean,
  triggers: Triggers) extends Configured

class MidiInputConfigManager(mainConfigManager: MainConfigManager)
    extends SubConfigManager[MidiInputConfig](MidiInputConfigManager.configRootPath, mainConfigManager) {
  import MidiConfigSerDe._
  import org.calinburloiu.music.microtuner.ConfigSerDe._

  override protected def serialize(config: MidiInputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devices = serializeDevices(config.devices)
    val triggersMap = Map(
      "cc" -> Map(
        "enabled" -> config.triggers.cc.enabled,
        "prevTuningCc" -> config.triggers.cc.prevTuningCc,
        "nextTuningCc" -> config.triggers.cc.nextTuningCc,
        "ccThreshold" -> config.triggers.cc.ccThreshold,
        "isFilteringThru" -> config.triggers.cc.isFilteringThru
      )
    )
    hoconConfig
      .withAnyRefValue("enabled", config.enabled)
      .withAnyRefValue("devices", devices)
      .withAnyRefValue("thru", config.thru)
      .withAnyRefValue("triggers", triggersMap)
  }

  override protected def deserialize(hc: Config): MidiInputConfig = {
    val actualInputConfig = MidiInputConfig(
      enabled = hc.getAs[Boolean]("enabled").getOrElse(false),
      devices = hc.as[Seq[MidiDeviceId]]("devices"),
      thru = hc.as[Boolean]("thru"),
      triggers = hc.as[Triggers]("triggers")
    )

    if (!actualInputConfig.triggers.cc.enabled) {
      actualInputConfig.copy(enabled = false)
    } else {
      actualInputConfig
    }
  }
}

object MidiInputConfigManager {
  val configRootPath = "input.midi"
}

case class Triggers(cc: CcTriggers)

case class CcTriggers(
  enabled: Boolean = false,
  prevTuningCc: Int = 67,
  nextTuningCc: Int = 66,
  ccThreshold: Int = 0,
  isFilteringThru: Boolean = true
)

object CcTriggers {
  val default: CcTriggers = CcTriggers()
}


case class MidiDeviceId(
  name: String,
  vendor: String,
  version: String
) {
  override def toString: String = {
    Seq(vendor, "\"" + name.replaceFirst("^CoreMIDI4J - ", "") + "\"", version)
      .filter(_.trim.nonEmpty).mkString(" ")
  }
}

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

  private[midi] implicit val triggersValueReader: ValueReader[Triggers] = ValueReader.relative { hc =>
    Triggers(
      cc = hc.getAs[CcTriggers]("cc").getOrElse(CcTriggers.default)
    )
  }

  private[midi] implicit val ccTriggersValueReader: ValueReader[CcTriggers] = ValueReader.relative { hc =>
    CcTriggers(
      enabled = hc.getAs[Boolean]("enabled").getOrElse(CcTriggers.default.enabled),
      prevTuningCc = hc.getAs[Int]("prevTuningCc").getOrElse(CcTriggers.default.prevTuningCc),
      nextTuningCc = hc.getAs[Int]("nextTuningCc").getOrElse(CcTriggers.default.nextTuningCc),
      ccThreshold = hc.getAs[Int]("ccThreshold").getOrElse(CcTriggers.default.ccThreshold),
      isFilteringThru = hc.getAs[Boolean]("isFilteringThru").getOrElse(CcTriggers.default.isFilteringThru)
    )
  }

  def serializeDevices(devices: Seq[MidiDeviceId]): Seq[Map[String, String]] = devices.map { device =>
    Map("name" -> device.name, "vendor" -> device.vendor, "version" -> device.version)
  }
}

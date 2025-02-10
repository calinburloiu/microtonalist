/*
 * Copyright 2025 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.config

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.calinburloiu.music.scmidi.{MidiDeviceId, PitchBendSensitivity}

case class MidiOutputConfig(devices: Seq[MidiDeviceId],
                            tunerType: TunerType,
                            pitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity.Default,
                            ccParams: Map[Int, Int] = Map.empty)
  extends Configured

class MidiOutputConfigManager(mainConfigManager: MainConfigManager)
  extends SubConfigManager[MidiOutputConfig](MidiOutputConfigManager.configRootPath, mainConfigManager) {

  import MidiConfigSerDe._
  import MidiOutputConfigManager._
  import org.calinburloiu.music.microtonalist.config.ConfigSerDe._

  override protected def serialize(config: MidiOutputConfig): Config = {
    val hoconConfig = this.hoconConfig
    val devices = serializeDevices(config.devices)
    val pitchBendSensitivity = Map(
      "semitones" -> config.pitchBendSensitivity.semitones,
      "cents" -> config.pitchBendSensitivity.cents
    )
    val ccParams = config.ccParams.map { case (number, value) => Map("number" -> number, "value" -> value) }.toSeq

    hoconConfig
      .withAnyRefValue(PropDevices, devices)
      .withAnyRefValue(PropTunerType, config.tunerType.toString)
      .withAnyRefValue(PropPitchBendSensitivity, pitchBendSensitivity)
      .withAnyRefValue(PropCcParams, ccParams)
  }

  override protected def deserialize(hoconConfig: Config): MidiOutputConfig = MidiOutputConfig(
    devices = hoconConfig.as[Seq[MidiDeviceId]](PropDevices),
    tunerType = TunerType.withName(hoconConfig.as[String](PropTunerType)),
    pitchBendSensitivity = hoconConfig.getAs[PitchBendSensitivity](PropPitchBendSensitivity)
      .getOrElse(PitchBendSensitivity.Default),
    ccParams = CcParam.toMap(hoconConfig.getAs[Seq[CcParam]](PropCcParams).getOrElse(Seq.empty))
  )
}

object MidiOutputConfigManager {
  val configRootPath = "output.midi"

  val PropDevices = "devices"
  val PropTunerType = "tunerType"
  val PropPitchBendSensitivity = "pitchBendSensitivity"
  val PropCcParams = "ccParams"
}


case class MidiInputConfig(enabled: Boolean = false,
                           devices: Seq[MidiDeviceId],
                           thru: Boolean,
                           triggers: Triggers) extends Configured

class MidiInputConfigManager(mainConfigManager: MainConfigManager)
  extends SubConfigManager[MidiInputConfig](MidiInputConfigManager.configRootPath, mainConfigManager) {

  import MidiConfigSerDe._
  import org.calinburloiu.music.microtonalist.config.ConfigSerDe._

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

@deprecated("To be removed with MIDI HOCON config support")
sealed trait TunerType

/**
 * Tuner type to be used based on input/output device capabilities.
 */
object TunerType {
  /** Tuner that sends system exclusive MIDI Tuning Standard (MIDI 1.0) messages. */
  case object MtsOctave1ByteNonRealTime extends TunerType

  /** Tuner that only allows monophonic playing by sending pitch bend values to tune notes. */
  case object MonophonicPitchBend extends TunerType

  def withName(name: String): TunerType = {
    name match {
      case "MtsOctave1ByteNonRealTime" => MtsOctave1ByteNonRealTime
      case "MonophonicPitchBend" => MonophonicPitchBend
      case _ => throw new IllegalArgumentException(s"Unknown tuner type: $name")
    }
  }
}

case class CcTriggers(enabled: Boolean = false,
                      prevTuningCc: Int = 67,
                      nextTuningCc: Int = 66,
                      ccThreshold: Int = 0,
                      isFilteringThru: Boolean = true)

object CcTriggers {
  val default: CcTriggers = CcTriggers()
}

case class Triggers(cc: CcTriggers)

/** Only used to deserializing CC params in HOCON. */
private case class CcParam(number: Int, value: Int)

private object CcParam {
  def toMap(ccParams: Seq[CcParam]): Map[Int, Int] = ccParams.map { p => (p.number, p.value) }.toMap
}

object MidiConfigSerDe {
  private[config] implicit val midiDeviceIdValueReader: ValueReader[MidiDeviceId] = ValueReader.relative { hc =>
    MidiDeviceId(
      name = hc.as[String]("name"),
      vendor = hc.as[String]("vendor"))
  }

  private[config] implicit val triggersValueReader: ValueReader[Triggers] = ValueReader.relative { hc =>
    Triggers(
      cc = hc.getAs[CcTriggers]("cc").getOrElse(CcTriggers.default)
    )
  }

  private[config] implicit val ccTriggersValueReader: ValueReader[CcTriggers] = ValueReader.relative { hc =>
    CcTriggers(
      enabled = hc.getAs[Boolean]("enabled").getOrElse(CcTriggers.default.enabled),
      prevTuningCc = hc.getAs[Int]("prevTuningCc").getOrElse(CcTriggers.default.prevTuningCc),
      nextTuningCc = hc.getAs[Int]("nextTuningCc").getOrElse(CcTriggers.default.nextTuningCc),
      ccThreshold = hc.getAs[Int]("ccThreshold").getOrElse(CcTriggers.default.ccThreshold),
      isFilteringThru = hc.getAs[Boolean]("isFilteringThru").getOrElse(CcTriggers.default.isFilteringThru)
    )
  }

  private[config] implicit val pitchBendSensitivityValueReader: ValueReader[PitchBendSensitivity] =
    ValueReader.relative { hc =>
      PitchBendSensitivity(
        semitones = hc.as[Int]("semitones"),
        cents = hc.getAs[Int]("cents").getOrElse(PitchBendSensitivity.Default.cents)
      )
    }

  private[config] implicit val ccParamValueReader: ValueReader[CcParam] = ValueReader.relative { hc =>
    CcParam(
      number = hc.as[Int]("number"),
      value = hc.as[Int]("value")
    )
  }

  def serializeDevices(devices: Seq[MidiDeviceId]): Seq[Map[String, String]] = devices.map { device =>
    Map("name" -> device.name, "vendor" -> device.vendor)
  }
}

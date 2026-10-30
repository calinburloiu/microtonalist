/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.perf.midilatency.config

import org.calinburloiu.music.microtonalist.perf.midilatency.run.{MeasurementSettings, Timeouts}
import org.calinburloiu.music.microtonalist.perf.midilatency.{PerfDevices, PerfSetupException}
import org.calinburloiu.music.scmidi.MidiDeviceId
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * The configuration of the latency suite, read from the JSON resource `midi-latency-config.json`, over which the
 * optional, git-ignored `midi-latency-config.local.json` sets what is specific to a machine, such as its MIDI devices.
 *
 * @param input The app's input, which the suite sends to.
 * @param output The app's output, which the suite receives from.
 * @param machineName A friendly label of the machine for the report.
 * @param java The `java` executable to run the app with; by default the suite's own.
 * @param jvmOptions The options the app is launched with.
 * @param resultsDir Where the run files go.
 */
case class PerfConfig(input: Option[MidiDeviceId],
                      output: Option[MidiDeviceId],
                      machineName: Option[String],
                      measurement: MeasurementSettings,
                      java: Option[Path],
                      jvmOptions: Seq[String],
                      resultsDir: Path) {

  /**
   * The two devices of the run.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException if one isn't configured.
   */
  def devices: PerfDevices = PerfDevices(
    input = input.getOrElse(throw missingDevice("input", "the app's input, which the suite sends to")),
    output = output.getOrElse(throw missingDevice("output", "the app's output, which the suite receives from"))
  )

  private def missingDevice(key: String, role: String): PerfSetupException = PerfSetupException(
    s"No MIDI device is configured as $role. Set devices.$key in perf/perf-midi-latency/src/test/resources/" +
      s"${PerfConfig.LocalResourceName}, e.g. {\"devices\": {\"$key\": {\"name\": \"…\", \"vendor\": \"…\"}}}")
}

object PerfConfig {
  /** The committed configuration's resource. */
  val ResourceName: String = "midi-latency-config.json"
  /** The resource of the machine's own settings, which is git-ignored. */
  val LocalResourceName: String = "midi-latency-config.local.json"

  /**
   * Reads the configuration from `base`, with `localOverride` merged into it, key by key.
   *
   * An empty string leaves a device, the machine name and the JDK unset; a relative `resultsDir` resolves against
   * `repoRoot`.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException naming the JSON path of a bad
   *   value.
   */
  def parse(base: JsValue, localOverride: Option[JsValue], repoRoot: Path): PerfConfig = {
    val merged = localOverride.foldLeft(base.as[JsObject])((config, local) => config.deepMerge(local.as[JsObject]))
    merged.validate(using configReads(repoRoot)) match {
      case JsSuccess(config, _) => config
      case JsError(errors) =>
        val described = errors.map { case (path, pathErrors) =>
          s"${path.toString.stripPrefix("/").replace('/', '.')}: ${pathErrors.flatMap(_.messages).mkString(", ")}"
        }
        throw PerfSetupException(s"Bad latency suite configuration: ${described.mkString("; ")}")
    }
  }

  private def nonEmpty(value: String): Option[String] = Option(value).filter(_.nonEmpty)

  private val deviceReads: Reads[Option[MidiDeviceId]] = (
    (__ \ "name").read[String] and
      (__ \ "vendor").read[String]
  )((name, vendor) => nonEmpty(name).map(MidiDeviceId(_, vendor)))

  private def millis(path: JsPath, minimum: Long): Reads[FiniteDuration] =
    path.read[Long](using Reads.min(minimum)).map(_.millis)

  private val measurementReads: Reads[MeasurementSettings] = (
    (__ \ "run" \ "launches").read[Int](using Reads.min(1)) and
      (__ \ "run" \ "warmupIterations").read[Int](using Reads.min(0)) and
      (__ \ "run" \ "timedIterations").read[Int](using Reads.min(1)) and
      millis(__ \ "run" \ "paceMillis", minimum = 0) and
      (__ \ "devices" \ "maxMessagesPerSecond").read[Int](using Reads.min(0)) and
      (
        millis(__ \ "timeouts" \ "readinessMillis", minimum = 1) and
          millis(__ \ "timeouts" \ "probeIntervalMillis", minimum = 1) and
          millis(__ \ "timeouts" \ "quietMillis", minimum = 1) and
          millis(__ \ "timeouts" \ "stepMillis", minimum = 1) and
          millis(__ \ "timeouts" \ "exitMillis", minimum = 1)
      )(Timeouts.apply)
  )(MeasurementSettings.apply)

  private def configReads(repoRoot: Path): Reads[PerfConfig] = (
    (__ \ "devices" \ "input").read(using deviceReads) and
      (__ \ "devices" \ "output").read(using deviceReads) and
      (__ \ "machineName").read[String].map(nonEmpty) and
      measurementReads and
      (__ \ "app" \ "java").read[String].map(nonEmpty(_).map(Paths.get(_))) and
      (__ \ "app" \ "jvmOptions").read[Seq[String]] and
      (__ \ "resultsDir").read[String].map(dir => repoRoot.resolve(dir))
  )(PerfConfig.apply)
}

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

package org.calinburloiu.music.microtonalist.perf.app

import org.calinburloiu.music.microtonalist.perf.PerfDevices
import play.api.libs.json.{JsString, Json}

import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/**
 * The files a launch of the app reads: a composition, its tracks file next to it, and the app config.
 */
case class PreparedRig(composition: Path, tracks: Path, appConfig: Path)

/**
 * Writes the files of a case's rig into a scratch directory, filling the configured devices into the tracks template.
 *
 * The template's variables are `{{input.name}}`, `{{input.vendor}}`, `{{output.name}}` and `{{output.vendor}}`, written
 * inside JSON strings: they are replaced with the JSON-escaped value. The app's input is the device the suite sends to,
 * and its output the device the suite receives from.
 */
object ScratchRig {

  /**
   * Fills `devices` into the variables of `template`.
   *
   * @throws IllegalArgumentException for any other variable.
   */
  def render(template: String, devices: PerfDevices): String = {
    val values = Map(
      "input.name" -> devices.input.name,
      "input.vendor" -> devices.input.vendor,
      "output.name" -> devices.output.name,
      "output.vendor" -> devices.output.vendor
    )
    VariablePattern.replaceAllIn(template,
      { variable =>
        val value = values.getOrElse(variable.group(1),
          throw IllegalArgumentException(s"Unknown variable ${variable.matched} in the tracks template"))
        Regex.quoteReplacement(jsonStringContent(value))
      })
  }

  /**
   * Writes the rig into `dir`: the composition as `compositionFileName`, the rendered tracks template next to it as
   * `<compositionFileName>.tracks`, where the app looks for it, and the app config as `app.conf`, empty when
   * `appConfig` is `None`, so that the app never reads or rewrites the user's own.
   */
  def prepare(dir: Path,
              compositionFileName: String,
              composition: String,
              tracksTemplate: String,
              appConfig: Option[String],
              devices: PerfDevices): PreparedRig = {
    Files.createDirectories(dir)
    val rig = PreparedRig(
      composition = dir.resolve(compositionFileName),
      tracks = dir.resolve(s"$compositionFileName.tracks"),
      appConfig = dir.resolve("app.conf")
    )
    Files.writeString(rig.composition, composition)
    Files.writeString(rig.tracks, render(tracksTemplate, devices))
    Files.writeString(rig.appConfig, appConfig.getOrElse(""))
    rig
  }

  private val VariablePattern: Regex = """\{\{([^}]*)\}\}""".r

  /** `value` as it goes between the quotes of a JSON string. */
  private def jsonStringContent(value: String): String = {
    val quoted = Json.stringify(JsString(value))
    quoted.substring(1, quoted.length - 1)
  }
}

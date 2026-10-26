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

package org.calinburloiu.music.microtonalist.perf.config

import org.calinburloiu.music.microtonalist.perf.PerfSetupException

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * The parameters of one run, given as ScalaTest config-map arguments, e.g. `-Djar=app.jar -Dcommit=a8ba00df`.
 *
 * @param jar The app's JAR to measure; without it, the latency cases are canceled.
 * @param commit The commit of a JAR whose `BuildInfo` doesn't tell it.
 * @param resultsDir Where the run file goes, instead of the configured directory.
 */
case class RunParameters(jar: Option[Path],
                         commit: Option[String],
                         launches: Option[Int],
                         warmupIterations: Option[Int],
                         timedIterations: Option[Int],
                         pace: Option[FiniteDuration],
                         maxMessageRate: Option[Int],
                         resultsDir: Option[Path]) {

  /** `config` with the values these parameters override. */
  def applyTo(config: PerfConfig): PerfConfig = {
    val measurement = config.measurement
    config.copy(
      measurement = measurement.copy(
        launches = launches.getOrElse(measurement.launches),
        warmupIterations = warmupIterations.getOrElse(measurement.warmupIterations),
        timedIterations = timedIterations.getOrElse(measurement.timedIterations),
        pace = pace.getOrElse(measurement.pace),
        maxMessageRate = maxMessageRate.getOrElse(measurement.maxMessageRate)
      ),
      resultsDir = resultsDir.getOrElse(config.resultsDir)
    )
  }
}

object RunParameters {

  /**
   * Reads the keys `jar`, `commit`, `launches`, `warmup`, `iterations`, `pace` (in milliseconds), `maxRate` (in
   * messages a second) and `results`. Relative paths resolve against `repoRoot`.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.PerfSetupException for a number that isn't one.
   */
  def fromConfigMap(configMap: Map[String, Any], repoRoot: Path): RunParameters = {
    def text(key: String): Option[String] = configMap.get(key).map(_.toString)

    def number(key: String): Option[Long] = text(key).map { value =>
      value.toLongOption.getOrElse(throw PerfSetupException(s"-D$key must be a whole number, but was $value"))
    }

    def path(key: String): Option[Path] = text(key).map(value => repoRoot.resolve(Paths.get(value)))

    RunParameters(
      jar = path("jar"),
      commit = text("commit"),
      launches = number("launches").map(_.toInt),
      warmupIterations = number("warmup").map(_.toInt),
      timedIterations = number("iterations").map(_.toInt),
      pace = number("pace").map(_.millis),
      maxMessageRate = number("maxRate").map(_.toInt),
      resultsDir = path("results")
    )
  }
}

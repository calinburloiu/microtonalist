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
import org.calinburloiu.music.microtonalist.perf.run.{MeasurementSettings, Timeouts}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.DurationInt

class RunParametersTest extends AnyWordSpec with Matchers {

  private val repoRoot: Path = Paths.get("/repo")

  "RunParameters" should {
    "read the config map, resolving relative paths against the repository" in {
      // Given
      val configMap = Map("jar" -> "/a/app.jar", "commit" -> "a8ba00df", "launches" -> "1", "warmup" -> "200",
        "iterations" -> "50", "pace" -> "10", "maxRate" -> "500", "results" -> "out")

      // When
      val parameters = RunParameters.fromConfigMap(configMap, repoRoot)

      // Then
      parameters shouldEqual RunParameters(jar = Some(Paths.get("/a/app.jar")), commit = Some("a8ba00df"),
        launches = Some(1), warmupIterations = Some(200), timedIterations = Some(50), pace = Some(10.millis),
        maxMessageRate = Some(500), resultsDir = Some(repoRoot.resolve("out")))
    }

    "leave out what isn't given" in {
      // When / Then
      RunParameters.fromConfigMap(Map.empty, repoRoot) shouldEqual
        RunParameters(None, None, None, None, None, None, None, None)
    }

    "reject a count that isn't a number" in {
      // When
      val exception = the[PerfSetupException] thrownBy RunParameters.fromConfigMap(Map("launches" -> "x"), repoRoot)

      // Then
      exception.getMessage should include("launches")
    }

    "override the config" in {
      // Given
      val timeouts = Timeouts(1.second, 1.second, 1.second, 1.second, 1.second)
      val config = PerfConfig(input = None, output = None, machineName = None,
        measurement = MeasurementSettings(3, 10000, 1000, 50.millis, 1000, timeouts), java = None,
        jvmOptions = Seq.empty,
        resultsDir = repoRoot.resolve("perf/results"))
      val parameters = RunParameters(jar = None, commit = None, launches = Some(1), warmupIterations = Some(200),
        timedIterations = None, pace = Some(10.millis), maxMessageRate = Some(500),
        resultsDir = Some(Paths.get("/tmp/out")))

      // When
      val overridden = parameters.applyTo(config)

      // Then
      overridden.measurement shouldEqual MeasurementSettings(1, 200, 1000, 10.millis, 500, timeouts)
      overridden.resultsDir shouldEqual Paths.get("/tmp/out")
    }
  }
}

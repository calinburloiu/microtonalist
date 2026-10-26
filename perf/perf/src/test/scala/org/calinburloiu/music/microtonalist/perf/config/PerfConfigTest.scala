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

import org.calinburloiu.music.microtonalist.perf.{PerfDevices, PerfSetupException}
import org.calinburloiu.music.scmidi.MidiDeviceId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsValue, Json}

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.util.Using

class PerfConfigTest extends AnyWordSpec with Matchers {

  private val repoRoot: Path = Paths.get("/repo")

  private val committed: JsValue = Using.resource(getClass.getResourceAsStream(s"/${PerfConfig.ResourceName}")) {
    stream => Json.parse(stream)
  }

  private val localDevices: JsValue = Json.parse(
    """{"devices": {
      |  "input": {"name": "CoreMIDI4J - IAC 1", "vendor": "Apple Inc."},
      |  "output": {"name": "CoreMIDI4J - IAC 2", "vendor": "Apple Inc."}
      |}}""".stripMargin)

  "PerfConfig" should {
    "read the committed defaults" in {
      // When
      val config = PerfConfig.parse(committed, None, repoRoot)

      // Then
      val measurement = config.measurement
      measurement.launches shouldEqual 3
      measurement.warmupIterations shouldEqual 10000
      measurement.timedIterations shouldEqual 1000
      measurement.pace shouldEqual 50.millis
      measurement.timeouts.readiness shouldEqual 60.seconds
      measurement.timeouts.probeInterval shouldEqual 1.second
      measurement.timeouts.quiet shouldEqual 250.millis
      measurement.timeouts.step shouldEqual 2.seconds
      measurement.timeouts.exit shouldEqual 10.seconds
      config.input shouldBe empty
      config.output shouldBe empty
      config.machineName shouldBe empty
      config.java shouldBe empty
      config.jvmOptions shouldBe empty
      config.resultsDir shouldEqual repoRoot.resolve("perf/results")
    }

    "ask for the devices in perf.local.json" in {
      // Given
      val config = PerfConfig.parse(committed, None, repoRoot)

      // When
      val exception = the[PerfSetupException] thrownBy config.devices

      // Then
      exception.getMessage should include(PerfConfig.LocalResourceName)
    }

    "let the local file set the devices and override one value, keeping its siblings" in {
      // Given
      val local = localDevices.as[play.api.libs.json.JsObject] ++
        Json.obj("run" -> Json.obj("launches" -> 1), "machineName" -> "Studio")

      // When
      val config = PerfConfig.parse(committed, Some(local), repoRoot)

      // Then
      config.devices shouldEqual PerfDevices(MidiDeviceId("CoreMIDI4J - IAC 1", "Apple Inc."),
        MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."))
      config.measurement.launches shouldEqual 1
      config.measurement.timedIterations shouldEqual 1000
      config.machineName shouldEqual Some("Studio")
    }

    "read the JDK and its options" in {
      // Given
      val local = Json.obj("app" -> Json.obj("java" -> "/jdk/bin/java", "jvmOptions" -> Json.arr("-Xmx1g")))

      // When
      val config = PerfConfig.parse(committed, Some(local), repoRoot)

      // Then
      config.java shouldEqual Some(Paths.get("/jdk/bin/java"))
      config.jvmOptions shouldEqual Seq("-Xmx1g")
    }

    "name the path of a bad value" in {
      // Given
      val local = Json.obj("run" -> Json.obj("launches" -> "x"))

      // When
      val exception = the[PerfSetupException] thrownBy PerfConfig.parse(committed, Some(local), repoRoot)

      // Then
      exception.getMessage should include("run.launches")
    }
  }
}

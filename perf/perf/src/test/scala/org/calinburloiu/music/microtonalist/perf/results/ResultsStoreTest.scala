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

package org.calinburloiu.music.microtonalist.perf.results

import org.calinburloiu.music.microtonalist.perf.results.RunResultFixtures.{Commit, sampleRun}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsValue, Json}

import java.nio.file.{Files, Path}
import java.time.Instant

class ResultsStoreTest extends AnyWordSpec with Matchers {

  private def readIndex(dir: Path): JsValue = Json.parse(Files.readString(dir.resolve(ResultsStore.IndexFileName)))

  "ResultsStore" should {
    "name the run file by machine, time, version and commit" in {
      // When / Then
      ResultsStore.relativePath(sampleRun) shouldEqual "f00d/20261026T101500Z-1.6.0-SNAPSHOT-a8ba00df.json"
    }

    "write the run file" in {
      // Given
      val dir = Files.createTempDirectory("results")

      // When
      val file = ResultsStore(dir).write(sampleRun)

      // Then
      file shouldEqual dir.resolve("f00d/20261026T101500Z-1.6.0-SNAPSHOT-a8ba00df.json")
      RunResultJson.read(Files.readString(file)) shouldEqual sampleRun
    }

    "create the index with the first run" in {
      // Given
      val dir = Files.createTempDirectory("results")

      // When
      ResultsStore(dir).write(sampleRun)

      // Then
      val index = readIndex(dir)
      (index \ "schemaVersion").as[Int] shouldEqual 1
      (index \ "runs").as[Seq[JsValue]] shouldEqual Seq(Json.obj(
        "path" -> "f00d/20261026T101500Z-1.6.0-SNAPSHOT-a8ba00df.json",
        "machineId" -> "f00d",
        "machineName" -> "Mac16,8 · Apple M4 Pro",
        "startTime" -> "2026-10-26T10:15:00Z",
        "appVersion" -> "1.6.0-SNAPSHOT",
        "appCommit" -> Commit
      ))
    }

    "add later runs to the index in time order, keeping the others" in {
      // Given
      val dir = Files.createTempDirectory("results")
      val store = ResultsStore(dir)
      val earlier = sampleRun.copy(startTime = Instant.parse("2026-10-25T08:00:00Z"))

      // When
      store.write(sampleRun)
      store.write(earlier)

      // Then
      (readIndex(dir) \ "runs").as[Seq[JsValue]].map(run => (run \ "startTime").as[String]) shouldEqual
        Seq("2026-10-25T08:00:00Z", "2026-10-26T10:15:00Z")
    }
  }
}

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

package org.calinburloiu.music.microtonalist.perf.midilatency.results

import org.calinburloiu.music.microtonalist.perf.midilatency.results.RunResultFixtures.{sampleRun, withoutSamples}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsNull, JsNumber, JsObject, JsValue, Json}

class RunResultJsonTest extends AnyWordSpec with Matchers {

  private lazy val json: JsValue = Json.parse(RunResultJson.write(sampleRun))

  "RunResultJson" should {
    "round-trip a run, except its raw samples" in {
      // When / Then
      RunResultJson.read(RunResultJson.write(sampleRun)) shouldEqual withoutSamples(sampleRun)
    }

    "leave the raw samples out" in {
      // Given
      val metric = (json \ "cases")(0) \ "metrics" \ 0

      // Then
      (metric \ "samples").toOption shouldBe empty
    }

    "write the run's name" in {
      // When / Then
      (json \ "name").as[String] shouldEqual "baseline"
    }

    "write an unnamed run's name as null" in {
      // Given
      val unnamed = Json.parse(RunResultJson.write(sampleRun.copy(name = None)))

      // Then
      (unnamed \ "name").toOption shouldEqual Some(JsNull)
    }

    "read a run file written before runs had a name" in {
      // Given
      val older = Json.stringify(json.as[JsObject] - "name")

      // When / Then
      RunResultJson.read(older).name shouldBe empty
    }

    "start with the schema version" in {
      // When / Then
      RunResultJson.write(sampleRun) should startWith("{\n  \"schemaVersion\" : 1,")
    }

    "flatten the statistics into the metric" in {
      // Given
      val metric = (json \ "cases")(0) \ "metrics" \ 0

      // Then
      (metric \ "name").as[String] shouldEqual "pedal→tuning"
      (metric \ "median").as[Long] shouldEqual 312345
      (metric \ "p99").as[Long] shouldEqual 512345
    }

    "write a histogram's bins as pairs" in {
      // When / Then
      ((json \ "floor" \ "metrics")(0) \ "histogram" \ "bins").as[JsValue] shouldEqual
        Json.arr(Json.arr(1000, 3), Json.arr(1010, 2997))
    }

    "write a failed case with its reason and no metrics" in {
      // Given
      val failed = (json \ "cases")(1)

      // Then
      (failed \ "outcome").as[String] shouldEqual "failed"
      (failed \ "failure").as[String] should include("Timed out")
      (failed \ "metrics").toOption shouldBe empty
    }

    "write the rig of a loopback case, which has none, as null" in {
      // Given
      val loopback = (json \ "cases")(2)

      // Then
      (loopback \ "tracks").toOption shouldEqual Some(JsNull)
      (loopback \ "composition").toOption shouldEqual Some(JsNull)
      (loopback \ "outcome").as[String] shouldEqual "passed"
    }

    "write an unknown dirty flag as null" in {
      // When / Then
      (json \ "app" \ "dirty").toOption shouldEqual Some(JsNull)
      (json \ "machine" \ "memoryBytes").toOption shouldEqual Some(JsNumber(51539607552L))
    }
  }
}

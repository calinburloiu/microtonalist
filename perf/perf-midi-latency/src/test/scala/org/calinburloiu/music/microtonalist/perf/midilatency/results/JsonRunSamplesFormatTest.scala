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

import org.calinburloiu.music.microtonalist.perf.midilatency.results.RunResultFixtures.sampleRun
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsValue, Json}

class JsonRunSamplesFormatTest extends AnyWordSpec with Matchers {

  private lazy val json: JsValue = Json.parse(JsonRunSamplesFormat.write(sampleRun))

  "JsonRunSamplesFormat" should {
    "write the schema version" in {
      // When / Then
      (json \ "schemaVersion").as[Int] shouldEqual 1
    }

    "write the floor's samples of each launch" in {
      // Given
      val floor = (json \ "floor" \ "metrics")(0)

      // Then
      (floor \ "name").as[String] shouldEqual "floor"
      (floor \ "launches").as[Seq[Seq[Long]]] shouldEqual Seq(Seq(97765L, 98765L, 99765L))
    }

    "write each case's samples of each launch, by metric" in {
      // Given
      val pedal = (json \ "cases")(0)

      // Then
      (pedal \ "name").as[String] shouldEqual "The MTS Octave 1-byte tuner should change the tuning on a pedal"
      ((pedal \ "metrics")(0) \ "name").as[String] shouldEqual "pedal→tuning"
      ((pedal \ "metrics")(0) \ "launches").as[Seq[Seq[Long]]] shouldEqual Seq(Seq(311345L, 312345L, 313345L))
    }

    "write a failed case with no metrics" in {
      // When / Then
      ((json \ "cases")(1) \ "metrics").as[Seq[JsValue]] shouldBe empty
    }

    "write compactly, on one line" in {
      // When / Then
      JsonRunSamplesFormat.write(sampleRun).linesIterator.size shouldEqual 1
    }
  }
}

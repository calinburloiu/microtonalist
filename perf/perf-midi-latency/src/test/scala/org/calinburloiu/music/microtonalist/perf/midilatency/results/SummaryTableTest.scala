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

class SummaryTableTest extends AnyWordSpec with Matchers {

  "SummaryTable" should {
    "show median, p99 and max in microseconds, the floor first" in {
      // When
      val lines = SummaryTable.render(sampleRun).linesIterator.toSeq

      // Then
      lines.head should include("Median (µs)")
      lines(1) should include("floor")
      lines(1) should include("98.8")
      val pedal = lines.find(_.contains("pedal→tuning")).get
      pedal should include("The MTS Octave 1-byte tuner should change the tuning on a pedal")
      pedal should include("312.3")
      pedal should include("512.3")
      pedal should include("900.0")
    }

    "show a failed case's reason" in {
      // When
      val table = SummaryTable.render(sampleRun)

      // Then
      table should include("FAILED: Launch 1: timed iteration 3, step 1: Timed out")
    }
  }
}

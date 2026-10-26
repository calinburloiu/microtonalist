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

package org.calinburloiu.music.microtonalist.perf.env

import org.calinburloiu.music.microtonalist.perf.PerfSetupException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MachineProbeTest extends AnyWordSpec with Matchers {

  "MachineProbe" should {
    "probe a Mac with commands of macOS" in {
      // When / Then
      MachineProbe.forOs("Mac OS X", ProcessCommandRunner) shouldBe a[MacMachineProbe]
    }

    "fail for an OS it can't probe yet" in {
      // When
      val exception = the[PerfSetupException] thrownBy MachineProbe.forOs("Linux", ProcessCommandRunner)

      // Then
      exception.getMessage should include("Linux")
    }
  }
}

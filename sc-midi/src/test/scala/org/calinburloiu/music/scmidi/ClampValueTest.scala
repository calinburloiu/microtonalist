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

package org.calinburloiu.music.scmidi

import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

class ClampValueTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  "clampValue" should {
    "keep an Int within the bounds and bring one outside them to the nearest bound" in {
      // Given
      val cases = Table(
        ("value", "min", "max", "expected"),
        (5, 0, 127, 5),
        (0, 0, 127, 0),
        (127, 0, 127, 127),
        (-1, 0, 127, 0),
        (128, 0, 127, 127),
        (-9000, -8192, 8191, -8192),
        (9000, -8192, 8191, 8191)
      )

      forAll(cases) { (value, min, max, expected) =>
        // When / Then
        clampValue(value, min, max) shouldEqual expected
      }
    }

    "keep a Double within the bounds and bring one outside them to the nearest bound" in {
      // Given
      val cases = Table(
        ("value", "min", "max", "expected"),
        (0.25, 0.0, 1.0, 0.25),
        (0.0, 0.0, 1.0, 0.0),
        (1.0, 0.0, 1.0, 1.0),
        (-0.5, 0.0, 1.0, 0.0),
        (1.5, 0.0, 1.0, 1.0),
        (-250.0, -200.0, 200.0, -200.0)
      )

      forAll(cases) { (value, min, max, expected) =>
        // When / Then
        clampValue(value, min, max) shouldEqual expected
      }
    }
  }
}

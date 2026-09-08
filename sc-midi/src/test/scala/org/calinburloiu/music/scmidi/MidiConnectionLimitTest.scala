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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiConnectionLimitTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  behavior of "allowsConnections"

  it should "be true for an unlimited or positive limit and false for zero" in {
    // Given
    val cases = Table[MidiConnectionLimit, Boolean](
      ("limit", "expected"),
      (MidiConnectionLimit.Unlimited, true),
      (MidiConnectionLimit.Limited(0), false),
      (MidiConnectionLimit.Limited(1), true),
      (MidiConnectionLimit.Limited(8), true)
    )

    forAll(cases) { (limit, expected) =>
      // When / Then
      limit.allowsConnections shouldBe expected
    }
  }

  behavior of "toString"

  it should "print unlimited or the count" in {
    // Given
    val cases = Table[MidiConnectionLimit, String](
      ("limit", "expected"),
      (MidiConnectionLimit.Unlimited, "unlimited"),
      (MidiConnectionLimit.Limited(0), "0"),
      (MidiConnectionLimit.Limited(8), "8")
    )

    forAll(cases) { (limit, expected) =>
      // When / Then
      limit.toString shouldEqual expected
    }
  }
}

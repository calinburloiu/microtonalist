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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.calinburloiu.music.microtonalist.testbench.qa.SendMidiLines.{Forbidden, validate}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SendMidiLinesTest extends AnyWordSpec with Matchers {

  "SendMidiLines.validate" should {
    "accept channel messages, raw bytes and timestamps" in {
      // Given
      val lines = Seq("ch 1 cc 66 127", "ch 1 raw 90H 3CH 00H", "+00.500 ch 1 cc 66 0")

      // When / Then
      validate(lines) shouldBe Right(lines)
    }

    "accept no lines" in {
      // When / Then
      validate(Seq.empty) shouldBe Right(Seq.empty)
    }

    "refuse each forbidden command and long form" in {
      // When / Then
      for (name <- Forbidden) {
        withClue(name) {
          validate(Seq(s"$name x")).isLeft shouldBe true
        }
      }
    }

    "refuse a forbidden command in another case or after an allowed one" in { // Review Focus 4
      // When / Then
      validate(Seq("DEV IAC")).isLeft shouldBe true
      validate(Seq("ch 1 cc 66 127 dev IAC")).isLeft shouldBe true
      validate(Seq("ch 1\tVirtual x")).isLeft shouldBe true
    }

    "refuse a forbidden command in double quotes" in {
      // When / Then
      validate(Seq("ch 1 \"dev\" IAC")).isLeft shouldBe true
      validate(Seq("ch 1 x\"dev\"")).isLeft shouldBe true
    }

    "refuse a line holding a line break" in { // Review Focus 4
      // When / Then
      validate(Seq("ch 1 cc 66 127\ndev IAC")).isLeft shouldBe true
      validate(Seq("ch 1\rdev IAC")).isLeft shouldBe true
    }

    "refuse a line holding another control character" in {
      // When / Then
      validate(Seq("ch 1\u0000dev IAC")).isLeft shouldBe true
      validate(Seq("ch 1\u0085dev IAC")).isLeft shouldBe true
    }

    "refuse the whole batch, naming the bad line" in {
      // When
      val result = validate(Seq("ch 1 cc 66 127", "hex", "ch 1 cc 66 0"))

      // Then
      result.isLeft shouldBe true
      result.left.toOption.get should include("\"hex\"")
    }

    "name a line holding a line break with the break escaped" in {
      // When
      val result = validate(Seq("ch 1\ndev IAC"))

      // Then
      result.left.toOption.get should include("\"ch 1\\ndev IAC\"")
    }
  }
}

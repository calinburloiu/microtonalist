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

  /** The error for `line`, which the case expects to be refused. */
  private def error(line: String): String = validate(Seq(line)).left.getOrElse(fail(s"accepted \"$line\""))

  "SendMidiLines.validate" should {
    "accept channel messages, raw bytes and timestamps" in {
      // Given
      val lines = Seq("ch 1 cc 66 127", "ch 1 raw 90H 3CH 00H", "+00.500 ch 1 cc 66 0")

      // When / Then
      validate(lines) shouldBe Right(lines)
    }

    "accept system exclusive, long forms, note names, value/ranges and absolute timestamps" in {
      // Given
      val lines = Seq(
        "syx 7EH 7FH 06H 01H",
        "channel 2 control-change 1 2 note-on 60 100 note-off 60 0",
        "ch 1 on C#-1 100 on Db4 1M off c3 0",
        "pb -1/2 pb 8192",
        "08:10:17.056 cc 1M 7fh",
        "+00:00:01.060 clock 120 4",
        "  CC 1 2  ",
        "",
      )

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
          error(s"ch 1 $name") should include("forbidden")
        }
      }
    }

    "refuse a forbidden command in another case or after an allowed one" in {
      // When / Then
      error("DEV IAC") should include("forbidden")
      error("ch 1 cc 66 127 dev IAC") should include("forbidden")
      error("ch 1\tVirtual x") should include("forbidden")
    }

    "refuse a forbidden command in double quotes" in {
      // When / Then
      validate(Seq("ch 1 \"dev\" IAC")).isLeft shouldBe true
      validate(Seq("ch 1 x\"dev\"")).isLeft shouldBe true
    }

    "refuse a program file, which sendmidi would load and run" in {
      // When / Then
      error("ch 1 /abs/prog.txt") should include("\"/abs/prog.txt\"")
      error("~/prog.txt") should include("\"~/prog.txt\"")
      error("prog.txt") should include("\"prog.txt\"")
    }

    "refuse a parameter where a command or a timestamp must be, which sendmidi would load as a program file" in {
      // When / Then
      error("ch 1 cc 66 127 5") should include("program file")
      error("+00.500 5") should include("program file")
      error("panic C3") should include("program file")
      error("5") should include("program file")
      error("on 60 +00.010 100") should include("program file")
      error("clock 120 4 5") should include("program file")
    }

    "refuse a SendMIDI command or option that sends no MIDI, or that changes how later lines parse" in {
      // When / Then
      for (token <- Seq("list", "omc 4", "octave-middle-c 4", "nowait", "no-wait", "--", "-h", "--help", "--version")) {
        withClue(token) {
          error(s"ch 1 $token") should include("isn't an allowed")
        }
      }
    }

    "refuse a parameter of another shape" in {
      // When / Then
      error("cc -1 1") should include("\"-1\"")
      error("cc 66 12x") should include("\"12x\"")
      error("on H3 1") should include("\"H3\"")
      error("pb 1.5/2") should include("\"1.5/2\"")
      error("+0.500 cc 1 1") should include("\"+0.500\"")
    }

    "refuse a line holding a line break" in {
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

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

package org.calinburloiu.music.microtonalist.testbench.driver

import javafx.scene.input.KeyCode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class KeyChordTest extends AnyWordSpec with Matchers {

  "KeyChord.parse" should {
    "parse a key" in {
      // When / Then
      KeyChord.parse("DIGIT1", isMac = true) shouldBe Right(KeyChord(Nil, KeyCode.DIGIT1))
    }

    "parse the modifiers in order, ignoring case" in {
      // When / Then
      KeyChord.parse("shift+Alt+TAB", isMac = true) shouldBe
        Right(KeyChord(Seq(KeyCode.SHIFT, KeyCode.ALT), KeyCode.TAB))
    }

    "map Shortcut to Command on macOS and to Control elsewhere" in {
      // When / Then
      KeyChord.parse("Shortcut+N", isMac = true) shouldBe Right(KeyChord(Seq(KeyCode.COMMAND), KeyCode.N))
      KeyChord.parse("shortcut+n", isMac = false) shouldBe Right(KeyChord(Seq(KeyCode.CONTROL), KeyCode.N))
    }

    "reject an unknown key" in {
      // When / Then
      KeyChord.parse("Hyper+N", isMac = true) shouldBe Left("""unknown key "Hyper" in "Hyper+N"""")
    }

    "reject an empty key" in {
      // When / Then
      KeyChord.parse("Shift+", isMac = true) shouldBe Left("""unknown key "" in "Shift+"""")
    }
  }
}

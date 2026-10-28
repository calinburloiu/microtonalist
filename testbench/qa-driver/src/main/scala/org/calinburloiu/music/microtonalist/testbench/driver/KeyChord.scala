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

/** A key pressed while holding modifiers, which are pressed in order and released in reverse. */
final case class KeyChord(modifiers: Seq[KeyCode], key: KeyCode)

/** Parses key chords. */
object KeyChord {
  /**
   * Parses a key, or modifiers and a key joined by `+`, each a JavaFX `KeyCode` name or `Shortcut`, ignoring case, e.g.
   * `DIGIT1`, `Shortcut+N` or `shift+TAB`.
   *
   * @param isMac whether `Shortcut` means Command, as on macOS, rather than Control
   * @return the chord, or why the text names none
   */
  def parse(text: String, isMac: Boolean): Either[String, KeyChord] = {
    val names = text.split("\\+", -1).toSeq
    val codes = names.flatMap { name => keyCode(name, isMac) }
    names.find { name => keyCode(name, isMac).isEmpty } match {
      case Some(unknown) => Left(s"""unknown key "$unknown" in "$text"""")
      case None => Right(KeyChord(codes.init, codes.last))
    }
  }

  private def keyCode(name: String, isMac: Boolean): Option[KeyCode] = {
    if (name.equalsIgnoreCase("Shortcut")) {
      Some(if (isMac) KeyCode.COMMAND else KeyCode.CONTROL)
    } else {
      KeyCode.values.find(_.name.equalsIgnoreCase(name))
    }
  }
}

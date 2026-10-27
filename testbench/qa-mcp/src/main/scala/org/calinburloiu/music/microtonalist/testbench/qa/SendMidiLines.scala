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

/** Checks the SendMIDI command lines that an agent sends to a device's `sendmidi`. */
object SendMidiLines {
  /**
   * The SendMIDI commands, short and long forms, that a line can't use, since they could reach another MIDI device,
   * read a file, or change how `sendmidi` parses the following numbers.
   */
  val Forbidden: Set[String] = Set("dev", "device", "virt", "virtual", "file", "syf", "system-exclusive-file", "mpp",
    "mpe-profile", "hex", "hexadecimal", "dec", "decimal")

  /**
   * Splits a line into the tokens compared with [[Forbidden]]. Splitting at double quotes too refuses a forbidden
   * command that `sendmidi` would read out of a quoted parameter.
   */
  private val TokenSeparator: String = "[\\s\"]+"

  /**
   * Checks each line, refusing the whole batch if one line is bad.
   *
   * A line is bad if one of its tokens, split at whitespace and double quotes, is a [[Forbidden]] command, ignoring
   * case, or if it holds a control character other than a tab, such as a line break, which would start another line.
   *
   * @return the lines; or an error naming the first bad line
   */
  def validate(lines: Seq[String]): Either[String, Seq[String]] = {
    lines.iterator.flatMap(problem).nextOption().toLeft(lines)
  }

  private def problem(line: String): Option[String] = {
    if (line.exists(isControl)) {
      Some(s"The line ${quote(line)} holds a line break or another control character")
    } else {
      line.split(TokenSeparator).find(token => Forbidden.exists(_.equalsIgnoreCase(token))).map { token =>
        s"The line ${quote(line)} uses \"$token\", one of the forbidden commands ${Forbidden.toSeq.sorted.mkString(", ")}"
      }
    }
  }

  private def isControl(char: Char): Boolean = Character.isISOControl(char) && char != '\t'

  /** Quotes a line, escaping its control characters, so that an error shows it on one line. */
  private def quote(line: String): String = {
    val escaped = line.flatMap {
      case '\n' => "\\n"
      case '\r' => "\\r"
      case char if isControl(char) => f"\\u${char.toInt}%04x"
      case char => char.toString
    }
    s"\"$escaped\""
  }
}

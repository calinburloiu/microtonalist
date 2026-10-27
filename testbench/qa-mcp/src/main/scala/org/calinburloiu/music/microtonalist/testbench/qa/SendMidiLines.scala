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

import java.util.Locale
import scala.util.boundary
import scala.util.matching.Regex

/** Checks the SendMIDI command lines that an agent sends to a device's `sendmidi`. */
object SendMidiLines {
  /**
   * The SendMIDI commands, short and long forms, that a line can't use, since they could reach another MIDI device,
   * read a file, or change how `sendmidi` parses the following numbers.
   */
  val Forbidden: Set[String] = Set("dev", "device", "virt", "virtual", "file", "syf", "system-exclusive-file", "mpp",
    "mpe-profile", "hex", "hexadecimal", "dec", "decimal")

  /** The parameter count of a command that takes any number of them. */
  private val Variadic: Int = Int.MaxValue

  /**
   * The commands a line can use, short and long forms, lower-case, each with the most parameters it takes.
   *
   * They're SendMIDI 1.4.4's `sendmidi --help` commands that send MIDI or wait, but [[Forbidden]], `omc` and `nowait`,
   * which change how `sendmidi` handles the following lines, and `list`. `clock` takes at most `bpm (beats)` here,
   * although `sendmidi` takes more; `midi-clock` is `mc`'s long form, as `sendmidi` runs it.
   */
  private val Commands: Map[String, Int] = {
    val shortForms = Seq("panic" -> 0, "ch" -> 1, "on" -> 2, "off" -> 2, "pp" -> 2, "cc" -> 2, "cc14" -> 2, "pc" -> 1,
      "cp" -> 1, "pb" -> 1, "rpn" -> 2, "nrpn" -> 2, "clock" -> 2, "mc" -> 0, "start" -> 0, "stop" -> 0, "cont" -> 0,
      "as" -> 0, "rst" -> 0, "syx" -> Variadic, "tc" -> 2, "spp" -> 1, "ss" -> 1, "tun" -> 0, "mpe" -> 2,
      "mpetest" -> 0, "raw" -> Variadic)
    val longForms = Map("channel" -> "ch", "note-on" -> "on", "note-off" -> "off", "poly-pressure" -> "pp",
      "control-change" -> "cc", "control-change-14" -> "cc14", "program-change" -> "pc", "channel-pressure" -> "cp",
      "pitch-bend" -> "pb", "midi-clock" -> "mc", "continue" -> "cont", "active-sensing" -> "as", "reset" -> "rst",
      "system-exclusive" -> "syx", "time-code" -> "tc", "song-position" -> "spp", "song-select" -> "ss",
      "tune-request" -> "tun", "mpe-test" -> "mpetest", "raw-midi" -> "raw")
    val shortFormMap = shortForms.toMap
    shortFormMap ++ longForms.view.mapValues(shortFormMap).toMap
  }

  /**
   * The parameter shapes of SendMIDI 1.4.4's `sendmidi --help`: a decimal number, also with an `M` suffix; a
   * hexadecimal one with an `H` suffix; a pitch bend's `value/range`; and a note name, e.g. `C#-1` or `Db4`.
   */
  private val Parameter: Regex = "(?i)[0-9]+m?|[0-9a-f]+h|-?[0-9]+/[0-9]+|[a-g][#b]?-?[0-9]".r

  /** The timestamps of SendMIDI 1.4.4's `sendmidi --help`: `HH:MM:SS.MIL`, `+HH:MM:SS.MIL` and `+SS.MIL`. */
  private val Timestamp: Regex = "\\+?[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}|\\+[0-9]{2}\\.[0-9]{3}".r

  /**
   * Checks each line, refusing the whole batch if one line is bad.
   *
   * A line is bad if it holds a control character other than a tab, such as a line break, which would start another
   * line. It's also bad unless each of its whitespace-separated tokens is, ignoring case, an allowed SendMIDI command;
   * a timestamp between commands; or a parameter of the command before, of an allowed shape, within the parameters that
   * command takes. `sendmidi` loads any other token where a command must be as a program file, which could run any
   * command.
   *
   * @return the lines; or an error naming the first bad line and its bad token
   */
  def validate(lines: Seq[String]): Either[String, Seq[String]] = {
    lines.iterator.flatMap(problem).nextOption().toLeft(lines)
  }

  private def problem(line: String): Option[String] = boundary {
    if (line.exists(isControl)) {
      boundary.break(Some(s"The line ${quote(line)} holds a line break or another control character"))
    }

    // How many more parameters the command before takes.
    var parametersLeft = 0
    for (token <- line.split("\\s+") if token.nonEmpty) {
      val command = Commands.get(token.toLowerCase(Locale.ROOT))
      if (Forbidden.exists(_.equalsIgnoreCase(token))) {
        boundary.break(Some(s"The line ${quote(line)} uses \"$token\", one of the forbidden commands " +
          Forbidden.toSeq.sorted.mkString(", ")))
      } else if (command.isDefined) {
        parametersLeft = command.get
      } else if (Timestamp.matches(token)) {
        parametersLeft = 0
      } else if (Parameter.matches(token) && parametersLeft > 0) {
        parametersLeft -= 1
      } else if (Parameter.matches(token)) {
        boundary.break(Some(s"The line ${quote(line)} has \"$token\" where a command or a timestamp must be, " +
          "which sendmidi would load as a program file"))
      } else {
        boundary.break(Some(s"The line ${quote(line)} has \"$token\", which isn't an allowed SendMIDI command, " +
          "timestamp or parameter"))
      }
    }
    None
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

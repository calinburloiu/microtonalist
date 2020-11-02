/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

/**
 * Text User Interface experiment performed in order to gather new requirements for the domain model for buiding a
 * Graphical User Interface.
 */
class TuiExperiment {

  def run(): Unit = {
    println(s"${ConsoleColors.Reset}${ConsoleColors.Cyan}cyan ${ConsoleColors.Reset}${ConsoleColors.BrightCyan}cyan" +
      s"${ConsoleColors.Reset} ${ConsoleColors.BrightMagenta + ConsoleColors.Reversed}magenta" +
      s"${ConsoleColors.Reset} ${ConsoleColors.BrightYellow}yellow")

    println("")
  }
}

object ConsoleColors {
  val Reset = "\u001b[0m"

  // Regular Colors
  val Black = "\u001b[0;30m"
  val Red = "\u001b[0;31m"
  val Green = "\u001b[0;32m"
  val Yellow = "\u001b[0;33m"
  val Blue = "\u001b[0;34m"
  val Magenta = "\u001b[0;35m"
  val Cyan = "\u001b[0;36m"
  val White = "\u001b[0;37m"

  // Bright colors
  val BrightBlack = "\u001b[0;90m"
  val BrightRed = "\u001b[0;91m"
  val BrightGreen = "\u001b[0;92m"
  val BrightYellow = "\u001b[0;93m"
  val BrightBlue = "\u001b[0;94m"
  val BrightMagenta = "\u001b[0;95m"
  val BrightCyan = "\u001b[0;96m"
  val BrightWhite = "\u001b[0;97m"

  val Reversed = "\u001b[7m"
}

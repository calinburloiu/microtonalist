/*
 * Copyright 2025 Calin-Andrei Burloiu
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

/**
 * The direction a MIDI endpoint, like a device, works in: whether it supports input and/or output.
 *
 * A device may work in both directions, or in neither, so all four values describe one.
 *
 * @param name     Descriptive name of the direction.
 * @param isInput  Indicates whether the endpoint supports MIDI input.
 * @param isOutput Indicates whether the endpoint supports MIDI output.
 */
enum MidiDirection(val name: String, isInput: Boolean, isOutput: Boolean) {
  override def toString: String = name

  case None extends MidiDirection("none", isInput = false, isOutput = false)
  case Input extends MidiDirection("input", isInput = true, isOutput = false)
  case Output extends MidiDirection("output", isInput = false, isOutput = true)
  case InputOutput extends MidiDirection("input/output", isInput = true, isOutput = true)
}

object MidiDirection {
  def apply(isInput: Boolean, isOutput: Boolean): MidiDirection = (isInput, isOutput) match {
    case (true, false) => Input
    case (false, true) => Output
    case (true, true) => InputOutput
    case _ => None
  }
}

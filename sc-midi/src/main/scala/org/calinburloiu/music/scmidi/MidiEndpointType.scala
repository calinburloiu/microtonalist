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

enum MidiEndpointType(val name: String, isInput: Boolean, isOutput: Boolean) {
  override def toString: String = name

  case None extends MidiEndpointType("none", isInput = false, isOutput = false)
  case Input extends MidiEndpointType("input", isInput = true, isOutput = false)
  case Output extends MidiEndpointType("output", isInput = false, isOutput = true)
  case InputOutput extends MidiEndpointType("input/output", isInput = true, isOutput = true)
}

object MidiEndpointType {
  def apply(isInput: Boolean, isOutput: Boolean): MidiEndpointType = (isInput, isOutput) match {
    case (true, false) => Input
    case (false, true) => Output
    case (true, true) => InputOutput
    case _ => None
  }
}

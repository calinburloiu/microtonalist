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

package org.calinburloiu.music.scmidi.message

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MidiMessage, ShortMessage}

class PitchBendScMidiMessageTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {
  private val channel = 3
  private val table = Table[MidiMessage, PitchBendScMidiMessage](
    ("Java MidiMessage", "PitchBendScMidiMessage"),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x00), PitchBendScMidiMessage(channel, -8192)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x38), PitchBendScMidiMessage(channel, -1024)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x40), PitchBendScMidiMessage(channel, 0)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x41), PitchBendScMidiMessage(channel, 128)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x1A, 0x48), PitchBendScMidiMessage(channel, 1050)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x7F, 0x7F), PitchBendScMidiMessage(channel, 8191))
  )

  behavior of "PitchBendScMidiMessage"

  it should "be extracted from a MidiMessage" in {
    // When / Then
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      inside(javaMidiMessage) {
        case PitchBendScMidiMessage(`channel`, value) => scalaPitchBendMessage.value should equal(value)
      }
    }
  }

  it should "be converted to a MidiMessage" in {
    // When / Then
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      scalaPitchBendMessage.toJavaMidiMessage.getMessage should equal(javaMidiMessage.getMessage)
    }
  }

  it should "be created from a Java MidiMessage" in {
    // When / Then
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      PitchBendScMidiMessage.fromJavaMessage(javaMidiMessage) should equal(Some(scalaPitchBendMessage))
    }
  }
}

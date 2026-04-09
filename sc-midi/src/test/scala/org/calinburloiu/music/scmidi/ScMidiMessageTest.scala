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

package org.calinburloiu.music.scmidi

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MidiMessage, ShortMessage}

class ScMidiMessageTest extends AnyFlatSpec with Matchers {
  behavior of "ScMidiMessage"

  it should "create correct ScMidiMessage from Java message" in {
    val channel = 1
    val noteNumber = 60
    val velocity = 100
    val ccNumber = 7
    val ccValue = 120
    val pressureValue = 80
    val pitchBendValue = 0

    val noteOn = new ShortMessage(ShortMessage.NOTE_ON, channel, noteNumber, velocity)
    val noteOff = new ShortMessage(ShortMessage.NOTE_OFF, channel, noteNumber, velocity)
    val cc = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, ccNumber, ccValue)
    val pitchBend = new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x40)
    val channelPressure = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, pressureValue, 0)
    val polyPressure = new ShortMessage(ShortMessage.POLY_PRESSURE, channel, noteNumber, pressureValue)
    val programChange = new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, 1, 0)

    ScMidiMessage.fromJavaMessage(noteOn) should equal(ScNoteOnMidiMessage(channel, noteNumber, velocity))
    ScMidiMessage.fromJavaMessage(noteOff) should equal(ScNoteOffMidiMessage(channel, noteNumber, velocity))
    ScMidiMessage.fromJavaMessage(cc) should equal(ScCcMidiMessage(channel, ccNumber, ccValue))
    ScMidiMessage.fromJavaMessage(pitchBend) should equal(ScPitchBendMidiMessage(channel, pitchBendValue))
    ScMidiMessage.fromJavaMessage(channelPressure) should equal(ScChannelPressureMidiMessage(channel, pressureValue))
    ScMidiMessage.fromJavaMessage(polyPressure) should equal(ScPolyPressureMidiMessage(channel, noteNumber,
      pressureValue))
    ScMidiMessage.fromJavaMessage(programChange) should equal(ScProgramChangeMidiMessage(channel, 1))
  }

  it should "throw IllegalArgumentException for null input" in {
    an[IllegalArgumentException] should be thrownBy ScMidiMessage.fromJavaMessage(null)
  }
}

class ScNoteMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 5
  private val noteNumber: Int = 62
  private val velocity = 102
  private val javaNoteOnMessage: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, noteNumber, velocity)
  private val javaNoteOffMessage: MidiMessage = new ShortMessage(ShortMessage.NOTE_OFF, channel, noteNumber, velocity)

  behavior of "ScNoteOnMidiMessage"

  it should "be extracted from a MidiMessage" in {
    inside(javaNoteOnMessage) {
      case ScNoteOnMidiMessage(`channel`, midiNote, `velocity`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "be converted to a MidiMessage" in {
    ScNoteOnMidiMessage(channel, noteNumber, velocity).javaMessage.getMessage should equal(javaNoteOnMessage
      .getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScNoteOnMidiMessage.fromJavaMessage(javaNoteOnMessage) should equal(Some(ScNoteOnMidiMessage(channel, noteNumber,
      velocity)))
  }

  behavior of "ScNoteOffMidiMessage"

  it should "be extracted from a MidiMessage" in {
    inside(javaNoteOffMessage) {
      case ScNoteOffMidiMessage(`channel`, midiNote, `velocity`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "be converted to a MidiMessage" in {
    ScNoteOffMidiMessage(channel, noteNumber, velocity).javaMessage.getMessage should equal(javaNoteOffMessage
      .getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScNoteOffMidiMessage.fromJavaMessage(javaNoteOffMessage) should equal(Some(ScNoteOffMidiMessage(channel,
      noteNumber, velocity)))
  }
}

class ScPitchBendMidiMessageTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {
  private val channel = 3
  private val table = Table[MidiMessage, ScPitchBendMidiMessage](
    ("Java MidiMessage", "ScPitchBendMidiMessage"),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x00), ScPitchBendMidiMessage(channel, -8192)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x38), ScPitchBendMidiMessage(channel, -1024)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x40), ScPitchBendMidiMessage(channel, 0)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x41), ScPitchBendMidiMessage(channel, 128)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x1A, 0x48), ScPitchBendMidiMessage(channel, 1050)),
    (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x7F, 0x7F), ScPitchBendMidiMessage(channel, 8191))
  )

  behavior of "ScPitchBendMidiMessage"

  it should "be extracted from a MidiMessage" in {
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      inside(javaMidiMessage) {
        case ScPitchBendMidiMessage(`channel`, value) => scalaPitchBendMessage.value should equal(value)
      }
    }
  }

  it should "be converted to a MidiMessage" in {
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      scalaPitchBendMessage.javaMessage.getMessage should equal(javaMidiMessage.getMessage)
    }
  }

  it should "be created from a Java MidiMessage" in {
    forAll(table) { (javaMidiMessage, scalaPitchBendMessage) =>
      ScPitchBendMidiMessage.fromJavaMessage(javaMidiMessage) should equal(Some(scalaPitchBendMessage))
    }
  }
}

class ScCcMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 15
  private val number = 67
  private val pitchBendValue = 64
  private val javaMidiMessage: MidiMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number,
    pitchBendValue)

  behavior of "ScCcMidiMessage"

  it should "be extracted from a MidiMessage" in {
    inside(javaMidiMessage) { case ScCcMidiMessage(`channel`, `number`, `pitchBendValue`) => }
  }

  it should "be converted to a MidiMessage" in {
    ScCcMidiMessage(channel, number, pitchBendValue).javaMessage.getMessage should equal(javaMidiMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScCcMidiMessage.fromJavaMessage(javaMidiMessage) should equal(Some(ScCcMidiMessage(channel, number,
      pitchBendValue)))
  }
}

class ScChannelPressureMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 3
  private val pressure = 100
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.CHANNEL_PRESSURE, channel, pressure, 0)

  behavior of "ScChannelPressureMidiMessage"

  it should "create correct Java MIDI message" in {
    val msg = ScChannelPressureMidiMessage(channel, pressure)
    msg.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScChannelPressureMidiMessage.fromJavaMessage(javaMessage) should equal(Some(ScChannelPressureMidiMessage(channel,
      pressure)))
  }

  it should "be extracted from a valid Channel Pressure message" in {
    inside(javaMessage) {
      case ScChannelPressureMidiMessage(`channel`, `pressure`) => succeed
    }
  }

  it should "return None for non-Channel-Pressure messages" in {
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, 60, 100)
    ScChannelPressureMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channel" in {
    an[IllegalArgumentException] should be thrownBy ScChannelPressureMidiMessage(16, pressure)
  }

  it should "reject invalid value" in {
    an[IllegalArgumentException] should be thrownBy ScChannelPressureMidiMessage(channel, 128)
    an[IllegalArgumentException] should be thrownBy ScChannelPressureMidiMessage(channel, -1)
  }
}

class ScProgramChangeMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 2
  private val program = 42
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)

  behavior of "ScProgramChangeMidiMessage"

  it should "create correct Java MIDI message" in {
    val msg = ScProgramChangeMidiMessage(channel, program)
    msg.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScProgramChangeMidiMessage.fromJavaMessage(javaMessage) should equal(Some(ScProgramChangeMidiMessage(channel,
      program)))
  }

  it should "be extracted from a valid Program Change message" in {
    inside(javaMessage) {
      case ScProgramChangeMidiMessage(`channel`, `program`) => succeed
    }
  }

  it should "return None for non-Program-Change messages" in {
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, 60, 100)
    ScProgramChangeMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channel" in {
    an[IllegalArgumentException] should be thrownBy ScProgramChangeMidiMessage(16, program)
  }

  it should "reject invalid program" in {
    an[IllegalArgumentException] should be thrownBy ScProgramChangeMidiMessage(channel, 128)
    an[IllegalArgumentException] should be thrownBy ScProgramChangeMidiMessage(channel, -1)
  }
}

class ScPolyPressureMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 5
  private val noteNumber: Int = 64
  private val pressure = 80
  private val javaMessage: MidiMessage = new ShortMessage(ShortMessage.POLY_PRESSURE, channel, noteNumber, pressure)

  behavior of "ScPolyPressureMidiMessage"

  it should "create correct Java MIDI message" in {
    val msg = ScPolyPressureMidiMessage(channel, noteNumber, pressure)
    msg.javaMessage.getMessage should equal(javaMessage.getMessage)
  }

  it should "be created from a Java MidiMessage" in {
    ScPolyPressureMidiMessage.fromJavaMessage(javaMessage) should equal(Some(ScPolyPressureMidiMessage(channel,
      noteNumber, pressure)))
  }

  it should "be extracted from a valid Poly Pressure message" in {
    inside(javaMessage) {
      case ScPolyPressureMidiMessage(`channel`, midiNote, `pressure`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "return None for non-Poly-Pressure messages" in {
    val noteOn: MidiMessage = new ShortMessage(ShortMessage.NOTE_ON, channel, 60, 100)
    ScPolyPressureMidiMessage.unapply(noteOn) shouldBe None
  }

  it should "reject invalid channel" in {
    an[IllegalArgumentException] should be thrownBy ScPolyPressureMidiMessage(16, noteNumber, pressure)
  }

  it should "reject invalid value" in {
    an[IllegalArgumentException] should be thrownBy ScPolyPressureMidiMessage(channel, noteNumber, 128)
    an[IllegalArgumentException] should be thrownBy ScPolyPressureMidiMessage(channel, noteNumber, -1)
  }
}

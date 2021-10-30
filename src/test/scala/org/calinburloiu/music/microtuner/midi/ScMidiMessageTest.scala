package org.calinburloiu.music.microtuner.midi

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MidiMessage, ShortMessage}

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
    ScNoteOnMidiMessage(channel, noteNumber, velocity).javaMidiMessage.getMessage should equal(javaNoteOnMessage.getMessage)
  }

  behavior of "ScNoteOffMidiMessage"

  it should "be extracted from a MidiMessage" in {
    inside(javaNoteOffMessage) {
      case ScNoteOffMidiMessage(`channel`, midiNote, `velocity`) => midiNote.number should equal(noteNumber)
    }
  }

  it should "be converted to a MidiMessage" in {
    ScNoteOffMidiMessage(channel, noteNumber, velocity).javaMidiMessage.getMessage should equal(javaNoteOffMessage.getMessage)
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
      scalaPitchBendMessage.javaMidiMessage.getMessage should equal(javaMidiMessage.getMessage)
    }
  }
}

class ScCcMidiMessageTest extends AnyFlatSpec with Matchers {
  private val channel = 15
  private val number = 67
  private val pitchBendValue = 64
  private val javaMidiMessage: MidiMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, pitchBendValue)

  behavior of "ScCcMidiMessage"

  it should "be extracted from a MidiMessage" in {
    inside(javaMidiMessage) { case ScCcMidiMessage(`channel`, `number`, `pitchBendValue`) => }
  }

  it should "be converted to a MidiMessage" in {
    ScCcMidiMessage(channel, number, pitchBendValue).javaMidiMessage.getMessage should equal(javaMidiMessage.getMessage)
  }
}

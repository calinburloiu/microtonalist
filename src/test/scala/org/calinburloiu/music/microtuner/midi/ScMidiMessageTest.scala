package org.calinburloiu.music.microtuner.midi

import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MidiMessage, ShortMessage}

class ScMidiMessageTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {
  val channel = 3

  {
    behavior of "ScPitchBendMidiMessage"

    val table = Table[MidiMessage, ScPitchBendMidiMessage](
      ("Java MidiMessage", "ScPitchBendMidiMessage"),
      (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x00), ScPitchBendMidiMessage(channel, -8192)),
      (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x32), ScPitchBendMidiMessage(channel, -1024)),
      (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x40), ScPitchBendMidiMessage(channel, 0)),
      (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x00, 0x41), ScPitchBendMidiMessage(channel, 128)),
      (new ShortMessage(ShortMessage.PITCH_BEND, channel, 0x7F, 0x7F), ScPitchBendMidiMessage(channel, 8191))
    )

    it should "be created from a MidiMessage" in {
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
  {
    behavior of "ScCcMidiMessage"

    val number = 67
    val value = 64
    val midiMessage: MidiMessage = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, number, value)

    it should "be created from a MidiMessage" in {
      inside(midiMessage) { case ScCcMidiMessage(`channel`, `number`, `value`) => }
    }

    it should "be converted to a MidiMessage" in {
      ScCcMidiMessage(channel, number, value).javaMidiMessage.getMessage should equal(midiMessage.getMessage)
    }
  }
}

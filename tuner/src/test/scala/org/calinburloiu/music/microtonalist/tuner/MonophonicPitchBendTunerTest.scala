/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.scmidi._
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

class MonophonicPitchBendTunerTest extends AnyFlatSpec with Matchers with Inside {
  private val inputChannel = 2
  private val outputChannel = 3
  private val pitchBendSensitivity = PitchBendSensitivity(1)

  //@formatter:off
  private val customTuning = OctaveTuning(
    "major-ish in 72-EDO",
    0.0,    // C
    16.67,  // Db (~16/15 from C)
    0.0,    // D
    -33.33, // D# (~7/6 from C
    -16.67, // E  (~5/4 from C)
    0.0,    // F
    -16.67, // F# (~7/5 from C)
    0.0,    // G
    16.67,  // Ab
    -16.67, // A  (~5/3 from C)
    0.0,    // Bb
    -16.67  // B
  )
  //@formatter:on
  private val customTuning2 = OctaveTuning("custom2", -45.0, -34.0, -23.0, -12.0, -1, 2, 13, 24, 35, 46, 17, 34)

  val Seq(noteC4, noteDFlat4, noteD4, noteDSharp4, noteE4, noteF4, noteFSharp4, noteG4,
  noteAb4, noteA4, noteBb4, noteB4) = MidiNote.C4 until MidiNote.C5

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  private trait Fixture {
    val tuner: MonophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel, pitchBendSensitivity)

    val output: mutable.Buffer[MidiMessage] = mutable.Buffer.empty

    def shortMessageOutput: Seq[ShortMessage] = output.toSeq.collect {
      case shortMessage: ShortMessage => shortMessage
    }

    def pitchBendOutput: Seq[ScPitchBendMidiMessage] = output.toSeq.collect {
      case ScPitchBendMidiMessage(channel, value) => ScPitchBendMidiMessage(channel, value)
    }
  }

  private def sendNote(tuner: Tuner, note: MidiNote, channel: Int = inputChannel): Seq[MidiMessage] = {
    Seq(
      tuner.process(ScNoteOnMidiMessage(channel, note).javaMidiMessage),
      tuner.process(ScNoteOffMidiMessage(channel, note).javaMidiMessage)
    ).flatten
  }

  private def filterNotes(shortMessages: Seq[ShortMessage]): Seq[ShortMessage] = {
    shortMessages.filter { m =>
      m.getCommand == ShortMessage.NOTE_ON || m.getCommand == ShortMessage.NOTE_OFF
    }
  }

  private def collectCcMessages(midiMessages: Seq[MidiMessage]): Seq[(Int, Int)] = {
    midiMessages.collect {
      case ScCcMidiMessage(channel, number, value) =>
        channel should equal(outputChannel)
        (number, value)
    }
  }

  behavior of "MonophonicPitchBendTuner on initialization"

  it should "configure the output device" in {
    val customPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(3, 37)
    val tuner: MonophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel, customPitchBendSensitivity)

    val output = tuner.init
    output should not be empty

    val ccMessages: Seq[(Int, Int)] = collectCcMessages(output)
    ccMessages should contain inOrderOnly(
      (ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      (ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      (ScCcMidiMessage.DataEntryMsb, customPitchBendSensitivity.semitones),
      (ScCcMidiMessage.DataEntryLsb, customPitchBendSensitivity.cents),
      (ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      (ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    )
  }

  behavior of "MonophonicPitchBendTuner after initialization"

  it should "tune all notes in 12-EDO by not sending any pitch bend" in new Fixture {
    for (note <- MidiNote.C4 to MidiNote.C5) {
      output ++= sendNote(tuner, note)
    }

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend when no note is on even if the tuning of C is changed" in new Fixture {
    // Note: C (MIDI note 0) is the default last note after initialization.
    // Internally the pitch bend value changes for consistency, but it is not sent.
    output ++= tuner.tune(customTuning2)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner after a new tuning is set"

  it should "not send pitch bend when the last note is replayed and its tuning did not change" in new Fixture {
    // Note: The initial last node is C (MIDI note 0).
    output ++= tuner.tune(customTuning)

    // Send some Cs
    output ++= sendNote(tuner, MidiNote(PitchClass.C, 4))
    output ++= sendNote(tuner, MidiNote(PitchClass.C, 3))
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, MidiNote(PitchClass.C, 6)).javaMidiMessage)
    // Send on of the "note off" as a note on with velocity 0
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, MidiNote(PitchClass.C, 6), 0).javaMidiMessage)

    pitchBendOutput shouldBe empty
  }

  it should "send pitch bend when playing a note with a different tuning than the previous one" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= sendNote(tuner, noteE4)
    output ++= sendNote(tuner, noteDSharp4)
    output ++= sendNote(tuner, noteDFlat4)

    output should have size 9

    val result: Seq[ScPitchBendMidiMessage] = pitchBendOutput
    result should have size 3
    result.map(_.value) should equal(Seq(
      Math.round(-16.67 / 100 * 8192),
      Math.round(-33.33 / 100 * 8192),
      Math.round(16.67 / 100 * 8191)
    ))
  }

  it should "not send pitch bend for consecutive notes with the same tuning" in new Fixture {
    output ++= tuner.tune(customTuning)
    output ++= sendNote(tuner, noteE4)
    output.clear()

    output ++= sendNote(tuner, noteA4)
    output ++= sendNote(tuner, noteB4)

    output should have size 4
    pitchBendOutput shouldBe empty
  }

  it should "always output messages to the same configured channel, " +
    "regardless of the channel on which they were received" in new Fixture {
    // Use a microtonal tuning such that pitch bend messages are also send
    output ++= tuner.tune(customTuning2)

    for (note <- MidiNote.C4 to MidiNote.C5; channel = note % 12) {
      output ++= sendNote(tuner, note, channel)
    }

    output should not be empty
    pitchBendOutput should not be empty
    shortMessageOutput.map(_.getChannel).forall(_ == outputChannel) should be(true)
  }

  behavior of "MonophonicPitchBendTuner when the tuning is changed"

  it should "not send pitch bend if a note is on and its tuning does not change" in new Fixture {
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output.clear()

    output ++= tuner.tune(customTuning)

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on does not change" in new Fixture {
    output ++= sendNote(tuner, noteC4)
    output.clear()

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  it should "send pitch bend if a note is on and its tuning changes" in new Fixture {
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output.clear()

    output ++= tuner.tune(customTuning2)

    output should have size 1
    inside(output.head) {
      case ScPitchBendMidiMessage(`outputChannel`, value) => value should equal(Math.round(-45.0 / 100 * 8192))
    }
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on changes" in new Fixture {
    // Note: Internally the pitch bend value changes for consistency, but it is not sent
    output ++= sendNote(tuner, noteE4)
    output.clear()

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner when multiple notes are on"

  it should "play monophonically even if no note off messages are sent" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteG4, 24).javaMidiMessage)
    // The next autogenerated note-off messages with use the velocity below (last velocity)
    val lastNoteOffVelocity: Int = 72
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteG4, lastNoteOffVelocity).javaMidiMessage)
    output.clear()

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4, 48).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteDSharp4, 64).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4, 96).javaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case ScNoteOnMidiMessage(_, note, 48) => note.number shouldEqual noteC4 }
    inside(outputNotes(1)) { case ScNoteOffMidiMessage(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteC4
    }
    inside(outputNotes(2)) { case ScNoteOnMidiMessage(_, note, 64) => note.number shouldEqual noteDSharp4 }
    inside(outputNotes(3)) { case ScNoteOffMidiMessage(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteDSharp4
    }
    inside(outputNotes(4)) { case ScNoteOnMidiMessage(_, note, 96) => note.number shouldEqual noteE4 }
  }

  it should "always revert to the last note played while releasing simultaneous notes one by one" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4, 20).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4, 40).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteG4, 60).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteBb4, 80).javaMidiMessage)

    output.clear()
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteBb4, 85).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteE4, 65).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteG4, 45).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteC4, 25).javaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    // Using the last note-on velocity sent, 80, for the auto-generated note-on messages
    inside(outputNotes.head) { case ScNoteOffMidiMessage(_, note, 85) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case ScNoteOnMidiMessage(_, note, 80) => note.number shouldEqual noteG4 }
    inside(outputNotes(2)) { case ScNoteOffMidiMessage(_, note, 45) => note.number shouldEqual noteG4 }
    inside(outputNotes(3)) { case ScNoteOnMidiMessage(_, note, 80) => note.number shouldEqual noteC4 }
    inside(outputNotes(4)) { case ScNoteOffMidiMessage(_, note, 25) => note.number shouldEqual noteC4 }
  }

  it should "tune reverted notes when holding a non-microtonal note while playing a microtonal one and lifting it" in
    new Fixture {
      output ++= tuner.tune(customTuning)
      output.clear()

      output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteG4).javaMidiMessage)
      output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteAb4).javaMidiMessage)
      output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteAb4).javaMidiMessage)

      output should have size 7
      inside(output.head) { case ScNoteOnMidiMessage(_, note, _) => note.number should equal(noteG4) }
      inside(output(1)) { case ScNoteOffMidiMessage(_, note, _) => note.number should equal(noteG4) }
      inside(output(2)) { case ScPitchBendMidiMessage(_, value) => value should be > 0 }
      inside(output(3)) { case ScNoteOnMidiMessage(_, note, _) => note.number should equal(noteAb4) }
      inside(output(4)) { case ScNoteOffMidiMessage(_, note, _) => note.number should equal(noteAb4) }
      inside(output(5)) { case ScPitchBendMidiMessage(_, value) => value should be(0) }
      inside(output(6)) { case ScNoteOnMidiMessage(_, note, _) => note.number should equal(noteG4) }
    }

  behavior of "MonophonicPitchBendTuner when it receives pitch bend messages"

  it should "only add the pitch bend received if the note played is not microtonal" in new Fixture {
    output ++= sendNote(tuner, noteC4)
    output.clear()

    output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, -2020).javaMidiMessage)

    output should have size 1
    pitchBendOutput should have size 1
    pitchBendOutput.head.value should equal(-2020)
  }

  it should "add the pitch bend received to the one computed for tuning a microtonal note" in new Fixture {
    output ++= tuner.tune(customTuning)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4, 96).javaMidiMessage)
    output.clear()

    val expressionPitchBendCents: Double = 50
    val expressionPitchBendValue: Int = ScPitchBendMidiMessage.convertCentsToValue(
      expressionPitchBendCents, pitchBendSensitivity)
    output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, expressionPitchBendValue).javaMidiMessage)

    output should have size 1
    pitchBendOutput should have size 1
    ScPitchBendMidiMessage.convertValueToCents(pitchBendOutput.head.value, pitchBendSensitivity) should equal(
      customTuning(4) + expressionPitchBendCents)
  }

  it should "add the pitch bend received to the one computed for tuning the last microtonal note (which is off)" in
    new Fixture {
      output ++= tuner.tune(customTuning)
      output ++= sendNote(tuner, noteE4)
      output.clear()

      val expressionPitchBendCents: Double = 50
      val expressionPitchBendValue: Int = ScPitchBendMidiMessage.convertCentsToValue(
        expressionPitchBendCents, pitchBendSensitivity)
      output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, expressionPitchBendValue).javaMidiMessage)

      output should have size 1
      pitchBendOutput should have size 1
      ScPitchBendMidiMessage.convertValueToCents(pitchBendOutput.head.value, pitchBendSensitivity) should equal(
        customTuning(4) + expressionPitchBendCents)
    }

  it should "continue adding the last pitch bend received to the one for notes of different tunings" in new Fixture {
    val expressionPitchBendCents: Double = -25
    val expressionPitchBendValue: Int = ScPitchBendMidiMessage.convertCentsToValue(
      expressionPitchBendCents, pitchBendSensitivity)
    output ++= tuner.tune(customTuning)
    output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, expressionPitchBendValue).javaMidiMessage)
    output.clear()

    output ++= sendNote(tuner, noteDSharp4)
    output ++= sendNote(tuner, noteC4)
    output ++= sendNote(tuner, noteE4)

    pitchBendOutput should have size 3

    val centResults: Seq[Double] = pitchBendOutput.map { message =>
      ScPitchBendMidiMessage.convertValueToCents(message.value, pitchBendSensitivity)
    }
    val expectedCentsResults: Seq[Double] = Seq(customTuning(3), customTuning(0), customTuning(4))
      .map(_ + expressionPitchBendCents)
    // Using a for because tolerance does not work with sequences
    (0 until 3).foreach { i => centResults(i) should equal(expectedCentsResults(i)) }
  }

  it should "clamp the pitch bend to min/max value if adding received pitch bend to tuning pitch bend " +
    "exceeds the bounds" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, ScPitchBendMidiMessage.MaxValue - 1).javaMidiMessage)
    output ++= sendNote(tuner, noteDFlat4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(ScPitchBendMidiMessage.MaxValue)

    output.clear()

    output ++= tuner.process(ScPitchBendMidiMessage(inputChannel, ScPitchBendMidiMessage.MinValue + 1).javaMidiMessage)
    output ++= sendNote(tuner, noteA4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(ScPitchBendMidiMessage.MinValue)
  }

  behavior of "MonophonicPitchBendTuner when non-tuning-related MIDI messages are received"

  it should "forward modulation CC message on the correct channel" in new Fixture {
    output ++= tuner.tune(customTuning2)
    output.clear()

    output ++= tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.Modulation, 34).javaMidiMessage)

    output should have size 1
    inside(output.head) {
      case ScCcMidiMessage(channel, number, 34) =>
        channel shouldEqual outputChannel
        number shouldEqual ScCcMidiMessage.Modulation
    }
  }

  behavior of "MonophonicPitchBendTuner when pedals are depressed"

  it should "interrupt sustain pedal in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SustainPedal, 64).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4).javaMidiMessage)

    shortMessageOutput should have size 9
    // Depress pedal
    inside(shortMessageOutput.head) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 64) => }
    // C on
    inside(shortMessageOutput(1)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 0) => }
    inside(shortMessageOutput(2)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 64) => }
    inside(shortMessageOutput(3)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // C off
    inside(shortMessageOutput(4)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(5)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 0) => }
    inside(shortMessageOutput(6)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 64) => }
    inside(shortMessageOutput(7)) { case ScPitchBendMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(8)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
  }

  it should "interrupt sustain pedal when holding notes in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4).javaMidiMessage)
    output ++= tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SustainPedal, 64).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteE4).javaMidiMessage)

    shortMessageOutput should have size 10
    // Play C
    inside(shortMessageOutput.head) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(1)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    inside(shortMessageOutput(2)) { case ScPitchBendMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(3)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
    // Depress pedal
    inside(shortMessageOutput(4)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 64) => }
    // Play C
    inside(shortMessageOutput(5)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
    inside(shortMessageOutput(6)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 0) => }
    inside(shortMessageOutput(7)) { case ScCcMidiMessage(_, ScCcMidiMessage.SustainPedal, 64) => }
    inside(shortMessageOutput(8)) { case ScPitchBendMidiMessage(_, value) => value shouldEqual 0 }
    inside(shortMessageOutput(9)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
  }

  it should "stop sostenuto pedal in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output ++= tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SostenutoPedal, 64).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteC4).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteE4).javaMidiMessage)

    shortMessageOutput should have size 6
    // C on
    inside(shortMessageOutput.head) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Depress pedal
    inside(shortMessageOutput(1)) { case ScCcMidiMessage(_, ScCcMidiMessage.SostenutoPedal, 64) => }
    // C off
    inside(shortMessageOutput(2)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(3)) { case ScCcMidiMessage(_, ScCcMidiMessage.SostenutoPedal, 0) => }
    inside(shortMessageOutput(4)) { case ScPitchBendMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(5)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
  }

  behavior of "MonophonicPitchBendTuner bugs"

  it should "work for note Bb on, note A on, note Bb off, note Bb on in custom tuning" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteBb4).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteA4).javaMidiMessage)
    output ++= tuner.process(ScNoteOffMidiMessage(inputChannel, noteBb4).javaMidiMessage)
    output ++= tuner.process(ScNoteOnMidiMessage(inputChannel, noteBb4).javaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(2)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(3)) { case ScNoteOffMidiMessage(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(4)) { case ScNoteOnMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
  }
}

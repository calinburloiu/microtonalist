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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.message.*
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

class MonophonicPitchBendTunerTest extends AnyFlatSpec with Matchers with Inside {
  private val inputChannel = 2
  private val outputChannel = 3
  private val semitonePitchBendSensitivity = PitchBendSensitivity(1)
  private val tonePitchBendSensitivity = PitchBendSensitivity(2)

  //@formatter:off
  private val customTuning = Tuning(
    "major-ish in 72-EDO",
    0.0,    // C
    16.67,  // Db (~16/15 from C)
    0.0,    // D
    -33.33, // D# (~7/6 from C)
    -16.67, // E  (~5/4 from C)
    0.0,    // F
    -16.67, // F# (~7/5 from C)
    0.0,    // G
    50.0,  // Ab (quarter-tone)
    -16.67, // A  (~5/3 from C)
    0.0,    // Bb
    -16.67  // B
  )
  //@formatter:on
  private val customTuning2 = Tuning("custom2", -45.0, -34.0, -23.0, -12.0, -1, 2, 13, 24, 35, 46, 17, 34)

  val Seq(noteC4, noteDFlat4, noteD4, noteDSharp4, noteE4, noteF4, noteFSharp4, noteG4,
    noteAb4, noteA4, noteBb4, noteB4) = MidiNote.C4.number until MidiNote.C5.number

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  private abstract class Fixture(val pitchBendSensitivity: PitchBendSensitivity = semitonePitchBendSensitivity) {
    val tuner: MonophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel, pitchBendSensitivity)

    val output: mutable.Buffer[MidiMessage] = mutable.Buffer.empty

    protected implicit val implPitchBendSensitivity: PitchBendSensitivity = pitchBendSensitivity

    def shortMessageOutput: Seq[ShortMessage] = output.toSeq.collect {
      case shortMessage: ShortMessage => shortMessage
    }

    def pitchBendOutput: Seq[PitchBendScMidiMessage] = output.toSeq.flatMap(
      PitchBendScMidiMessage.fromJavaMessage)

    def sendNote(note: MidiNote, channel: Int = inputChannel): Seq[MidiMessage] = {
      Seq(
        tuner.process(NoteOnScMidiMessage(channel, note).toJavaMidiMessage),
        tuner.process(NoteOffScMidiMessage(channel, note).toJavaMidiMessage)
      ).flatten
    }
  }

  private def filterNotes(shortMessages: Seq[ShortMessage]): Seq[ShortMessage] = {
    shortMessages.filter { m =>
      m.getCommand == ShortMessage.NOTE_ON || m.getCommand == ShortMessage.NOTE_OFF
    }
  }

  private def collectCcMessages(midiMessages: Seq[MidiMessage]): Seq[(Int, Int)] = {
    midiMessages.collect {
      case CcScMidiMessage(channel, number, value) =>
        channel should equal(outputChannel)
        (number, value)
    }
  }

  behavior of "MonophonicPitchBendTuner on initialization"

  it should "fail if constructed with out of bounds output channel" in {
    an[IllegalArgumentException] should be thrownBy MonophonicPitchBendTuner(-1, semitonePitchBendSensitivity)
    an[IllegalArgumentException] should be thrownBy MonophonicPitchBendTuner(16, semitonePitchBendSensitivity)
  }

  it should "configure the output device" in {
    val customPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(3, 37)
    val tuner: MonophonicPitchBendTuner = MonophonicPitchBendTuner(outputChannel, customPitchBendSensitivity)

    val output = tuner.reset()
    output should not be empty

    val ccMessages: Seq[(Int, Int)] = collectCcMessages(output)
    ccMessages should contain inOrderOnly(
      (ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      (ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      (ScMidiCc.DataEntryMsb, customPitchBendSensitivity.semitones),
      (ScMidiCc.DataEntryLsb, customPitchBendSensitivity.cents),
      (ScMidiCc.RpnLsb, ScMidiRpn.NullLsb),
      (ScMidiCc.RpnMsb, ScMidiRpn.NullMsb)
    )
  }

  behavior of "MonophonicPitchBendTuner after initialization"

  it should "tune all notes in 12-EDO by not sending any pitch bend" in new Fixture {
    for (note <- MidiNote.C4.number to MidiNote.C5.number) {
      output ++= sendNote(note)
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
    output ++= sendNote(MidiNote(PitchClass.C, 4))
    output ++= sendNote(MidiNote(PitchClass.C, 3))
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, MidiNote(PitchClass.C, 6)).toJavaMidiMessage)
    // Send on of the "note off" as a note on with velocity 0
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, MidiNote(PitchClass.C, 6), 0).toJavaMidiMessage)

    pitchBendOutput shouldBe empty
  }

  it should "send pitch bend when playing a note with a different tuning than the previous one" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= sendNote(noteE4)
    output ++= sendNote(noteDSharp4)
    output ++= sendNote(noteDFlat4)

    output should have size 9

    val result: Seq[PitchBendScMidiMessage] = pitchBendOutput
    result should have size 3

    val expectedTuningValues: Seq[Double] = Seq(-16.67, -33.33, 16.67)
    val tuningValues: Seq[Double] = result.map(_.cents)
    for (i <- tuningValues.indices) {
      tuningValues(i) shouldEqual expectedTuningValues(i)
    }
  }

  it should "not send pitch bend for consecutive notes with the same tuning" in new Fixture {
    output ++= tuner.tune(customTuning)
    output ++= sendNote(noteE4)
    output.clear()

    output ++= sendNote(noteA4)
    output ++= sendNote(noteB4)

    output should have size 4
    pitchBendOutput shouldBe empty
  }

  it should "always output messages to the same configured channel, " +
    "regardless of the channel on which they were received" in new Fixture {
    // Use a microtonal tuning such that pitch bend messages are also send
    output ++= tuner.tune(customTuning2)

    for (note <- MidiNote.C4.number to MidiNote.C5.number; channel = note % 12) {
      output ++= sendNote(note, channel)
    }

    output should not be empty
    pitchBendOutput should not be empty
    shortMessageOutput.map(_.getChannel).forall(_ == outputChannel) should be(true)
  }

  behavior of "MonophonicPitchBendTuner when the tuning is changed"

  it should "not send pitch bend if a note is on and its tuning does not change" in new Fixture {
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output.clear()

    output ++= tuner.tune(customTuning)

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on does not change" in new Fixture {
    output ++= sendNote(noteC4)
    output.clear()

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  it should "send pitch bend if a note is on and its tuning changes" in new Fixture {
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output.clear()

    output ++= tuner.tune(customTuning2)

    output should have size 1
    inside(output.head) {
      case PitchBendScMidiMessage(`outputChannel`, value) =>
        value shouldEqual PitchBendScMidiMessage.convertCentsToValue(-45.0, pitchBendSensitivity)
    }
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on changes" in new Fixture {
    // Note: Internally the pitch bend value changes for consistency, but it is not sent
    output ++= sendNote(noteE4)
    output.clear()

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner when multiple notes are on"

  it should "play monophonically even if no note off messages are sent" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteG4, 24).toJavaMidiMessage)
    // The next autogenerated note-off messages with use the velocity below (last velocity)
    val lastNoteOffVelocity: Int = 72
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteG4, lastNoteOffVelocity).toJavaMidiMessage)
    output.clear()

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 48).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteDSharp4, 64).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4, 96).toJavaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case NoteOnScMidiMessage(_, note, 48) => note.number shouldEqual noteC4 }
    inside(outputNotes(1)) { case NoteOffScMidiMessage(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteC4
    }
    inside(outputNotes(2)) { case NoteOnScMidiMessage(_, note, 64) => note.number shouldEqual noteDSharp4 }
    inside(outputNotes(3)) { case NoteOffScMidiMessage(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteDSharp4
    }
    inside(outputNotes(4)) { case NoteOnScMidiMessage(_, note, 96) => note.number shouldEqual noteE4 }
  }

  it should "always revert to the last note played while releasing simultaneous notes one by one" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4, 20).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4, 40).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteG4, 60).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteBb4, 80).toJavaMidiMessage)

    output.clear()
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteBb4, 85).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteE4, 65).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteG4, 45).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4, 25).toJavaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    // Using the last note-on velocity sent, 80, for the auto-generated note-on messages
    inside(outputNotes.head) { case NoteOffScMidiMessage(_, note, 85) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case NoteOnScMidiMessage(_, note, 80) => note.number shouldEqual noteG4 }
    inside(outputNotes(2)) { case NoteOffScMidiMessage(_, note, 45) => note.number shouldEqual noteG4 }
    inside(outputNotes(3)) { case NoteOnScMidiMessage(_, note, 80) => note.number shouldEqual noteC4 }
    inside(outputNotes(4)) { case NoteOffScMidiMessage(_, note, 25) => note.number shouldEqual noteC4 }
  }

  it should "tune reverted notes when holding a non-microtonal note while playing a microtonal one and lifting it" in
    new Fixture {
      output ++= tuner.tune(customTuning)
      output.clear()

      output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteG4).toJavaMidiMessage)
      output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteAb4).toJavaMidiMessage)
      output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteAb4).toJavaMidiMessage)

      output should have size 7
      inside(output.head) { case NoteOnScMidiMessage(_, note, _) => note.number should equal(noteG4) }
      inside(output(1)) { case NoteOffScMidiMessage(_, note, _) => note.number should equal(noteG4) }
      inside(output(2)) { case PitchBendScMidiMessage(_, value) => value should be > 0 }
      inside(output(3)) { case NoteOnScMidiMessage(_, note, _) => note.number should equal(noteAb4) }
      inside(output(4)) { case NoteOffScMidiMessage(_, note, _) => note.number should equal(noteAb4) }
      inside(output(5)) { case PitchBendScMidiMessage(_, value) => value should be(0) }
      inside(output(6)) { case NoteOnScMidiMessage(_, note, _) => note.number should equal(noteG4) }
    }

  behavior of "MonophonicPitchBendTuner when it receives pitch bend messages"

  it should "only add the pitch bend received if the note played is not microtonal" in new Fixture {
    output ++= sendNote(noteC4)
    output.clear()

    output ++= tuner.process(PitchBendScMidiMessage(inputChannel, -2020).toJavaMidiMessage)

    output should have size 1
    pitchBendOutput should have size 1
    pitchBendOutput.head.value should equal(-2020)
  }

  it should "add the pitch bend received to the one computed for tuning a microtonal note" in new Fixture {
    output ++= tuner.tune(customTuning)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4, 96).toJavaMidiMessage)
    output.clear()

    val expressionPitchBendCents: Double = 50
    val expressionPitchBendValue: Int = PitchBendScMidiMessage.convertCentsToValue(
      expressionPitchBendCents, semitonePitchBendSensitivity)
    output ++= tuner.process(PitchBendScMidiMessage(inputChannel, expressionPitchBendValue).toJavaMidiMessage)

    output should have size 1
    pitchBendOutput should have size 1
    PitchBendScMidiMessage.convertValueToCents(pitchBendOutput.head.value, semitonePitchBendSensitivity) should equal(
      customTuning(4) + expressionPitchBendCents)
  }

  it should "add the pitch bend received to the one computed for tuning the last microtonal note (which is off)" in
    new Fixture {
      output ++= tuner.tune(customTuning)
      output ++= sendNote(noteE4)
      output.clear()

      val expressionPitchBendCents: Double = 50
      val expressionPitchBendValue: Int = PitchBendScMidiMessage.convertCentsToValue(
        expressionPitchBendCents, semitonePitchBendSensitivity)
      output ++= tuner.process(PitchBendScMidiMessage(inputChannel, expressionPitchBendValue).toJavaMidiMessage)

      output should have size 1
      pitchBendOutput should have size 1
      PitchBendScMidiMessage.convertValueToCents(pitchBendOutput.head.value, semitonePitchBendSensitivity) should equal(
        customTuning(4) + expressionPitchBendCents)
    }

  it should "continue adding the last pitch bend received to the one for notes of different tunings" in new Fixture {
    val expressionPitchBendCents: Double = -25
    val expressionPitchBendValue: Int = PitchBendScMidiMessage.convertCentsToValue(
      expressionPitchBendCents, semitonePitchBendSensitivity)
    output ++= tuner.tune(customTuning)
    output ++= tuner.process(PitchBendScMidiMessage(inputChannel, expressionPitchBendValue).toJavaMidiMessage)
    output.clear()

    output ++= sendNote(noteDSharp4)
    output ++= sendNote(noteC4)
    output ++= sendNote(noteE4)

    pitchBendOutput should have size 3

    val centResults: Seq[Double] = pitchBendOutput.map { message =>
      PitchBendScMidiMessage.convertValueToCents(message.value, semitonePitchBendSensitivity)
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

    output ++= tuner.process(PitchBendScMidiMessage(inputChannel, PitchBendScMidiMessage.MaxValue - 1)
      .toJavaMidiMessage)
    output ++= sendNote(noteDFlat4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(PitchBendScMidiMessage.MaxValue)

    output.clear()

    output ++= tuner.process(PitchBendScMidiMessage(inputChannel, PitchBendScMidiMessage.MinValue + 1)
      .toJavaMidiMessage)
    output ++= sendNote(noteA4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(PitchBendScMidiMessage.MinValue)
  }

  behavior of "MonophonicPitchBendTuner when non-tuning-related MIDI messages are received"

  it should "forward modulation CC message on the correct channel" in new Fixture {
    output ++= tuner.tune(customTuning2)
    output.clear()

    output ++= tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.Modulation, 34).toJavaMidiMessage)

    output should have size 1
    inside(output.head) {
      case CcScMidiMessage(channel, number, 34) =>
        channel shouldEqual outputChannel
        number shouldEqual ScMidiCc.Modulation
    }
  }

  behavior of "MonophonicPitchBendTuner when pedals are depressed"

  it should "interrupt sustain pedal in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 64).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)

    shortMessageOutput should have size 9
    // Depress pedal
    inside(shortMessageOutput.head) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 64) => }
    // C on
    inside(shortMessageOutput(1)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 0) => }
    inside(shortMessageOutput(2)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 64) => }
    inside(shortMessageOutput(3)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // C off
    inside(shortMessageOutput(4)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(5)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 0) => }
    inside(shortMessageOutput(6)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 64) => }
    inside(shortMessageOutput(7)) { case PitchBendScMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(8)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
  }

  it should "interrupt sustain pedal when holding notes in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)
    output ++= tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.SustainPedal, 64).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)

    shortMessageOutput should have size 10
    // Play C
    inside(shortMessageOutput.head) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(1)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    inside(shortMessageOutput(2)) { case PitchBendScMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(3)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
    // Depress pedal
    inside(shortMessageOutput(4)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 64) => }
    // Play C
    inside(shortMessageOutput(5)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
    inside(shortMessageOutput(6)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 0) => }
    inside(shortMessageOutput(7)) { case CcScMidiMessage(_, ScMidiCc.SustainPedal, 64) => }
    inside(shortMessageOutput(8)) { case PitchBendScMidiMessage(_, value) => value shouldEqual 0 }
    inside(shortMessageOutput(9)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
  }

  it should "stop sostenuto pedal in order to not violate monophony" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output ++= tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.SostenutoPedal, 64).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteC4).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)

    shortMessageOutput should have size 6
    // C on
    inside(shortMessageOutput.head) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Depress pedal
    inside(shortMessageOutput(1)) { case CcScMidiMessage(_, ScMidiCc.SostenutoPedal, 64) => }
    // C off
    inside(shortMessageOutput(2)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(shortMessageOutput(3)) { case CcScMidiMessage(_, ScMidiCc.SostenutoPedal, 0) => }
    inside(shortMessageOutput(4)) { case PitchBendScMidiMessage(_, value) => value should be < 0 }
    inside(shortMessageOutput(5)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteE4 }
  }

  it should "change pitch bend sensitivity via MIDI RPN messages" in new Fixture {
    tuner.tune(customTuning)

    // Current pbs is 1 semitone (semitonePitchBendSensitivity)
    // Send RPN messages to change it to 2 semitones
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).toJavaMidiMessage)
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).toJavaMidiMessage)
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.DataEntryMsb, tonePitchBendSensitivity.semitones)
      .toJavaMidiMessage)
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.DataEntryLsb, tonePitchBendSensitivity.cents)
      .toJavaMidiMessage)

    // Play noteE4 (offset is -16.67 cents in customTuning)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)

    pitchBendOutput should have size 1
    // The pitch bend value should be calculated using the NEW pbs (2 semitones)
    // cents = -16.67, pbs = 2 semitones = 200 cents
    // value = cents / pbs * 8192 = -16.67 / 200 * 8192 = -682.8032 -> -683
    pitchBendOutput.head.value should equal(
      PitchBendScMidiMessage.convertCentsToValue(-16.67, tonePitchBendSensitivity)
    )
  }

  it should "immediately send updated pitch bend if sensitivity changes while a note is on" in new Fixture {
    tuner.tune(customTuning)
    // Play noteE4 (-16.67 cents) with default PBS (1 semitone = 100 cents)
    // value = -16.67 / 100 * 8192 = -1365.6 -> -1366
    tuner.process(NoteOnScMidiMessage(inputChannel, noteE4).toJavaMidiMessage)

    // Send RPN messages to change it to 2 semitones
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).toJavaMidiMessage)
    tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).toJavaMidiMessage)
    // MSB change
    output ++= tuner.process(CcScMidiMessage(inputChannel, ScMidiCc.DataEntryMsb, tonePitchBendSensitivity
      .semitones).toJavaMidiMessage)

    // It should have sent a new pitch bend message immediately after DataEntryMsb
    // value = -16.67 / 200 * 8192 = -683
    pitchBendOutput should have size 1
    pitchBendOutput.head.value should equal(
      PitchBendScMidiMessage.convertCentsToValue(-16.67, tonePitchBendSensitivity)
    )
  }

  behavior of "MonophonicPitchBendTuner for playing microtonal notes"

  val pitchBendSensitivities: Seq[PitchBendSensitivity] = Seq(
    semitonePitchBendSensitivity, tonePitchBendSensitivity,
    PitchBendSensitivity(semitones = 12),
    PitchBendSensitivity(semitones = 3, cents = 19)
  )

  def stringOfPitchBendSensitivity(pbs: PitchBendSensitivity): String =
    s"${pbs.semitones} semitone(s) and ${pbs.cents} cent(s)"

  for (pbs <- pitchBendSensitivities) {
    val pbsString: String = stringOfPitchBendSensitivity(pbs)

    it should s"play a scale with microtonal notes when pitch bend sensitivity is $pbsString" in new Fixture(pbs) {
      tuner.tune(customTuning)

      for (note <- MidiNote.C4.number until MidiNote.C5.number) {
        output ++= sendNote(note)
      }

      pitchBendOutput should have size 11

      val tuningValues: Seq[Double] = pitchBendOutput.map { message => message.cents.round.toDouble }
      val expectedTuningValues: Seq[Double] = customTuning.offsets.tail.map(_.round.toDouble)
      tuningValues should contain theSameElementsAs expectedTuningValues
    }
  }

  behavior of "MonophonicPitchBendTuner bugs"

  it should "work for note Bb on, note A on, note Bb off, note Bb on in custom tuning" in new Fixture {
    output ++= tuner.tune(customTuning)
    output.clear()

    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteBb4).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteA4).toJavaMidiMessage)
    output ++= tuner.process(NoteOffScMidiMessage(inputChannel, noteBb4).toJavaMidiMessage)
    output ++= tuner.process(NoteOnScMidiMessage(inputChannel, noteBb4).toJavaMidiMessage)

    val outputNotes: Seq[ShortMessage] = filterNotes(shortMessageOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(2)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(3)) { case NoteOffScMidiMessage(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(4)) { case NoteOnScMidiMessage(_, note, _) => note.number shouldEqual noteBb4 }
  }
}

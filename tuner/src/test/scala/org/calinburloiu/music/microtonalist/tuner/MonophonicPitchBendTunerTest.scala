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

    val output: mutable.Buffer[MidiMsg] = mutable.Buffer.empty

    protected implicit val implPitchBendSensitivity: PitchBendSensitivity = pitchBendSensitivity

    def channelMessageOutput: Seq[ChannelMidiMsg] = output.toSeq.collect {
      case channelMessage: ChannelMidiMsg => channelMessage
    }

    def scMidiOutput: Seq[MidiMsg] = output.toSeq

    def pitchBendOutput: Seq[PitchBendMidiMsg] = scMidiOutput.collect {
      case m: PitchBendMidiMsg => m
    }

    def sendNote(note: MidiNote, channel: Int = inputChannel): Seq[MidiMsg] = {
      Seq(
        tuner.process(NoteOnMidiMsg(channel, note)),
        tuner.process(NoteOffMidiMsg(channel, note))
      ).flatten
    }
  }

  private def filterNotes(messages: Seq[MidiMsg]): Seq[MidiMsg] = {
    messages.collect {
      case m: NoteOnMidiMsg => m
      case m: NoteOffMidiMsg => m
    }
  }

  private def collectCcMessages(midiMessages: Seq[MidiMsg]): Seq[(Int, Int)] = {
    midiMessages.collect {
      case CcMidiMsg(channel, number, value) =>
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
      (MidiCc.RpnLsb, MidiRpn.PitchBendSensitivityLsb),
      (MidiCc.RpnMsb, MidiRpn.PitchBendSensitivityMsb),
      (MidiCc.DataEntryMsb, customPitchBendSensitivity.semitones),
      (MidiCc.DataEntryLsb, customPitchBendSensitivity.cents),
      (MidiCc.RpnLsb, MidiRpn.NullLsb),
      (MidiCc.RpnMsb, MidiRpn.NullMsb)
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
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, MidiNote(PitchClass.C, 6)))
    // Send on of the "note off" as a note on with velocity 0
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, MidiNote(PitchClass.C, 6), 0))

    pitchBendOutput shouldBe empty
  }

  it should "send pitch bend when playing a note with a different tuning than the previous one" in new Fixture {
    output ++= tuner.tune(customTuning)

    output ++= sendNote(noteE4)
    output ++= sendNote(noteDSharp4)
    output ++= sendNote(noteDFlat4)

    output should have size 9

    val result: Seq[PitchBendMidiMsg] = pitchBendOutput
    result should have size 3

    val expectedTuningValues: Seq[Double] = Seq(-16.67, -33.33, 16.67)
    val tuningValues: Seq[Double] = result.map(_.cents)
    for (i <- tuningValues.indices) {
      tuningValues(i) shouldEqual expectedTuningValues(i)
    }
  }

  it should "not send pitch bend for consecutive notes with the same tuning" in new Fixture {
    tuner.tune(customTuning)
    sendNote(noteE4)

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
    channelMessageOutput.map(_.channel).forall(_ == outputChannel) should be(true)
  }

  behavior of "MonophonicPitchBendTuner when the tuning is changed"

  it should "not send pitch bend if a note is on and its tuning does not change" in new Fixture {
    tuner.process(NoteOnMidiMsg(inputChannel, noteC4))

    output ++= tuner.tune(customTuning)

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on does not change" in new Fixture {
    sendNote(noteC4)

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  it should "send pitch bend if a note is on and its tuning changes" in new Fixture {
    tuner.process(NoteOnMidiMsg(inputChannel, noteC4))

    output ++= tuner.tune(customTuning2)

    output should have size 1
    inside(output.head) {
      case PitchBendMidiMsg(`outputChannel`, value) =>
        value shouldEqual PitchBendMidiMsg.convertCentsToValue(-45.0, pitchBendSensitivity)
    }
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on changes" in new Fixture {
    // Note: Internally the pitch bend value changes for consistency, but it is not sent
    sendNote(noteE4)

    output ++= tuner.tune(customTuning)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner when multiple notes are on"

  it should "play monophonically even if no note off messages are sent" in new Fixture {
    tuner.tune(customTuning)

    tuner.process(NoteOnMidiMsg(inputChannel, noteG4, 24))
    // The next autogenerated note-off messages with use the velocity below (last velocity)
    val lastNoteOffVelocity: Int = 72
    tuner.process(NoteOffMidiMsg(inputChannel, noteG4, lastNoteOffVelocity))

    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 48))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteDSharp4, 64))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 96))

    val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case NoteOnMidiMsg(_, note, 48) => note.number shouldEqual noteC4 }
    inside(outputNotes(1)) { case NoteOffMidiMsg(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteC4
    }
    inside(outputNotes(2)) { case NoteOnMidiMsg(_, note, 64) => note.number shouldEqual noteDSharp4 }
    inside(outputNotes(3)) { case NoteOffMidiMsg(_, note, `lastNoteOffVelocity`) =>
      note.number shouldEqual noteDSharp4
    }
    inside(outputNotes(4)) { case NoteOnMidiMsg(_, note, 96) => note.number shouldEqual noteE4 }
  }

  it should "always revert to the last note played while releasing simultaneous notes one by one" in new Fixture {
    tuner.tune(customTuning)

    tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 20))
    tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 40))
    tuner.process(NoteOnMidiMsg(inputChannel, noteG4, 60))
    tuner.process(NoteOnMidiMsg(inputChannel, noteBb4, 80))

    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteBb4, 85))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteE4, 65))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteG4, 45))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4, 25))

    val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
    outputNotes should have size 5
    // Using the last note-on velocity sent, 80, for the auto-generated note-on messages
    inside(outputNotes.head) { case NoteOffMidiMsg(_, note, 85) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case NoteOnMidiMsg(_, note, 80) => note.number shouldEqual noteG4 }
    inside(outputNotes(2)) { case NoteOffMidiMsg(_, note, 45) => note.number shouldEqual noteG4 }
    inside(outputNotes(3)) { case NoteOnMidiMsg(_, note, 80) => note.number shouldEqual noteC4 }
    inside(outputNotes(4)) { case NoteOffMidiMsg(_, note, 25) => note.number shouldEqual noteC4 }
  }

  it should "tune reverted notes when holding a non-microtonal note while playing a microtonal one and lifting it" in
    new Fixture {
      tuner.tune(customTuning)

      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteG4))
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteAb4))
      output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteAb4))

      output should have size 7
      inside(output.head) { case NoteOnMidiMsg(_, note, _) => note.number should equal(noteG4) }
      inside(output(1)) { case NoteOffMidiMsg(_, note, _) => note.number should equal(noteG4) }
      inside(output(2)) { case PitchBendMidiMsg(_, value) => value should be > 0 }
      inside(output(3)) { case NoteOnMidiMsg(_, note, _) => note.number should equal(noteAb4) }
      inside(output(4)) { case NoteOffMidiMsg(_, note, _) => note.number should equal(noteAb4) }
      inside(output(5)) { case PitchBendMidiMsg(_, value) => value should be(0) }
      inside(output(6)) { case NoteOnMidiMsg(_, note, _) => note.number should equal(noteG4) }
    }

  it should "keep a re-pressed note sounding until its last Note Off, then revert to the note still held" in
    new Fixture {
      // Given
      // C4 is pressed, E4 takes over, then C4 is pressed a second time without the first press being released.
      tuner.tune(customTuning)
      tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 20))
      tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 40))
      tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 60))

      // When
      // The first of the two C4 presses is released.
      output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4, 25))

      // Then
      // C4 is still held by the second press, so nothing at all is emitted and it keeps sounding.
      output shouldBe empty

      // When
      // The second press is released too.
      output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4, 25))

      // Then
      // Only now does C4 stop, handing the sound back to the still-held E4 with E's tuning.
      val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
      outputNotes should have size 2
      inside(outputNotes.head) { case NoteOffMidiMsg(_, note, 25) => note.number shouldEqual noteC4 }
      inside(outputNotes(1)) { case NoteOnMidiMsg(_, note, 60) => note.number shouldEqual noteE4 }
      pitchBendOutput should have size 1
      PitchBendMidiMsg.convertValueToCents(pitchBendOutput.head.value, pitchBendSensitivity) shouldEqual
        customTuning(4)
    }

  it should "keep a re-pressed note sounding until its last velocity-0 Note On, then revert to the note still held" in
    new Fixture {
      // Given
      // C4 is pressed, E4 takes over, then C4 is pressed a second time without the first press being released.
      tuner.tune(customTuning)
      tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 20))
      tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 40))
      tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 60))

      // When
      // The first of the two C4 presses is released, spelled as a velocity-0 Note On rather than a Note Off.
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 0))

      // Then
      // C4 is still held by the second press, so nothing at all is emitted and it keeps sounding.
      output shouldBe empty

      // When
      // The second press is released too, again spelled as a velocity-0 Note On.
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 0))

      // Then
      // Only now does C4 stop, handing the sound back to the still-held E4 with E's tuning.
      val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
      outputNotes should have size 2
      inside(outputNotes.head) { case NoteOnMidiMsg(_, note, 0) => note.number shouldEqual noteC4 }
      inside(outputNotes(1)) { case NoteOnMidiMsg(_, note, 60) => note.number shouldEqual noteE4 }
      pitchBendOutput should have size 1
      PitchBendMidiMsg.convertValueToCents(pitchBendOutput.head.value, pitchBendSensitivity) shouldEqual
        customTuning(4)
    }

  it should "emit nothing when a note that is not the sounding one is released press by press" in new Fixture {
    // Given
    // C4 is pressed twice, then E4 takes over as the sounding note while both C4 presses are still down.
    tuner.tune(customTuning)
    tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 20))
    tuner.process(NoteOnMidiMsg(inputChannel, noteC4, 30))
    tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 40))

    // When
    // Both of C4's presses are released while E4 is still held.
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4, 25))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4, 25))

    // Then
    // Neither release is audible: E4 keeps sounding undisturbed.
    output shouldBe empty

    // When
    // E4 is released in turn.
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteE4, 45))

    // Then
    // E4 simply stops; C4 is not revived, both of its presses having been discharged.
    val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
    outputNotes should have size 1
    inside(outputNotes.head) { case NoteOffMidiMsg(_, note, 45) => note.number shouldEqual noteE4 }
  }

  behavior of "MonophonicPitchBendTuner when it receives pitch bend messages"

  it should "only add the pitch bend received if the note played is not microtonal" in new Fixture {
    sendNote(noteC4)

    output ++= tuner.process(PitchBendMidiMsg(inputChannel, -2020))

    output should have size 1
    pitchBendOutput should have size 1
    pitchBendOutput.head.value should equal(-2020)
  }

  it should "add the pitch bend received to the one computed for tuning a microtonal note" in new Fixture {
    tuner.tune(customTuning)
    tuner.process(NoteOnMidiMsg(inputChannel, noteE4, 96))

    val expressionPitchBendCents: Double = 50
    val expressionPitchBendValue: Int = PitchBendMidiMsg.convertCentsToValue(
      expressionPitchBendCents, semitonePitchBendSensitivity)
    output ++= tuner.process(PitchBendMidiMsg(inputChannel, expressionPitchBendValue))

    output should have size 1
    pitchBendOutput should have size 1
    PitchBendMidiMsg.convertValueToCents(pitchBendOutput.head.value, semitonePitchBendSensitivity) should equal(
      customTuning(4) + expressionPitchBendCents)
  }

  it should "add the pitch bend received to the one computed for tuning the last microtonal note (which is off)" in
    new Fixture {
      tuner.tune(customTuning)
      sendNote(noteE4)

      val expressionPitchBendCents: Double = 50
      val expressionPitchBendValue: Int = PitchBendMidiMsg.convertCentsToValue(
        expressionPitchBendCents, semitonePitchBendSensitivity)
      output ++= tuner.process(PitchBendMidiMsg(inputChannel, expressionPitchBendValue))

      output should have size 1
      pitchBendOutput should have size 1
      PitchBendMidiMsg.convertValueToCents(pitchBendOutput.head.value, semitonePitchBendSensitivity) should equal(
        customTuning(4) + expressionPitchBendCents)
    }

  it should "continue adding the last pitch bend received to the one for notes of different tunings" in new Fixture {
    val expressionPitchBendCents: Double = -25
    val expressionPitchBendValue: Int = PitchBendMidiMsg.convertCentsToValue(
      expressionPitchBendCents, semitonePitchBendSensitivity)
    tuner.tune(customTuning)
    tuner.process(PitchBendMidiMsg(inputChannel, expressionPitchBendValue))

    output ++= sendNote(noteDSharp4)
    output ++= sendNote(noteC4)
    output ++= sendNote(noteE4)

    pitchBendOutput should have size 3

    val centResults: Seq[Double] = pitchBendOutput.map { message =>
      PitchBendMidiMsg.convertValueToCents(message.value, semitonePitchBendSensitivity)
    }
    val expectedCentsResults: Seq[Double] = Seq(customTuning(3), customTuning(0), customTuning(4))
      .map(_ + expressionPitchBendCents)
    // Using a for because tolerance does not work with sequences
    (0 until 3).foreach { i => centResults(i) should equal(expectedCentsResults(i)) }
  }

  it should "clamp the pitch bend to min/max value if adding received pitch bend to tuning pitch bend " +
    "exceeds the bounds" in new Fixture {
    tuner.tune(customTuning)

    output ++= tuner.process(PitchBendMidiMsg(inputChannel, PitchBendMidiMsg.MaxValue - 1))
    output ++= sendNote(noteDFlat4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(PitchBendMidiMsg.MaxValue)

    output.clear()

    output ++= tuner.process(PitchBendMidiMsg(inputChannel, PitchBendMidiMsg.MinValue + 1))
    output ++= sendNote(noteA4)
    pitchBendOutput should have size 2
    pitchBendOutput(1).value should equal(PitchBendMidiMsg.MinValue)
  }

  behavior of "MonophonicPitchBendTuner when non-tuning-related MIDI messages are received"

  it should "forward modulation CC message on the correct channel" in new Fixture {
    tuner.tune(customTuning2)

    output ++= tuner.process(CcMidiMsg(inputChannel, MidiCc.ModulationMsb, 34))

    output should have size 1
    inside(output.head) {
      case CcMidiMsg(channel, number, 34) =>
        channel shouldEqual outputChannel
        number shouldEqual MidiCc.ModulationMsb
    }
  }

  it should "forward a System Real-Time message unchanged" in new Fixture {
    // Given
    tuner.tune(customTuning2)

    // When
    output ++= tuner.process(TimingClockMidiMsg)

    // Then
    scMidiOutput shouldEqual Seq(TimingClockMidiMsg)
  }

  behavior of "MonophonicPitchBendTuner when pedals are depressed"

  it should "interrupt sustain pedal in order to not violate monophony" in new Fixture {
    tuner.tune(customTuning)

    output ++= tuner.process(CcMidiMsg(inputChannel, MidiCc.SustainPedal, 64))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4))

    channelMessageOutput should have size 9
    // Depress pedal
    inside(scMidiOutput.head) { case CcMidiMsg(_, MidiCc.SustainPedal, 64) => }
    // C on
    inside(scMidiOutput(1)) { case CcMidiMsg(_, MidiCc.SustainPedal, 0) => }
    inside(scMidiOutput(2)) { case CcMidiMsg(_, MidiCc.SustainPedal, 64) => }
    inside(scMidiOutput(3)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    // C off
    inside(scMidiOutput(4)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(scMidiOutput(5)) { case CcMidiMsg(_, MidiCc.SustainPedal, 0) => }
    inside(scMidiOutput(6)) { case CcMidiMsg(_, MidiCc.SustainPedal, 64) => }
    inside(scMidiOutput(7)) { case PitchBendMidiMsg(_, value) => value should be < 0 }
    inside(scMidiOutput(8)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
  }

  it should "interrupt sustain pedal when holding notes in order to not violate monophony" in new Fixture {
    tuner.tune(customTuning)

    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4))
    output ++= tuner.process(CcMidiMsg(inputChannel, MidiCc.SustainPedal, 64))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteE4))

    channelMessageOutput should have size 10
    // Play C
    inside(scMidiOutput.head) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(scMidiOutput(1)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    inside(scMidiOutput(2)) { case PitchBendMidiMsg(_, value) => value should be < 0 }
    inside(scMidiOutput(3)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
    // Depress pedal
    inside(scMidiOutput(4)) { case CcMidiMsg(_, MidiCc.SustainPedal, 64) => }
    // Play C
    inside(scMidiOutput(5)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
    inside(scMidiOutput(6)) { case CcMidiMsg(_, MidiCc.SustainPedal, 0) => }
    inside(scMidiOutput(7)) { case CcMidiMsg(_, MidiCc.SustainPedal, 64) => }
    inside(scMidiOutput(8)) { case PitchBendMidiMsg(_, value) => value shouldEqual 0 }
    inside(scMidiOutput(9)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
  }

  it should "stop sostenuto pedal in order to not violate monophony" in new Fixture {
    tuner.tune(customTuning)

    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4))
    output ++= tuner.process(CcMidiMsg(inputChannel, MidiCc.SostenutoPedal, 64))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4))

    channelMessageOutput should have size 6
    // C on
    inside(scMidiOutput.head) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    // Depress pedal
    inside(scMidiOutput(1)) { case CcMidiMsg(_, MidiCc.SostenutoPedal, 64) => }
    // C off
    inside(scMidiOutput(2)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
    // Play E
    inside(scMidiOutput(3)) { case CcMidiMsg(_, MidiCc.SostenutoPedal, 0) => }
    inside(scMidiOutput(4)) { case PitchBendMidiMsg(_, value) => value should be < 0 }
    inside(scMidiOutput(5)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
  }

  it should "change pitch bend sensitivity via MIDI RPN messages" in new Fixture {
    tuner.tune(customTuning)

    // Current pbs is 1 semitone (semitonePitchBendSensitivity)
    // Send RPN messages to change it to 2 semitones
    tuner.process(CcMidiMsg(inputChannel, MidiCc.RpnLsb, MidiRpn.PitchBendSensitivityLsb))
    tuner.process(CcMidiMsg(inputChannel, MidiCc.RpnMsb, MidiRpn.PitchBendSensitivityMsb))
    tuner.process(CcMidiMsg(inputChannel, MidiCc.DataEntryMsb, tonePitchBendSensitivity.semitones))
    tuner.process(CcMidiMsg(inputChannel, MidiCc.DataEntryLsb, tonePitchBendSensitivity.cents))

    // Play noteE4 (offset is -16.67 cents in customTuning)
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4))

    pitchBendOutput should have size 1
    // The pitch bend value should be calculated using the NEW pbs (2 semitones)
    // cents = -16.67, pbs = 2 semitones = 200 cents
    // value = cents / pbs * 8192 = -16.67 / 200 * 8192 = -682.8032 -> -683
    pitchBendOutput.head.value should equal(
      PitchBendMidiMsg.convertCentsToValue(-16.67, tonePitchBendSensitivity)
    )
  }

  it should "immediately send updated pitch bend if sensitivity changes while a note is on" in new Fixture {
    tuner.tune(customTuning)
    // Play noteE4 (-16.67 cents) with default PBS (1 semitone = 100 cents)
    // value = -16.67 / 100 * 8192 = -1365.6 -> -1366
    tuner.process(NoteOnMidiMsg(inputChannel, noteE4))

    // Send RPN messages to change it to 2 semitones
    tuner.process(CcMidiMsg(inputChannel, MidiCc.RpnLsb, MidiRpn.PitchBendSensitivityLsb))
    tuner.process(CcMidiMsg(inputChannel, MidiCc.RpnMsb, MidiRpn.PitchBendSensitivityMsb))
    // MSB change
    output ++= tuner.process(CcMidiMsg(inputChannel, MidiCc.DataEntryMsb, tonePitchBendSensitivity.semitones))

    // It should have sent a new pitch bend message immediately after DataEntryMsb
    // value = -16.67 / 200 * 8192 = -683
    pitchBendOutput should have size 1
    pitchBendOutput.head.value should equal(
      PitchBendMidiMsg.convertCentsToValue(-16.67, tonePitchBendSensitivity)
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
    tuner.tune(customTuning)

    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteBb4))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteA4))
    output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteBb4))
    output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteBb4))

    val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
    outputNotes should have size 5
    inside(outputNotes.head) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(1)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteBb4 }
    inside(outputNotes(2)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(3)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteA4 }
    inside(outputNotes(4)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteBb4 }
  }

  it should "revert to a still-held note after re-pressing then releasing an already-played note twice" in
    new Fixture {
      // Given a microtonal tuning so pitch-bend updates are observable
      tuner.tune(customTuning)

      // When holding C, then E, then re-articulating C while E is still held, then releasing C twice — one release
      // per press, since the tracker now requires every Note On on an already-active note to be matched by its own
      // Note Off before the note is considered released
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4))
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteE4))
      output ++= tuner.process(NoteOnMidiMsg(inputChannel, noteC4))
      output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4))
      output ++= tuner.process(NoteOffMidiMsg(inputChannel, noteC4))

      // Then the last release should turn off C and revert to the still-held E
      val outputNotes: Seq[MidiMsg] = filterNotes(scMidiOutput)
      outputNotes should have size 7
      inside(outputNotes.head) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
      inside(outputNotes(1)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
      inside(outputNotes(2)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
      inside(outputNotes(3)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
      inside(outputNotes(4)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
      inside(outputNotes(5)) { case NoteOffMidiMsg(_, note, _) => note.number shouldEqual noteC4 }
      inside(outputNotes(6)) { case NoteOnMidiMsg(_, note, _) => note.number shouldEqual noteE4 }
    }
}

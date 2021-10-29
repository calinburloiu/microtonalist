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

package org.calinburloiu.music.microtuner

import org.calinburloiu.music.microtuner.midi.{MidiNote, MidiProcessorFixture, PitchBendSensitivity, Rpn, ScCcMidiMessage, ScNoteOffMidiMessage, ScNoteOnMidiMessage, ScPitchBendMidiMessage}
import org.calinburloiu.music.tuning.Tuning
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.MidiMessage

class MonophonicPitchBendTunerTest extends AnyFlatSpec with Matchers with Inside {
  private val inputChannel = 2
  private val outputChannel = 3
  private val pitchBendSensitivity = PitchBendSensitivity(1)

  //@formatter:off
  private val customTuning = Tuning(
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
  private val customTuning2 = Tuning("custom2", -45.0, -34.0, -23.0, -12.0, -1, 2, 13, 24, 35, 46, 17, 34)

  val Seq(noteC4, noteDFlat4, noteD4, noteDSharp4, noteE4, noteF4, noteFSharp4, noteG4,
    noteAb4, noteA4, noteBb4, noteB4) = 60 until 72

  /** Default fixture that auto connects. Create a custom one for other cases. */
  private trait Fixture extends MidiProcessorFixture[MonophonicPitchBendTuner] {
    override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(outputChannel, pitchBendSensitivity)

    /** Alias for `midiProcessor` for better test readability. */
    val tuner: MonophonicPitchBendTuner = midiProcessor

    connect()
    resetOutput()

    def pitchBendOutput: Seq[ScPitchBendMidiMessage] = output.collect {
      case ScPitchBendMidiMessage(channel, value) => ScPitchBendMidiMessage(channel, value)
    }
  }

  private def sendNote(tuner: TunerProcessor, note: MidiNote, channel: Int = inputChannel): Unit = {
    tuner.send(ScNoteOnMidiMessage(channel, note))
    tuner.send(ScNoteOffMidiMessage(channel, note))
  }

  private def collectNotes(midiMessages: Seq[MidiMessage]): Seq[Either[Int, Int]] = {
    midiMessages.collect {
      case ScNoteOnMidiMessage(_, note, _) => Right(note.number)
      case ScNoteOffMidiMessage(_, note, _) => Left(note.number)
    }
  }

  private def collectCcMessage(midiMessages: Seq[MidiMessage]): Seq[(Int, Int)] = {
    midiMessages.collect {
      case ScCcMidiMessage(channel, number, value) =>
        channel should equal (outputChannel)
        (number, value)
    }
  }

  behavior of "MonophonicPitchBendTuner when a receiver is set"

  it should "configure the output device on connect" in new MidiProcessorFixture[MonophonicPitchBendTuner] {
    val customPitchBendSensitivity: PitchBendSensitivity = PitchBendSensitivity(3, 37)
    override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(
      outputChannel,
      customPitchBendSensitivity
    )
    connect()

    output should not be empty

    val ccMessages: Seq[(Int, Int)] = collectCcMessage(output)
    ccMessages should contain inOrderOnly (
      (ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      (ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      (ScCcMidiMessage.DataEntryMsb, customPitchBendSensitivity.semitones),
      (ScCcMidiMessage.DataEntryLsb, customPitchBendSensitivity.cents),
      (ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
      (ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
    )
  }

  it should "reset the output device to default parameters on disconnect, including the pitch bend" in
    new MidiProcessorFixture[MonophonicPitchBendTuner] {
      // Note: Only resetting those that were previously set on connect
      override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(
        outputChannel, PitchBendSensitivity(3, 37))
      connect()
      resetOutput()
      // Reconnecting triggers onDisconnect(). The output will also contain onConnect() messages.
      connect()

      List(0, 1, 2, 2, 99, 3, 3, 3, 5) should contain inOrder (1, 2, 3)
      val ccMessages: Seq[(Int, Int)] = collectCcMessage(output)
      ccMessages.containsSlice(Seq(
        (ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
        (ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
        (ScCcMidiMessage.DataEntryMsb, PitchBendSensitivity.Default.semitones),
        (ScCcMidiMessage.DataEntryLsb, PitchBendSensitivity.Default.cents),
        (ScCcMidiMessage.RpnLsb, Rpn.NullLsb),
        (ScCcMidiMessage.RpnMsb, Rpn.NullMsb)
      )) should be (true)

      val pitchBendValues: Seq[Int] = shortMessageOutput.collect { case ScPitchBendMidiMessage(_, value) => value }
      pitchBendValues should equal (Seq(0))
    }

  behavior of "MonophonicPitchBendTuner after initialization"

  it should "tune all notes in 12-EDO by not sending any pitch bend" in new Fixture {
    for (note <- 60 to 72) {
      sendNote(tuner, note)
    }

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend when no note is on even if the tuning of C is changed" in new Fixture {
    // Note: C (MIDI note 0) is the default last note after initialization.
    // Internally the pitch bend value changes for consistency, but it is not sent.
    tuner.tune(customTuning2)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner after a new tuning is set"

  it should "not send pitch bend when the last note is replayed and its tuning did not change" in new Fixture {
    // Note: The initial last node is C (MIDI note 0).
    tuner.tune(customTuning)

    // Send some Cs
    sendNote(tuner, 60)
    sendNote(tuner, 48)
    sendNote(tuner, 84)

    pitchBendOutput shouldBe empty
  }

  it should "send pitch bend when playing a note with a different tuning than the previous one" in new Fixture {
    tuner.tune(customTuning)

    sendNote(tuner, noteE4)
    sendNote(tuner, noteDSharp4)
    sendNote(tuner, noteDFlat4)

    output should have size 9

    val result: Seq[ScPitchBendMidiMessage] = pitchBendOutput
    result should have size 3
    result.map(_.value) should equal (Seq(
      Math.round(-16.67/100 * 8192),
      Math.round(-33.33/100 * 8192),
      Math.round(16.67/100 * 8191)
    ))
  }

  it should "not send pitch bend for consecutive notes with the same tuning" in new Fixture {
    tuner.tune(customTuning)
    sendNote(tuner, noteE4)
    resetOutput()

    sendNote(tuner, noteA4)
    sendNote(tuner, noteB4)

    output should have size 4
    pitchBendOutput shouldBe empty
  }

  it should "always output messages to the same configured channel, " +
    "regardless of the channel on which they were received" in new Fixture {
    // Use a microtonal tuning such that pitch bend messages are also send
    tuner.tune(customTuning2)

    for (note <- 60 to 72; channel = note % 12) {
      sendNote(tuner, note, channel)
    }

    output should not be empty
    pitchBendOutput should not be empty
    shortMessageOutput.map(_.getChannel).forall(_ == outputChannel) should be (true)
  }

  behavior of "MonophonicPitchBendTuner when the tuning is changed"

  it should "not send pitch bend if a note is on and its tuning does not change" in new Fixture {
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteC4))
    resetOutput()

    tuner.tune(customTuning)

    pitchBendOutput shouldBe empty
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on does not change" in new Fixture {
    sendNote(tuner, noteC4)
    resetOutput()

    tuner.tune(customTuning)

    output shouldBe empty
  }

  it should "send pitch bend if a note is on and its tuning changes" in new Fixture {
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteC4))
    resetOutput()

    tuner.tune(customTuning2)

    output should have size 1
    inside(output.head) {
      case ScPitchBendMidiMessage(`outputChannel`, value) => value should equal (Math.round(-45.0/100 * 8192))
    }
  }

  it should "not send pitch bend if there is no note on " +
    "and the tuning of the last note on changes" in new Fixture {
    // Note: Internally the pitch bend value changes for consistency, but it is not sent
    sendNote(tuner, noteE4)
    resetOutput()

    tuner.tune(customTuning)

    output shouldBe empty
  }

  behavior of "MonophonicPitchBendTuner when multiple notes are on"

  it should "play monophonically even if no note off messages are sent" in new Fixture {
    tuner.tune(customTuning)

    tuner.send(ScNoteOnMidiMessage(inputChannel, noteC4))
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteDSharp4))
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteE4))

    val outputNotes: Seq[Either[Int, Int]] = collectNotes(shortMessageOutput)
    outputNotes should equal (Seq(
      Right(noteC4),
      Left(noteC4),
      Right(noteDSharp4),
      Left(noteDSharp4),
      Right(noteE4)
    ))
  }

  // TODO Enable
  ignore should "always revert to the last note played while releasing simultaneous notes one by one" in new Fixture {
    tuner.tune(customTuning)

    tuner.send(ScNoteOnMidiMessage(inputChannel, noteC4))
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteE4))
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteG4))
    tuner.send(ScNoteOnMidiMessage(inputChannel, noteBb4))

    resetOutput()
    tuner.send(ScNoteOffMidiMessage(inputChannel, noteBb4))
    tuner.send(ScNoteOffMidiMessage(inputChannel, noteE4))
    tuner.send(ScNoteOffMidiMessage(inputChannel, noteG4))
    tuner.send(ScNoteOffMidiMessage(inputChannel, noteC4))

    val outputNotes: Seq[Either[Int, Int]] = collectNotes(shortMessageOutput)
    outputNotes should equal (Seq(
      Left(noteBb4),
      Right(noteG4),  // Playing G
      Left(noteE4),
      Left(noteG4),  // Playing C
      Right(noteC4),  // Playing C
      Left(noteC4)
    ))
  }
}

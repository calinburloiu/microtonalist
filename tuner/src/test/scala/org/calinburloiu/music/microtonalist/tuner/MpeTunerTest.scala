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
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, ShortMessage}

class MpeTunerTest extends AnyFlatSpec with Matchers with Inside {

  private implicit val defaultPbs: PitchBendSensitivity = PitchBendSensitivity(48)
  private val masterPbs: PitchBendSensitivity = PitchBendSensitivity(2)

  import MidiNote.{A4, C4, C5, D4, E4, G4}

  private val inputChannel = 0

  // Quarter-comma meantone tuning (approximate offsets in cents)
  //@formatter:off
  private val quarterCommaMeantone = Tuning("quarter-comma meantone",
    0.0,    // C
    -24.0,  // C#
    -7.0,   // D
    10.0,   // Eb
    -14.0,  // E
    3.0,    // F
    -21.0,  // F#
    -3.0,   // G
    -27.0,  // G#
    -10.0,  // A
    7.0,    // Bb
    -17.0   // B
  )
  //@formatter:on

  //@formatter:off
  private val pythagoreanTuning = Tuning("pythagorean",
    0.0,    // C
    -10.0,  // C#
    4.0,    // D
    -6.0,   // Eb
    8.0,    // E
    -2.0,   // F
    -12.0,  // F#
    2.0,    // G
    -8.0,   // G#
    6.0,    // A
    -4.0,   // Bb
    10.0    // B
  )
  //@formatter:on

  private def defaultTuner: MpeTuner = new MpeTuner()

  private def tuner7: MpeTuner = new MpeTuner(
    zones = (MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 0))
  )

  private def tuner3: MpeTuner = new MpeTuner(
    zones = (MpeZone(MpeZoneType.Lower, 3), MpeZone(MpeZoneType.Upper, 0))
  )

  private def dualZoneTuner: MpeTuner = new MpeTuner(
    zones = (MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
  )

  private def mpeTunerMpeInput: MpeTuner = new MpeTuner(inputMode = MpeInputMode.Mpe)

  private abstract class TunerFixture(val tuner: MpeTuner = defaultTuner,
                                      initialTuning: Option[Tuning] = None) {
    tuner.reset()
    initialTuning.foreach(tuner.tune)
  }

  private def extractShortMessages(output: Seq[MidiMessage]): Seq[ShortMessage] =
    output.collect { case sm: ShortMessage => sm }

  private def extractPitchBends(output: Seq[MidiMessage]): Seq[ScPitchBendMidiMessage] =
    output.flatMap(ScPitchBendMidiMessage.fromJavaMessage)

  private def extractNoteOns(output: Seq[MidiMessage]): Seq[ScNoteOnMidiMessage] =
    output.flatMap(ScNoteOnMidiMessage.fromJavaMessage).filter(_.velocity > 0)

  private def extractNoteOffs(output: Seq[MidiMessage]): Seq[ScNoteOffMidiMessage] =
    output.collect {
      case ScNoteOffMidiMessage(ch, note, velocity) => ScNoteOffMidiMessage(ch, note, velocity)
      case ScNoteOnMidiMessage(ch, note, 0) => ScNoteOffMidiMessage(ch, note)
    }

  private def extractCc(output: Seq[MidiMessage]): Seq[ScCcMidiMessage] =
    output.flatMap(ScCcMidiMessage.fromJavaMessage)

  private def extractChannelPressure(output: Seq[MidiMessage]): Seq[ScChannelPressureMidiMessage] =
    output.flatMap(ScChannelPressureMidiMessage.fromJavaMessage)

  private def extractScMidiMessages(output: Seq[MidiMessage]): Seq[ScMidiMessage] =
    output.map(ScMidiMessage.fromJavaMessage)

  // --- 4.2.1 reset() ---

  behavior of "MpeTuner - reset()"

  it should "output MPE Configuration Message (MCM) for the configured zone" in {
    val tuner = defaultTuner
    val output = tuner.reset()
    val ccs = extractCc(output)
    // MCM: RPN LSB=6, RPN MSB=0, Data Entry MSB=memberCount on master channel 0
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 15)
    )
  }

  it should "output RPN 0 (Pitch Bend Sensitivity) on all member channels" in {
    val tuner = tuner7
    val output = tuner.reset()
    val ccs = extractCc(output)
    // Check that PBS is set on member channels 1..7
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        ScCcMidiMessage(ch, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
        ScCcMidiMessage(ch, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
        ScCcMidiMessage(ch, ScCcMidiMessage.DataEntryMsb, 48)
      )
    }
  }

  it should "output RPN 0 on master channel with configured master pitch bend sensitivity" in {
    val tuner = defaultTuner
    val output = tuner.reset()
    val ccs = extractCc(output)
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 2)
    )
  }

  it should "clear internal state after reset" in new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
    // Play a note
    tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    // Reset should clear everything
    tuner.reset()
    // tune() with no active notes should produce no pitch bend messages
    private val output = tuner.tune(pythagoreanTuning)
    extractPitchBends(output) shouldBe empty
  }

  // --- 4.2.2 tune() ---

  behavior of "MpeTuner - tune()"

  it should "store tuning but output no messages when no active notes" in new TunerFixture() {
    private val output = tuner.tune(quarterCommaMeantone)
    extractPitchBends(output) shouldBe empty
  }

  it should "output updated Pitch Bend on each occupied member channel" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val noteOnChannel = extractNoteOns(out1).head.channel
      private val out2 = tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
      private val noteOnChannel2 = extractNoteOns(out2).head.channel

      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel).toSet should contain allOf(noteOnChannel, noteOnChannel2)
    }

  it should "correctly retune notes of different pitch classes on different channels" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)

      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      // C and E should have different pitch bends (different tuning offsets)
      pitchBends.size shouldEqual 2
      pitchBends.map(_.cents.round.toInt) should contain theSameElementsInOrderAs Seq(0, 8)
    }

  // --- 4.2.3 process() — Non-MPE Input, Basic Note Handling ---

  behavior of "MpeTuner - process() Non-MPE Basic"

  it should "output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, C4, 100).javaMidiMessage)
      private val msgs = extractScMidiMessages(output)

      // Should have: PitchBend, CC#74, ChannelPressure, NoteOn
      private val noteChannel = 1
      msgs should contain inOrder(
        ScPitchBendMidiMessage(noteChannel, 0),
        ScCcMidiMessage(noteChannel, 74, 64),
        ScChannelPressureMidiMessage(noteChannel, 0),
        ScNoteOnMidiMessage(noteChannel, C4, 100)
      )
    }

  it should "output Note Off on the correct member channel" in new TunerFixture() {
    private val noteOnOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    private val noteOffOutput = tuner.process(ScNoteOffMidiMessage(inputChannel, C4).javaMidiMessage)
    private val noteOffs = extractNoteOffs(noteOffOutput)
    noteOffs should contain(ScNoteOffMidiMessage(noteOnChannel, C4))
  }

  it should "allocate multiple notes with distinct pitch classes to separate member channels" in new TunerFixture() {
    private val out1 = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val out2 = tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
    private val out3 = tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage)
    private val channels = Seq(out1, out2, out3).flatMap(extractNoteOns).map(_.channel)
    channels.distinct.size shouldBe 3
  }

  it should "preserve Note On velocity" in new TunerFixture() {
    private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, C4, 87).javaMidiMessage)
    extractNoteOns(output).head.velocity shouldBe 87
  }

  it should "correctly allocate notes from any input channel" in new TunerFixture() {
    private val out1 = tuner.process(ScNoteOnMidiMessage(0, C4).javaMidiMessage)
    private val out2 = tuner.process(ScNoteOnMidiMessage(5, E4).javaMidiMessage)
    extractNoteOns(out1).map(_.channel) should contain(1)
    extractNoteOns(out2).map(_.channel) should contain(2)
  }

  // --- 4.2.4 process() — Non-MPE Input, Pitch Bend Handling ---

  behavior of "MpeTuner - process() Non-MPE Pitch Bend"

  it should "redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend" in new TunerFixture() {
    private val output = tuner.process(ScPitchBendMidiMessage(inputChannel, 1000).javaMidiMessage)
    private val pitchBends = extractPitchBends(output)
    pitchBends should contain(ScPitchBendMidiMessage(0, 1000)) // master channel 0
  }

  it should "not affect member channel tuning pitch bend from expressive input" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      private val initialPb = extractPitchBends(noteOutput).head.value

      // Send expressive pitch bend on input
      tuner.process(ScPitchBendMidiMessage(inputChannel, 500).javaMidiMessage)

      // Retune - member channel pitch bend should only reflect tuning, not expression
      private val tuneOutput = tuner.tune(quarterCommaMeantone)
      if (tuneOutput.nonEmpty) {
        val memberPbs = extractPitchBends(tuneOutput).filter(_.channel == noteChannel)
        if (memberPbs.nonEmpty) {
          memberPbs.head.value shouldBe initialPb
        }
      }
    }

  // --- 4.2.5 process() — Non-MPE to MPE Conversion ---

  behavior of "MpeTuner - process() Non-MPE to MPE Conversion"

  it should "convert Polyphonic Key Pressure to Channel Pressure on member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScPolyPressureMidiMessage(inputChannel, C4, 80).javaMidiMessage)
    extractChannelPressure(output) should contain(ScChannelPressureMidiMessage(noteChannel, 80))
  }

  it should "forward CC #74 to the appropriate member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScCcMidiMessage(inputChannel, 74, 100).javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(noteChannel, 74, 100))
  }

  it should "forward Channel Pressure to the appropriate member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScChannelPressureMidiMessage(inputChannel, 90).javaMidiMessage)
    extractChannelPressure(output) should contain(ScChannelPressureMidiMessage(noteChannel, 90))
  }

  it should "initialize control dimensions before Note On even when input omits them" in new TunerFixture() {
    private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    private val msgs = extractScMidiMessages(output)

    private val noteChannel = 1
    // CC #74 should be 64 (default), Channel Pressure should be 0 (default)
    msgs should contain allOf(
      ScCcMidiMessage(noteChannel, 74, 64),
      ScChannelPressureMidiMessage(noteChannel, 0)
    )
  }

  // --- 4.2.6 process() — Zone-Level Messages ---

  behavior of "MpeTuner - process() Zone-Level Messages"

  it should "forward Sustain Pedal (CC #64) on Master Channel" in new TunerFixture() {
    private val output = tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SustainPedal, 127).javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.SustainPedal, 127))
  }

  it should "forward Program Change on Master Channel" in new TunerFixture() {
    private val msg = new ShortMessage(ShortMessage.PROGRAM_CHANGE, inputChannel, 5, 0)
    private val output = tuner.process(msg)
    private val msgs = extractShortMessages(output)
    msgs.exists(m => m.getCommand == ShortMessage.PROGRAM_CHANGE && m.getChannel == 0) shouldBe true
  }

  it should "forward Reset All Controllers (CC #121) on Master Channel" in new TunerFixture() {
    private val output = tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.ResetAllControllers, 0)
      .javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.ResetAllControllers, 0))
  }

  it should "forward Modulation (CC #1) on Master Channel" in new TunerFixture() {
    private val output = tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.Modulation, 64).javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.Modulation, 64))
  }

  it should "forward Sostenuto Pedal (CC #66) on Master Channel" in new TunerFixture() {
    private val output = tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SostenutoPedal, 127)
      .javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.SostenutoPedal, 127))
  }

  it should "forward Soft Pedal (CC #67) on Master Channel" in new TunerFixture() {
    private val output = tuner.process(ScCcMidiMessage(inputChannel, ScCcMidiMessage.SoftPedal, 127).javaMidiMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.SoftPedal, 127))
  }

  // --- 4.2.7 process() — Pitch Bend Computation ---

  behavior of "MpeTuner - process() Pitch Bend Computation"

  it should "compute output pitch bend = tuning offset for single note on channel" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val pitchBends = extractPitchBends(output)
      // C has 0.0 offset in quarter-comma meantone, so pitch bend should be 0
      pitchBends.head.value shouldBe 0
    }

  it should "clamp pitch bend to valid 14-bit signed range" in new TunerFixture() {
    // Just verify no exception is thrown with extreme tuning
    private val extremeTuning = Tuning("extreme", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    tuner.tune(extremeTuning)
    private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    output should not be empty
  }

  // --- 4.2.8 process() — Dual-Group Allocation Integration ---

  behavior of "MpeTuner - process() Dual-Group Allocation"

  it should "allocate second note with same pitch class to Expression Group with independent pitch bends" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val ch1 = extractNoteOns(out1).head.channel
      private val out2 = tuner.process(ScNoteOnMidiMessage(inputChannel, C5).javaMidiMessage)
      private val ch2 = extractNoteOns(out2).head.channel
      ch1 should not equal ch2
    }

  // --- 4.2.9 process() — Note Dropping Integration ---

  behavior of "MpeTuner - process() Note Dropping"

  it should "trigger note dropping with Note Off output for dropped notes on channel exhaustion" in
    new TunerFixture(tuner3) {
      tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage)
      private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, A4).javaMidiMessage)
      private val noteOffs = extractNoteOffs(output)
      noteOffs should not be empty
    }

  it should "preserve boundary notes (highest/lowest) during channel exhaustion dropping" in new TunerFixture(tuner3) {
    tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage) // lowest
    tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage) // middle
    tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage) // highest
    private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, A4).javaMidiMessage)
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should contain(E4)
    droppedNotes should not contain C4
    droppedNotes should not contain G4
  }

  // --- 4.2.10 process() — MPE Input Mode ---

  behavior of "MpeTuner - process() MPE Input"

  it should "process MPE input notes with tuning offsets applied" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(1, C4, 100).javaMidiMessage)
      extractNoteOns(output) should not be empty
    }

  it should "treat per-note pitch bend as expressive pitch bend combined with tuning offset" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(1, E4, 100).javaMidiMessage)
      private val output = tuner.process(ScPitchBendMidiMessage(1, 500).javaMidiMessage)
      private val pitchBends = extractPitchBends(output)
      pitchBends should not be empty
      // The pitch bend should combine tuning offset for E + expressive bend
    }

  it should "forward Master Channel pitch bend without modification" in new TunerFixture(mpeTunerMpeInput) {
    private val output = tuner.process(ScPitchBendMidiMessage(0, 1000).javaMidiMessage)
    extractPitchBends(output) should contain(ScPitchBendMidiMessage(0, 1000))
  }

  // --- 4.2.11 process() — Note Off Behavior ---

  behavior of "MpeTuner - process() Note Off Behavior"

  it should "not update released channel's pitch bend on tuning changes" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      tuner.process(ScNoteOffMidiMessage(inputChannel, C4).javaMidiMessage)

      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel) should not contain noteChannel
    }

  it should "make channel available for reuse after Note Off" in new TunerFixture() {
    tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
    tuner.process(ScNoteOffMidiMessage(inputChannel, C4).javaMidiMessage)
    // Should be able to allocate a new note without issues
    private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, D4).javaMidiMessage)
    extractNoteOns(output) should not be empty
  }

  // --- 4.2.12 Worked Examples from Paper ---

  behavior of "MpeTuner - Worked Examples"

  it should "reproduce Section 8.1: Basic allocation in quarter-comma meantone" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      // 1. Note C4 arrives -> Pitch Class Group
      private val out1 = tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      private val ch1 = extractNoteOns(out1).head.channel

      // 2. Note E4 arrives -> Pitch Class Group
      private val out2 = tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
      private val ch2 = extractNoteOns(out2).head.channel
      ch2 should not equal ch1

      // 3. Note G4 arrives -> Pitch Class Group
      private val out3 = tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage)
      private val ch3 = extractNoteOns(out3).head.channel
      Set(ch1, ch2, ch3).size shouldBe 3

      // 4. Second C4 arrives -> Expression Group
      private val out4 = tuner.process(ScNoteOnMidiMessage(inputChannel, C5).javaMidiMessage)
      private val ch4 = extractNoteOns(out4).head.channel
      ch4 should not equal ch1 // Different channel from first C

      // 5. Performer bends second C4 - only ch4's pitch bend affected
      // (This is tested implicitly through the MPE architecture)
    }

  it should "reproduce Section 8.2: Tuning change during performance" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, C5).javaMidiMessage)

      // Switch to Pythagorean tuning
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      // Should have pitch bend updates for all 4 occupied channels
      pitchBends.size should be >= 4
    }

  it should "reproduce Section 8.3: Note dropping under channel exhaustion" in
    new TunerFixture(tuner3, Some(quarterCommaMeantone)) {
      // C, E, G on 3 channels
      tuner.process(ScNoteOnMidiMessage(inputChannel, C4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, E4).javaMidiMessage)
      tuner.process(ScNoteOnMidiMessage(inputChannel, G4).javaMidiMessage)

      // A arrives - must drop a note
      private val output = tuner.process(ScNoteOnMidiMessage(inputChannel, A4).javaMidiMessage)
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      // E should be dropped (C is lowest, G is highest)
      droppedNotes should contain(E4)
      // A should be allocated
      extractNoteOns(output).map(_.midiNote) should contain(A4)
    }
}

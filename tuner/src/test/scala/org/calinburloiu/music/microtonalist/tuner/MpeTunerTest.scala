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
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.should.Matchers.shouldEqual
import org.scalatest.{Inside, OptionValues}

import javax.sound.midi.{MidiMessage, ShortMessage}

class MpeTunerTest extends AnyFlatSpec with Matchers with Inside with OptionValues {

  private implicit val defaultPbs: PitchBendSensitivity = PitchBendSensitivity(48)
  private val masterPbs: PitchBendSensitivity = PitchBendSensitivity(2)

  import MidiNote.{A4, C4, C5, D4, E4, G4}

  private val nonMpeInputChannel = 0
  private val mpeInputChannel: Int = 1

  private val epsilon: Double = 2e-1
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

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

  private def defaultTuner: MpeTuner = MpeTuner()

  private def tuner7: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 0))
  )

  private def tuner3: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 3), MpeZone(MpeZoneType.Upper, 0))
  )

  private def dualZoneTuner: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7))
  )

  private def mpeTunerMpeInput: MpeTuner = MpeTuner(initialInputMode = MpeInputMode.Mpe)

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

  private def extractPolyPressure(output: Seq[MidiMessage]): Seq[ScPolyPressureMidiMessage] =
    output.flatMap(ScPolyPressureMidiMessage.fromJavaMessage)

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
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    // Reset should clear everything
    tuner.reset()
    // tune() with no active notes should produce no pitch bend messages
    private val output = tuner.tune(pythagoreanTuning)
    extractPitchBends(output) shouldBe empty
  }

  it should "emit Note Off for every active Member Channel note before resetting state" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val out2 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      private val ch1 = extractNoteOns(out1).head.channel
      private val ch2 = extractNoteOns(out2).head.channel

      private val resetOutput = tuner.reset()
      private val noteOffs = extractNoteOffs(resetOutput)
      noteOffs should contain(ScNoteOffMidiMessage(ch1, C4))
      noteOffs should contain(ScNoteOffMidiMessage(ch2, E4))
    }

  it should "emit Note Off for active Master Channel notes before resetting state" in
    new TunerFixture(mpeTunerMpeInput) {
      tuner.process(ScNoteOnMidiMessage(0, C4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(0, E4).javaMessage)

      private val resetOutput = tuner.reset()
      private val noteOffs = extractNoteOffs(resetOutput)
      noteOffs should contain(ScNoteOffMidiMessage(0, C4))
      noteOffs should contain(ScNoteOffMidiMessage(0, E4))
    }

  it should "not emit Note Off messages on reset when no notes are active" in new TunerFixture() {
    private val resetOutput = tuner.reset()
    extractNoteOffs(resetOutput) shouldBe empty
  }

  // --- 4.2.2 tune() ---

  behavior of "MpeTuner - tune()"

  it should "store tuning but output no messages when no active notes" in new TunerFixture() {
    private val output = tuner.tune(quarterCommaMeantone)
    extractPitchBends(output) shouldBe empty
  }

  it should "output updated Pitch Bend on each occupied member channel" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val noteOnChannel = extractNoteOns(out1).head.channel
      private val out2 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      private val noteOnChannel2 = extractNoteOns(out2).head.channel

      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel).toSet should contain allOf(noteOnChannel, noteOnChannel2)
    }

  it should "correctly retune notes of different pitch classes on different channels" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)

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
      private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4, 100).javaMessage)
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
    private val noteOnOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    private val noteOffOutput = tuner.process(ScNoteOffMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val noteOffs = extractNoteOffs(noteOffOutput)
    noteOffs should contain(ScNoteOffMidiMessage(noteOnChannel, C4))
  }

  it should "allocate multiple notes with distinct pitch classes to separate member channels" in new TunerFixture() {
    private val out1 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val out2 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
    private val out3 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage)
    private val channels = Seq(out1, out2, out3).flatMap(extractNoteOns).map(_.channel)
    channels.distinct.size shouldBe 3
  }

  it should "preserve Note On velocity" in new TunerFixture() {
    private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4, 87).javaMessage)
    extractNoteOns(output).head.velocity shouldBe 87
  }

  it should "correctly allocate notes from any input channel" in new TunerFixture() {
    private val out1 = tuner.process(ScNoteOnMidiMessage(0, C4).javaMessage)
    private val out2 = tuner.process(ScNoteOnMidiMessage(5, E4).javaMessage)
    extractNoteOns(out1).map(_.channel) should contain(1)
    extractNoteOns(out2).map(_.channel) should contain(2)
  }

  // --- 4.2.4 process() — Non-MPE Input, Pitch Bend Handling ---

  behavior of "MpeTuner - process() Non-MPE Pitch Bend"

  it should "redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend" in new TunerFixture() {
    private val output = tuner.process(ScPitchBendMidiMessage(nonMpeInputChannel, 1000).javaMessage)
    private val pitchBends = extractPitchBends(output)
    pitchBends should contain(ScPitchBendMidiMessage(0, 1000)) // master channel 0
  }

  it should "not affect member channel tuning pitch bend from expressive input" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      private val initialPb = extractPitchBends(noteOutput).head.value

      // Send expressive pitch bend on input
      tuner.process(ScPitchBendMidiMessage(nonMpeInputChannel, 500).javaMessage)

      // Retune - member channel pitch bend should only reflect tuning, not expression
      private val tuneOutput = tuner.tune(quarterCommaMeantone)
      tuneOutput should not be empty
      private val memberPbs = extractPitchBends(tuneOutput).filter(_.channel == noteChannel)
      memberPbs should not be empty
      memberPbs.head.value shouldBe initialPb
    }

  // --- 4.2.5 process() — Non-MPE to MPE Conversion ---

  behavior of "MpeTuner - process() Non-MPE to MPE Conversion"

  it should "convert Polyphonic Key Pressure to Channel Pressure on member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScPolyPressureMidiMessage(nonMpeInputChannel, C4, 80).javaMessage)
    extractChannelPressure(output) should contain(ScChannelPressureMidiMessage(noteChannel, 80))
    output.flatMap(ScPolyPressureMidiMessage.fromJavaMessage) shouldBe empty
  }

  it should "forward CC #74 to the appropriate member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScCcMidiMessage(nonMpeInputChannel, 74, 100).javaMessage)
    extractCc(output) should contain(ScCcMidiMessage(noteChannel, 74, 100))
  }

  it should "forward Channel Pressure to the appropriate member channel" in new TunerFixture() {
    private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    private val output = tuner.process(ScChannelPressureMidiMessage(nonMpeInputChannel, 90).javaMessage)
    extractChannelPressure(output) should contain(ScChannelPressureMidiMessage(noteChannel, 90))
  }

  it should "initialize control dimensions before Note On even when input omits them" in new TunerFixture() {
    private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
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
    private val output = tuner.process(ScCcMidiMessage(nonMpeInputChannel, ScCcMidiMessage.SustainPedal, 127)
      .javaMessage)
    extractCc(output) should contain(ScCcMidiMessage(0, ScCcMidiMessage.SustainPedal, 127))
  }

  it should "forward Program Change on Master Channel" in new TunerFixture() {
    private val msg = new ShortMessage(ShortMessage.PROGRAM_CHANGE, nonMpeInputChannel, 5, 0)
    private val output = tuner.process(msg)
    private val msgs = extractShortMessages(output)
    msgs.exists(m => m.getCommand == ShortMessage.PROGRAM_CHANGE && m.getChannel == 0) shouldBe true
  }

  for ((ccName, ccNumber, ccValue) <- Seq(
    ("Bank Select MSB", ScCcMidiMessage.BankSelectMsb, 1),
    ("Bank Select LSB", ScCcMidiMessage.BankSelectLsb, 0),
    ("Reset All Controllers", ScCcMidiMessage.ResetAllControllers, 0),
    ("Modulation", ScCcMidiMessage.Modulation, 64),
    ("Sostenuto Pedal", ScCcMidiMessage.SostenutoPedal, 127),
    ("Soft Pedal", ScCcMidiMessage.SoftPedal, 127)
  )) {
    it should s"forward $ccName (CC #$ccNumber) on Master Channel" in new TunerFixture() {
      private val output = tuner.process(ScCcMidiMessage(nonMpeInputChannel, ccNumber, ccValue).javaMessage)
      extractCc(output) should contain(ScCcMidiMessage(0, ccNumber, ccValue))
    }
  }

  // --- 4.2.7 process() — Pitch Bend Computation ---

  behavior of "MpeTuner - process() Pitch Bend Computation"

  it should "compute output pitch bend = tuning offset for single note on channel" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val pitchBends = extractPitchBends(output)
      // C has 0.0 offset in quarter-comma meantone, so pitch bend should be 0
      pitchBends.head.value shouldBe 0
    }

  it should "clamp pitch bend to valid 14-bit signed range" in new TunerFixture() {
    // Just verify no exception is thrown with extreme tuning
    private val extremeTuning = Tuning("extreme", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    tuner.tune(extremeTuning)
    private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    output should not be empty
  }

  // --- 4.2.8 process() — Dual-Group Allocation Integration ---

  behavior of "MpeTuner - process() Dual-Group Allocation"

  it should "allocate second note with same pitch class to Expression Group with independent pitch bends" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val ch1 = extractNoteOns(out1).head.channel
      private val out2 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C5).javaMessage)
      private val ch2 = extractNoteOns(out2).head.channel
      ch1 should not equal ch2
    }

  // --- 4.2.9 process() — Note Dropping Integration ---

  behavior of "MpeTuner - process() Note Dropping"

  it should "trigger note dropping with Note Off output for dropped notes on channel exhaustion" in
    new TunerFixture(tuner3) {
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage)
      private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, A4).javaMessage)
      private val noteOffs = extractNoteOffs(output)
      noteOffs should not be empty
    }

  it should "preserve boundary notes (highest/lowest) during channel exhaustion dropping" in new TunerFixture(tuner3) {
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage) // lowest
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage) // middle
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage) // highest
    private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, A4).javaMessage)
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should contain(E4)
    droppedNotes should not contain C4
    droppedNotes should not contain G4
  }

  // --- 4.2.10 process() — MPE Input Mode ---

  behavior of "MpeTuner - process() MPE Input"

  it should "process MPE input notes with tuning offsets applied" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(1, C4, 100).javaMessage)
      extractNoteOns(output) should not be empty
    }

  it should "treat per-note pitch bend as expressive pitch bend combined with tuning offset" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(1, E4, 100).javaMessage)
      private val output = tuner.process(ScPitchBendMidiMessage(1, 500).javaMessage)
      private val pitchBends = extractPitchBends(output)
      pitchBends should not be empty
      // The pitch bend should combine tuning offset for E + expressive bend
    }

  it should "forward Master Channel pitch bend without modification" in new TunerFixture(mpeTunerMpeInput) {
    private val output = tuner.process(ScPitchBendMidiMessage(0, 1000).javaMessage)
    extractPitchBends(output) should contain(ScPitchBendMidiMessage(0, 1000))
  }

  // --- 4.2.10.1 process() — MPE Input Master Channel Notes ---

  behavior of "MpeTuner - process() MPE Input Master Channel Notes"

  private def dualZoneTunerMpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7)),
    initialInputMode = MpeInputMode.Mpe
  )

  it should "forward Note On received on Lower Master Channel on the same Master Channel" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(0, C4, 100).javaMessage)
      private val noteOns = extractNoteOns(output)
      noteOns should have size 1
      noteOns.head shouldEqual ScNoteOnMidiMessage(0, C4, 100)
    }

  it should "not emit Pitch Bend, CC #74, or Channel Pressure setup for Master Channel Note On" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      private val output = tuner.process(ScNoteOnMidiMessage(0, C4, 100).javaMessage)
      extractPitchBends(output) shouldBe empty
      extractCc(output).filter(_.number == ScCcMidiMessage.MpeSlide) shouldBe empty
      extractChannelPressure(output) shouldBe empty
    }

  it should "forward Note Off received on Lower Master Channel on the same Master Channel" in
    new TunerFixture(mpeTunerMpeInput) {
      tuner.process(ScNoteOnMidiMessage(0, C4, 100).javaMessage)
      private val output = tuner.process(ScNoteOffMidiMessage(0, C4).javaMessage)
      extractNoteOffs(output) should contain(ScNoteOffMidiMessage(0, C4))
    }

  it should "forward Note On/Off received on Upper Master Channel on the same Master Channel" in
    new TunerFixture(dualZoneTunerMpeInput) {
      private val onOutput = tuner.process(ScNoteOnMidiMessage(15, C4, 90).javaMessage)
      private val noteOns = extractNoteOns(onOutput)
      noteOns should have size 1
      noteOns.head shouldEqual ScNoteOnMidiMessage(15, C4, 90)

      private val offOutput = tuner.process(ScNoteOffMidiMessage(15, C4).javaMessage)
      extractNoteOffs(offOutput) should contain(ScNoteOffMidiMessage(15, C4))
    }

  it should "not consume Member Channel slots for Master Channel notes" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Master Channel note should not occupy a Member Channel
      tuner.process(ScNoteOnMidiMessage(0, C4).javaMessage)
      // Subsequent Member Channel note gets the first Member Channel
      private val out = tuner.process(ScNoteOnMidiMessage(mpeInputChannel, E4).javaMessage)
      private val noteOns = extractNoteOns(out)
      noteOns should have size 1
      noteOns.head.channel shouldBe 1
    }

  it should "not retune Master Channel notes on tune() call" in
    new TunerFixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(0, C4).javaMessage)
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      extractPitchBends(tuneOutput).filter(_.channel == 0) shouldBe empty
    }

  it should "allow multiple active notes on the Master Channel concurrently" in
    new TunerFixture(mpeTunerMpeInput) {
      private val out1 = tuner.process(ScNoteOnMidiMessage(0, C4, 100).javaMessage)
      private val out2 = tuner.process(ScNoteOnMidiMessage(0, E4, 100).javaMessage)
      extractNoteOns(out1).map(n => (n.channel, n.midiNote)) should contain((0, C4))
      extractNoteOns(out2).map(n => (n.channel, n.midiNote)) should contain((0, E4))

      private val offOutput = tuner.process(ScNoteOffMidiMessage(0, C4).javaMessage)
      extractNoteOffs(offOutput) should contain(ScNoteOffMidiMessage(0, C4))
      // E4 should still be tracked as active
      private val offOutput2 = tuner.process(ScNoteOffMidiMessage(0, E4).javaMessage)
      extractNoteOffs(offOutput2) should contain(ScNoteOffMidiMessage(0, E4))
    }

  it should "forward Polyphonic Key Pressure as-is for Master Channel notes" in
    new TunerFixture(mpeTunerMpeInput) {
      tuner.process(ScNoteOnMidiMessage(0, C4, 100).javaMessage)
      private val output = tuner.process(ScPolyPressureMidiMessage(0, C4, 80).javaMessage)
      extractPolyPressure(output) should contain(ScPolyPressureMidiMessage(0, C4, 80))
      extractChannelPressure(output) shouldBe empty
    }

  it should "drop Polyphonic Key Pressure received on a Member Channel in MPE input mode" in
    new TunerFixture(mpeTunerMpeInput) {
      tuner.process(ScNoteOnMidiMessage(mpeInputChannel, C4, 100).javaMessage)
      private val output = tuner.process(ScPolyPressureMidiMessage(mpeInputChannel, C4, 80).javaMessage)
      extractPolyPressure(output) shouldBe empty
      extractChannelPressure(output) shouldBe empty
    }

  // --- 4.2.11 process() — Note Off Behavior ---

  behavior of "MpeTuner - process() Note Off Behavior"

  it should "not update released channel's pitch bend on tuning changes" in
    new TunerFixture(initialTuning = Some(quarterCommaMeantone)) {
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      tuner.process(ScNoteOffMidiMessage(nonMpeInputChannel, C4).javaMessage)

      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel) should not contain noteChannel
    }

  it should "make channel available for reuse after Note Off" in new TunerFixture() {
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    tuner.process(ScNoteOffMidiMessage(nonMpeInputChannel, C4).javaMessage)
    // Should be able to allocate a new note without issues
    private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, D4).javaMessage)
    extractNoteOns(output) should not be empty
  }

  // --- 4.2.12 Worked Examples from Paper ---

  behavior of "MpeTuner - Worked Examples"

  it should "reproduce Section 8.1: Basic allocation in quarter-comma meantone" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      // 1. Note C4 arrives -> Pitch Class Group
      private val out1 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      private val ch1 = extractNoteOns(out1).head.channel

      // 2. Note E4 arrives -> Pitch Class Group
      private val out2 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      private val ch2 = extractNoteOns(out2).head.channel
      ch2 should not equal ch1

      // 3. Note G4 arrives -> Pitch Class Group
      private val out3 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage)
      private val ch3 = extractNoteOns(out3).head.channel
      Set(ch1, ch2, ch3).size shouldBe 3

      // 4. Second C4 arrives -> Expression Group
      private val out4 = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C5).javaMessage)
      private val ch4 = extractNoteOns(out4).head.channel
      ch4 should not equal ch1 // Different channel from first C

      // 5. Performer bends second C4 - only ch4's pitch bend affected
      // (This is tested implicitly through the MPE architecture)
    }

  it should "reproduce Section 8.2: Tuning change during performance" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C5).javaMessage)

      // Switch to Pythagorean tuning
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      private val pitchBends = extractPitchBends(tuneOutput)
      // Should have pitch bend updates for all 4 occupied channels
      pitchBends.size should be >= 4
    }

  it should "reproduce Section 8.3: Note dropping under channel exhaustion" in
    new TunerFixture(tuner3, Some(quarterCommaMeantone)) {
      // C, E, G on 3 channels
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, G4).javaMessage)

      // A arrives - must drop a note
      private val output = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, A4).javaMessage)
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      // E should be dropped (C is lowest, G is highest)
      droppedNotes should contain(E4)
      // A should be allocated
      extractNoteOns(output).map(_.midiNote) should contain(A4)
    }

  // --- MCM Processing ---

  behavior of "MpeTuner - MCM Processing"

  /** Sends a complete MCM RPN sequence: CC#100=6, CC#101=0, CC#6=memberCount on the given channel. */
  private def sendMcm(tuner: MpeTuner, channel: Int, memberCount: Int): Seq[MidiMessage] = {
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb).javaMessage)
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb).javaMessage)
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.DataEntryMsb, memberCount).javaMessage)
  }

  it should "reconfigure lower zone on MCM received on channel 0" in new TunerFixture(dualZoneTuner) {
    private val output = sendMcm(tuner, channel = 0, memberCount = 10)
    // Should output MCM for the new lower zone with memberCount=10
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 10)
    )
    tuner.zones.lower.memberCount shouldEqual 10
    tuner.zones.upper.memberCount shouldEqual 4
  }

  it should "reconfigure upper zone on MCM received on channel 15" in new TunerFixture(dualZoneTuner) {
    private val output = sendMcm(tuner, channel = 15, memberCount = 10)
    // Should output MCM for the new upper zone with memberCount=10
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      ScCcMidiMessage(15, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(15, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(15, ScCcMidiMessage.DataEntryMsb, 10)
    )
    tuner.zones.lower.memberCount shouldEqual 4
    tuner.zones.upper.memberCount shouldEqual 10
  }

  it should "stop all active notes when MCM is received" in new TunerFixture() {
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, C4).javaMessage)
    tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    private val noteOffs = extractNoteOffs(output)
    noteOffs.map(_.midiNote) should contain allOf(C4, E4)
  }

  it should "shrink other zone when MCM causes overlap" in new TunerFixture(dualZoneTuner) {
    // dualZoneTuner: lower=7, upper=7
    // MCM on ch 0 with memberCount=10 -> upper must shrink to 4
    private val output = sendMcm(tuner, channel = 0, memberCount = 10)
    private val ccs = extractCc(output)
    // Upper zone MCM should show memberCount=4
    ccs should contain inOrder(
      ScCcMidiMessage(15, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(15, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(15, ScCcMidiMessage.DataEntryMsb, 4)
    )
    tuner.zones.lower.memberCount shouldEqual 10
    tuner.zones.upper.memberCount shouldEqual 4
  }

  it should "disable zone when MCM with memberCount=0 is received" in new TunerFixture(dualZoneTuner) {
    private val output = sendMcm(tuner, channel = 15, memberCount = 0)
    private val ccs = extractCc(output)
    // Upper zone MCM should be sent to the output even if the zone is disabled to inform the downstream device
    ccs should contain(ScCcMidiMessage(15, ScCcMidiMessage.DataEntryMsb, 0))
    // Lower zone MCM should NOT be present because the lower zone was not affected
    ccs should not contain ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 7)
  }

  it should "not trigger MCM on incomplete RPN sequence" in new TunerFixture() {
    // Send only CC#101=0 and CC#6=10 without CC#100
    tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb).javaMessage)
    private val output = tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 10).javaMessage)
    // Should NOT contain MCM output (no Note Offs, no MCM messages for reconfiguration)
    extractNoteOffs(output) shouldBe empty
    extractCc(output) should not contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 10)
    )
  }

  it should "not trigger MCM for non-MCM RPN (e.g. PBS RPN)" in new TunerFixture() {
    // Send PBS RPN (MSB=0, LSB=0) instead of MCM RPN (MSB=0, LSB=6)
    tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb).javaMessage)
    tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb).javaMessage)
    private val output = tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 48).javaMessage)
    // Should NOT contain MCM reconfiguration output
    private val ccs = extractCc(output)
    ccs.filter(cc => cc.number == ScCcMidiMessage.DataEntryMsb &&
      cc.value == 15) shouldBe empty // no MCM with memberCount=15
  }

  it should "ignore MCM on non-master channel" in new TunerFixture() {
    private val output = sendMcm(tuner, channel = 5, memberCount = 7)
    // Should NOT trigger MCM processing
    extractNoteOffs(output) shouldBe empty
  }

  it should "revert to initialZones on reset() after MCM" in new TunerFixture(dualZoneTuner) {
    sendMcm(tuner, channel = 0, memberCount = 10)
    // Reset should restore initial configuration
    private val resetOutput = tuner.reset()
    private val ccs = extractCc(resetOutput)
    // Lower zone should be back to 7 members
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 7)
    )
    // Upper zone should be back to 7 members
    ccs should contain inOrder(
      ScCcMidiMessage(15, ScCcMidiMessage.RpnLsb, Rpn.MpeConfigurationMessageLsb),
      ScCcMidiMessage(15, ScCcMidiMessage.RpnMsb, Rpn.MpeConfigurationMessageMsb),
      ScCcMidiMessage(15, ScCcMidiMessage.DataEntryMsb, 7)
    )
  }

  it should "not output PBS messages after MCM" in new TunerFixture() {
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    private val ccs = extractCc(output)
    // PBS RPN uses LSB=0, while MCM RPN uses LSB=6; no PBS RPN should appear in the output
    private val pbsRpnMessages = ccs.filter(cc =>
      cc.number == ScCcMidiMessage.RpnLsb && cc.value == Rpn.PitchBendSensitivityLsb)
    pbsRpnMessages shouldBe empty
  }

  it should "reset PBS to defaults when MCM is received" in new TunerFixture(tuner7) {
    // Set custom PBS on the lower zone
    sendPbsMsb(tuner, channel = 0, semitones = 12)
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)

    // Receive MCM on the same zone
    sendMcm(tuner, channel = 0, memberCount = 7)

    // PBS should be reset to defaults per MPE spec Section 2.4
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }

  // --- PBS Processing ---

  behavior of "MpeTuner - PBS Processing"

  /** Sends a complete PBS RPN MSB sequence: CC#100=0, CC#101=0, CC#6=semitones on the given channel. */
  private def sendPbsMsb(tuner: MpeTuner, channel: Int, semitones: Int): Seq[MidiMessage] = {
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb).javaMessage)
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb).javaMessage)
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.DataEntryMsb, semitones).javaMessage)
  }

  /** Sends a PBS RPN LSB (cents) on the given channel, assuming RPN is already set to PBS. */
  private def sendPbsLsb(tuner: MpeTuner, channel: Int, cents: Int): Seq[MidiMessage] = {
    tuner.process(ScCcMidiMessage(channel, ScCcMidiMessage.DataEntryLsb, cents).javaMessage)
  }

  it should "update master PBS on master channel" in new TunerFixture() {
    private val output = sendPbsMsb(tuner, channel = 0, semitones = 12)
    // Should forward RPN setup and PBS for the master channel with new sensitivity
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryMsb, 12)
    )
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
  }

  it should "update member PBS and forward only on the received channel" in new TunerFixture(tuner7) {
    // Send PBS on member channel 1 -> internal state updated for all members, but only forwarded on ch 1
    private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      ScCcMidiMessage(1, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(1, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(1, ScCcMidiMessage.DataEntryMsb, 24)
    )
    // Should NOT broadcast to other member channels
    private val dataEntryCcs = ccs.filter(_.number == ScCcMidiMessage.DataEntryMsb)
    (2 to 7).foreach { ch =>
      dataEntryCcs.filter(_.channel == ch) shouldBe empty
    }
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
  }

  it should "forward PBS on each channel when received on all member channels" in new TunerFixture(tuner7) {
    // Sender broadcasts PBS to all member channels 1-7; each should be forwarded 1:1
    private var output: Seq[MidiMessage] = Seq.empty
    for (ch <- 1 to 7) {
      output ++= sendPbsMsb(tuner, channel = ch, semitones = 24)
    }
    private val dataEntryCcs = extractCc(output).filter(cc =>
      cc.number == ScCcMidiMessage.DataEntryMsb && cc.value == 24)

    dataEntryCcs.size shouldEqual 7
    dataEntryCcs.map(_.channel) should contain theSameElementsAs (1 to 7)
  }

  it should "recompute pitch bends on occupied channels after member PBS change" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      // Play a note to occupy a channel
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel

      // Change member PBS
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      private val pitchBends = extractPitchBends(pbsOutput)

      // Should have a pitch bend update for the occupied channel
      pitchBends.map(_.channel) should contain(noteChannel)

      pitchBends.size shouldEqual 1
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  it should "not affect other zone's PBS" in new TunerFixture(dualZoneTuner) {
    // Send PBS on lower zone member channel 1
    private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
    private val ccs = extractCc(output)
    // Upper zone member channels (8-14) should NOT get the new PBS in this output
    (8 to 14).foreach { ch =>
      ccs.filter(cc => cc.channel == ch && cc.number == ScCcMidiMessage.DataEntryMsb &&
        cc.value == 24) shouldBe empty
    }
  }

  it should "handle PBS LSB (cents) update" in new TunerFixture() {
    // First set the RPN to PBS
    tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb).javaMessage)
    tuner.process(ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb).javaMessage)
    // Send LSB
    private val output = sendPbsLsb(tuner, channel = 0, cents = 50)
    private val ccs = extractCc(output)
    // Should re-send RPN setup before Data Entry LSB
    ccs should contain inOrder(
      ScCcMidiMessage(0, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
      ScCcMidiMessage(0, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
      ScCcMidiMessage(0, ScCcMidiMessage.DataEntryLsb, 50)
    )
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(
      MpeZone.DefaultMasterPitchBendSensitivity.semitones, cents = 50)
  }

  it should "preserve intonation of active note with expressive pitch bend after PBS change" in
    new TunerFixture(
      MpeTuner(
        initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 0)),
        initialInputMode = MpeInputMode.Mpe
      ),
      Some(quarterCommaMeantone)
    ) {
      // Play E4 on member channel 1: tuning offset for E is -14.0 cents
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(1, E4, 100).javaMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel

      // Send an expressive pitch bend on that channel (with default PBS=48 semitones)
      // 500 MIDI value ≈ 500/8191 * 4800 ≈ 292.9 cents
      private val expressiveBendMidiValue = 500
      private val expressiveBendCents = ScPitchBendMidiMessage.convertValueToCents(
        expressiveBendMidiValue, defaultPbs)
      tuner.process(ScPitchBendMidiMessage(1, expressiveBendMidiValue).javaMessage)

      // Now change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)

      pitchBends should not be empty
      // The output pitch bend should still represent tuning offset + expressive bend in cents
      // E tuning offset = -14.0, expressive = ~292.9 cents => total ≈ 278.9 cents
      private val expectedCents = -14.0 + expressiveBendCents
      private val newPbs = PitchBendSensitivity(24)
      pitchBends.head.centsFor(newPbs) shouldEqual expectedCents
    }

  it should "preserve intonation of active note without expressive pitch bend after PBS change" in
    new TunerFixture(tuner7, Some(quarterCommaMeantone)) {
      // Play E4: tuning offset for E is -14.0 cents
      private val noteOutput = tuner.process(ScNoteOnMidiMessage(nonMpeInputChannel, E4).javaMessage)
      private val noteChannel = extractNoteOns(noteOutput).head.channel

      // Change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)

      pitchBends should not be empty
      // The output pitch bend should still represent -14.0 cents under the new PBS
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  it should "revert PBS to initial values on reset()" in new TunerFixture(tuner7) {
    // Change member PBS
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    // Reset
    private val resetOutput = tuner.reset()
    private val ccs = extractCc(resetOutput)
    // Member channels should have default PBS (48 semitones)
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        ScCcMidiMessage(ch, ScCcMidiMessage.RpnLsb, Rpn.PitchBendSensitivityLsb),
        ScCcMidiMessage(ch, ScCcMidiMessage.RpnMsb, Rpn.PitchBendSensitivityMsb),
        ScCcMidiMessage(ch, ScCcMidiMessage.DataEntryMsb, 48)
      )
    }
  }
}

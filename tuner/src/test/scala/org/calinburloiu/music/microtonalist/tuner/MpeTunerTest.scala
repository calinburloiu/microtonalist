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
import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.should.Matchers.shouldEqual
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Inside, OptionValues}

import javax.sound.midi.{MidiMessage, ShortMessage}

class MpeTunerTest extends AnyFlatSpec with Matchers with Inside with OptionValues with TableDrivenPropertyChecks {

  private implicit val defaultPbs: PitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity
  private val masterPbs: PitchBendSensitivity = MpeZone.DefaultMasterPitchBendSensitivity

  import MidiNote.{A4, C4, C5, D4, E4, G4}

  private val C3: MidiNote = MidiNote(C4 - 12)
  private val E5: MidiNote = MidiNote(E4 + 12)

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

  private def tuner7MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 0)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def tuner3MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 3), MpeZone(MpeZoneType.Upper, 0)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def dualZoneTunerMpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 7)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def mpeTunerMpeInput: MpeTuner = MpeTuner(initialInputMode = MpeInputMode.Mpe)

  private def extractShortMessages(output: Seq[MidiMessage]): Seq[ShortMessage] =
    output.collect { case sm: ShortMessage => sm }

  private def extractPitchBends(output: Seq[MidiMessage]): Seq[PitchBendScMidiMessage] =
    output.map(_.asScala).collect { case m: PitchBendScMidiMessage => m }

  private def extractNoteOns(output: Seq[MidiMessage]): Seq[NoteOnScMidiMessage] =
    output.map(_.asScala).collect { case m: NoteOnScMidiMessage => m }.filter(_.velocity > 0)

  private def extractNoteOffs(output: Seq[MidiMessage]): Seq[NoteOffScMidiMessage] =
    output.map(_.asScala).collect {
      case NoteOffScMidiMessage(ch, note, velocity) => NoteOffScMidiMessage(ch, note, velocity)
      case NoteOnScMidiMessage(ch, note, 0) => NoteOffScMidiMessage(ch, note)
    }

  private def extractCc(output: Seq[MidiMessage]): Seq[CcScMidiMessage] =
    output.map(_.asScala).collect { case m: CcScMidiMessage => m }

  private def extractChannelPressure(output: Seq[MidiMessage]): Seq[ChannelPressureScMidiMessage] =
    output.map(_.asScala).collect { case m: ChannelPressureScMidiMessage => m }

  private def extractPolyPressure(output: Seq[MidiMessage]): Seq[PolyPressureScMidiMessage] =
    output.map(_.asScala).collect { case m: PolyPressureScMidiMessage => m }

  private def extractScMidiMessages(output: Seq[MidiMessage]): Seq[ScMidiMessage] =
    output.map(_.asScala)

  private abstract class Fixture(protected val tuner: MpeTuner = defaultTuner,
                                 initialTuning: Option[Tuning] = None) {
    initialTuning.foreach(tuner.tune)

    def noteOn(channel: Int, note: MidiNote, velocity: Int = 64,
               pbCents: Option[Double] = None,
               pressure: Option[Int] = None,
               slide: Option[Int] = None): Seq[MidiMessage] = {
      val pre = pbCents.toSeq.flatMap(c => pitchBend(channel, c)) ++
        pressure.toSeq.flatMap(p => this.pressure(channel, p)) ++
        slide.toSeq.flatMap(s => this.slide(channel, s))
      pre ++ tuner.process(NoteOnScMidiMessage(channel, note, velocity).asJava)
    }

    def noteOff(channel: Int, note: MidiNote, velocity: Int = 64): Seq[MidiMessage] =
      tuner.process(NoteOffScMidiMessage(channel, note, velocity).asJava)

    def pitchBend(channel: Int, cents: Double): Seq[MidiMessage] = {
      val value = PitchBendScMidiMessage.convertCentsToValue(cents, defaultPbs)
      tuner.process(PitchBendScMidiMessage(channel, value).asJava)
    }

    def pressure(channel: Int, value: Int): Seq[MidiMessage] =
      tuner.process(ChannelPressureScMidiMessage(channel, value).asJava)

    def slide(channel: Int, value: Int): Seq[MidiMessage] = {
      tuner.process(CcScMidiMessage(channel, ScMidiCc.MpeSlide, value).asJava)
    }
  }

  // --- 4.2.1 reset() ---

  behavior of "MpeTuner - reset()"

  it should "output MPE Configuration Message (MCM) for the configured zone" in new Fixture(defaultTuner) {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output)
    // MCM: RPN LSB=6, RPN MSB=0, Data Entry MSB=memberCount on master channel 0
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 15)
    )
  }

  it should "output RPN 0 (Pitch Bend Sensitivity) on all member channels" in new Fixture(tuner7) {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output)
    // Check that PBS is set on member channels 1..7
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        CcScMidiMessage(ch, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(ch, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(ch, ScMidiCc.DataEntryMsb, 48)
      )
    }
  }

  it should "output RPN 0 on master channel with configured master pitch bend sensitivity" in new Fixture {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 2)
    )
  }

  it should "clear internal state after reset" in new Fixture(initialTuning = Some(quarterCommaMeantone)) {
    // Given
    // Play a note
    noteOn(nonMpeInputChannel, C4)
    // When
    // Reset should clear everything
    tuner.reset()
    // Then
    // tune() with no active notes should produce no pitch bend messages
    private val output = tuner.tune(pythagoreanTuning)
    extractPitchBends(output) shouldBe empty
  }

  it should "emit Note Off for every active Member Channel note before resetting state" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // Given
      private val out1 = noteOn(nonMpeInputChannel, C4)
      private val out2 = noteOn(nonMpeInputChannel, E4)
      private val ch1 = extractNoteOns(out1).head.channel
      private val ch2 = extractNoteOns(out2).head.channel
      // When
      private val resetOutput = tuner.reset()
      // Then
      private val noteOffs = extractNoteOffs(resetOutput)
      noteOffs should contain(NoteOffScMidiMessage(ch1, C4))
      noteOffs should contain(NoteOffScMidiMessage(ch2, E4))
    }

  it should "emit Note Off for active Master Channel notes before resetting state" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(0, C4)
      noteOn(0, E4)
      // When
      private val resetOutput = tuner.reset()
      // Then
      private val noteOffs = extractNoteOffs(resetOutput)
      noteOffs should contain(NoteOffScMidiMessage(0, C4))
      noteOffs should contain(NoteOffScMidiMessage(0, E4))
    }

  it should "not emit Note Off messages on reset when no notes are active" in new Fixture {
    // When
    private val resetOutput = tuner.reset()
    // Then
    extractNoteOffs(resetOutput) shouldBe empty
  }

  // --- 4.2.2 tune() ---

  behavior of "MpeTuner - tune()"

  it should "store tuning but output no messages when no active notes" in new Fixture {
    // When
    private val output = tuner.tune(quarterCommaMeantone)
    // Then
    tuner.tuning shouldEqual quarterCommaMeantone
    extractPitchBends(output) shouldBe empty
  }

  it should "output updated Pitch Bend on each occupied member channel" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // Given
      private val out1 = noteOn(nonMpeInputChannel, C4)
      private val noteOnChannel = extractNoteOns(out1).head.channel
      private val out2 = noteOn(nonMpeInputChannel, E4)
      private val noteOnChannel2 = extractNoteOns(out2).head.channel
      // When
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel).toSet should contain allOf(noteOnChannel, noteOnChannel2)
    }

  it should "correctly retune notes of different pitch classes on different channels" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // Given
      noteOn(nonMpeInputChannel, C4)
      noteOn(nonMpeInputChannel, E4)
      // When
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)
      // C and E should have different pitch bends (different tuning offsets)
      pitchBends.size shouldEqual 2
      pitchBends.map(_.cents.round.toInt) should contain theSameElementsInOrderAs Seq(0, 8)
    }

  it should "output updated Pitch Bend on each occupied member channel in MPE input mode" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val out1 = noteOn(1, C4)
      private val noteOnChannel = extractNoteOns(out1).head.channel
      private val out2 = noteOn(2, E4)
      private val noteOnChannel2 = extractNoteOns(out2).head.channel
      // When
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends.map(_.channel).toSet should contain allOf(noteOnChannel, noteOnChannel2)
      // C and E should reflect pythagorean tuning offsets (0.0, 8.0)
      pitchBends.size shouldEqual 2
      pitchBends.map(_.cents.round.toInt) should contain theSameElementsInOrderAs Seq(0, 8)
    }

  it should "recompute pitch bend = new tuning offset + current expressive pitch bend on each occupied channel" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 in quarter-comma meantone, +8.0 in pythagorean
      private val noteOutE = noteOn(1, E4)
      private val chE = extractNoteOns(noteOutE).head.channel
      private val noteOutG = noteOn(2, G4)
      private val chG = extractNoteOns(noteOutG).head.channel

      // Apply small expressive pitch bends (under the high-bend threshold) per note in MPE mode
      private val eExprCents = 20.0
      private val gExprCents = -30.0
      pitchBend(1, eExprCents)
      pitchBend(2, gExprCents)

      // When
      // Switch tuning — output PB on each channel must combine new tuning offset + expressive bend.
      // Compare on MIDI values (not cents) to avoid resolution noise from the cents↔value roundtrip.
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pbByChannel = extractPitchBends(tuneOutput).map(pb => pb.channel -> pb.value).toMap
      private val expectedE = PitchBendScMidiMessage.convertCentsToValue(8.0 + eExprCents, defaultPbs)
      private val expectedG = PitchBendScMidiMessage.convertCentsToValue(2.0 + gExprCents, defaultPbs)
      pbByChannel(chE) shouldBe expectedE
      pbByChannel(chG) shouldBe expectedG
    }

  // --- 4.2.3 process() — Non-MPE Input, Basic Note Handling ---

  behavior of "MpeTuner - process() Non-MPE Basic"

  it should "output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(nonMpeInputChannel, C4, 100)
      // Then
      private val msgs = extractScMidiMessages(output)
      private val noteChannel = extractNoteOns(output).head.channel

      // Should have: PitchBend, CC#74, ChannelPressure, NoteOn
      msgs should contain inOrder(
        PitchBendScMidiMessage(noteChannel, 0),
        CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 64),
        ChannelPressureScMidiMessage(noteChannel, 0),
        NoteOnScMidiMessage(noteChannel, C4, 100)
      )
    }

  it should "output Note Off on the correct member channel" in new Fixture {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4)
    // Then
    private val noteOffs = extractNoteOffs(noteOffOutput)
    noteOffs should contain(NoteOffScMidiMessage(noteOnChannel, C4))
  }

  it should "allocate multiple notes with distinct pitch classes to separate member channels" in new Fixture {
    // When
    private val out1 = noteOn(nonMpeInputChannel, C4)
    private val out2 = noteOn(nonMpeInputChannel, E4)
    private val out3 = noteOn(nonMpeInputChannel, G4)
    // Then
    private val channels = Seq(out1, out2, out3).flatMap(extractNoteOns).map(_.channel)
    channels.distinct.size shouldBe 3
  }

  it should "preserve Note On velocity" in new Fixture {
    // When
    private val output = noteOn(nonMpeInputChannel, C4, 87)
    // Then
    extractNoteOns(output).head.velocity shouldBe 87
  }

  it should "preserve Note Off velocity" in new Fixture {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4, 73)
    // Then
    extractNoteOffs(noteOffOutput) should contain(NoteOffScMidiMessage(noteOnChannel, C4, 73))
  }

  it should "preserve Note Off velocity in MPE input mode" in new Fixture(tuner7MpeInput) {
    // Given
    private val noteOnOutput = noteOn(1, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(1, C4, 73)
    // Then
    extractNoteOffs(noteOffOutput) should contain(NoteOffScMidiMessage(noteOnChannel, C4, 73))
  }

  it should "treat Note On with velocity 0 as Note Off" in new Fixture {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOn(nonMpeInputChannel, C4, 0)
    // Then
    extractNoteOffs(noteOffOutput) should contain(NoteOffScMidiMessage(noteOnChannel, C4))
  }

  it should "correctly allocate notes from any input channel" in new Fixture {
    // When
    private val out1 = noteOn(0, C4)
    private val out2 = noteOn(5, E4)
    // Then
    extractNoteOns(out1).map(_.channel) should contain(1)
    extractNoteOns(out2).map(_.channel) should contain(2)
  }

  // --- 4.2.4 process() — Non-MPE Input, Pitch Bend Handling ---

  behavior of "MpeTuner - process() Non-MPE Pitch Bend"

  it should "redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend" in new Fixture {
    // When
    private val output = tuner.process(PitchBendScMidiMessage(nonMpeInputChannel, 1000).asJava)
    // Then
    private val pitchBends = extractPitchBends(output)
    pitchBends should contain(PitchBendScMidiMessage(0, 1000)) // master channel 0
  }

  it should "not bleed master channel pitch bend into member channel tuning on retune" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 cents offset in quarter-comma meantone
      private val noteOutput = noteOn(nonMpeInputChannel, E4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      extractPitchBends(noteOutput).head.cents shouldEqual -14.0

      // In NonMpe mode, pitch bend goes to the master channel as zone-level expression
      tuner.process(PitchBendScMidiMessage(nonMpeInputChannel, 500).asJava)

      // When
      // Retune — member channel pitch bend should only reflect tuning, not master expression
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val memberPb = extractPitchBends(tuneOutput).filter(_.channel == noteChannel)
      memberPb should have size 1
      // Pythagorean E offset is 8.0 cents — no contamination from the master pitch bend
      memberPb.head.cents.round.toInt shouldBe 8
    }

  // --- 4.2.5 process() — Non-MPE to MPE Conversion ---

  behavior of "MpeTuner - process() Non-MPE to MPE Conversion"

  it should "convert Polyphonic Key Pressure to Channel Pressure on member channel" in new Fixture {
    // Given
    private val noteOutput = noteOn(nonMpeInputChannel, C4)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    // When
    private val output = tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
    // Then
    extractChannelPressure(output) should contain(ChannelPressureScMidiMessage(noteChannel, 80))
    output.map(_.asScala).collect { case m: PolyPressureScMidiMessage => m } shouldBe empty
  }

  it should "redirect CC #74 to Master Channel as Zone-level timbre" in new Fixture {
    // Given
    noteOn(nonMpeInputChannel, C4)
    // When
    private val output = slide(nonMpeInputChannel, 100)
    // Then
    extractCc(output) should contain(CcScMidiMessage(0, ScMidiCc.MpeSlide, 100)) // master channel 0
  }

  it should "not forward CC #74 on any member channel in non-MPE mode" in new Fixture {
    // Given
    private val noteOutput = noteOn(nonMpeInputChannel, C4)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    // When
    private val output = slide(nonMpeInputChannel, 100)
    // Then
    extractCc(output).filter(cc => cc.number == ScMidiCc.MpeSlide && cc.channel == noteChannel) shouldBe empty
  }

  it should "redirect Channel Pressure to Master Channel as Zone-level pressure" in new Fixture {
    // Given
    noteOn(nonMpeInputChannel, C4)
    // When
    private val output = pressure(nonMpeInputChannel, 90)
    // Then
    extractChannelPressure(output) should contain(ChannelPressureScMidiMessage(0, 90)) // master channel 0
  }

  it should "not forward Channel Pressure on any member channel in non-MPE mode" in new Fixture {
    // Given
    private val noteOutput = noteOn(nonMpeInputChannel, C4)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    // When
    private val output = pressure(nonMpeInputChannel, 90)
    // Then
    extractChannelPressure(output).filter(_.channel == noteChannel) shouldBe empty
  }

  it should "initialize member channel CC #74 to default 64 even after sending CC #74 in non-MPE mode" in
    new Fixture {
      // Given
      // Sender sends CC #74 before Note On; value goes to Master Channel only
      slide(nonMpeInputChannel, 120)
      // When
      private val output = noteOn(nonMpeInputChannel, C4)
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      // Member channel must be initialized with neutral default (64), not the sender's value,
      // to avoid double-counting with the Master Channel value.
      extractCc(output) should contain(CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 64))
    }

  it should "initialize member channel Channel Pressure to default 0 even after sending CP in non-MPE mode" in
    new Fixture {
      // Given
      pressure(nonMpeInputChannel, 100)
      // When
      private val output = noteOn(nonMpeInputChannel, C4)
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractChannelPressure(output) should contain(ChannelPressureScMidiMessage(noteChannel, 0))
    }

  it should "initialize control dimensions before Note On even when input omits them" in new Fixture {
    // When
    private val output = noteOn(nonMpeInputChannel, C4)
    // Then
    private val msgs = extractScMidiMessages(output)

    private val noteChannel = 1
    // CC #74 should be 64 (default), Channel Pressure should be 0 (default)
    msgs should contain allOf(
      CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 64),
      ChannelPressureScMidiMessage(noteChannel, 0)
    )
  }

  // --- 4.2.6 process() — Zone-Level Messages ---

  behavior of "MpeTuner - process() Zone-Level Messages"

  it should "forward Sustain Pedal (CC #64) on Master Channel" in new Fixture {
    // When
    private val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.SustainPedal, 127)
      .asJava)
    // Then
    extractCc(output) should contain(CcScMidiMessage(0, ScMidiCc.SustainPedal, 127))
  }

  it should "forward Program Change on Master Channel" in new Fixture {
    // When
    private val output = tuner.process(ProgramChangeScMidiMessage(nonMpeInputChannel, 5).asJava)
    // Then
    private val programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
    programChanges should contain(ProgramChangeScMidiMessage(0, 5))
  }

  it should "forward zone-level CCs on Master Channel" in new Fixture {
    private val zoneLevelCcs = Table(
      ("ccName", "ccNumber", "ccValue"),
      ("Bank Select MSB", ScMidiCc.BankSelectMsb, 1),
      ("Bank Select LSB", ScMidiCc.BankSelectLsb, 0),
      ("Reset All Controllers", ScMidiCc.ResetAllControllers, 0),
      ("Modulation", ScMidiCc.ModulationMsb, 64),
      ("Sostenuto Pedal", ScMidiCc.SostenutoPedal, 127),
      ("Soft Pedal", ScMidiCc.SoftPedal, 127)
    )
    forAll(zoneLevelCcs) { (_, ccNumber, ccValue) =>
      // When
      val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ccNumber, ccValue).asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(0, ccNumber, ccValue))
    }
  }

  // --- 4.2.6.1 process() — Zone-Level Messages MPE Input ---

  behavior of "MpeTuner - process() Zone-Level Messages MPE Input"

  it should "forward Sustain Pedal (CC #64) received on member channel to zone Master Channel" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = tuner.process(CcScMidiMessage(mpeInputChannel, ScMidiCc.SustainPedal, 127)
        .asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(0, ScMidiCc.SustainPedal, 127))
    }

  it should "forward Program Change received on member channel to zone Master Channel" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = tuner.process(ProgramChangeScMidiMessage(mpeInputChannel, 5).asJava)
      // Then
      private val programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
      programChanges should contain(ProgramChangeScMidiMessage(0, 5))
    }

  it should "forward zone-level CCs received on member channel to zone Master Channel" in
    new Fixture(tuner7MpeInput) {
      private val zoneLevelCcs = Table(
        ("ccName", "ccNumber", "ccValue"),
        ("Bank Select MSB", ScMidiCc.BankSelectMsb, 1),
        ("Bank Select LSB", ScMidiCc.BankSelectLsb, 0),
        ("Reset All Controllers", ScMidiCc.ResetAllControllers, 0),
        ("Modulation", ScMidiCc.ModulationMsb, 64),
        ("Sostenuto Pedal", ScMidiCc.SostenutoPedal, 127),
        ("Soft Pedal", ScMidiCc.SoftPedal, 127)
      )
      forAll(zoneLevelCcs) { (_, ccNumber, ccValue) =>
        // When
        val output = tuner.process(CcScMidiMessage(mpeInputChannel, ccNumber, ccValue).asJava)
        // Then
        extractCc(output) should contain(CcScMidiMessage(0, ccNumber, ccValue))
      }
    }

  it should "route zone-level CC to upper zone Master Channel when received on upper member channel" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      // upper zone: members 8-14, master 15
      private val output = tuner.process(CcScMidiMessage(8, ScMidiCc.SustainPedal, 127).asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(15, ScMidiCc.SustainPedal, 127))
    }

  it should "route Program Change to upper zone Master Channel when received on upper member channel" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      private val output = tuner.process(ProgramChangeScMidiMessage(8, 5).asJava)
      // Then
      private val programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
      programChanges should contain(ProgramChangeScMidiMessage(15, 5))
    }

  // --- 4.2.7 process() — Pitch Bend Computation ---

  behavior of "MpeTuner - process() Pitch Bend Computation"

  it should "compute output pitch bend = tuning offset for single note on channel" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // C has 0.0 cents offset
      private val outC = noteOn(nonMpeInputChannel, C4)
      extractPitchBends(outC).head.cents shouldEqual 0.0

      // E has -14.0 cents offset
      private val outE = noteOn(nonMpeInputChannel, E4)
      extractPitchBends(outE).head.cents shouldEqual -14.0

      // D has -7.0 cents offset
      private val outD = noteOn(nonMpeInputChannel, D4)
      extractPitchBends(outD).head.cents shouldEqual -7.0

      // G has -3.0 cents offset
      private val outG = noteOn(nonMpeInputChannel, G4)
      extractPitchBends(outG).head.cents shouldEqual -3.0
    }

  it should "clamp pitch bend to valid range when tuning offset exceeds PBS" in {
    // Use a small PBS (2 semitones = 200 cents) so that a large tuning offset exceeds the range
    val smallPbs = PitchBendSensitivity(2)
    val smallPbsTuner = MpeTuner(
      initialZones = MpeZones(
        MpeZone(MpeZoneType.Lower, 15, memberPitchBendSensitivity = smallPbs),
        MpeZone(MpeZoneType.Upper, 0)
      )
    )
    new Fixture(smallPbsTuner) {
      // B: exceeds ±200 cents PBS range
      private val extremeTuning = Tuning("extreme", b = Some(500.0))
      tuner.tune(extremeTuning)

      // B should be clamped to max pitch bend value
      private val outB = noteOn(nonMpeInputChannel, MidiNote.B4)
      private val pbB = extractPitchBends(outB).head
      pbB.value shouldBe PitchBendScMidiMessage.MaxValue
      pbB.centsFor(smallPbs) shouldEqual smallPbs.totalCents.toDouble

      // C has 0.0 offset, should not be clamped
      private val outC = noteOn(nonMpeInputChannel, C4)
      extractPitchBends(outC).head.value shouldBe 0
    }
  }

  it should "compute output pitch bend = tuning offset for single note on channel in MPE input mode" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // C has 0.0 cents offset
      private val outC = noteOn(1, C4)
      extractPitchBends(outC).head.cents shouldEqual 0.0

      // E has -14.0 cents offset
      private val outE = noteOn(2, E4)
      extractPitchBends(outE).head.cents shouldEqual -14.0

      // D has -7.0 cents offset
      private val outD = noteOn(3, D4)
      extractPitchBends(outD).head.cents shouldEqual -7.0

      // G has -3.0 cents offset
      private val outG = noteOn(4, G4)
      extractPitchBends(outG).head.cents shouldEqual -3.0
    }

  it should "clamp pitch bend to valid range when tuning offset exceeds PBS in MPE input mode" in {
    val smallPbs = PitchBendSensitivity(2)
    val smallPbsTuner = MpeTuner(
      initialZones = MpeZones(
        MpeZone(MpeZoneType.Lower, 15, memberPitchBendSensitivity = smallPbs),
        MpeZone(MpeZoneType.Upper, 0)
      ),
      initialInputMode = MpeInputMode.Mpe
    )
    new Fixture(smallPbsTuner) {
      private val extremeTuning = Tuning("extreme", b = Some(500.0))
      tuner.tune(extremeTuning)

      private val outB = noteOn(1, MidiNote.B4)
      private val pbB = extractPitchBends(outB).head
      pbB.value shouldBe PitchBendScMidiMessage.MaxValue
      pbB.centsFor(smallPbs) shouldEqual smallPbs.totalCents.toDouble

      private val outC = noteOn(2, C4)
      extractPitchBends(outC).head.value shouldBe 0
    }
  }

  // TODO #154 Wrong for the correct tuner version
  it should "average expressive pitch bends across notes sharing a single member channel" in
    new Fixture(tuner3MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // tuner3: n=3 (PCG=1, EG=2). Input channels are 1..3 — the zone applies to input as well.
      private val outE4 = noteOn(1, E4)
      private val sharedChannel = extractNoteOns(outE4).head.channel

      // Send a non-high expressive bend on input ch 1 (E4 is the only note on that channel).
      private val eExprCents = 30.0
      pitchBend(1, eExprCents)

      // Fill remaining member channels with distinct pitch classes.
      noteOn(2, G4)
      noteOn(3, C4)

      // When
      // E5 (same pitch class as E4) arrives on input ch 1 — shares the same output channel.
      private val outE5 = noteOn(1, E5)
      // Then
      private val sharedPb = extractPitchBends(outE5).filter(_.channel == sharedChannel)
      sharedPb should have size 1

      // E5 enters with expressive bend = 0; average of (eExprCents, 0) = eExprCents / 2.
      // Output PB = tuning offset for E (-14.0) + average expressive bend.
      // Compare on MIDI values to sidestep the cents↔value roundtrip resolution.
      private val expected = PitchBendScMidiMessage.convertCentsToValue(-14.0 + eExprCents / 2, defaultPbs)
      sharedPb.head.value shouldBe expected
    }

  // --- 4.2.8 process() — Dual-Group Allocation Integration ---

  behavior of "MpeTuner - process() Dual-Group Allocation"

  it should "leave Pitch Class Group channel unaffected when bending Expression Group channel of same pitch class" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E4 -> PCG channel (E enters Pitch Class Group)
      private val outE4 = noteOn(1, E4)
      private val pcgChannel = extractNoteOns(outE4).head.channel

      // E5 (same pitch class) -> Expression Group channel (PCG slot for E is already taken)
      private val outE5 = noteOn(2, E5)
      private val egChannel = extractNoteOns(outE5).head.channel
      egChannel should not equal pcgChannel

      // When
      // Send a non-high expressive pitch bend on the EG input channel
      private val eExprCents = 30.0
      private val bendOutput = pitchBend(2, eExprCents)
      // Then
      private val pitchBends = extractPitchBends(bendOutput)

      // Only the EG channel should receive an updated pitch bend; the PCG channel must not.
      pitchBends.map(_.channel) should contain(egChannel)
      pitchBends.map(_.channel) should not contain pcgChannel
      pitchBends.filter(_.channel == egChannel).head.cents shouldEqual (-14.0 + eExprCents)
    }
  it should "allocate second note with same pitch class to Expression Group with independent pitch bends" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // When
      // E has -14.0 cents offset in quarter-comma meantone
      private val out1 = noteOn(nonMpeInputChannel, E4)
      private val ch1 = extractNoteOns(out1).head.channel
      private val pb1 = extractPitchBends(out1).head

      private val out2 = noteOn(nonMpeInputChannel, E5)
      private val ch2 = extractNoteOns(out2).head.channel
      private val pb2 = extractPitchBends(out2).head

      // Then
      // Notes should be on different channels
      ch1 should not equal ch2

      // Both should have pitch bends reflecting the -14.0 cents tuning offset for E
      pb1.channel shouldBe ch1
      pb1.cents shouldEqual -14.0
      pb2.channel shouldBe ch2
      pb2.cents shouldEqual -14.0
    }

  // --- 4.2.9 process() — Note Dropping Integration ---

  behavior of "MpeTuner - process() Note Dropping"

  it should "trigger note dropping with Note Off output for dropped notes on channel exhaustion" in
    new Fixture(tuner3) {
      // Given
      noteOn(nonMpeInputChannel, C4)
      noteOn(nonMpeInputChannel, E4)
      noteOn(nonMpeInputChannel, G4)
      // When
      private val output = noteOn(nonMpeInputChannel, A4)
      // Then
      private val noteOffs = extractNoteOffs(output)
      noteOffs should have size 1
      // Dropped E4, not C4. The latter, although older, is the bass note which is excluded.
      noteOffs.head.midiNote shouldEqual E4
    }

  it should "preserve the lowest note during channel exhaustion dropping" in new Fixture(tuner3) {
    // Given
    noteOn(nonMpeInputChannel, C4) // lowest
    noteOn(nonMpeInputChannel, E4) // middle
    noteOn(nonMpeInputChannel, G4) // highest
    // When
    private val output = noteOn(nonMpeInputChannel, A4)
    // Then
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should not contain C4
  }

  it should "preserve the highest note during channel exhaustion dropping" in new Fixture(tuner3) {
    // Given
    // Oldest not, but will not be dropped since it's the highest.
    noteOn(nonMpeInputChannel, G4) // highest
    noteOn(nonMpeInputChannel, C4) // lowest
    noteOn(nonMpeInputChannel, E4) // middle
    // When
    private val output = noteOn(nonMpeInputChannel, A4)
    // Then
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should not contain G4
  }

  it should "trigger note dropping with Note Off output for dropped notes on channel exhaustion in MPE input mode" in
    new Fixture(tuner3MpeInput) {
      // Given
      noteOn(1, C4)
      noteOn(2, E4)
      noteOn(3, G4)
      // When
      private val output = noteOn(1, A4)
      // Then
      private val noteOffs = extractNoteOffs(output)
      noteOffs should have size 1
      // Dropped E4, not C4. The latter, although older, is the bass note which is excluded.
      noteOffs.head.midiNote shouldEqual E4
    }

  it should "preserve the lowest note during channel exhaustion dropping in MPE input mode" in
    new Fixture(tuner3MpeInput) {
      // Given
      noteOn(1, C4) // lowest
      noteOn(2, E4) // middle
      noteOn(3, G4) // highest
      // When
      private val output = noteOn(1, A4)
      // Then
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      droppedNotes should not contain C4
    }

  it should "preserve the highest note during channel exhaustion dropping in MPE input mode" in
    new Fixture(tuner3MpeInput) {
      // Given
      // Oldest not, but will not be dropped since it's the highest.
      noteOn(3, G4) // highest
      noteOn(1, C4) // lowest
      noteOn(2, E4) // middle
      // When
      private val output = noteOn(1, A4)
      // Then
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      droppedNotes should not contain G4
    }

  // TODO #154 Wrong for the correct tuner version
  it should "drop other notes on a shared channel when one note develops a high expressive pitch bend" in
    new Fixture(tuner3MpeInput) {
      // Given
      // tuner3 in MPE input: PCG=1, EG=2. Input channels are 1..3.
      // Share E4 + E5 on the same output channel by sending E5 on E4's input channel.
      private val outE4 = noteOn(1, E4)
      private val sharedChannel = extractNoteOns(outE4).head.channel
      noteOn(2, G4)
      noteOn(3, C4)
      noteOn(1, E5)

      // When
      // High expressive pitch bend (> 50 cents threshold) on input ch 1 -> drops co-resident E4.
      private val output = pitchBend(1, 100.0)
      // Then
      private val noteOffs = extractNoteOffs(output)
      noteOffs.map(n => (n.channel, n.midiNote)) should contain(sharedChannel, E4)
      noteOffs.map(n => (n.channel, n.midiNote)) should not contain(sharedChannel, E5)
    }

  // TODO #154 Wrong for the correct tuner version
  it should "drop existing notes when a new note is allocated to a channel holding a high-bend note" in
    new Fixture(tuner3MpeInput) {
      // Given
      // E4 alone on a PCG channel; bend it past the high-bend threshold (50 cents).
      private val outE4 = noteOn(1, E4)
      private val pcgChannel = extractNoteOns(outE4).head.channel
      pitchBend(1, 100.0)

      // Fill remaining channels so the next E forces allocator sharing on pcgChannel.
      noteOn(2, G4)
      noteOn(3, C4)

      // When
      // New E5 lands on pcgChannel via sharing. Channel must be freed because E4 has high bend.
      private val output = noteOn(1, E5)
      // Then
      extractNoteOffs(output) should contain(NoteOffScMidiMessage(pcgChannel, E4))
      extractNoteOns(output).map(n => (n.channel, n.midiNote)) should contain((pcgChannel, E5))
    }

  // --- 4.2.10 process() — MPE Input Mode ---

  behavior of "MpeTuner - process() MPE Input"

  it should "process MPE input notes with tuning offsets applied" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(1, E4, 100)
      // Then
      extractPitchBends(output).head.cents shouldEqual -14.0
    }

  it should "treat per-note pitch bend as expressive pitch bend combined with tuning offset" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 cents offset in quarter-comma meantone
      private val noteOutput = noteOn(1, E4, 100)
      private val noteChannel = extractNoteOns(noteOutput).head.channel

      private val eExprCents = 290.0
      // When
      private val output = pitchBend(1, eExprCents)
      // Then
      private val pitchBendMsg = extractPitchBends(output).filter(_.channel == noteChannel).head

      // Output pitch bend should combine tuning offset for E (-14.0) + expressive bend
      pitchBendMsg.cents shouldEqual (-14.0 + eExprCents)
    }

  it should "forward Master Channel pitch bend without modification" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = tuner.process(PitchBendScMidiMessage(0, 1000).asJava)
    // Then
    extractPitchBends(output) should contain(PitchBendScMidiMessage(0, 1000))
  }

  // --- 4.2.10.1 process() — MPE Input Master Channel Notes ---

  behavior of "MpeTuner - process() MPE Input Master Channel Notes"

  it should "forward Note On received on Lower Master Channel on the same Master Channel" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(0, C4, 100)
      // Then
      private val noteOns = extractNoteOns(output)
      noteOns should have size 1
      noteOns.head shouldEqual NoteOnScMidiMessage(0, C4, 100)
    }

  it should "not emit Pitch Bend, CC #74, or Channel Pressure setup for Master Channel Note On" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(0, C4, 100)
      // Then
      extractPitchBends(output) shouldBe empty
      extractCc(output).filter(_.number == ScMidiCc.MpeSlide) shouldBe empty
      extractChannelPressure(output) shouldBe empty
    }

  it should "forward Note Off received on Lower Master Channel on the same Master Channel" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(0, C4, 100)
      // When
      private val output = noteOff(0, C4)
      // Then
      extractNoteOffs(output) should contain(NoteOffScMidiMessage(0, C4))
    }

  it should "forward Note On/Off received on Upper Master Channel on the same Master Channel" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      private val onOutput = noteOn(15, C4, 90)
      // Then
      private val noteOns = extractNoteOns(onOutput)
      noteOns should have size 1
      noteOns.head shouldEqual NoteOnScMidiMessage(15, C4, 90)

      // When
      private val offOutput = noteOff(15, C4)
      // Then
      extractNoteOffs(offOutput) should contain(NoteOffScMidiMessage(15, C4))
    }

  it should "route member channel notes to their own zone in dual-zone MPE input mode" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      // Lower zone: master 0, members 1..7. Upper zone: master 15, members 8..14.
      private val lowerOut = noteOn(1, C4)
      private val lowerChannel = extractNoteOns(lowerOut).head.channel
      // Then
      lowerChannel should (be >= 1 and be <= 7)

      // When
      private val upperOut = noteOn(8, C4)
      private val upperChannel = extractNoteOns(upperOut).head.channel
      // Then
      upperChannel should (be >= 8 and be <= 14)
    }

  it should "not consume Member Channel slots for Master Channel notes" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Master Channel note should not occupy a Member Channel
      noteOn(0, C4)
      // When
      // Subsequent Member Channel note gets the first Member Channel
      private val out = noteOn(mpeInputChannel, E4)
      // Then
      private val noteOns = extractNoteOns(out)
      noteOns should have size 1
      noteOns.head.channel shouldBe 1
    }

  it should "not retune Master Channel notes on tune() call" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      noteOn(0, C4)
      // When
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      extractPitchBends(tuneOutput).filter(_.channel == 0) shouldBe empty
    }

  it should "allow multiple active notes on the Master Channel concurrently" in
    new Fixture(mpeTunerMpeInput) {
      // When
      private val out1 = noteOn(0, C4, 100)
      private val out2 = noteOn(0, E4, 100)
      // Then
      extractNoteOns(out1).map(n => (n.channel, n.midiNote)) should contain((0, C4))
      extractNoteOns(out2).map(n => (n.channel, n.midiNote)) should contain((0, E4))

      // When
      private val offOutput = noteOff(0, C4)
      // Then
      extractNoteOffs(offOutput) should contain(NoteOffScMidiMessage(0, C4))
      // E4 should still be tracked as active
      // When
      private val offOutput2 = noteOff(0, E4)
      // Then
      extractNoteOffs(offOutput2) should contain(NoteOffScMidiMessage(0, E4))
    }

  it should "forward Polyphonic Key Pressure as-is for Master Channel notes" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(0, C4, 100)
      // When
      private val output = tuner.process(PolyPressureScMidiMessage(0, C4, 80).asJava)
      // Then
      extractPolyPressure(output) should contain(PolyPressureScMidiMessage(0, C4, 80))
      extractChannelPressure(output) shouldBe empty
    }

  it should "drop Polyphonic Key Pressure received on a Member Channel in MPE input mode" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(mpeInputChannel, C4, 100)
      // When
      private val output = tuner.process(PolyPressureScMidiMessage(mpeInputChannel, C4, 80).asJava)
      // Then
      extractPolyPressure(output) shouldBe empty
      extractChannelPressure(output) shouldBe empty
    }

  it should "not forward CC #74 on an MPE input channel with no active note" in
    new Fixture(tuner7MpeInput) {
      // When
      // Send CC #74 on a member channel that has no active note
      private val output = slide(mpeInputChannel, 100)
      // Then
      extractCc(output) shouldBe empty
    }

  it should "not forward Channel Pressure on an MPE input channel with no active note" in
    new Fixture(tuner7MpeInput) {
      // When
      // Send Channel Pressure on a member channel that has no active note
      private val output = pressure(mpeInputChannel, 90)
      // Then
      extractChannelPressure(output) shouldBe empty
    }

  it should "not forward Pitch Bend on an MPE input member channel with no active note" in
    new Fixture(tuner7MpeInput) {
      // When
      // Send Pitch Bend on a member channel that has no active note
      private val output = tuner.process(PitchBendScMidiMessage(mpeInputChannel, 4000).asJava)
      // Then
      extractPitchBends(output) shouldBe empty
    }

  it should "forward CC #74 to the allocated Member Channel when an active note exists on MPE input channel" in
    new Fixture(tuner7MpeInput) {
      // Given
      private val noteOutput = noteOn(mpeInputChannel, C4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When
      private val output = slide(mpeInputChannel, 100)
      // Then
      extractCc(output) should contain(CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 100))
    }

  it should "forward Channel Pressure to allocated Member Channel when active note exists on MPE input channel" in
    new Fixture(tuner7MpeInput) {
      // Given
      private val noteOutput = noteOn(mpeInputChannel, C4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When
      private val output = pressure(mpeInputChannel, 90)
      // Then
      extractChannelPressure(output) should contain(ChannelPressureScMidiMessage(noteChannel, 90))
    }

  it should "seed Member Channel CC #74 from the per-input-channel value at Note On in MPE mode" in
    new Fixture(tuner7MpeInput) {
      // Given
      // Send CC #74 first (no active note, so forwarding is dropped) — but the per-input-channel value is recorded
      slide(mpeInputChannel, 100)
      // When
      // Note On should seed the Member Channel CC #74 with the recorded value (not the neutral default 64)
      private val output = noteOn(mpeInputChannel, C4)
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractCc(output) should contain(CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 100))
    }

  it should "seed Member Channel Channel Pressure from the per-input-channel value at Note On in MPE mode" in
    new Fixture(tuner7MpeInput) {
      // Given
      pressure(mpeInputChannel, 90)
      // When
      private val output = noteOn(mpeInputChannel, C4)
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractChannelPressure(output) should contain(ChannelPressureScMidiMessage(noteChannel, 90))
    }

  // --- 4.2.11 process() — Note Off Behavior ---

  behavior of "MpeTuner - process() Note Off Behavior"

  it should "not update released channel's pitch bend on tuning changes" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 cents offset; pythagorean E has 8.0 cents — non-zero in both tunings
      private val noteOutputE = noteOn(nonMpeInputChannel, E4)
      private val releasedChannel = extractNoteOns(noteOutputE).head.channel

      // G stays active as a control
      private val noteOutputG = noteOn(nonMpeInputChannel, G4)
      private val activeChannel = extractNoteOns(noteOutputG).head.channel

      // Release E4
      noteOff(nonMpeInputChannel, E4)

      // When
      // Retune — only the active channel (G) should get a pitch bend update
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends should have size 1
      pitchBends.head.channel shouldBe activeChannel
      pitchBends.head.cents.round.toInt shouldBe 2 // pythagorean G offset
    }

  it should "make channel available for reuse after Note Off" in new Fixture(tuner3) {
    // Given
    // Fill all 3 member channels
    noteOn(nonMpeInputChannel, C4)
    private val out = noteOn(nonMpeInputChannel, E4)
    private val ch = extractNoteOns(out).head.channel
    noteOn(nonMpeInputChannel, G4)

    // When
    // Release the second note
    noteOff(nonMpeInputChannel, E4)

    // Then
    // New note should reuse the released channel
    private val output = noteOn(nonMpeInputChannel, D4)
    extractNoteOns(output).head.channel shouldBe ch
  }

  it should "not update released channel's pitch bend on tuning changes in MPE input mode" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 cents offset; pythagorean E has 8.0 cents — non-zero in both tunings
      private val noteOutputE = noteOn(1, E4)
      private val releasedChannel = extractNoteOns(noteOutputE).head.channel

      // G stays active as a control
      private val noteOutputG = noteOn(2, G4)
      private val activeChannel = extractNoteOns(noteOutputG).head.channel

      // Release E4
      noteOff(1, E4)

      // When
      // Retune — only the active channel (G) should get a pitch bend update
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)
      pitchBends should have size 1
      pitchBends.head.channel shouldBe activeChannel
      pitchBends.head.cents.round.toInt shouldBe 2 // pythagorean G offset
    }

  it should "make channel available for reuse after Note Off in MPE input mode" in
    new Fixture(tuner3MpeInput) {
      // Given
      noteOn(1, C4)
      private val out = noteOn(2, E4)
      private val ch = extractNoteOns(out).head.channel
      noteOn(3, G4)

      // When
      // Release the second note
      noteOff(2, E4)

      // Then
      // New note arriving on a different input channel should reuse the released channel
      private val output = noteOn(1, D4)
      extractNoteOns(output).head.channel shouldBe ch
    }

  it should "not forward expressive controls from an input channel after its notes have been released" in
    new Fixture(tuner7MpeInput) {
      // Given
      // Note On routes mpeInputChannel -> some output channel
      noteOn(mpeInputChannel, C4)
      noteOff(mpeInputChannel, C4)

      // When / Then
      // After Note Off, no input->output mapping should exist for mpeInputChannel — expressive
      // CC #74 / Channel Pressure / Pitch Bend on this input channel must NOT be forwarded to a
      // (now stale) member channel.
      private val ccOutput = slide(mpeInputChannel, 100)
      extractCc(ccOutput) shouldBe empty

      private val cpOutput = pressure(mpeInputChannel, 90)
      extractChannelPressure(cpOutput) shouldBe empty

      private val pbOutput = tuner.process(PitchBendScMidiMessage(mpeInputChannel, 4000).asJava)
      extractPitchBends(pbOutput) shouldBe empty
    }

  // --- 4.2.12 Worked Examples from Paper ---

  behavior of "MpeTuner - Worked Examples"

  it should "reproduce Section 8.1: Basic allocation in quarter-comma meantone" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // 1. Note C4 arrives on input ch 1 -> Pitch Class Group; C has 0.0 cents offset
      private val out1 = noteOn(1, C4)
      private val ch1 = extractNoteOns(out1).head.channel
      extractPitchBends(out1).head.cents shouldEqual 0.0

      // 2. Note E4 arrives on input ch 2 -> Pitch Class Group; E has -14.0 cents offset
      private val out2 = noteOn(2, E4)
      private val ch2 = extractNoteOns(out2).head.channel
      ch2 should not equal ch1
      extractPitchBends(out2).head.cents shouldEqual -14.0

      // 3. Note G4 arrives on input ch 3 -> Pitch Class Group; G has -3.0 cents offset
      private val out3 = noteOn(3, G4)
      private val ch3 = extractNoteOns(out3).head.channel
      ch3 should not equal ch2
      extractPitchBends(out3).head.cents shouldEqual -3.0

      // 4. Second C (C5) arrives on input ch 4 -> Expression Group; C has 0.0 cents offset
      private val out4 = noteOn(4, C5)
      private val ch4 = extractNoteOns(out4).head.channel
      ch4 should not equal ch1
      extractPitchBends(out4).head.cents shouldEqual 0.0

      // 5. Performer bends C5 on input ch 4 — only ch4's pitch bend is affected
      private val cExprCents = 586.0
      private val bendOut = pitchBend(4, cExprCents)
      private val pitchBends = extractPitchBends(bendOut)
      pitchBends should have size 1
      pitchBends.head.channel shouldBe ch4
      pitchBends.head.cents shouldEqual (0.0 + cExprCents)
    }

  it should "reproduce Section 8.2: Tuning change during performance" in
    new Fixture(tuner7, Some(quarterCommaMeantone)) {
      // Given
      private val chC = extractNoteOns(noteOn(nonMpeInputChannel, C4)).head.channel
      private val chE = extractNoteOns(noteOn(nonMpeInputChannel, E4)).head.channel
      private val chG = extractNoteOns(noteOn(nonMpeInputChannel, G4)).head.channel
      private val chC5 = extractNoteOns(noteOn(nonMpeInputChannel, C5)).head.channel

      // When
      // Switch to Pythagorean tuning
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pitchBends = extractPitchBends(tuneOutput)

      // Should have pitch bend updates for all 4 occupied channels
      pitchBends should have size 4

      // Pythagorean offsets: C = 0.0, E = 8.0, G = 2.0
      private val pbByChannel = pitchBends.map(pb => pb.channel -> pb.cents.round.toInt).toMap
      pbByChannel(chC) shouldBe 0
      pbByChannel(chE) shouldBe 8
      pbByChannel(chG) shouldBe 2
      // C5 shares pitch class C, so same offset
      pbByChannel(chC5) shouldBe 0
    }

  it should "reproduce Section 8.3: Note dropping under channel exhaustion" in
    new Fixture(tuner3, Some(quarterCommaMeantone)) {
      // Given
      // C, E, G on 3 channels
      noteOn(nonMpeInputChannel, C4)
      noteOn(nonMpeInputChannel, E4)
      noteOn(nonMpeInputChannel, G4)

      // When
      // A arrives - must drop a note
      private val output = noteOn(nonMpeInputChannel, A4)
      // Then
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
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, memberCount).asJava)
  }

  it should "reconfigure lower zone on MCM received on channel 0" in new Fixture(dualZoneTuner) {
    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 10)
    // Then
    // Should output MCM for the new lower zone with memberCount=10
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 10)
    )
    tuner.zones.lower.memberCount shouldEqual 10
    tuner.zones.upper.memberCount shouldEqual 4
  }

  it should "reconfigure upper zone on MCM received on channel 15" in new Fixture(dualZoneTuner) {
    // When
    private val output = sendMcm(tuner, channel = 15, memberCount = 10)
    // Then
    // Should output MCM for the new upper zone with memberCount=10
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(15, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(15, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 10)
    )
    tuner.zones.lower.memberCount shouldEqual 4
    tuner.zones.upper.memberCount shouldEqual 10
  }

  it should "stop all active notes when MCM is received" in new Fixture {
    // Given
    noteOn(nonMpeInputChannel, C4)
    noteOn(nonMpeInputChannel, E4)
    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    private val noteOffs = extractNoteOffs(output)
    noteOffs.map(_.midiNote) should contain allOf(C4, E4)
  }

  it should "shrink other zone when MCM causes overlap" in new Fixture(dualZoneTuner) {
    // dualZoneTuner: lower=7, upper=7
    // When - MCM on ch 0 with memberCount=10 -> upper must shrink to 4
    private val output = sendMcm(tuner, channel = 0, memberCount = 10)
    // Then
    private val ccs = extractCc(output)
    // Upper zone MCM should show memberCount=4
    ccs should contain inOrder(
      CcScMidiMessage(15, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(15, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 4)
    )
    tuner.zones.lower.memberCount shouldEqual 10
    tuner.zones.upper.memberCount shouldEqual 4
  }

  it should "disable zone when MCM with memberCount=0 is received" in new Fixture(dualZoneTuner) {
    // When
    private val output = sendMcm(tuner, channel = 15, memberCount = 0)
    // Then
    private val ccs = extractCc(output)
    // Upper zone MCM should be sent to the output even if the zone is disabled to inform the downstream device
    ccs should contain(CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 0))
    // Lower zone MCM should NOT be present because the lower zone was not affected
    ccs should not contain CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 7)
  }

  it should "not trigger MCM on incomplete RPN sequence" in new Fixture {
    // Given - Send only CC#101=0 and CC#6=10 without CC#100
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb).asJava)
    // When
    private val output = tuner.process(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 10).asJava)
    // Then - Should NOT contain MCM output (no Note Offs, no MCM messages for reconfiguration)
    extractNoteOffs(output) shouldBe empty
    extractCc(output) should not contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 10)
    )
  }

  it should "not trigger MCM for non-MCM RPN (e.g. PBS RPN)" in new Fixture {
    // When - Send PBS RPN (MSB=0, LSB=0) instead of MCM RPN (MSB=0, LSB=6)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    private val output = tuner.process(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 48).asJava)
    // Then - Should NOT contain MCM reconfiguration output
    private val ccs = extractCc(output)
    ccs.filter(cc => cc.number == ScMidiCc.DataEntryMsb &&
      cc.value == 15) shouldBe empty // no MCM with memberCount=15
  }

  it should "ignore MCM on non-master channel" in new Fixture {
    // When
    private val output = sendMcm(tuner, channel = 5, memberCount = 7)
    // Then - Should NOT trigger MCM processing
    extractNoteOffs(output) shouldBe empty
  }

  it should "revert to initialZones on reset() after MCM" in new Fixture(dualZoneTuner) {
    // Given
    sendMcm(tuner, channel = 0, memberCount = 10)
    // When - Reset should restore initial configuration
    private val resetOutput = tuner.reset()
    // Then
    private val ccs = extractCc(resetOutput)
    // Lower zone should be back to 7 members
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 7)
    )
    // Upper zone should be back to 7 members
    ccs should contain inOrder(
      CcScMidiMessage(15, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(15, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 7)
    )
  }

  it should "not output PBS messages after MCM" in new Fixture {
    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    private val ccs = extractCc(output)
    // PBS RPN uses LSB=0, while MCM RPN uses LSB=6; no PBS RPN should appear in the output
    private val pbsRpnMessages = ccs.filter(cc =>
      cc.number == ScMidiCc.RpnLsb && cc.value == ScMidiRpn.PitchBendSensitivityLsb)
    pbsRpnMessages shouldBe empty
  }

  it should "switch input mode to MPE automatically when an MCM is received" in new Fixture {
    // Given
    tuner.inputMode shouldBe MpeInputMode.NonMpe
    // When
    sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    tuner.inputMode shouldBe MpeInputMode.Mpe
  }

  it should "reset PBS to defaults when MCM is received" in new Fixture(tuner7) {
    // Given - Set custom PBS on the lower zone
    sendPbsMsb(tuner, channel = 0, semitones = 12)
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    // When - Receive MCM on the same zone
    sendMcm(tuner, channel = 0, memberCount = 7)
    // Then - PBS should be reset to defaults per MPE spec Section 2.4
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }

  // --- PBS Processing ---

  behavior of "MpeTuner - PBS Processing"

  /** Sends a complete PBS RPN MSB sequence: CC#100=0, CC#101=0, CC#6=semitones on the given channel. */
  private def sendPbsMsb(tuner: MpeTuner, channel: Int, semitones: Int): Seq[MidiMessage] = {
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, semitones).asJava)
  }

  /** Sends a PBS RPN LSB (cents) on the given channel, assuming RPN is already set to PBS. */
  private def sendPbsLsb(tuner: MpeTuner, channel: Int, cents: Int): Seq[MidiMessage] = {
    tuner.process(CcScMidiMessage(channel, ScMidiCc.DataEntryLsb, cents).asJava)
  }

  it should "update master PBS on master channel" in new Fixture {
    // When
    private val output = sendPbsMsb(tuner, channel = 0, semitones = 12)
    // Then
    // Should forward RPN setup and PBS for the master channel with new sensitivity
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12)
    )
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
  }

  it should "update member PBS and forward only on the received channel" in new Fixture(tuner7) {
    // When - Send PBS on member channel 1 -> internal state updated for all members, but only forwarded on ch 1
    private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
    // Then
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(1, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(1, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(1, ScMidiCc.DataEntryMsb, 24)
    )
    // Should NOT broadcast to other member channels
    private val dataEntryCcs = ccs.filter(_.number == ScMidiCc.DataEntryMsb)
    (2 to 7).foreach { ch =>
      dataEntryCcs.filter(_.channel == ch) shouldBe empty
    }
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
  }

  it should "forward PBS on each channel when received on all member channels" in new Fixture(tuner7) {
    // When - Sender broadcasts PBS to all member channels 1-7; each should be forwarded 1:1
    private var output: Seq[MidiMessage] = Seq.empty
    for (ch <- 1 to 7) {
      output ++= sendPbsMsb(tuner, channel = ch, semitones = 24)
    }
    // Then
    private val dataEntryCcs = extractCc(output).filter(cc =>
      cc.number == ScMidiCc.DataEntryMsb && cc.value == 24)

    dataEntryCcs.size shouldEqual 7
    dataEntryCcs.map(_.channel) should contain theSameElementsAs (1 to 7)
  }

  it should "recompute pitch bends on occupied channels after member PBS change" in
    new Fixture(tuner7, Some(quarterCommaMeantone)) {
      // Given - Play a note to occupy a channel
      private val noteOutput = noteOn(nonMpeInputChannel, E4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When - Change member PBS
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then - Should have a pitch bend update for the occupied channel
      private val pitchBends = extractPitchBends(pbsOutput)
      pitchBends.map(_.channel) should contain(noteChannel)

      pitchBends.size shouldEqual 1
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  it should "not affect other zone's PBS" in new Fixture(dualZoneTuner) {
    // When - Send PBS on lower zone member channel 1
    private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
    // Then - Upper zone member channels (8-14) should NOT get the new PBS in this output
    private val ccs = extractCc(output)
    (8 to 14).foreach { ch =>
      ccs.filter(cc => cc.channel == ch && cc.number == ScMidiCc.DataEntryMsb &&
        cc.value == 24) shouldBe empty
    }
  }

  it should "handle PBS LSB (cents) update" in new Fixture {
    // Given - First set the RPN to PBS
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    // When - Send LSB
    private val output = sendPbsLsb(tuner, channel = 0, cents = 50)
    // Then - Should re-send RPN setup before Data Entry LSB
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryLsb, 50)
    )
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(
      MpeZone.DefaultMasterPitchBendSensitivity.semitones, cents = 50)
  }

  it should "preserve intonation of active note with expressive pitch bend after PBS change" in
    new Fixture(
      MpeTuner(
        initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 7), MpeZone(MpeZoneType.Upper, 0)),
        initialInputMode = MpeInputMode.Mpe
      ),
      Some(quarterCommaMeantone)
    ) {
      // Given - Play E4 on member channel 1: tuning offset for E is -14.0 cents
      private val noteOutput = noteOn(1, E4, 100)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // Send an expressive pitch bend of ~293 cents on that channel (with default PBS=48 semitones)
      private val eExprCents = 293.0
      pitchBend(1, eExprCents)
      // When - Now change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)
      pitchBends should have size 1
      // The output pitch bend should still represent tuning offset + expressive bend in cents.
      // E tuning offset = -14.0 cents; total ≈ -14 + 293 = 279 cents.
      private val expectedCents = -14.0 + eExprCents
      private val newPbs = PitchBendSensitivity(24)
      pitchBends.head.centsFor(newPbs) shouldEqual expectedCents
    }

  it should "preserve intonation of active note without expressive pitch bend after PBS change" in
    new Fixture(tuner7, Some(quarterCommaMeantone)) {
      // Given - Play E4: tuning offset for E is -14.0 cents
      private val noteOutput = noteOn(nonMpeInputChannel, E4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When - Change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then - The output pitch bend should still represent -14.0 cents under the new PBS
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)
      pitchBends should have size 1
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  it should "revert PBS to initial values on reset()" in new Fixture(tuner7) {
    // Given - Change member PBS
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    // When
    private val resetOutput = tuner.reset()
    // Then - Member channels should have default PBS (48 semitones)
    private val ccs = extractCc(resetOutput)
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        CcScMidiMessage(ch, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(ch, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(ch, ScMidiCc.DataEntryMsb, 48)
      )
    }
  }

  it should "update master PBS on master channel in MPE input mode" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = sendPbsMsb(tuner, channel = 0, semitones = 12)
    // Then
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12)
    )
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
  }

  it should "update member PBS and forward only on the received channel in MPE input mode" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      private val ccs = extractCc(output)
      ccs should contain inOrder(
        CcScMidiMessage(1, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(1, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(1, ScMidiCc.DataEntryMsb, 24)
      )
      // Should NOT broadcast to other member channels
      private val dataEntryCcs = ccs.filter(_.number == ScMidiCc.DataEntryMsb)
      (2 to 7).foreach { ch =>
        dataEntryCcs.filter(_.channel == ch) shouldBe empty
      }
      tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    }

  it should "recompute pitch bends on occupied channels after member PBS change in MPE input mode" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given - Play a note to occupy a channel
      private val noteOutput = noteOn(2, E4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When - Change member PBS
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      private val pitchBends = extractPitchBends(pbsOutput)
      pitchBends.map(_.channel) should contain(noteChannel)
      pitchBends.size shouldEqual 1
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  it should "revert PBS to initial values on reset() in MPE input mode" in new Fixture(tuner7MpeInput) {
    // Given
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    // When
    private val resetOutput = tuner.reset()
    // Then
    private val ccs = extractCc(resetOutput)
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        CcScMidiMessage(ch, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(ch, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(ch, ScMidiCc.DataEntryMsb, 48)
      )
    }
  }
}

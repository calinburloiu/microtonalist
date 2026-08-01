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

/**
 * Tests for [[MpeTuner]].
 *
 * == Test Organization ==
 *
 * Tests are divided into eight categories matching the operations and MIDI-behavior kinds under test:
 * `reset()`, `tune()`, `process() - Basic`, `process() - Expression`, `process() - Note Dropping`,
 * `process() - Zone-level Messages`, `MCM Processing`, and `PBS Processing`.
 *
 * Each category is further split by input mode into one or two `behavior of` blocks whose headings
 * follow the pattern `"MpeTuner - <category> - <Non-MPE Input | MPE Input>"`. When both modes have
 * tests, the Non-MPE Input block comes first.
 *
 * Inside each `behavior of` block, tests are grouped into named subgroups separated by
 * `// ---- <subgroup name> ----` comment lines. Within a subgroup, tests are ordered by similarity,
 * from simplest to most complex: general cases before special cases, happy path before edge cases.
 *
 * When a Non-MPE and an MPE test cover the same behavior, they share the same (or a near-matching)
 * name — the input mode is already captured in the `behavior of` heading and need not appear in the
 * test name itself.
 *
 * When adding a test, pick the `behavior of` block that matches the category and input mode of the
 * behavior under test, then place it in the most fitting subgroup (creating a new one at the end of
 * the block if none fits).
 */
class MpeTunerTest extends AnyFlatSpec with Matchers with Inside with OptionValues with TableDrivenPropertyChecks {

  private implicit val defaultPbs: PitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity
  private val masterPbs: PitchBendSensitivity = MpeZone.DefaultMasterPitchBendSensitivity

  import MidiNote.{A4, C4, C5, D4, E4, F4, G4}

  private val C3: MidiNote = MidiNote(C4 - 12)
  private val E1: MidiNote = MidiNote(E4 - 36)
  private val E2: MidiNote = MidiNote(E4 - 24)
  private val E3: MidiNote = MidiNote(E4 - 12)
  private val D5: MidiNote = MidiNote(D4 + 12)
  private val E5: MidiNote = MidiNote(E4 + 12)

  private val nonMpeInputChannel = 2
  private val mpeInputChannel: Int = 1

  // One Pitch Bend unit is ≈0.586 cents at the default Member Channel Pitch Bend Sensitivity of ±48
  // semitones, and an average over quantized per-note values lands up to half a unit from the arithmetic
  // expectation, so the tolerance is one unit. Assertions that need finer resolution compare MIDI values.
  private val epsilon: Double = 6e-1
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

  private def tuner1: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 1), MpeZone(MpeZoneType.Upper, 0))
  )

  private def tuner2: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 2), MpeZone(MpeZoneType.Upper, 0))
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

  private def tuner1MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 1), MpeZone(MpeZoneType.Upper, 0)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def tuner2MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 2), MpeZone(MpeZoneType.Upper, 0)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def tuner3MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 3), MpeZone(MpeZoneType.Upper, 0)),
    initialInputMode = MpeInputMode.Mpe
  )

  private def tuner4MpeInput: MpeTuner = MpeTuner(
    initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 4), MpeZone(MpeZoneType.Upper, 0)),
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

  private def extractPitchBendsWithCents(output: Seq[MidiMessage]): Seq[(Int, Int)] =
    extractPitchBends(output).map(msg => (msg.channel, msg.cents.round.toInt))

  private def extractNoteOns(output: Seq[MidiMessage]): Seq[NoteOnScMidiMessage] =
    output.map(_.asScala).collect { case m: NoteOnScMidiMessage => m }.filter(_.velocity > 0)

  private def extractNoteOffs(output: Seq[MidiMessage]): Seq[NoteOffScMidiMessage] =
    output.map(_.asScala).collect {
      case NoteOffScMidiMessage(ch, note, velocity) => NoteOffScMidiMessage(ch, note, velocity)
      case NoteOnScMidiMessage(ch, note, 0) => NoteOffScMidiMessage(ch, note)
    }

  private def extractCc(output: Seq[MidiMessage]): Seq[CcScMidiMessage] =
    output.map(_.asScala).collect { case m: CcScMidiMessage => m }

  private def extractChannelPressures(output: Seq[MidiMessage]): Seq[ChannelPressureScMidiMessage] =
    output.map(_.asScala).collect { case m: ChannelPressureScMidiMessage => m }

  private def extractPolyPressures(output: Seq[MidiMessage]): Seq[PolyPressureScMidiMessage] =
    output.map(_.asScala).collect { case m: PolyPressureScMidiMessage => m }

  private def extractSlides(output: Seq[MidiMessage]): Seq[CcScMidiMessage] =
    extractCc(output).filter(_.number == ScMidiCc.MpeSlide)

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

  private abstract class DistributeFixture extends Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
    // Fill all channels
    private val output1: Seq[MidiMessage] = noteOn(1, D4)
    private val output2: Seq[MidiMessage] = noteOn(1, E4)
    private val output3: Seq[MidiMessage] = noteOn(3, F4)
    private val output4: Seq[MidiMessage] = noteOn(3, G4)
    // D5 must share with D4
    private val output1bis: Seq[MidiMessage] = noteOn(3, D5)

    protected val output1Channel: Int = extractNoteOns(output1).head.channel
    protected val output2Channel: Int = extractNoteOns(output2).head.channel
    protected val output3Channel: Int = extractNoteOns(output3).head.channel
    protected val output4Channel: Int = extractNoteOns(output4).head.channel
    private val output1bisChannel: Int = extractNoteOns(output1bis).head.channel

    output1bisChannel shouldEqual output1Channel
  }

  /** Sends a complete MCM RPN sequence: CC#100=6, CC#101=0, CC#6=memberCount on the given channel. */
  private def sendMcm(tuner: MpeTuner, channel: Int, memberCount: Int): Seq[MidiMessage] = {
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb).asJava)
    tuner.process(CcScMidiMessage(channel, ScMidiCc.DataEntryMsb, memberCount).asJava)
  }

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

  behavior of "MpeTuner - reset() - Non-MPE Input"

  // ---- RPN 0 emission on reset ----

  it should "output Pitch Bend Sensitivity on all channels" in new Fixture(tuner7) {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 2)
    )
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        CcScMidiMessage(ch, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(ch, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(ch, ScMidiCc.DataEntryMsb, 48)
      )
    }
  }

  // ---- State teardown ----

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

  // ---- Note Off emission ----

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

  it should "not emit Note Off messages on reset when no notes are active" in new Fixture {
    // When
    private val resetOutput = tuner.reset()
    // Then
    extractNoteOffs(resetOutput) shouldBe empty
  }

  behavior of "MpeTuner - reset() - MPE Input"

  // ---- RPN 0 emission on reset ----

  it should "output Pitch Bend Sensitivity on all channels" in new Fixture(tuner7) {
    // When
    private val output = tuner.reset()
    // Then
    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 2)
    )
    (1 to 7).foreach { ch =>
      ccs should contain inOrder(
        CcScMidiMessage(ch, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb),
        CcScMidiMessage(ch, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb),
        CcScMidiMessage(ch, ScMidiCc.DataEntryMsb, 48)
      )
    }
  }

  // ---- State teardown ----

  it should "clear internal state after reset" in
    new Fixture(mpeTunerMpeInput, initialTuning = Some(quarterCommaMeantone)) {
      // Given
      // Play a note carrying expression, and leave a CC #74 on another input channel.
      noteOn(1, C4, pbCents = Some(50.0), pressure = Some(32))
      slide(2, 64)

      // When
      // Reset should clear everything
      tuner.reset()

      // Then
      // tune() with no active notes should produce no pitch bend messages
      private var output = tuner.tune(pythagoreanTuning)
      extractPitchBends(output) shouldBe empty

      // The retained Expression Values are back to their defaults: the note carries no expression bend,
      // and neither Channel Pressure nor CC #74 is emitted because both already hold their default.
      output = noteOn(1, C4)
      extractPitchBendsWithCents(output) should contain((1, 0))
      extractChannelPressures(output) shouldBe empty
      extractSlides(output) shouldBe empty

      private val output2 = noteOn(2, D4)
      extractSlides(output2) shouldBe empty
    }

  it should "emit Note Off for all channel notes before resetting state" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      private val out0 = noteOn(0, C4)
      private val out1 = noteOn(1, E4)
      private val out2 = noteOn(2, G4)
      private val ch0 = extractNoteOns(out0).head.channel
      private val ch1 = extractNoteOns(out1).head.channel
      private val ch2 = extractNoteOns(out2).head.channel
      // When
      private val resetOutput = tuner.reset()
      // Then
      private val noteOffs = extractNoteOffs(resetOutput)
      noteOffs should contain(NoteOffScMidiMessage(ch0, C4))
      noteOffs should contain(NoteOffScMidiMessage(ch1, E4))
      noteOffs should contain(NoteOffScMidiMessage(ch2, G4))
    }

  it should "not emit Note Off messages on reset when no notes are active" in new Fixture {
    // When
    private val resetOutput = tuner.reset()
    // Then
    extractNoteOffs(resetOutput) shouldBe empty
  }

  behavior of "MpeTuner - tune() - Non-MPE Input"

  // ---- Output messages when idle ----

  it should "store tuning but output no messages when no active notes" in new Fixture {
    // When
    private val output = tuner.tune(quarterCommaMeantone)
    // Then
    tuner.tuning shouldEqual quarterCommaMeantone
    extractPitchBends(output) shouldBe empty
  }

  // ---- Retune of active notes ----

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
      // Every occupied member channel must receive a pitch bend
      pitchBends.map(_.channel).toSet should contain allOf(noteOnChannel, noteOnChannel2)
      // C and E should have different pitch bends reflecting pythagorean tuning offsets
      pitchBends.size shouldEqual 2
      pitchBends.map(_.cents.round.toInt) should contain theSameElementsInOrderAs Seq(0, 8)
    }

  // ---- Released-channel behavior ----

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

  // ---- Paper worked examples ----

  it should "reproduce paper section \"Tuning change during performance\"" in
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

  behavior of "MpeTuner - tune() - MPE Input"

  // ---- Output messages when idle ----

  it should "store tuning but output no messages when no active notes" in new Fixture(tuner7MpeInput) {
    // When
    private val output = tuner.tune(quarterCommaMeantone)
    // Then
    tuner.tuning shouldEqual quarterCommaMeantone
    extractPitchBends(output) shouldBe empty
  }

  // ---- Member-channel retune ----

  it should "output updated Pitch Bend on each occupied member channel" in
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

  // ---- Expression PB interaction ----

  it should "recompute pitch bend = new tuning offset + current expression pitch bend on each occupied channel" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E has -14.0 in quarter-comma meantone, +8.0 in pythagorean
      private val eExprCents = 20.0
      private val noteOutE = noteOn(1, E4, pbCents = Some(eExprCents))
      private val chE = extractNoteOns(noteOutE).head.channel
      private val noteOutG = noteOn(2, G4)
      private val chG = extractNoteOns(noteOutG).head.channel

      // Apply small expression pitch bends (under the high-bend threshold) per note in MPE mode
      private val gExprCents = -30.0
      pitchBend(2, gExprCents)

      // When
      // Switch tuning — output PB on each channel must combine new tuning offset + expression bend.
      // Compare on MIDI values (not cents) to avoid resolution noise from the cents↔value roundtrip.
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      private val pbByChannel = extractPitchBends(tuneOutput).map(pb => pb.channel -> pb.value).toMap
      private val expectedE = PitchBendScMidiMessage.convertCentsToValue(8.0 + eExprCents, defaultPbs)
      private val expectedG = PitchBendScMidiMessage.convertCentsToValue(2.0 + gExprCents, defaultPbs)
      pbByChannel(chE) shouldBe expectedE
      pbByChannel(chG) shouldBe expectedG
    }

  // ---- Master-channel notes immunity ----

  it should "not retune Master Channel notes on tune() call" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      noteOn(0, C4)
      // When
      private val tuneOutput = tuner.tune(pythagoreanTuning)
      // Then
      extractPitchBends(tuneOutput).filter(_.channel == 0) shouldBe empty
    }

  // ---- Released-channel behavior ----

  it should "not update released channel's pitch bend on tuning changes" in
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

  behavior of "MpeTuner - process() - Basic - Non-MPE Input"

  // ---- Note On output stream ----

  it should "output Pitch Bend, then Note On for single Note On" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(nonMpeInputChannel, C4, 100)
      // Then
      private val msgs = extractScMidiMessages(output)
      private val noteChannel = extractNoteOns(output).head.channel

      // Pitch Bend carries the tuning offset; CC #74 never appears on a Member Channel in this mode and
      // Channel Pressure already holds its default, so both are omitted.
      msgs should contain inOrder(
        PitchBendScMidiMessage(noteChannel, 0),
        NoteOnScMidiMessage(noteChannel, C4, 100)
      )
      extractSlides(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
    }

  it should "preserve Note On velocity" in new Fixture {
    // When
    private val output = noteOn(nonMpeInputChannel, C4, 87)
    // Then
    extractNoteOns(output).head.velocity shouldBe 87
  }

  // ---- Note Off behavior ----

  it should "output Note Off on the correct member channel" in new Fixture {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4)
    // Then
    private val noteOffsChannel = extractNoteOffs(noteOffOutput).head.channel
    noteOffsChannel shouldEqual noteOnChannel
  }

  it should "preserve Note Off velocity" in new Fixture {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4, 73)
    // Then
    extractNoteOffs(noteOffOutput).head.velocity shouldBe 73
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

  it should "reset Channel Pressure before the Note Off when the released note was the last on its channel" in
    new Fixture(tuner7) {
      // Given
      private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
      private val channel = extractNoteOns(noteOnOutput).head.channel
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
      // When
      private val output = noteOff(nonMpeInputChannel, C4)
      // Then
      // In this mode the Tuner is the controller that synthesized the Channel Pressure, so it zeroes it
      // itself — the one control message emitted before the Note Off.
      extractScMidiMessages(output).collect {
        case _: ChannelPressureScMidiMessage => "pressure"
        case _: NoteOffScMidiMessage => "noteOff"
      } shouldEqual Seq("pressure", "noteOff")
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(channel, 0))
    }

  it should "emit the reduced Channel Pressure average after the Note Off when other notes remain" in
    new Fixture(tuner2) {
      // Given
      // PCG=1, EG=1: C4 and C5 take the two channels and C3 shares C4's, the oldest by onset.
      private val out1 = noteOn(nonMpeInputChannel, C4)
      private val sharedChannel = extractNoteOns(out1).head.channel
      noteOn(nonMpeInputChannel, C5)
      extractNoteOns(noteOn(nonMpeInputChannel, C3)).head.channel shouldBe sharedChannel
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C3, 20).asJava)
      // When
      private val output = noteOff(nonMpeInputChannel, C4)
      // Then
      // The channel keeps a note, so the withdrawal reduces the average rather than zeroing it, and the
      // recomputed value follows the Note Off.
      extractScMidiMessages(output).collect {
        case _: ChannelPressureScMidiMessage => "pressure"
        case _: NoteOffScMidiMessage => "noteOff"
      } shouldEqual Seq("noteOff", "pressure")
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(sharedChannel, 20))
    }

  it should "not emit a Channel Pressure reset when the channel already holds the default" in new Fixture(tuner7) {
    // Given
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
    extractNoteOns(noteOnOutput) should have size 1
    // When
    // No Polyphonic Key Pressure ever arrived, so the retained value is already 0.
    private val output = noteOff(nonMpeInputChannel, C4)
    // Then
    extractChannelPressures(output) shouldBe empty
  }

  // ---- Member-channel control-dimension initialization ----

  it should "initialize member channel Pitch Bend to default 0 even after sending a non-0 Pitch Bend on that channel" in
    new Fixture {
      // Given
      pitchBend(nonMpeInputChannel, -33.3)
      // When
      private val output = noteOn(nonMpeInputChannel, C4)
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractPitchBendsWithCents(output) should contain((noteChannel, 0))
    }

  it should "never send CC #74 on a Member Channel" in new Fixture {
    // Given
    // The sender's CC #74 is redirected to the Master Channel, never seeding a Member Channel.
    slide(nonMpeInputChannel, 120)
    // When
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
    private val polyPressureOutput = tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4)
    // Then
    extractSlides(noteOnOutput) shouldBe empty
    extractSlides(polyPressureOutput) shouldBe empty
    extractSlides(noteOffOutput) shouldBe empty
  }

  it should "not send Channel Pressure on a Member Channel at Note On" in new Fixture {
    // Given
    // The sender's Channel Pressure is redirected to the Master Channel.
    pressure(nonMpeInputChannel, 100)
    // When
    private val output = noteOn(nonMpeInputChannel, C4)
    // Then
    // The Member Channel's Channel Pressure already holds its default of 0, so no message is needed.
    extractChannelPressures(output) shouldBe empty
  }

  // ---- Channel allocation across pitch classes ----

  it should "allocate multiple notes with distinct pitch classes to separate member channels" in new Fixture {
    // When
    private val out1 = noteOn(nonMpeInputChannel, C4)
    private val out2 = noteOn(nonMpeInputChannel, E4)
    private val out3 = noteOn(nonMpeInputChannel, G4)
    // Then
    private val channels = Seq(out1, out2, out3).flatMap(extractNoteOns).map(_.channel)
    channels.distinct.size shouldBe 3
  }

  it should "correctly allocate notes from any input channel" in new Fixture {
    // When
    private val out1 = noteOn(0, C4)
    private val out2 = noteOn(5, E4)
    // Then
    extractNoteOns(out1).map(_.channel) should contain(1)
    extractNoteOns(out2).map(_.channel) should contain(2)
  }

  it should "allocate second note with same pitch class to Expression Group" in
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

  it should "route notes to the Lower Zone only when both Zones are enabled" in new Fixture(dualZoneTuner) {
    // Non-MPE input is routed exclusively to the Lower Zone, so the Upper Zone's Member Channels (8..14)
    // are unreachable and wasted — the configuration the Tuner warns about at construction and on reset().
    private val out1 = noteOn(nonMpeInputChannel, C4)
    private val out2 = noteOn(nonMpeInputChannel, E4)
    // Then
    Seq(out1, out2).flatMap(extractNoteOns).map(_.channel).foreach { channel =>
      channel should (be >= 1 and be <= 7)
    }
  }

  // ---- Channel reuse after Note Off ----

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

  // ---- Pitch bend computation ----

  it should "compute output Pitch Bend equal to tuning offset for each pitch class" in
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

  behavior of "MpeTuner - process() - Basic - MPE Input"

  // ---- Note On output stream ----

  it should "output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On" in
    new Fixture(tuner7MpeInput, initialTuning = Some(quarterCommaMeantone)) {
      // When
      // The input channel carries a bend, a pressure and a CC #74 that all differ from the output
      // channel's retained defaults, so all three setup messages are emitted.
      private val output = noteOn(mpeInputChannel, C4, 100,
        pbCents = Some(20.0), pressure = Some(90), slide = Some(100))
      // Then
      private val msgs = extractScMidiMessages(output)
      private val noteChannel = extractNoteOns(output).head.channel
      private val pitchBend = extractPitchBends(output).head

      msgs should contain inOrder(
        pitchBend,
        CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 100),
        ChannelPressureScMidiMessage(noteChannel, 90),
        NoteOnScMidiMessage(noteChannel, C4, 100)
      )
      // C has a 0.0 cents offset in quarter-comma meantone, so the Pitch Bend is the expression component.
      pitchBend.channel shouldBe noteChannel
      pitchBend.cents shouldEqual 20.0
    }

  it should "preserve Note On velocity" in new Fixture(tuner7MpeInput) {
    // When
    private val output = noteOn(mpeInputChannel, C4, 87)
    // Then
    extractNoteOns(output).head.velocity shouldBe 87
  }

  // ---- Note Off behavior ----

  it should "output Note Off on the correct member channel" in new Fixture(tuner7MpeInput) {
    // Given
    private val noteOnOutput = noteOn(mpeInputChannel, C4)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(mpeInputChannel, C4)
    // Then
    private val noteOffsChannel = extractNoteOffs(noteOffOutput).head.channel
    noteOffsChannel shouldEqual noteOnChannel
  }

  it should "preserve Note Off velocity" in new Fixture(tuner7MpeInput) {
    // Given
    private val noteOnOutput = noteOn(mpeInputChannel, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOff(mpeInputChannel, C4, 73)
    // Then
    extractNoteOffs(noteOffOutput).head.velocity shouldBe 73
  }

  it should "treat Note On with velocity 0 as Note Off" in new Fixture(tuner7MpeInput) {
    // Given
    private val noteOnOutput = noteOn(mpeInputChannel, C4, 100)
    private val noteOnChannel = extractNoteOns(noteOnOutput).head.channel
    // When
    private val noteOffOutput = noteOn(mpeInputChannel, C4, 0)
    // Then
    extractNoteOffs(noteOffOutput) should contain(NoteOffScMidiMessage(noteOnChannel, C4))
  }

  it should "emit the Expression Values recomputed over the remaining notes after the Note Off" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // PCG=2, EG=2: E1 takes a Pitch Class Group channel, E3 and E4 fill the Expression Group, and E2
      // shares E1's channel (criterion (c): the oldest onset).
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0), pressure = Some(32), slide = Some(48))
      private val sharedChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3)
      noteOn(4, E4)
      noteOn(2, E2, pbCents = Some(30.0), pressure = Some(96), slide = Some(96))

      // When
      private val output = noteOff(1, E1)

      // Then
      // The Note Off is emitted first, then the values recomputed over E2 alone, in the order
      // Pitch Bend, CC #74, Channel Pressure.
      extractScMidiMessages(output).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
        case cc: CcScMidiMessage if cc.number == ScMidiCc.MpeSlide => "slide"
        case _: ChannelPressureScMidiMessage => "pressure"
      } shouldEqual Seq("noteOff", "pitchBend", "slide", "pressure")

      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(sharedChannel, E1))
      extractPitchBends(output).head.cents shouldEqual (quarterCommaMeantone.e + 30.0)
      extractSlides(output) shouldEqual Seq(CcScMidiMessage(sharedChannel, ScMidiCc.MpeSlide, 96))
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(sharedChannel, 96))
    }

  it should "emit the Note Off alone when the released note was the last on its channel" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val noteOnOutput = noteOn(1, E4, pbCents = Some(30.0), pressure = Some(96), slide = Some(96))
      private val channel = extractNoteOns(noteOnOutput).head.channel
      // When
      private val output = noteOff(1, E4)
      // Then
      // Averaging no longer applies and the channel retains its latest Expression Values, so none of the
      // three changes and none is emitted. In MPE Input Mode the Tuner emits no Channel Pressure reset of
      // its own either: that dimension passes through from the sender.
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractPitchBends(output) shouldBe empty
      extractSlides(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
    }

  // ---- Pitch bend computation ----

  it should "compute output Pitch Bend equal to tuning offset for each pitch class" in
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

  it should "clamp pitch bend to valid range when tuning offset exceeds PBS" in {
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

  // ---- Channel allocation & splitting ----

  it should "split notes with different pitch classes from the same MPE input channel onto different output channels" in
    new Fixture(tuner7MpeInput) {
      // Given
      // C4 on input ch 2 — allocator honors the input channel hint, places C4 on output ch 2.
      private val out1 = noteOn(2, C4)
      private val ch1 = extractNoteOns(out1).head.channel

      // When
      // E4 on the same input ch 2 — different pitch class, so the pitch-class invariant prevents
      // sharing output ch 2 with C4. The allocator must split E4 onto a different output channel.
      private val out2 = noteOn(2, E4)
      private val ch2 = extractNoteOns(out2).head.channel

      // Then
      ch1 shouldBe 2
      ch2 should not be ch1
      ch2 should (be >= 1 and be <= 7)
    }

  it should "correctly allocate notes from any input member channel" in new Fixture(tuner7MpeInput) {
    // When
    private val out1 = noteOn(2, C4)
    private val out2 = noteOn(5, E4)
    // Then
    extractNoteOns(out1).map(_.channel) should contain(2)
    extractNoteOns(out2).map(_.channel) should contain(5)
  }

  it should "leave Pitch Class Group channel unaffected when bending Expression Group channel of same pitch class" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E4 -> PCG channel (E enters Pitch Class Group)
      private val outE4 = noteOn(1, E4)
      private val pcgChannel = extractNoteOns(outE4).head.channel
      private val pcgPitchBends = extractPitchBends(outE4)

      // E5 (same pitch class) -> Expression Group channel (PCG slot for E is already taken)
      private val outE5 = noteOn(2, E5)
      private val egChannel = extractNoteOns(outE5).head.channel
      egChannel should not equal pcgChannel

      // When
      // Send a non-high expression pitch bend on the EG input channel
      private val eExprCents = 30.0
      private val bendOutput = pitchBend(2, eExprCents)
      // Then
      private val egPitchBends = extractPitchBends(bendOutput)

      // Only the EG channel should receive an updated pitch bend; the PCG channel must not.
      egPitchBends.size shouldBe 1
      egPitchBends.head.channel shouldEqual egChannel
      egPitchBends.head.cents shouldEqual (-14.0 + eExprCents)

      pcgPitchBends.size shouldBe 1
      pcgPitchBends.head.channel shouldEqual pcgChannel
      pcgPitchBends.head.cents shouldEqual -14.0
    }

  // ---- Master-Channel note forwarding (Lower / Upper zone) ----

  it should "forward Note On/Off on Master Channels without emitting member-channel setup messages" in
    new Fixture(dualZoneTunerMpeInput, Some(quarterCommaMeantone)) {
      private val table = Table(
        "Master Channel",
        0,
        15
      )

      forAll(table) { masterChannel =>
        // When
        val onOutput = noteOn(masterChannel, C4, 100)
        // Then
        // (a) Note On is forwarded on the Master Channel with the original velocity
        val noteOns = extractNoteOns(onOutput)
        noteOns should have size 1
        noteOns.head shouldEqual NoteOnScMidiMessage(masterChannel, C4, 100)
        // (b) No Pitch Bend / CC #74 / Channel Pressure setup messages on any member channel
        extractPitchBends(onOutput) shouldBe empty
        extractCc(onOutput).filter(_.number == ScMidiCc.MpeSlide) shouldBe empty
        extractChannelPressures(onOutput) shouldBe empty

        // When
        val offOutput = noteOff(masterChannel, C4)
        // Then
        // (c) Note Off is forwarded on the Master Channel
        extractNoteOffs(offOutput) should contain(NoteOffScMidiMessage(masterChannel, C4))
      }
    }

  it should "allow multiple active notes on Master Channels concurrently" in
    new Fixture(dualZoneTunerMpeInput) {
      private val table = Table(
        "Master Channel",
        0,
        15
      )

      forAll(table) { masterChannel =>
        // When
        val out1 = noteOn(0, C4, 100)
        val out2 = noteOn(0, E4, 100)
        // Then
        extractNoteOns(out1).map(n => (n.channel, n.midiNote)) should contain((0, C4))
        extractNoteOns(out2).map(n => (n.channel, n.midiNote)) should contain((0, E4))

        // When
        val offOutput = noteOff(0, C4)
        // Then
        extractNoteOffs(offOutput) should contain(NoteOffScMidiMessage(0, C4))
        // E4 should still be tracked as active
        // When
        val offOutput2 = noteOff(0, E4)
        // Then
        extractNoteOffs(offOutput2) should contain(NoteOffScMidiMessage(0, E4))
      }
    }

  // ---- Master/Member separation ----

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

  it should "route member channel notes to their own zone in dual-zone" in
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

  // ---- Member-channel control-dimension seeding ----

  it should "seed Member Channel Pitch Bend from the per-input-channel value at Note On" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = noteOn(mpeInputChannel, C4, pbCents = Some(-20.0))
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractPitchBendsWithCents(output) should contain((noteChannel, -20))
    }

  it should "seed Member Channel CC #74 from the per-input-channel value at Note On" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = noteOn(mpeInputChannel, C4, slide = Some(100))
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractCc(output) should contain(CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 100))
    }

  it should "seed Member Channel Channel Pressure from the per-input-channel value at Note On" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = noteOn(mpeInputChannel, C4, pressure = Some(90))
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractChannelPressures(output) should contain(ChannelPressureScMidiMessage(noteChannel, 90))
    }

  // ---- Channel reuse after Note Off ----

  it should "make channel available for reuse after Note Off" in
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

  // ---- Paper worked examples ----

  it should "reproduce paper section \"Basic allocation in quarter-comma meantone\"" in
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

  it should "reproduce paper section \"Duplicate Note On messages\" part 1 — the same input channel" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // 1. Note On E4 on input Channel 1: the reference count goes 0 -> 1, so allocation runs and the
      //    tuning Pitch Bend is emitted for the allocating Note On.
      private val out1 = noteOn(1, E4)
      private val channel = extractNoteOns(out1).head.channel
      extractPitchBends(out1).head.cents shouldEqual quarterCommaMeantone.e

      // 2. Channel Pressure 80 on input Channel 1: the channel holds one identity, so its average is 80.
      private val out2 = pressure(1, 80)
      extractChannelPressures(out2) shouldEqual Seq(ChannelPressureScMidiMessage(channel, 80))

      // 3. A second Note On for E4 on input Channel 1, the first still active: the identity is unchanged,
      //    so the count goes 1 -> 2, allocation is bypassed, and overriding the note's Expression Values
      //    with the input channel's current state moves no average — the Note On is emitted alone.
      private val out3 = noteOn(1, E4)
      extractNoteOns(out3) shouldEqual Seq(NoteOnScMidiMessage(channel, E4))
      extractScMidiMessages(out3) should have size 1

      // 4. Note Off E4: the count goes 2 -> 1; the identity stays active and stays in the channel's
      //    averages, so nothing follows the Note Off.
      private val out4 = noteOff(1, E4)
      extractNoteOffs(out4) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractScMidiMessages(out4) should have size 1

      // 5. Note Off E4: the count goes 1 -> 0 and the identity leaves the averages, emptying the channel;
      //    retention leaves all three values unchanged, so the Note Off is again emitted alone.
      private val out5 = noteOff(1, E4)
      extractNoteOffs(out5) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractScMidiMessages(out5) should have size 1

      // Two Note Ons entered and two were forwarded, two Note Offs entered and two were forwarded.
      // A third Note Off finds no count and every message for it is discarded entirely.
      extractScMidiMessages(noteOff(1, E4)) shouldBe empty
    }

  it should "reproduce paper section \"Duplicate Note On messages\" part 2 — different input channels" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // PCG=2, EG=2. Input Channel 1 carries an Expression Pitch Bend of +10 cents and input Channel 2
      // one of −20 cents; neither channel has an active note yet, so nothing is emitted for them.
      extractPitchBends(pitchBend(1, 10.0)) shouldBe empty
      extractPitchBends(pitchBend(2, -20.0)) shouldBe empty

      // 1. Note On E4 on input Channel 1 -> identity (1, E4), Step 1 assigns output Channel 1.
      private val out1 = noteOn(1, E4)
      private val chE = extractNoteOns(out1).head.channel
      chE shouldBe 1
      extractPitchBends(out1).head.cents shouldEqual (quarterCommaMeantone.e + 10.0)

      // 2. Note On G4 on input Channel 1 -> identity (1, G4): the same input channel, a different note
      //    number and hence a different identity, filling the Pitch Class Group.
      private val out2 = noteOn(1, G4)
      private val chG = extractNoteOns(out2).head.channel
      chG should not be chE
      extractPitchBends(out2).head.cents shouldEqual (quarterCommaMeantone.g + 10.0)

      // 3. C4 and A4 fill the Expression Group; all four Member Channels are now occupied.
      noteOn(3, C4)
      noteOn(4, A4)

      // 4. Note On E4 on input Channel 2 -> identity (2, E4), distinct from (1, E4). Steps 1 and 2 fail,
      //    so Step 3 assigns the channel already holding pitch class E, and its Expression Pitch Bend
      //    becomes the average of the two identities.
      private val out4 = noteOn(2, E4)
      extractNoteOns(out4).head.channel shouldBe chE
      extractPitchBends(out4).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 - 20.0) / 2)

      // The fan-out that accompanies this fan-in: a Pitch Bend on input Channel 1 reaches both output
      // channels its notes were placed on, and only its own note's contribution moves on the shared one.
      private val bendOutput = pitchBend(1, 20.0)
      private val bends = extractPitchBends(bendOutput).map(pb => pb.channel -> pb.cents).toMap
      bends.keySet shouldEqual Set(chE, chG)
      bends(chE) shouldEqual (quarterCommaMeantone.e + (20.0 - 20.0) / 2)
      bends(chG) shouldEqual (quarterCommaMeantone.g + 20.0)

      // Both reference counts remain 1: no merging occurred, so each identity is released by its own
      // Note Off and both are forwarded on the shared channel.
      extractNoteOffs(noteOff(1, E4)) shouldEqual Seq(NoteOffScMidiMessage(chE, E4))
      extractNoteOffs(noteOff(2, E4)) shouldEqual Seq(NoteOffScMidiMessage(chE, E4))
    }

  behavior of "MpeTuner - process() - Expression - Non-MPE Input"

  // ---- Zone-level redirection from Pitch Bend ----

  it should "redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend" in new Fixture {
    // When
    private var output = pitchBend(nonMpeInputChannel, 50.0)
    // Then
    private var pitchBends = extractPitchBendsWithCents(output)
    pitchBends should contain theSameElementsAs Seq((0, 50))

    // When
    noteOn(nonMpeInputChannel, E4)
    output = pitchBend(nonMpeInputChannel, 25.0)
    // Then
    pitchBends = extractPitchBendsWithCents(output)
    pitchBends should contain theSameElementsAs Seq((0, 25))
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

  // ---- Zone-level redirection from Channel Pressure / Slide ----

  it should "redirect input Channel Pressure to Master Channel as Zone-level Channel Pressure" in new Fixture {
    // When
    private var output = pressure(nonMpeInputChannel, 32)
    // Then
    private var channelPressures = extractChannelPressures(output)
    channelPressures should contain theSameElementsAs Seq(ChannelPressureScMidiMessage(0, 32))

    // When
    noteOn(nonMpeInputChannel, E4)
    output = pressure(nonMpeInputChannel, 25)
    // Then
    channelPressures = extractChannelPressures(output)
    channelPressures should contain theSameElementsAs Seq(ChannelPressureScMidiMessage(0, 25))
  }

  it should "redirect input Slide CC #74 to Master Channel as Zone-level Slide CC #74" in new Fixture {
    // When
    private var output = slide(nonMpeInputChannel, 72)
    // Then
    private var slides = extractSlides(output)
    slides should contain theSameElementsAs Seq(CcScMidiMessage(0, ScMidiCc.MpeSlide, 72))

    // When
    noteOn(nonMpeInputChannel, E4)
    output = slide(nonMpeInputChannel, 96)
    // Then
    slides = extractSlides(output)
    slides should contain theSameElementsAs Seq(CcScMidiMessage(0, ScMidiCc.MpeSlide, 96))
  }

  // ---- PolyPressure → Channel Pressure conversion ----

  it should "convert Polyphonic Key Pressure to Channel Pressure on member channel" in new Fixture {
    // Given
    private val noteOutput = noteOn(nonMpeInputChannel, C4)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    // When
    private val output = tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
    // Then
    extractChannelPressures(output) should contain(ChannelPressureScMidiMessage(noteChannel, 80))
    extractPolyPressures(output) shouldBe empty
  }

  it should "ignore Polyphonic Key Pressure for non-active notes" in new Fixture {
    // Given
    private val noteOutput = noteOn(nonMpeInputChannel, C4)
    private val noteChannel = extractNoteOns(noteOutput).head.channel
    // When
    private val output = tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, D4, 80).asJava)
    // Then
    extractChannelPressures(output) shouldBe empty
    extractPolyPressures(output) shouldBe empty
  }

  behavior of "MpeTuner - process() - Expression - MPE Input"

  // ---- Per-note PB (combined with tuning offset) ----

  it should "treat per-note pitch bend as expression pitch bend combined with tuning offset" in
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

      // Output pitch bend should combine tuning offset for E (-14.0) + expression bend
      pitchBendMsg.cents shouldEqual (-14.0 + eExprCents)
    }

  // ---- Fan-out across split notes (PB / CC #74 / CP) ----

  it should "fan out expression Pitch Bend to all output channels for split notes from same MPE input channel" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val out1 = noteOn(2, C4)
      private val ch1 = extractNoteOns(out1).head.channel
      private val out2 = noteOn(2, E4)
      private val ch2 = extractNoteOns(out2).head.channel

      private val exprCents = 30.0
      // When
      private val output = pitchBend(2, exprCents)

      // Then
      // Both output channels must receive the expression bend on top of their tuning offset.
      // C: 0.0 cents, E: -14.0 cents in quarter-comma meantone.
      private val pbs = extractPitchBends(output)
      private val ch1Pb = pbs.find(_.channel == ch1).value
      private val ch2Pb = pbs.find(_.channel == ch2).value
      ch1Pb.cents shouldEqual (0.0 + exprCents)
      ch2Pb.cents shouldEqual (-14.0 + exprCents)
    }

  it should "fan out CC #74 to all output channels for split notes from same MPE input channel" in
    new Fixture(tuner7MpeInput) {
      // Given
      private val out1 = noteOn(2, C4)
      private val ch1 = extractNoteOns(out1).head.channel
      private val out2 = noteOn(2, E4)
      private val ch2 = extractNoteOns(out2).head.channel

      // When
      private val output = slide(2, 100)

      // Then
      private val slides = extractSlides(output).map(cc => (cc.channel, cc.value)).toSet
      slides should contain((ch1, 100))
      slides should contain((ch2, 100))
    }

  it should "fan out Channel Pressure to all output channels for split notes from same MPE input channel" in
    new Fixture(tuner7MpeInput) {
      // Given
      private val out1 = noteOn(2, C4)
      private val ch1 = extractNoteOns(out1).head.channel
      private val out2 = noteOn(2, E4)
      private val ch2 = extractNoteOns(out2).head.channel

      // When
      private val output = pressure(2, 90)

      // Then
      private val cps = extractChannelPressures(output).map(cp => (cp.channel, cp.value)).toSet
      cps should contain((ch1, 90))
      cps should contain((ch2, 90))
    }

  // ---- Forward to allocated Member Channel when an active note exists ----

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
      extractChannelPressures(output) should contain(ChannelPressureScMidiMessage(noteChannel, 90))
    }

  // ---- Gating: no active note on input channel ----

  it should "not forward Pitch Bend on an MPE input member channel with no active note" in
    new Fixture(tuner7MpeInput) {
      // When
      // Send Pitch Bend on a member channel that has no active note
      private val output = pitchBend(mpeInputChannel, 16.67)
      // Then
      extractPitchBends(output) shouldBe empty
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
      extractChannelPressures(output) shouldBe empty
    }

  // ---- Expression after Note Off ----

  it should "not forward control dimensions from an input channel after its notes have been released" in
    new Fixture(tuner7MpeInput) {
      // Given
      // Note On routes mpeInputChannel -> some output channel
      noteOn(mpeInputChannel, C4)
      noteOff(mpeInputChannel, C4)

      // When / Then
      // After Note Off, no input->output mapping should exist for mpeInputChannel — expression
      // CC #74 / Channel Pressure / Pitch Bend on this input channel must NOT be forwarded to a
      // (now stale) member channel.
      private val ccOutput = slide(mpeInputChannel, 100)
      extractCc(ccOutput) shouldBe empty

      private val cpOutput = pressure(mpeInputChannel, 90)
      extractChannelPressures(cpOutput) shouldBe empty

      private val pbOutput = pitchBend(mpeInputChannel, 33.33)
      extractPitchBends(pbOutput) shouldBe empty
    }

  // ---- Master-channel PB forwarding ----

  it should "forward Master Channel pitch bend without modification" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = tuner.process(PitchBendScMidiMessage(0, 1000).asJava)
    // Then
    extractPitchBends(output) should contain(PitchBendScMidiMessage(0, 1000))
  }

  // ---- Master/Member-channel PolyPressure handling ----

  it should "forward Polyphonic Key Pressure as-is for Master Channel notes" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(0, C4, 100)
      // When
      private val output = tuner.process(PolyPressureScMidiMessage(0, C4, 80).asJava)
      // Then
      extractPolyPressures(output) should contain(PolyPressureScMidiMessage(0, C4, 80))
      extractChannelPressures(output) shouldBe empty
    }

  it should "drop Polyphonic Key Pressure received on a Member Channel" in
    new Fixture(mpeTunerMpeInput) {
      // Given
      noteOn(mpeInputChannel, C4, 100)
      // When
      private val output = tuner.process(PolyPressureScMidiMessage(mpeInputChannel, C4, 80).asJava)
      // Then
      extractPolyPressures(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
    }

  // ---- Averaging across active notes on member channel ----

  it should "average the expression pitch bend value of all active notes on a member channel" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given: PCG=2, EG=2
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3)
      noteOn(4, E4)

      // When: 1 PCG free, but cannot be used for E => new E2 will share channel with E1
      private var output = noteOn(2, E2, pbCents = Some(-20.0))
      // Then
      private var outputPitchBends = extractPitchBends(output)
      outputPitchBends should have size 1
      outputPitchBends.head.channel shouldEqual e1OutputChannel
      outputPitchBends.head.cents shouldEqual (quarterCommaMeantone.e + (10.0 - 20.0) / 2)

      // When
      output = pitchBend(2, 30.0)
      // Then
      outputPitchBends = extractPitchBends(output)
      outputPitchBends.head.channel shouldEqual e1OutputChannel
      outputPitchBends.head.cents shouldEqual (quarterCommaMeantone.e + (10.0 + 30.0) / 2)
    }

  it should "average the channel pressure value of all active notes on a member channel" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given: PCG=2, EG=2
      private val e1Output = noteOn(1, E1, pressure = Some(32))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3)
      noteOn(4, E4)

      // When: 1 PCG free, but cannot be used for E => new E2 will share channel with E1
      private var output = noteOn(2, E2, pressure = Some(96))
      // Then
      private var outputPressures = extractChannelPressures(output)
      outputPressures should have size 1
      outputPressures.head.channel shouldEqual e1OutputChannel
      outputPressures.head.value shouldEqual (32 + 96) / 2

      // When
      output = pressure(2, 16)
      // Then
      outputPressures = extractChannelPressures(output)
      outputPressures should have size 1
      outputPressures.head.channel shouldEqual e1OutputChannel
      outputPressures.head.value shouldEqual (32 + 16) / 2
    }

  it should "average the MPE slide (CC #74) value of all active notes on a member channel" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given: PCG=2, EG=2
      private val e1Output = noteOn(1, E1, slide = Some(48))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3)
      noteOn(4, E4)

      // When: 1 PCG free, but cannot be used for E => new E2 will share channel with E1
      private var output = noteOn(2, E2, slide = Some(16))
      // Then
      private var outputSlides = extractSlides(output)
      outputSlides should have size 1
      outputSlides.head.channel shouldEqual e1OutputChannel
      outputSlides.head.value shouldEqual (48 + 16) / 2

      // When
      output = slide(2, 96)
      // Then
      outputSlides = extractSlides(output)
      outputSlides should have size 1
      outputSlides.head.channel shouldEqual e1OutputChannel
      outputSlides.head.value shouldEqual (48 + 96) / 2
    }

  // ---- Distributing across input channel ----

  it should "distribute the pitch bend values of the input channel" in new DistributeFixture {
    // When
    private val pitchBends1 = extractPitchBends(pitchBend(1, 10.0))
    private val pitchBends3 = extractPitchBends(pitchBend(3, 30.0))

    // Then
    // Input channel 1 feeds output channels 1 and 2. Output channel 1 also holds D5, which arrived on
    // input channel 3 and still carries no bend, so its Expression Pitch Bend is the average of the two.
    pitchBends1.map(_.channel) shouldEqual Seq(output1Channel, output2Channel)
    pitchBends1.head.cents shouldEqual (quarterCommaMeantone.d + (10.0 + 0.0) / 2)
    pitchBends1(1).cents shouldEqual (quarterCommaMeantone.e + 10.0)

    pitchBends3.map(_.channel) shouldEqual Seq(output3Channel, output4Channel, output1Channel)
    pitchBends3.head.cents shouldEqual (quarterCommaMeantone.f + 30.0)
    pitchBends3(1).cents shouldEqual (quarterCommaMeantone.g + 30.0)
    pitchBends3(2).cents shouldEqual (quarterCommaMeantone.d + (10.0 + 30.0) / 2)
  }

  it should "distribute the channel pressure values of the input channel" in new DistributeFixture {
    // When
    private val channelPressures1 = extractChannelPressures(pressure(1, 10))
    private val channelPressures3 = extractChannelPressures(pressure(3, 30))

    // Then
    // Output channel 1 also holds D5, which arrived on input channel 3 and still carries pressure 0, so
    // the channel emits the average of the two notes.
    channelPressures1 should contain theSameElementsAs Seq(
      ChannelPressureScMidiMessage(output1Channel, (10 + 0) / 2),
      ChannelPressureScMidiMessage(output2Channel, 10)
    )
    channelPressures3 should contain theSameElementsAs Seq(
      ChannelPressureScMidiMessage(output3Channel, 30),
      ChannelPressureScMidiMessage(output4Channel, 30),
      ChannelPressureScMidiMessage(output1Channel, (10 + 30) / 2)
    )
  }

  it should "distribute the slide values of the input channel" in new DistributeFixture {
    // When
    private val slides1 = extractSlides(slide(1, 10))
    private val slides3 = extractSlides(slide(3, 30))

    // Then
    // Output channel 1 also holds D5, which arrived on input channel 3 and still carries the default CC #74
    // of 64, so the channel emits the average of the two notes.
    slides1 should contain theSameElementsAs Seq(
      CcScMidiMessage(output1Channel, ScMidiCc.MpeSlide, (10 + 64) / 2),
      CcScMidiMessage(output2Channel, ScMidiCc.MpeSlide, 10)
    )
    slides3 should contain theSameElementsAs Seq(
      CcScMidiMessage(output3Channel, ScMidiCc.MpeSlide, 30),
      CcScMidiMessage(output4Channel, ScMidiCc.MpeSlide, 30),
      CcScMidiMessage(output1Channel, ScMidiCc.MpeSlide, (10 + 30) / 2)
    )
  }

  // ---- Paper worked examples ----

  it should "reproduce paper section \"Averaging Expression Values\"" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // 1. E1 arrives on input Channel 1, which carries Pitch Bend +10 cents, Channel Pressure 32 and
      //    CC #74 48 — remembered from before the note and used to initialize its Expression Values.
      //    Step 1 assigns output Channel 1.
      private val out1 = noteOn(1, E1, pbCents = Some(10.0), pressure = Some(32), slide = Some(48))
      private val ch = extractNoteOns(out1).head.channel
      ch shouldBe 1
      extractPitchBends(out1).head.cents shouldEqual (quarterCommaMeantone.e + 10.0)
      extractSlides(out1) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, 48))
      extractChannelPressures(out1) shouldEqual Seq(ChannelPressureScMidiMessage(ch, 32))

      // 2. E3 and E4 arrive on input Channels 3 and 4, both at default expression: pitch class E is
      //    already in the Pitch Class Group, so Step 2 places them in the Expression Group, which is now
      //    at full capacity. Each emits only its tuning Pitch Bend.
      private val out2 = noteOn(3, E3)
      private val out3 = noteOn(4, E4)
      extractPitchBends(out2).head.cents shouldEqual quarterCommaMeantone.e
      extractSlides(out2) shouldBe empty
      extractChannelPressures(out2) shouldBe empty
      extractPitchBends(out3).head.cents shouldEqual quarterCommaMeantone.e

      // 3. E2 arrives on input Channel 2 carrying Pitch Bend −20 cents, Channel Pressure 96 and CC #74 96.
      //    Both groups are unavailable for it, so Step 3 shares the oldest E channel and all three
      //    Expression Values become averages.
      private val out4 = noteOn(2, E2, pbCents = Some(-20.0), pressure = Some(96), slide = Some(96))
      extractNoteOns(out4).head.channel shouldBe ch
      extractPitchBends(out4).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 - 20.0) / 2)
      extractSlides(out4) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, (48 + 96) / 2))
      extractChannelPressures(out4) shouldEqual Seq(ChannelPressureScMidiMessage(ch, (32 + 96) / 2))

      // 4. The performer bends E2 to +30 cents: the channel's Expression Pitch Bend becomes +20 — the
      //    half-amplitude attenuation of a shared channel — and no note is dropped, the threshold applying
      //    to a note's own bend.
      private val out5 = pitchBend(2, 30.0)
      extractPitchBends(out5) should have size 1
      extractPitchBends(out5).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 + 30.0) / 2)
      extractNoteOffs(out5) shouldBe empty

      // 5. Note Off for E1: the Note Off is emitted first and the values recomputed without it follow.
      //    The Channel Pressure becomes the surviving note's own value rather than 0: in MPE Input Mode
      //    the dimension passes through from the sender.
      private val out6 = noteOff(1, E1)
      extractScMidiMessages(out6).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
        case cc: CcScMidiMessage if cc.number == ScMidiCc.MpeSlide => "slide"
        case _: ChannelPressureScMidiMessage => "pressure"
      } shouldEqual Seq("noteOff", "pitchBend", "slide", "pressure")
      extractPitchBends(out6).head.cents shouldEqual (quarterCommaMeantone.e + 30.0)
      extractSlides(out6) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, 96))
      extractChannelPressures(out6) shouldEqual Seq(ChannelPressureScMidiMessage(ch, 96))

      // 6. Note Off for E2, the channel's last active note: removal empties the channel, so averaging no
      //    longer applies and retention fixes what it keeps. None of the three values changes, so the
      //    Note Off is emitted alone — the Channel Pressure in particular is not zeroed.
      private val out7 = noteOff(2, E2)
      extractNoteOffs(out7) shouldEqual Seq(NoteOffScMidiMessage(ch, E2))
      extractPitchBends(out7) shouldBe empty
      extractSlides(out7) shouldBe empty
      extractChannelPressures(out7) shouldBe empty
    }

  behavior of "MpeTuner - process() - Note Dropping - Non-MPE Input"

  // ---- Single-channel edge case ----

  it should "free a single channel during exhaustion dropping when there is a single member channel" in
    new Fixture(tuner1) {
      // Given
      noteOn(nonMpeInputChannel, C4)
      // When
      private val output = noteOn(nonMpeInputChannel, E4)
      // Then
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      droppedNotes should contain(C4)
    }

  // ---- Channel exhaustion dropping (with Note Off output) ----

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
    }

  // ---- Drop policy: preserve highest / lowest ----

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
    // Oldest note, but will not be dropped since it's the highest.
    noteOn(nonMpeInputChannel, G4) // highest
    noteOn(nonMpeInputChannel, C4) // lowest
    noteOn(nonMpeInputChannel, E4) // middle
    // When
    private val output = noteOn(nonMpeInputChannel, A4)
    // Then
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should not contain G4
  }

  it should "preserve the highest and drop the lowest note during channel exhaustion dropping when there are only" +
    " 2 candidate channels" in new Fixture(tuner2) {
    // Given
    noteOn(nonMpeInputChannel, G4) // highest
    noteOn(nonMpeInputChannel, C4) // lowest
    // When
    private val output = noteOn(nonMpeInputChannel, E4)
    // Then
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should contain(C4)
  }

  behavior of "MpeTuner - process() - Note Dropping - MPE Input"

  // ---- Channel exhaustion dropping (mirrors Non-MPE) ----

  it should "trigger note dropping with Note Off output for dropped notes on channel exhaustion" in
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
    }

  it should "preserve the lowest note during channel exhaustion dropping" in
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

  it should "preserve the highest note during channel exhaustion dropping" in
    new Fixture(tuner3MpeInput) {
      // Given
      // Oldest note, but will not be dropped since it's the highest.
      noteOn(3, G4) // highest
      noteOn(1, C4) // lowest
      noteOn(2, E4) // middle
      // When
      private val output = noteOn(1, A4)
      // Then
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      droppedNotes should not contain G4
    }

  it should "preserve the highest and drop the lowest note during channel exhaustion dropping when there are only" +
    " 2 candidate channels" in new Fixture(tuner2MpeInput) {
    // Given
    noteOn(2, G4) // highest
    noteOn(1, C4) // lowest
    // When
    private val output = noteOn(1, E4)
    // Then
    private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
    droppedNotes should contain(C4)
  }

  it should "emit a dropped note's Note Off before the incoming note's own setup messages" in
    new Fixture(tuner1MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // A single Member Channel, so the incoming note reuses the very channel it frees. Emitting the setup
      // messages first would retune C4 on its way out.
      private val c4Output = noteOn(1, C4)
      private val channel = extractNoteOns(c4Output).head.channel

      // When
      private val output = noteOn(1, E4)

      // Then
      extractScMidiMessages(output).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
        case _: NoteOnScMidiMessage => "noteOn"
      } shouldEqual Seq("noteOff", "pitchBend", "noteOn")

      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(channel, C4))
      extractPitchBends(output).head.cents shouldEqual quarterCommaMeantone.e
    }

  it should "emit one Note Off per forwarded Note On when a duplicated note is dropped" in
    new Fixture(tuner1MpeInput) {
      // Given
      // A single Member Channel and two Note Ons for the same identity, so the Tuner has forwarded two Note
      // Ons and owes two Note Offs for it.
      private val c4Output = noteOn(1, C4)
      private val channel = extractNoteOns(c4Output).head.channel
      noteOn(1, C4)

      // When
      // E4 needs the only channel, dropping the duplicated C4.
      private val output = noteOn(1, E4)

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(channel, C4),
        NoteOffScMidiMessage(channel, C4)
      )
    }

  it should "discard the Note Off of a note the Tuner has dropped" in new Fixture(tuner3MpeInput) {
    // Given
    // PCG=1, EG=2: C4, E4 and G4 fill the three Member Channels; A4 then forces a channel to be freed and
    // the middle note E4 is the only non-boundary candidate.
    noteOn(1, C4)
    private val e4Output = noteOn(2, E4)
    private val e4Channel = extractNoteOns(e4Output).head.channel
    noteOn(3, G4)
    private val dropOutput = noteOn(1, A4)
    extractNoteOffs(dropOutput) shouldEqual Seq(NoteOffScMidiMessage(e4Channel, E4))

    // When
    // The performer eventually releases the note the Tuner had already dropped.
    private val output = noteOff(2, E4)

    // Then
    // No second Note Off downstream: the Tuner has already discharged this note's obligation.
    extractNoteOffs(output) shouldBe empty
    // And no stale binding steers a later Expression Value update at the dropped note's former channel.
    extractPitchBends(pitchBend(2, 20.0)) shouldBe empty
  }

  // ---- Single-channel edge case ----

  it should "free a single channel during exhaustion dropping when there is a single member channel" in
    new Fixture(tuner1MpeInput) {
      // Given
      noteOn(1, C4)
      // When
      private val output = noteOn(1, E4)
      // Then
      private val droppedNotes = extractNoteOffs(output).map(_.midiNote)
      droppedNotes should contain(C4)
    }

  // ---- High-expression-PB dropping — incoming-note triggered (future-work truth table) ----

  it should "drop a channel with high expression PB to make room for an incoming note with low expression PB" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val e1Output = noteOn(1, E1, pbCents = Some(110.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3, pbCents = Some(130.0))
      noteOn(4, E4, pbCents = Some(140.0))

      // When
      private val output = noteOn(2, E2, pbCents = Some(20.0))

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(e1OutputChannel, E1)
      )
    }

  it should "drop a channel with high expression PB to make room for an incoming note with high expression PB" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val e1Output = noteOn(1, E1, pbCents = Some(110.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3, pbCents = Some(130.0))
      noteOn(4, E4, pbCents = Some(140.0))

      // When
      private val output = noteOn(2, E2, pbCents = Some(120.0))

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(e1OutputChannel, E1)
      )
    }

  it should "drop a channel with low expression PB to make room for an incoming note with high expression PB" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3, pbCents = Some(30.0))
      noteOn(4, E4, pbCents = Some(40.0))

      // When
      private val output = noteOn(2, E2, pbCents = Some(120.0))

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(e1OutputChannel, E1)
      )
    }

  it should "prefer to drop a channel with low expression PB to make room for an incoming note with high " +
    "expression PB" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      noteOn(1, E1, pbCents = Some(110.0))
      private val e3Output = noteOn(3, E3, pbCents = Some(30.0))
      private val e3OutputChannel = extractNoteOns(e3Output).head.channel
      noteOn(4, E4, pbCents = Some(40.0))

      // When
      private val output = noteOn(2, E2, pbCents = Some(120.0))

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(e3OutputChannel, E3)
      )
    }

  // ---- High-expression-PB dropping — runtime developed ----

  it should "drop other notes on a shared channel when one note develops a high expression pitch bend" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3, pbCents = Some(30.0))
      noteOn(4, E4, pbCents = Some(40.0))

      // Will share
      private val e2Output = noteOn(2, E2, pbCents = Some(12.0))
      private val e2OutputChannel = extractNoteOns(e2Output).head.channel
      e1OutputChannel shouldEqual e2OutputChannel

      // When
      private val output = pitchBend(1, 101.0)

      // Then
      extractNoteOffs(output) shouldEqual Seq(
        NoteOffScMidiMessage(e1OutputChannel, E2)
      )
    }

  it should "not drop other notes on a shared channel when one note develops a low expression pitch bend" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0))
      private val e1OutputChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3, pbCents = Some(30.0))
      noteOn(4, E4, pbCents = Some(40.0))

      // Will share
      private val e2Output = noteOn(2, E2, pbCents = Some(12.0))
      private val e2OutputChannel = extractNoteOns(e2Output).head.channel
      e1OutputChannel shouldEqual e2OutputChannel

      // When
      private val output = pitchBend(1, 49.0)

      // Then
      extractNoteOffs(output) shouldBe empty
    }

  // ---- Shared-channel dropping with common input channel ----

  it should "keep the most recently sounded note on a shared channel with a common input channel when a high " +
    "expression pitch bend is received on it" in
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
      // One Pitch Bend message gives both notes a High Expression Pitch Bend (> 50 cents), so the
      // divergence rule keeps the most recently sounded (E5) and drops the other.
      private val output = pitchBend(1, 100.0)
      // Then
      private val noteOffs = extractNoteOffs(output).map(n => (n.channel, n.midiNote))
      noteOffs should contain theSameElementsAs Seq((sharedChannel, E4))
    }

  // ---- Paper worked examples ----

  it should "reproduce paper section \"Note dropping under High Expression Pitch Bend\"" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // The state reached at step 4 of "Averaging Expression Values": E1 (input Channel 1, +10 cents) and
      // E2 (input Channel 2, +30 cents) share an output channel, averaging to +20.
      private val out1 = noteOn(1, E1, pbCents = Some(10.0))
      private val ch = extractNoteOns(out1).head.channel
      noteOn(3, E3)
      noteOn(4, E4)
      extractNoteOns(noteOn(2, E2, pbCents = Some(30.0))).head.channel shouldBe ch

      // When
      // The performer sends Pitch Bend +101 cents on input Channel 1: the value belongs to E1, which
      // thereby acquires a High Expression Pitch Bend.
      private val output = pitchBend(1, 101.0)

      // Then
      // E1 shares its channel, so the divergence rule drops E2 — whose own bend is well below the
      // threshold. The Note Off comes first, carrying the neutral release velocity 64 that any note ended
      // by the Tuner's decision receives, and the recomputed Pitch Bend follows: emitting it first would
      // sweep E2 to E1's bend on its way out.
      extractScMidiMessages(output).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
      } shouldEqual Seq("noteOff", "pitchBend")
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(ch, E2, 64))
      extractPitchBends(output) should have size 1
      extractPitchBends(output).head.channel shouldBe ch
      extractPitchBends(output).head.cents shouldEqual (quarterCommaMeantone.e + 101.0)
    }

  behavior of "MpeTuner - process() - Zone-level Messages - Non-MPE Input"

  // ---- Zone-level CCs forwarded to Master Channel ----

  it should "forward CCs on Master Channel" in new Fixture {
    private val ccs = Table(
      ("ccName", "ccNumber", "ccValue"),
      ("Bank Select MSB", ScMidiCc.BankSelectMsb, 1),
      ("Bank Select LSB", ScMidiCc.BankSelectLsb, 0),
      ("Reset All Controllers", ScMidiCc.ResetAllControllers, 0),
      ("Modulation", ScMidiCc.ModulationMsb, 64),
      ("Sostenuto Pedal", ScMidiCc.SostenutoPedal, 127),
      ("Soft Pedal", ScMidiCc.SoftPedal, 127)
    )
    forAll(ccs) { (_, ccNumber, ccValue) =>
      // When
      val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ccNumber, ccValue).asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(0, ccNumber, ccValue))
    }
  }

  it should "forward Sustain Pedal (CC #64) on Master Channel" in new Fixture {
    // When
    private val output = tuner.process(CcScMidiMessage(nonMpeInputChannel, ScMidiCc.SustainPedal, 127)
      .asJava)
    // Then
    extractCc(output) should contain(CcScMidiMessage(0, ScMidiCc.SustainPedal, 127))
  }

  // ---- Other zone-level messages forwarded to Master Channel ----

  it should "forward Program Change on Master Channel" in new Fixture {
    // When
    private val output = tuner.process(ProgramChangeScMidiMessage(nonMpeInputChannel, 5).asJava)
    // Then
    private val programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
    programChanges should contain(ProgramChangeScMidiMessage(0, 5))
  }

  behavior of "MpeTuner - process() - Zone-level Messages - MPE Input"

  // ---- Forwarding to zone Master Channel (single-zone) ----

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

  // ---- Routing to upper zone Master Channel (dual-zone) ----

  it should "route zone-level CC to the appropriate zone Master Channel when received on a member channel" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      // lower zone: members 1-7, master 0
      private var output = tuner.process(CcScMidiMessage(3, ScMidiCc.SustainPedal, 72).asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(0, ScMidiCc.SustainPedal, 72))

      // When
      // upper zone: members 8-14, master 15
      output = tuner.process(CcScMidiMessage(8, ScMidiCc.SustainPedal, 127).asJava)
      // Then
      extractCc(output) should contain(CcScMidiMessage(15, ScMidiCc.SustainPedal, 127))
    }

  it should "route Program Change to the appropriate zone Master Channel when received on a member channel" in
    new Fixture(dualZoneTunerMpeInput) {
      // When
      private var output = tuner.process(ProgramChangeScMidiMessage(4, 6).asJava)
      // Then
      private var programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
      programChanges should contain(ProgramChangeScMidiMessage(0, 6))

      // When
      output = tuner.process(ProgramChangeScMidiMessage(8, 5).asJava)
      // Then
      programChanges = output.map(_.asScala).collect { case m: ProgramChangeScMidiMessage => m }
      programChanges should contain(ProgramChangeScMidiMessage(15, 5))
    }

  behavior of "MpeTuner - MCM Processing - Non-MPE Input"

  // ---- Mode switching ----

  it should "switch input mode to MPE automatically when an MCM is received" in new Fixture {
    // Given
    tuner.inputMode shouldBe MpeInputMode.NonMpe

    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)

    // Then
    tuner.inputMode shouldBe MpeInputMode.Mpe

    private val ccs = extractCc(output)
    ccs should contain inOrder(
      CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.MpeConfigurationMessageLsb),
      CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.MpeConfigurationMessageMsb),
      CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 7)
    )
    tuner.zones.lower.memberCount shouldEqual 7
    tuner.zones.upper.memberCount shouldEqual 0
  }

  behavior of "MpeTuner - MCM Processing - MPE Input"

  // ---- MCM emission on reset ----

  it should "output MPE Configuration Message (MCM) for the configured zone" in new Fixture(mpeTunerMpeInput) {
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

  // ---- MCM-driven zone reconfiguration ----

  it should "reconfigure lower zone on MCM received on channel 0" in new Fixture(dualZoneTunerMpeInput) {
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

  it should "reconfigure upper zone on MCM received on channel 15" in new Fixture(dualZoneTunerMpeInput) {
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

  it should "disable zone when MCM with memberCount=0 is received" in new Fixture(dualZoneTunerMpeInput) {
    // When
    private val output = sendMcm(tuner, channel = 15, memberCount = 0)
    // Then
    private val ccs = extractCc(output)
    // Upper zone MCM should be sent to the output even if the zone is disabled to inform the downstream device
    ccs should contain(CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 0))
    // Lower zone MCM should NOT be present because the lower zone was not affected
    ccs should not contain CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 7)
  }

  it should "shrink other zone when MCM causes overlap" in new Fixture(dualZoneTunerMpeInput) {
    // dualZoneTunerMpeInput: lower=7, upper=7
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

  // ---- Effects on active notes / other state ----

  it should "stop all active notes when MCM is received" in new Fixture(mpeTunerMpeInput) {
    // Given - Play notes on MPE member channels
    noteOn(2, C4)
    noteOn(2, E4)
    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    private val noteOffs = extractNoteOffs(output)
    noteOffs.map(_.midiNote) should contain allOf(C4, E4)
  }

  it should "reset PBS to defaults when MCM is received" in new Fixture(tuner7MpeInput) {
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

  it should "not output PBS messages after MCM" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = sendMcm(tuner, channel = 0, memberCount = 7)
    // Then
    private val ccs = extractCc(output)
    // PBS RPN uses LSB=0, while MCM RPN uses LSB=6; no PBS RPN should appear in the output
    private val pbsRpnMessages = ccs.filter(cc =>
      cc.number == ScMidiCc.RpnLsb && cc.value == ScMidiRpn.PitchBendSensitivityLsb)
    pbsRpnMessages shouldBe empty
  }

  // ---- RPN sequence validation gating ----

  it should "not trigger MCM on incomplete RPN sequence" in new Fixture(mpeTunerMpeInput) {
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

  it should "not trigger MCM for non-MCM RPN (e.g. PBS RPN)" in new Fixture(mpeTunerMpeInput) {
    // When - Send PBS RPN (MSB=0, LSB=0) instead of MCM RPN (MSB=0, LSB=6)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    private val output = tuner.process(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 48).asJava)
    // Then - Should NOT contain MCM reconfiguration output
    private val ccs = extractCc(output)
    ccs.filter(cc => cc.number == ScMidiCc.DataEntryMsb &&
      cc.value == 15) shouldBe empty // no MCM with memberCount=15
  }

  // ---- Channel-of-receipt gating ----

  it should "ignore MCM on non-master channel" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = sendMcm(tuner, channel = 5, memberCount = 7)
    // Then - Should NOT trigger MCM processing
    extractNoteOffs(output) shouldBe empty
  }

  // ---- Revert on reset ----

  it should "revert to initialZones on reset() after MCM" in new Fixture(dualZoneTunerMpeInput) {
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

  behavior of "MpeTuner - PBS Processing - Non-MPE Input"

  // ---- Master-channel PBS update ----

  it should "update master PBS and forward to lower zone master channel when PBS arrives on any input channel" in {
    val inputChannels = Table("inputChannel", 0, 5, 10, 15)
    forAll(inputChannels) { inputChannel =>
      // Given
      val tuner = tuner7
      // When
      val output = sendPbsMsb(tuner, channel = inputChannel, semitones = 12)
      // Then
      val ccs = extractCc(output)
      ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12))
      ccs.map(_.channel) should contain only 0
      tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
    }
  }

  it should "update master PBS and forward to upper zone master channel when only upper zone is enabled" in {
    // Given - Upper-zone-only tuner
    val tuner = MpeTuner(
      initialZones = MpeZones(MpeZone(MpeZoneType.Lower, 0), MpeZone(MpeZoneType.Upper, 7))
    )
    // When - PBS on an arbitrary input channel
    val output = sendPbsMsb(tuner, channel = 5, semitones = 12)
    // Then
    val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 12))
    tuner.zones.upper.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
  }

  it should "route PBS to lower zone master in dual-zone setup regardless of input channel" in
    new Fixture(dualZoneTuner) {
    // When - PBS arrives on channel 12 (would be an upper zone member in MPE mode)
    private var output = sendPbsMsb(tuner, channel = 4, semitones = 12)
      output ++= sendPbsMsb(tuner, channel = 12, semitones = 2)
    // Then - Non-MPE mode always routes to lower zone master (ch 0)
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12))
      ccs should not contain CcScMidiMessage(15, ScMidiCc.DataEntryMsb, 2)
      tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(2)
      tuner.zones.upper.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
  }

  // ---- LSB (cents) handling ----

  it should "handle PBS LSB (cents) update by forwarding to master channel" in new Fixture(tuner7) {
    // Given - Set RPN to PBS on a non-master channel
    tuner.process(CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    // When - Send LSB on the same non-master channel
    private val output = sendPbsLsb(tuner, channel = 5, cents = 50)
    // Then - Should be forwarded to master channel (ch 0)
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryLsb, 50))
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(
      MpeZone.DefaultMasterPitchBendSensitivity.semitones, cents = 50)
  }

  // ---- Zone isolation of PBS ----

  it should "not update member PBS" in new Fixture(tuner7) {
    // When - Send PBS on various channels
    sendPbsMsb(tuner, channel = 1, semitones = 12)
    sendPbsMsb(tuner, channel = 5, semitones = 24)
    // Then - Member PBS should remain at default
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }

  it should "not affect other zone's PBS" in new Fixture(dualZoneTuner) {
    // When - Send PBS on any channel in non-MPE dual-zone mode
    private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
    // Then - Routed to lower zone master (ch 0); no PBS messages on upper zone channels
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 24))
    (8 to 15).foreach { ch =>
      ccs.filter(cc => cc.channel == ch && cc.number == ScMidiCc.DataEntryMsb) shouldBe empty
    }
  }

  // ---- Revert on reset ----

  it should "revert PBS to initial values on reset()" in new Fixture(tuner7) {
    // Given - Change master PBS (in non-MPE mode PBS always updates master)
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
    // When
    tuner.reset()
    // Then - Member channels should have default PBS (48 semitones)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }

  behavior of "MpeTuner - PBS Processing - MPE Input"

  // ---- Master-channel PBS update ----

  it should "update master PBS on master channel" in new Fixture(mpeTunerMpeInput) {
    // When
    private val output = sendPbsMsb(tuner, channel = 0, semitones = 12)
    // Then
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 12))
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
  }

  // ---- Member-channel PBS update & forwarding ----

  it should "update member PBS and forward only on the received channel" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      private val ccs = extractCc(output)
      ccs should contain(CcScMidiMessage(1, ScMidiCc.DataEntryMsb, 24))
      // Should NOT broadcast to other member channels
      private val dataEntryCcs = ccs.filter(_.number == ScMidiCc.DataEntryMsb)
      (2 to 7).foreach { ch =>
        dataEntryCcs.filter(_.channel == ch) shouldBe empty
      }
      tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    }

  it should "forward PBS on each channel once when received on all member channels" in new Fixture(tuner7MpeInput) {
    // When - Sender broadcasts PBS to all member channels 1-7; each should be forwarded 1:1
    private var output: Seq[MidiMessage] = Seq.empty
    for (ch <- 1 to 7) {
      output ++= sendPbsMsb(tuner, channel = ch, semitones = 24)
    }
    // Then
    private val dataEntryCcs = extractCc(output).filter(cc =>
      cc.number == ScMidiCc.DataEntryMsb && cc.value == 24)
    dataEntryCcs.size shouldEqual 7
    dataEntryCcs.map(_.channel) should contain theSameElementsInOrderAs (1 to 7)
  }

  it should "handle PBS LSB (cents) update on master channel" in new Fixture(tuner7MpeInput) {
    // Given
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(0, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    // When
    private val output = sendPbsLsb(tuner, channel = 0, cents = 50)
    // Then
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(0, ScMidiCc.DataEntryLsb, 50))
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(
      MpeZone.DefaultMasterPitchBendSensitivity.semitones, cents = 50)
  }

  it should "handle PBS LSB (cents) update on member channel" in new Fixture(tuner7MpeInput) {
    // Given - Set RPN to PBS on a non-master channel
    tuner.process(CcScMidiMessage(5, ScMidiCc.RpnLsb, ScMidiRpn.PitchBendSensitivityLsb).asJava)
    tuner.process(CcScMidiMessage(5, ScMidiCc.RpnMsb, ScMidiRpn.PitchBendSensitivityMsb).asJava)
    // When - Send LSB on the same non-master channel
    private val output = sendPbsLsb(tuner, channel = 5, cents = 50)
    // Then - Should be forwarded to master channel (ch 0)
    private val ccs = extractCc(output)
    ccs should contain(CcScMidiMessage(5, ScMidiCc.DataEntryLsb, 50))
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(
      MpeZone.DefaultMemberPitchBendSensitivity.semitones, cents = 50)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
  }

  // ---- Pitch-bend recomputation after PBS change ----

  it should "recompute pitch bends on occupied channels after member PBS change" in
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
      pitchBends.head.centsFor(PitchBendSensitivity(24)).round.toInt shouldEqual -14
    }

  it should "preserve intonation of active note with expression pitch bend after PBS change" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given - Play E4 on member channel 1 with initial expression PB: tuning offset for E is -14.0 cents
      private val eExprCents = 293.0
      private val noteOutput = noteOn(1, E4, 100, pbCents = Some(eExprCents))
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When - Now change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)
      pitchBends should have size 1
      // The output pitch bend should still represent tuning offset + expression bend in cents.
      // E tuning offset = -14.0 cents; total ≈ -14 + 293 = 279 cents.
      private val expectedCents = -14.0 + eExprCents
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual expectedCents
    }

  it should "emit a single recomputed pitch bend on each occupied channel after member PBS change, " +
    "preserving intonation of an active note without expression pitch bend" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given - Play E4 on MPE member channel 2: tuning offset for E is -14.0 cents
      private val noteOutput = noteOn(2, E4)
      private val noteChannel = extractNoteOns(noteOutput).head.channel
      // When - Change member PBS from 48 to 24 semitones
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then - A single recomputed pitch bend on the occupied channel
      private val pitchBends = extractPitchBends(pbsOutput)
      pitchBends.size shouldEqual 1
      pitchBends.map(_.channel) should contain(noteChannel)
      // The output pitch bend should still represent -14.0 cents under the new PBS
      pitchBends.head.centsFor(PitchBendSensitivity(24)) shouldEqual -14.0
    }

  // ---- Revert on reset ----

  it should "revert PBS to initial values on reset()" in new Fixture(tuner7MpeInput) {
    // Given
    sendPbsMsb(tuner, channel = 0, semitones = 3)
    sendPbsMsb(tuner, channel = 1, semitones = 24)
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(3)
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    // When
    tuner.reset()
    // Then
    tuner.zones.lower.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
    tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }
}

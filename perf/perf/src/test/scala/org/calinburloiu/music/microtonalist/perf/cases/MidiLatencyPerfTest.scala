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

package org.calinburloiu.music.microtonalist.perf.cases

import org.calinburloiu.music.microtonalist.perf.PerfDsl.{*, given}
import org.calinburloiu.music.microtonalist.perf.{Expected, Readiness}
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*

import scala.collection.immutable.ArraySeq

/**
 * The end-to-end MIDI latency of the app with each tuner, from a Note On in to the Note On out, and from a pedal in to
 * the tuning change out (issue #394), and the latency of the MIDI loopback alone for the messages of those flows.
 *
 * Every case plays on a track with a `pedal` tuning changer, over a composition of two tunings that differ on the notes
 * played, E4 and A4: just and Pythagorean major. Each iteration leaves the app as it found it, and each readiness probe
 * leaves the app as an iteration does, so that the expected output is the same in every iteration.
 */
class MidiLatencyPerfTest extends LatencySuite {

  private val E4 = MidiNote(64)
  private val A4 = MidiNote(69)

  private val TwoTunings = "compositions/two-tunings.mtlist"
  private val MtsTracks = "tracks/mts.mtlist.tracks"
  private val MpbTracks = "tracks/mpb.mtlist.tracks"
  private val MpeTracks = "tracks/mpe.mtlist.tracks"

  /** Presses and releases the pedal that changes to the next tuning. */
  private val pedal = Seq(CcMidiMsg(0, 66, 127), CcMidiMsg(0, 66, 0))
  /** Presses and releases the pedal that changes to the previous tuning. */
  private val pedalBack = Seq(CcMidiMsg(0, 67, 127), CcMidiMsg(0, 67, 0))

  private def noteOn(note: MidiNote): NoteOnMidiMsg = NoteOnMidiMsg(0, note)

  private def noteOff(note: MidiNote): NoteOffMidiMsg = NoteOffMidiMsg(0, note)

  private def noteOnOnAnyChannel(note: MidiNote): Expected = matching(s"a Note On of $note on any channel") {
    case NoteOnMidiMsg(_, received, _) if received.number == note.number =>
  }

  private def noteOffOnAnyChannel(note: MidiNote): Expected = matching(s"a Note Off of $note on any channel") {
    case NoteOffMidiMsg(_, received, _) if received.number == note.number =>
  }

  private def pitchBendOn(channel: Int): Expected = matching(s"a Pitch Bend on channel $channel") {
    case PitchBendMidiMsg(`channel`, _) =>
  }

  /** The MPE tuner puts each note on the Member Channel least recently used, so the channel changes. */
  private val pitchBendOnAnyChannel: Expected = matching("a Pitch Bend on any channel") {
    case _: PitchBendMidiMsg =>
  }

  /** Ready once a note played comes back, which leaves `note` as the last one played, as an iteration does. */
  private def readyAfterPlaying(note: MidiNote): Readiness =
    Readiness.Probe(Seq(noteOn(note), noteOff(note)), noteOffOnAnyChannel(note))

  /** An MTS Octave 1-byte non-real-time SysEx message with the given tuning values (cents + 64) from C to B. */
  private def mtsOctave1Byte(values: Int*): SysExMidiMsg = {
    val bytes = Seq(0xF0, 0x7E, 0x7F, 0x08, 0x08, 0x03, 0x7F, 0x7F) ++ values :+ 0xF7
    SysExMidiMsg(ArraySeq.from(bytes.map(_.toByte)))
  }

  private val justSysEx = mtsOctave1Byte(0x40, 0x40, 0x44, 0x40, 0x32, 0x3E, 0x40, 0x42, 0x40, 0x30, 0x40, 0x34)
  private val pythagoreanSysEx = mtsOctave1Byte(0x40, 0x40, 0x44, 0x40, 0x48, 0x3E, 0x40, 0x42, 0x40, 0x46, 0x40, 0x4A)

  // Pitch Bends of E4 in the two tunings, as the Pitch Bend tuner sends them with its default sensitivity of 2 semitones
  private val justE4PitchBend = PitchBendMidiMsg(0, -561)
  private val pythagoreanE4PitchBend = PitchBendMidiMsg(0, 320)

  // The messages of the tuners' flows, with no app: what the setup alone costs for each of them
  "The MIDI loopback" should {
    "carry a Note On, as the MTS tuner passes it through" in loopbackCase(
      input("noteOn")(noteOn(E4)) ~> output("noteOn")(noteOn(E4)),
      input(noteOff(E4)) ~> output(noteOff(E4))
    )(metric("noteOn", "noteOn"))

    "carry a Pitch Bend and a Note On, as the Pitch Bend and MPE tuners play a note" in loopbackCase(
      input("noteOn")(justE4PitchBend, noteOn(E4)) ~> output("noteOn")(justE4PitchBend, noteOn(E4)),
      input(noteOff(E4)) ~> output(noteOff(E4))
    )(metric("noteOn", "noteOn"))

    "carry an MTS SysEx, as the MTS tuner changes the tuning" in loopbackCase(
      input("tuning")(pythagoreanSysEx) ~> output("tuning")(pythagoreanSysEx),
      input(justSysEx) ~> output(justSysEx)
    )(metric("tuning", "tuning"))

    "carry a Pitch Bend while a note sounds, as the Pitch Bend and MPE tuners change the tuning" in loopbackCase(
      input(noteOn(E4)) ~> output(noteOn(E4)),
      input("tuning")(pythagoreanE4PitchBend) ~> output("tuning")(pythagoreanE4PitchBend),
      input(justE4PitchBend) ~> output(justE4PitchBend),
      input(noteOff(E4)) ~> output(noteOff(E4))
    )(metric("tuning", "tuning"))

    "carry a Pitch Bend without a note" in loopbackCase(
      input("tuning")(pythagoreanE4PitchBend) ~> output("tuning")(pythagoreanE4PitchBend),
      input(justE4PitchBend) ~> output(justE4PitchBend)
    )(metric("tuning", "tuning"))

    "carry the pedal's Control Changes, as a performer presses it" in loopbackCase(
      input("pedal")(pedal*) ~> output("pedal")(pedal.map(Expected.Exactly(_))*),
      input(pedalBack*) ~> output(pedalBack.map(Expected.Exactly(_))*)
    )(metric("pedal", "pedal"))
  }

  "The MTS Octave 1-byte tuner" should {
    "pass a note through" in perfCase(MtsTracks, TwoTunings, readyAfterPlaying(A4))(
      input("noteOn")(noteOn(E4)) ~> output("noteOn")(noteOn(E4)),
      input(noteOff(E4)) ~> output(noteOff(E4)),
      input(noteOn(A4)) ~> output(noteOn(A4)),
      input(noteOff(A4)) ~> output(noteOff(A4))
    )(metric("noteOn", "noteOn"))

    "change the tuning on a pedal" in perfCase(MtsTracks, TwoTunings, readyAfterPlaying(E4))(
      input("pedal")(pedal*) ~> output("tuning")(pythagoreanSysEx),
      input(pedalBack*) ~> output(justSysEx)
    )(metric("pedal", "tuning"))
  }

  "The Monophonic Pitch Bend tuner" should {
    // The tuner sends a Pitch Bend only when its value changes, so the notes played alternate between two offsets
    "pass a note through" in perfCase(MpbTracks, TwoTunings, readyAfterPlaying(A4))(
      input("noteOn")(noteOn(E4)) ~> output("noteOn")(pitchBendOn(0), noteOn(E4)),
      input(noteOff(E4)) ~> output(noteOff(E4)),
      input(noteOn(A4)) ~> output(pitchBendOn(0), noteOn(A4)),
      input(noteOff(A4)) ~> output(noteOff(A4))
    )(metric("noteOn", "noteOn"))

    // A tuning change sends a Pitch Bend only for a sounding note; the readiness probe already bent the output to E4's
    // offset, so its Note On goes out alone
    "change the tuning on a pedal" in perfCase(MpbTracks, TwoTunings, readyAfterPlaying(E4))(
      input(noteOn(E4)) ~> output(noteOn(E4)),
      input("pedal")(pedal*) ~> output("tuning")(pitchBendOn(0)),
      input(pedalBack*) ~> output(pitchBendOn(0)),
      input(noteOff(E4)) ~> output(noteOff(E4))
    )(metric("pedal", "tuning"))
  }

  "The MPE tuner" should {
    "pass a note through" in perfCase(MpeTracks, TwoTunings, readyAfterPlaying(A4))(
      input("noteOn")(noteOn(E4)) ~> output("noteOn")(pitchBendOnAnyChannel, noteOnOnAnyChannel(E4)),
      input(noteOff(E4)) ~> output(noteOffOnAnyChannel(E4)),
      input(noteOn(A4)) ~> output(pitchBendOnAnyChannel, noteOnOnAnyChannel(A4)),
      input(noteOff(A4)) ~> output(noteOffOnAnyChannel(A4))
    )(metric("noteOn", "noteOn"))

    // A tuning change sends a Pitch Bend only for a sounding note
    "change the tuning on a pedal" in perfCase(MpeTracks, TwoTunings, readyAfterPlaying(E4))(
      input(noteOn(E4)) ~> output(pitchBendOnAnyChannel, noteOnOnAnyChannel(E4)),
      input("pedal")(pedal*) ~> output("tuning")(pitchBendOnAnyChannel),
      input(pedalBack*) ~> output(pitchBendOnAnyChannel),
      input(noteOff(E4)) ~> output(noteOffOnAnyChannel(E4))
    )(metric("pedal", "tuning"))
  }
}

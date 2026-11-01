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

package org.calinburloiu.music.microtonalist.perf.midilatency.cases

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.matching
import org.calinburloiu.music.microtonalist.perf.midilatency.{Expected, Readiness}
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*

/** The resources, messages and patterns that the latency suites share. */
private[cases] object LatencyMessages {

  val E4: MidiNote = MidiNote.E4
  val A4: MidiNote = MidiNote.A4

  /** Two tunings that differ on the notes played, E4 and A4: just and Pythagorean major. */
  val TwoTunings = "compositions/two-tunings.mtlist"

  /** Presses and releases the pedal that changes to the next tuning. */
  val pedal: Seq[CcMidiMsg] = Seq(CcMidiMsg(0, MidiCc.SostenutoPedal, 127), CcMidiMsg(0, MidiCc.SostenutoPedal, 0))
  /** Presses and releases the pedal that changes to the previous tuning. */
  val pedalBack: Seq[CcMidiMsg] = Seq(CcMidiMsg(0, MidiCc.SoftPedal, 127), CcMidiMsg(0, MidiCc.SoftPedal, 0))

  def noteOn(note: MidiNote): NoteOnMidiMsg = NoteOnMidiMsg(0, note)

  def noteOff(note: MidiNote): NoteOffMidiMsg = NoteOffMidiMsg(0, note)

  def noteOffOnAnyChannel(note: MidiNote): Expected = matching(s"a Note Off of $note on any channel") {
    case NoteOffMidiMsg(_, received, _) if received.number == note.number =>
  }

  /** Ready once a note played comes back, which leaves `note` as the last one played, as an iteration does. */
  def readyAfterPlaying(note: MidiNote): Readiness =
    Readiness.Probe(Seq(noteOn(note), noteOff(note)), noteOffOnAnyChannel(note))
}

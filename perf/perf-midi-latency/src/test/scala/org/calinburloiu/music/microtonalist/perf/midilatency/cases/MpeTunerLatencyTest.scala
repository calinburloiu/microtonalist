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

import org.calinburloiu.music.microtonalist.perf.midilatency.Expected
import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.*
import org.calinburloiu.music.microtonalist.perf.midilatency.cases.LatencyMessages.*
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.{NoteOnMidiMsg, PitchBendMidiMsg}
import org.scalatest.DoNotDiscover

/** The latency of the app with the MPE tuner. */
@DoNotDiscover
class MpeTunerLatencyTest(session: LatencySession) extends LatencySuite("mpe", session) {

  private val MpeTracks = "tracks/mpe.mtlist.tracks"

  /** The MPE tuner puts each note on the Member Channel least recently used, so the channel changes. */
  private val pitchBendOnAnyChannel: Expected = matching("a Pitch Bend on any channel") {
    case _: PitchBendMidiMsg =>
  }

  private def noteOnOnAnyChannel(note: MidiNote): Expected = matching(s"a Note On of $note on any channel") {
    case NoteOnMidiMsg(_, received, _) if received.number == note.number =>
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

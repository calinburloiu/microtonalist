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
import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.{*, given}
import org.calinburloiu.music.microtonalist.perf.midilatency.cases.LatencyMessages.*
import org.calinburloiu.music.scmidi.message.PitchBendMidiMsg
import org.scalatest.DoNotDiscover

/** The latency of the app with the Monophonic Pitch Bend tuner. */
@DoNotDiscover
class MonophonicPitchBendTunerLatencyTest(session: LatencySession) extends LatencySuite("mpb", session) {

  private val MpbTracks = "tracks/mpb.mtlist.tracks"

  private def pitchBendOn(channel: Int): Expected = matching(s"a Pitch Bend on channel $channel") {
    case PitchBendMidiMsg(`channel`, _) =>
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
}

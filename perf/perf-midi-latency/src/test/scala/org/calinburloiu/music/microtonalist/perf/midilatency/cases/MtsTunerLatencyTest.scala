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

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.{*, given}
import org.calinburloiu.music.microtonalist.perf.midilatency.cases.LatencyMessages.*
import org.calinburloiu.music.scmidi.message.SysExMidiMsg
import org.scalatest.DoNotDiscover

import scala.collection.immutable.ArraySeq

/** The latency of the app with the MTS Octave 1-byte tuner. */
@DoNotDiscover
class MtsTunerLatencyTest(session: LatencySession) extends LatencySuite("mts", session) {

  private val MtsTracks = "tracks/mts.mtlist.tracks"

  /** An MTS Octave 1-byte non-real-time SysEx message with the given tuning values (cents + 64) from C to B. */
  private def mtsOctave1Byte(values: Int*): SysExMidiMsg = {
    val bytes = Seq(0xF0, 0x7E, 0x7F, 0x08, 0x08, 0x03, 0x7F, 0x7F) ++ values :+ 0xF7
    SysExMidiMsg(ArraySeq.from(bytes.map(_.toByte)))
  }

  private val justSysEx = mtsOctave1Byte(0x40, 0x40, 0x44, 0x40, 0x32, 0x3E, 0x40, 0x42, 0x40, 0x30, 0x40, 0x34)
  private val pythagoreanSysEx =
    mtsOctave1Byte(0x40, 0x40, 0x44, 0x40, 0x48, 0x3E, 0x40, 0x42, 0x40, 0x46, 0x40, 0x4A)

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
}

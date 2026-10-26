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

package org.calinburloiu.music.microtonalist.perf.midilatency

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.{*, given}
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.ArraySeq

class PerfDslTest extends AnyWordSpec with Matchers {

  private val cc66On = CcMidiMsg(0, 66, 127)
  private val cc66Off = CcMidiMsg(0, 66, 0)
  private val sysEx = SysExMidiMsg(ArraySeq(0xF0.toByte, 0x7E.toByte, 0xF7.toByte))
  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))

  "PerfDsl" should {
    "build a step from a marked input and a marked output" in {
      // When
      val step = input("pedal")(cc66On, cc66Off) ~> output("tuning")(sysEx)

      // Then
      step shouldEqual Step(
        Seq(InputSeries(Some("pedal"), Seq(cc66On, cc66Off))),
        Seq(OutputSeries(Some("tuning"), Seq(Expected.Exactly(sysEx))))
      )
    }

    "build unmarked series" in {
      // When
      val step = input(noteOff) ~> output(noteOff)

      // Then
      step shouldEqual
        Step(Seq(InputSeries(None, Seq(noteOff))), Seq(OutputSeries(None, Seq(Expected.Exactly(noteOff)))))
    }

    "mix exact messages and patterns in an output" in {
      // When
      val series = output(noteOn, matching("a Pitch Bend") { case _: PitchBendMidiMsg => })

      // Then
      series.expected.map(_.description) shouldEqual Seq(noteOn.toString, "a Pitch Bend")
    }

    "build a step with several series on each side" in {
      // When
      val result = step(input("a")(noteOn), input("b")(noteOff))(output("c")(noteOn), output(noteOff))

      // Then
      result.inputs.map(_.mark) shouldEqual Seq(Some("a"), Some("b"))
      result.outputs.map(_.mark) shouldEqual Seq(Some("c"), None)
    }

    "name a metric after its marks" in {
      // When / Then
      metric("pedal", "tuning") shouldEqual Metric("pedal→tuning", from = "pedal", to = "tuning")
    }
  }
}

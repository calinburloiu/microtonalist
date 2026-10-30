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

package org.calinburloiu.music.microtonalist.perf.midilatency.run

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfDsl.{*, given}
import org.calinburloiu.music.microtonalist.perf.midilatency.{Expected, Readiness, Scenario, Step}
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.{Duration, DurationInt}

class TrafficTest extends AnyWordSpec with Matchers {

  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))
  private val pedalDown = CcMidiMsg(0, MidiCc.SostenutoPedal, 127)
  private val pedalUp = CcMidiMsg(0, MidiCc.SostenutoPedal, 0)

  /** A SysEx of `length` bytes, its `0xF0` and `0xF7` included. */
  private def sysEx(length: Int): SysExMidiMsg =
    SysExMidiMsg(ArraySeq.from((0xF0 +: Seq.fill(length - 2)(0x40) :+ 0xF7).map(_.toByte)))

  private def scenarioOf(steps: Step*): Scenario = Scenario(Readiness.Immediately, steps, Seq(metric("in", "out")))

  "Traffic" should {
    "count a short message as one" in {
      // When / Then
      Traffic.weight(noteOn) shouldEqual 1
      Traffic.weight(pedalDown) shouldEqual 1
    }

    "count a SysEx as one message per 6 data bytes" in {
      // When / Then
      Traffic.weight(sysEx(2)) shouldEqual 1
      Traffic.weight(sysEx(8)) shouldEqual 1
      Traffic.weight(sysEx(9)) shouldEqual 2
      Traffic.weight(sysEx(21)) shouldEqual 4
    }

    "count the traffic of an iteration on the busier device" in {
      // Given
      val busierInput = scenarioOf(
        input("in")(pedalDown, pedalUp) ~> output("out")(noteOn),
        input(pedalDown, pedalUp) ~> output(noteOff)
      )
      val busierOutput = scenarioOf(input("in")(pedalDown, pedalUp) ~> output("out")(sysEx(21), noteOn))

      // When / Then
      Traffic.perIteration(busierInput) shouldEqual 4
      Traffic.perIteration(busierOutput) shouldEqual 5
    }

    "count an expected pattern as one message" in {
      // Given
      val anyPitchBend = Expected.Matching("a Pitch Bend", { case _: PitchBendMidiMsg => })
      val scenario = scenarioOf(input("in")(noteOn) ~> output("out")(anyPitchBend, noteOn))

      // When / Then
      Traffic.perIteration(scenario) shouldEqual 2
    }

    "pace iterations so that each device stays under the rate" in {
      // Given
      val scenario = scenarioOf(input("in")(pedalDown, pedalUp) ~> output("out")(sysEx(21), sysEx(21)))

      // When / Then
      Traffic.warmupPace(scenario, maxMessageRate = 1000) shouldEqual 8.millis
      Traffic.warmupPace(scenario, maxMessageRate = 3000) shouldEqual 2666667.nanos
    }

    "not pace iterations without a limit" in {
      // Given
      val scenario = scenarioOf(input("in")(noteOn) ~> output("out")(noteOn))

      // When / Then
      Traffic.warmupPace(scenario, maxMessageRate = 0) shouldEqual Duration.Zero
    }
  }
}

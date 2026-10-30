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

class PerfCaseTest extends AnyWordSpec with Matchers {

  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))
  private val pedal = CcMidiMsg(0, MidiCc.SostenutoPedal, 127)
  private val pitchBend = PitchBendMidiMsg(0, 100)

  private val noteOnStep: Step = input(noteOn) ~> output(noteOn)
  private val pedalStep: Step = input("pedal")(pedal) ~> output("tuning")(pitchBend)
  private val backStep: Step = input("back")(pedal) ~> output("tuned back")(pitchBend)

  private def scenario(steps: Seq[Step], metrics: Seq[Metric]): Scenario =
    Scenario(Readiness.Immediately, steps, metrics)

  "Expected" should {
    "match exactly the message it holds" in {
      // Given
      val expected = Expected.Exactly(noteOn)

      // Then
      expected.matches(noteOn) shouldBe true
      expected.matches(noteOff) shouldBe false
      expected.description shouldEqual noteOn.toString
    }

    "match what its pattern covers" in {
      // Given
      val expected = matching("a Note On of E4") { case NoteOnMidiMsg(_, note, _) if note.number == 64 => }

      // Then
      expected.matches(NoteOnMidiMsg(5, MidiNote(64))) shouldBe true
      expected.matches(NoteOnMidiMsg(5, MidiNote(65))) shouldBe false
      expected.matches(noteOff) shouldBe false
      expected.description shouldEqual "a Note On of E4"
    }
  }

  "A series" should {
    "not be empty" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy InputSeries(Some("x"), Seq.empty)
      an[IllegalArgumentException] should be thrownBy OutputSeries(None, Seq.empty)
    }
  }

  "A step" should {
    "need an input and an output to wait for" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy Step(Seq(InputSeries(None, Seq(noteOn))), Seq.empty)
      an[IllegalArgumentException] should be thrownBy
        Step(Seq.empty, Seq(OutputSeries(None, Seq(Expected.Exactly(noteOn)))))
    }
  }

  "A scenario" should {
    "find the step that sends a metric's first series" in {
      // When
      val result = scenario(Seq(noteOnStep, pedalStep, backStep), Seq(metric("pedal", "tuning")))

      // Then
      result.measuredStepIndex shouldEqual 1
    }

    "need a step" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy scenario(Seq.empty, Seq(metric("pedal", "tuning")))
    }

    "need a metric" in {
      // When / Then
      an[IllegalArgumentException] should be thrownBy scenario(Seq(pedalStep), Seq.empty)
    }

    "reject an input mark used twice" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy
        scenario(Seq(pedalStep, input("pedal")(pedal) ~> output(pitchBend)), Seq(metric("pedal", "tuning")))

      // Then
      exception.getMessage should include("'pedal'")
    }

    "reject an output mark used twice" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy
        scenario(Seq(pedalStep, input(pedal) ~> output("tuning")(pitchBend)), Seq(metric("pedal", "tuning")))

      // Then
      exception.getMessage should include("'tuning'")
    }

    "reject a metric from an unknown input mark" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy scenario(Seq(pedalStep), Seq(metric("note", "tuning")))

      // Then
      exception.getMessage should include("'note'")
    }

    "reject a metric to an unknown output mark" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy scenario(Seq(pedalStep), Seq(metric("pedal", "sysex")))

      // Then
      exception.getMessage should include("'sysex'")
    }

    "reject a metric whose output comes before its input" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy
        scenario(Seq(pedalStep, backStep), Seq(metric("back", "tuning")))

      // Then
      exception.getMessage should include("back→tuning")
    }

    "reject two metrics with one name" in {
      // When
      val exception = the[IllegalArgumentException] thrownBy
        scenario(Seq(pedalStep), Seq(metric("pedal", "tuning"), Metric("pedal→tuning", "pedal", "tuning")))

      // Then
      exception.getMessage should include("pedal→tuning")
    }
  }
}

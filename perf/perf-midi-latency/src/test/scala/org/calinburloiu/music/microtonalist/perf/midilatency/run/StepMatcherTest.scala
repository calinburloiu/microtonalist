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
import org.calinburloiu.music.microtonalist.perf.midilatency.run.StepMatcher.Status
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StepMatcherTest extends AnyWordSpec with Matchers {

  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))
  private val pitchBend = PitchBendMidiMsg(0, 100)
  private val anyNoteOnE4 = matching("a Note On of E4 on any channel") {
    case NoteOnMidiMsg(_, note, _) if note.number == 64 =>
  }

  "StepMatcher" should {
    "complete a series matched in order" in {
      // Given
      val matcher = StepMatcher(Seq(output("notes")(noteOn, noteOff)))

      // When / Then
      matcher.accept(noteOn, 10) shouldEqual Status.Pending
      matcher.isComplete shouldBe false
      matcher.accept(noteOff, 20) shouldEqual Status.Complete
      matcher.isComplete shouldBe true
      matcher.completionTimes shouldEqual IndexedSeq(20)
    }

    "match series in any order relative to each other" in {
      // Given
      val matcher = StepMatcher(Seq(output("a")(noteOn), output("b")(pitchBend)))

      // When
      val first = matcher.accept(pitchBend, 10)
      val second = matcher.accept(noteOn, 20)

      // Then
      first shouldEqual Status.Pending
      second shouldEqual Status.Complete
      matcher.completionTimes shouldEqual IndexedSeq(20, 10)
    }

    "report an unexpected message with what was expected" in {
      // Given
      val matcher = StepMatcher(Seq(output("tuning")(pitchBend)))

      // When
      val status = matcher.accept(noteOff, 10)

      // Then
      status shouldBe a[Status.Mismatch]
      val reason = status.asInstanceOf[Status.Mismatch].reason
      reason should include(noteOff.toString)
      reason should include(pitchBend.toString)
      reason should include("tuning")
    }

    "report a message after completion as extra" in {
      // Given
      val matcher = StepMatcher(Seq(output(noteOn)))
      matcher.accept(noteOn, 10)

      // When
      val status = matcher.accept(noteOn, 20)

      // Then
      status shouldBe a[Status.Mismatch]
      status.asInstanceOf[Status.Mismatch].reason should include("extra")
    }

    "match a pattern on any channel" in {
      // Given
      val matcher = StepMatcher(Seq(output(anyNoteOnE4)))

      // When / Then
      matcher.accept(NoteOnMidiMsg(7, MidiNote(64)), 10) shouldEqual Status.Complete
    }

    "give an ambiguous message to the first series" in {
      // Given
      val matcher = StepMatcher(Seq(output("first")(noteOn, noteOff), output("second")(noteOn)))

      // When
      matcher.accept(noteOn, 10)
      val status = matcher.accept(noteOn, 20)

      // Then
      // The first Note On went to the first series, which still waits for its Note Off
      status shouldEqual Status.Pending
      matcher.describeMissing should include("series 'first' still expected")
      matcher.describeMissing should not include "series 'second' still expected"
    }

    "describe what is missing" in {
      // Given
      val matcher = StepMatcher(Seq(output("notes")(noteOn, noteOff)))
      matcher.accept(noteOn, 10)

      // When
      val description = matcher.describeMissing

      // Then
      description should include("notes")
      description should include(s"still expected: ${noteOff.toString}")
      description should include(s"received: ${noteOn.toString}")
    }

    "start over after reset" in {
      // Given
      val matcher = StepMatcher(Seq(output(noteOn)))
      matcher.accept(noteOn, 10)

      // When
      matcher.reset()

      // Then
      matcher.isComplete shouldBe false
      matcher.accept(noteOn, 30) shouldEqual Status.Complete
      matcher.completionTimes shouldEqual IndexedSeq(30)
    }
  }
}

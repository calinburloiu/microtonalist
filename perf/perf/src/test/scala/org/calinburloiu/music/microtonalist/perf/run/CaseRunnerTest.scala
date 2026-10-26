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

package org.calinburloiu.music.microtonalist.perf.run

import org.calinburloiu.music.microtonalist.perf.PerfDsl.{*, given}
import org.calinburloiu.music.microtonalist.perf.{Expected, Readiness, Scenario}
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class CaseRunnerTest extends AnyWordSpec with Matchers {

  private val ReadinessTimeout: FiniteDuration = 2.seconds
  /** How long a step waits for an output that the test makes sure never comes. */
  private val StepTimeout: FiniteDuration = 200.millis

  private val timeouts = Timeouts(readiness = ReadinessTimeout, probeInterval = 50.millis, quiet = 30.millis,
    step = StepTimeout, exit = 100.millis)

  private val noteOnE4 = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOffE4 = NoteOffMidiMsg(0, MidiNote(64))
  private val noteOnA4 = NoteOnMidiMsg(0, MidiNote(69))
  private val noteOffA4 = NoteOffMidiMsg(0, MidiNote(69))

  private val scenario = Scenario(
    readiness = Readiness.Probe(Seq(noteOnA4, noteOffA4), Expected.Exactly(noteOffA4)),
    steps = Seq(
      input("noteOn")(noteOnE4) ~> output("noteOn")(noteOnE4),
      input(noteOffE4) ~> output(noteOffE4)
    ),
    metrics = Seq(metric("noteOn", "noteOn"))
  )

  private def settings(launches: Int = 1, warmup: Int = 5, timed: Int = 10,
                       pace: FiniteDuration = 0.millis): MeasurementSettings =
    MeasurementSettings(launches, warmup, timed, pace, timeouts)

  private def runOn(app: FakeApp, settings: MeasurementSettings, scenario: Scenario = scenario): CaseOutcome =
    try {
      CaseRunner(app, settings).run(scenario, _ => app.launch())
    } finally {
      app.close()
    }

  private def failureOf(outcome: CaseOutcome): String = outcome match {
    case CaseOutcome.Failed(reason) => reason
    case passed => fail(s"Expected a failure, but got $passed")
  }

  "CaseRunner" should {
    "measure every timed iteration of every launch" in {
      // Given
      val app = FakeApp()

      // When
      val outcome = runOn(app, settings(launches = 2))

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
      val metrics = outcome.asInstanceOf[CaseOutcome.Passed].metrics
      metrics.map(_.name) shouldEqual Seq("noteOn→noteOn")
      metrics.head.statistics.count shouldEqual 20
      metrics.head.statistics.min should be > 0L
      metrics.head.launches.map(_.count) shouldEqual Seq(10, 10)
      app.launchCount.get shouldEqual 2
      app.stopCount.get shouldEqual 2
    }

    "resend the probe until the app answers" in {
      // Given
      val app = FakeApp()
      app.ignoredMessages.set(4)

      // When
      val outcome = runOn(app, settings())

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
      app.receivedCount.get should be > 4 + 2 * (5 + 10)
    }

    "fail at once on an unexpected output, showing expected and actual" in {
      // Given
      val wrongNote = NoteOnMidiMsg(0, MidiNote(65))
      val app = FakeApp {
        case `noteOnE4` => Seq(wrongNote)
        case message => Seq(message)
      }

      // When
      val reason = failureOf(runOn(app, settings()))

      // Then
      reason should include("Launch 1")
      reason should include(noteOnE4.toString)
      reason should include(wrongNote.toString)
      reason should include(app.logFile.toString)
      app.stopCount.get shouldEqual 1
    }

    "fail when an output is missing" in {
      // Given
      val app = FakeApp {
        case `noteOffE4` => Seq.empty
        case message => Seq(message)
      }

      // When
      val reason = failureOf(runOn(app, settings()))

      // Then
      reason.toLowerCase should include("timed out")
      reason should include(noteOffE4.toString)
    }

    "fail when the app sends more than expected" in {
      // Given
      val app = FakeApp {
        case `noteOffE4` => Seq(noteOffE4, CcMidiMsg(0, 1, 0))
        case message => Seq(message)
      }

      // When
      val reason = failureOf(runOn(app, settings()))

      // Then
      reason should include(CcMidiMsg(0, 1, 0).toString)
    }

    "fail at once when the app exits before it is ready" in {
      // Given
      val app = FakeApp()
      app.exitCode = Some(2)
      val start = System.nanoTime()

      // When
      val reason = failureOf(runOn(app, settings()))

      // Then
      (System.nanoTime() - start) should be < (ReadinessTimeout / 2).toNanos
      reason should include("exited")
      reason should include("2")
    }

    "discard the output an app sends while it starts" in {
      // Given
      val app = FakeApp()
      app.startupOutput = Seq(PitchBendMidiMsg(0, 0), CcMidiMsg(0, 101, 0), CcMidiMsg(0, 100, 0))

      // When
      val outcome = runOn(app, settings())

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
    }

    "fail when the app sends more after the last iteration" in {
      // Given
      val app = FakeApp {
        case `noteOffE4` => Seq(noteOffE4, CcMidiMsg(0, 1, 0))
        case message => Seq(message)
      }

      // When
      val reason = failureOf(runOn(app, settings(warmup = 0, timed = 1)))

      // Then
      reason should include("after the last iteration")
      reason should include(CcMidiMsg(0, 1, 0).toString)
    }

    "wait for an output after the launch" in {
      // Given
      val ready = CcMidiMsg(0, 101, 0)
      val app = FakeApp()
      app.startupOutput = Seq(PitchBendMidiMsg(0, 0), ready)

      // When
      val outcome = runOn(app, settings(), scenario.copy(readiness = Readiness.AfterLaunch(Expected.Exactly(ready))))

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
    }

    "pace the measured step" in {
      // Given
      val app = FakeApp()
      val pace = 20.millis
      val start = System.nanoTime()

      // When
      val outcome = runOn(app, settings(warmup = 0, timed = 5, pace = pace))

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
      (System.nanoTime() - start) should be >= (pace * 4).toNanos
    }

    "measure without an app" in {
      // Given
      val app = FakeApp()
      app.launch()
      val loopback = scenario.copy(readiness = Readiness.Immediately)

      // When
      val outcome =
        try {
          CaseRunner(app, settings()).run(loopback, _ => NoApp)
        } finally {
          app.close()
        }

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
    }
  }
}

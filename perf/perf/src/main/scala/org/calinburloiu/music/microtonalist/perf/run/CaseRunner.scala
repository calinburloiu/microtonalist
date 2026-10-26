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

import org.calinburloiu.music.microtonalist.perf.midi.{MidiPath, ReceiveBuffer}
import org.calinburloiu.music.microtonalist.perf.run.StepMatcher.Status
import org.calinburloiu.music.microtonalist.perf.stats.MetricResult
import org.calinburloiu.music.microtonalist.perf.{Expected, Readiness, Scenario}
import org.calinburloiu.music.scmidi.message.MidiMsg

import scala.concurrent.duration.FiniteDuration
import scala.util.boundary

/**
 * Measures a [[Scenario]] over several launches of the app: for each, it waits for the app to be ready, runs the
 * warm-up iterations back to back, then the timed ones with their measured step paced, and stops the app.
 *
 * It isn't thread-safe: one thread runs a case at a time.
 */
final class CaseRunner(path: MidiPath, settings: MeasurementSettings) {

  import CaseRunner.*

  /**
   * Measures `scenario`, failing at the first iteration whose output isn't the expected one.
   *
   * @param launch Launches the app for the launch with the given number, counted from 1.
   */
  def run(scenario: Scenario, launch: Int => RunningApp): CaseOutcome = {
    val buffer = ReceiveBuffer()
    val measurement = Measurement(scenario, buffer)
    path.attach(buffer)
    try {
      boundary {
        for (launchNumber <- 1 to settings.launches) {
          measurement.runLaunch(launchNumber, launch) match {
            case Some(reason) => boundary.break(CaseOutcome.Failed(reason))
            case None =>
          }
        }
        CaseOutcome.Passed(measurement.results)
      }
    } finally {
      path.detach(buffer)
    }
  }

  /** The state of measuring one scenario: preallocated, so that the iterations allocate as little as possible. */
  private class Measurement(scenario: Scenario, buffer: ReceiveBuffer) {
    private val steps = scenario.steps.toIndexedSeq
    private val inputMarks = steps.flatMap(_.inputs.flatMap(_.mark))
    private val outputMarks = steps.flatMap(_.outputs.flatMap(_.mark))
    // For each step, the mark index of each of its series, or -1 for an unmarked one
    private val inputMarkIndexes = steps.map(_.inputs.map(_.mark.map(inputMarks.indexOf).getOrElse(-1)).toArray)
    private val outputMarkIndexes = steps.map(_.outputs.map(_.mark.map(outputMarks.indexOf).getOrElse(-1)).toArray)
    private val metrics = scenario.metrics.toIndexedSeq
    private val metricFroms = metrics.map(metric => inputMarks.indexOf(metric.from)).toArray
    private val metricTos = metrics.map(metric => outputMarks.indexOf(metric.to)).toArray
    private val warmupPace = Traffic.warmupPace(scenario, settings.maxMessageRate)

    private val sendTimes = new Array[Long](inputMarks.size)
    private val receiveTimes = new Array[Long](outputMarks.size)
    private val samples = Array.ofDim[Long](metrics.size, settings.launches * settings.timedIterations)
    private var sampleCount: Int = 0

    /** Runs a launch of the app, returning the reason it failed, if it did. */
    def runLaunch(launchNumber: Int, launch: Int => RunningApp): Option[String] = {
      buffer.clear()
      val app = launch(launchNumber)
      val pacer = pacerOf(settings.pace)
      val matchers = steps.map(step => StepMatcher(step.outputs))
      try {
        awaitReady(app)
        val warmupPacer = pacerOf(warmupPace)
        try {
          for (iteration <- 1 to settings.warmupIterations) {
            runIteration(matchers, warmupPacer, s"warm-up iteration $iteration")
          }
        } finally {
          warmupPacer.foreach(_.close())
        }
        for (iteration <- 1 to settings.timedIterations) {
          runIteration(matchers, pacer, s"timed iteration $iteration")
          recordSamples()
        }
        val extra = buffer.drainUntilQuiet(settings.timeouts.quiet)
        if (extra.nonEmpty) {
          throw IterationFailure(s"Received ${extra.map(_.message).mkString(", ")} after the last iteration")
        }
        None
      } catch {
        case failure: IterationFailure =>
          val log = app.logFile.map(file => s"; app log: $file").getOrElse("")
          Some(s"Launch $launchNumber: ${failure.getMessage}$log")
      } finally {
        pacer.foreach(_.close())
        app.stop(settings.timeouts.exit)
        buffer.clear()
      }
    }

    /** The results of every metric, once every launch passed. */
    def results: Seq[MetricResult] = {
      val launchSizes = Seq.fill(settings.launches)(settings.timedIterations)
      metrics.zip(samples).map { case (metric, metricSamples) => MetricResult.of(metric, metricSamples, launchSizes) }
    }

    private def pacerOf(period: FiniteDuration): Option[Pacer] = Option.when(period.toNanos > 0)(Pacer(period))

    private def awaitReady(app: RunningApp): Unit = {
      scenario.readiness match {
        case Readiness.Immediately =>
        case Readiness.AfterLaunch(until) => awaitMessage(app, until, probe = Seq.empty)
        case Readiness.Probe(input, until) =>
          awaitMessage(app, until, probe = input)
          // The app may still be settling, e.g. its first tuning may reach its tracks after their input opened: a
          // second probe, after the drain, leaves it as an iteration expects to find it
          buffer.drainUntilQuiet(settings.timeouts.quiet)
          awaitMessage(app, until, probe = input)
      }
      // What the app sent while it started, and the replies to the extra probes, aren't part of any iteration
      buffer.drainUntilQuiet(settings.timeouts.quiet)
    }

    /** Waits for a message matching `until`, sending `probe` every probe interval meanwhile. */
    private def awaitMessage(app: RunningApp, until: Expected, probe: Seq[MidiMsg]): Unit = boundary {
      val timeouts = settings.timeouts
      val deadline = System.nanoTime() + timeouts.readiness.toNanos
      while (true) {
        if (!app.isAlive) {
          val code = app.exitCode.map(code => s" with code $code").getOrElse("")
          throw IterationFailure(s"The app exited$code before it was ready")
        }
        if (System.nanoTime() >= deadline) {
          throw IterationFailure(s"The app wasn't ready after ${timeouts.readiness}: no ${until.description} arrived")
        }

        probe.foreach(message => path.sender.send(message))
        val probeDeadline = Math.min(System.nanoTime() + timeouts.probeInterval.toNanos, deadline)
        var received = buffer.pollUntil(probeDeadline)
        while (received.isDefined) {
          if (until.matches(received.get.message)) {
            boundary.break()
          }
          received = buffer.pollUntil(probeDeadline)
        }
      }
    }

    private def runIteration(matchers: IndexedSeq[StepMatcher], pacer: Option[Pacer], iteration: String): Unit = {
      for (stepIndex <- steps.indices) {
        if (stepIndex == scenario.measuredStepIndex) {
          pacer.foreach(_.awaitNextTick())
        }
        try {
          runStep(stepIndex, matchers(stepIndex))
        } catch {
          case failure: IterationFailure =>
            throw IterationFailure(s"$iteration, step ${stepIndex + 1}: ${failure.getMessage}")
        }
      }
    }

    private def runStep(stepIndex: Int, matcher: StepMatcher): Unit = {
      val step = steps(stepIndex)
      matcher.reset()

      val inputIndexes = inputMarkIndexes(stepIndex)
      for (seriesIndex <- step.inputs.indices) {
        val markIndex = inputIndexes(seriesIndex)
        val sendTime = System.nanoTime()
        if (markIndex >= 0) {
          sendTimes(markIndex) = sendTime
        }
        step.inputs(seriesIndex).messages.foreach(message => path.sender.send(message))
      }

      val stepTimeout = settings.timeouts.step
      val deadline = System.nanoTime() + stepTimeout.toNanos
      while (!matcher.isComplete) {
        buffer.pollUntil(deadline) match {
          case None => throw IterationFailure(s"Timed out after $stepTimeout; ${matcher.describeMissing}")
          case Some(received) => matcher.accept(received.message, received.nanos) match {
              case Status.Mismatch(reason) => throw IterationFailure(reason)
              case Status.Pending | Status.Complete =>
            }
        }
      }
      if (buffer.hasOverflowed) {
        throw IterationFailure("Lost messages: the receive buffer overflowed")
      }

      val outputIndexes = outputMarkIndexes(stepIndex)
      val completionTimes = matcher.completionTimes
      for (seriesIndex <- outputIndexes.indices if outputIndexes(seriesIndex) >= 0) {
        receiveTimes(outputIndexes(seriesIndex)) = completionTimes(seriesIndex)
      }
    }

    private def recordSamples(): Unit = {
      for (metricIndex <- metrics.indices) {
        samples(metricIndex)(sampleCount) = receiveTimes(metricTos(metricIndex)) - sendTimes(metricFroms(metricIndex))
      }
      sampleCount += 1
    }
  }
}

private object CaseRunner {
  /** Fails the launch in progress; it carries no stack trace, which only the reason matters for. */
  private class IterationFailure(reason: String) extends RuntimeException(reason, null, false, false)
}

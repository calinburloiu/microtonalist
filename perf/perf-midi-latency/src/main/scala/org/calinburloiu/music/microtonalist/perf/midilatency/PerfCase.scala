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

import org.calinburloiu.music.scmidi.message.MidiMsg

/** A message the suite expects from the app: an exact one, or any that a pattern covers. */
sealed trait Expected {
  /** Describes the expected message in a failure. */
  def description: String

  /** Tells whether `message` is what is expected. */
  def matches(message: MidiMsg): Boolean
}

object Expected {

  /** Expects exactly `message`. */
  case class Exactly(message: MidiMsg) extends Expected {
    override def description: String = message.toString

    override def matches(message: MidiMsg): Boolean = message == this.message
  }

  /**
   * Expects any message that `pattern` is defined at, e.g. a Note On on any channel.
   *
   * @param description Describes the messages the pattern covers, for failures.
   */
  case class Matching(description: String, pattern: PartialFunction[MidiMsg, Unit]) extends Expected {
    override def matches(message: MidiMsg): Boolean = pattern.isDefinedAt(message)
  }
}

/**
 * Messages the suite sends back to back. The send time of the series is when its first message is sent.
 *
 * @param mark Names the series for a [[Metric]].
 */
case class InputSeries(mark: Option[String], messages: Seq[MidiMsg]) {
  require(messages.nonEmpty, s"The input series ${mark.getOrElse("")} has no message")
}

/**
 * Messages the suite expects in this order. The receive time of the series is when its last message arrives.
 *
 * @param mark Names the series for a [[Metric]].
 */
case class OutputSeries(mark: Option[String], expected: Seq[Expected]) {
  require(expected.nonEmpty, s"The output series ${mark.getOrElse("")} expects no message")
}

/**
 * A step of an iteration: the suite sends its input series, then waits for all its output series before the next step.
 * No order is imposed among the output series, so their messages may arrive interleaved.
 */
case class Step(inputs: Seq[InputSeries], outputs: Seq[OutputSeries]) {
  require(inputs.nonEmpty, "A step needs an input series")
  require(outputs.nonEmpty, "A step needs an output series to wait for")
}

/**
 * A latency to measure: from the send time of the input series marked `from` to the receive time of the output series
 * marked `to`, in each timed iteration.
 */
case class Metric(name: String, from: String, to: String)

/** When a launched app is ready for the iterations to start. */
enum Readiness {
  /** At once, for a measurement without an app, such as the floor. */
  case Immediately

  /** Once a message matching `until` arrives after the launch. */
  case AfterLaunch(until: Expected)

  /**
   * Once a message matching `until` arrives, sending `input` again and again until it does, then once more, after the
   * app's output went quiet, so that an app still settling as it starts gets the probe in its final state.
   */
  case Probe(input: Seq[MidiMsg], until: Expected)
}

/**
 * What the suite measures: when the app is ready, the steps of an iteration and the metrics.
 *
 * Each iteration must leave the app as it found it, so that its expected output is the same every time.
 *
 * @throws IllegalArgumentException if it has no step or no metric, if a mark is used twice in a direction, or if a
 *   metric refers to a mark that doesn't exist, or measures to an output series of an earlier step than its input.
 */
case class Scenario(readiness: Readiness, steps: Seq[Step], metrics: Seq[Metric]) {
  require(steps.nonEmpty, "A scenario needs a step")
  require(metrics.nonEmpty, "A scenario needs a metric")

  private val inputMarkSteps: Map[String, Int] = markSteps("input", _.inputs.flatMap(_.mark))
  private val outputMarkSteps: Map[String, Int] = markSteps("output", _.outputs.flatMap(_.mark))

  for (name <- duplicates(metrics.map(_.name))) {
    throw IllegalArgumentException(s"The metric name '$name' is used more than once")
  }
  metrics.foreach { metric =>
    val fromStep = inputMarkSteps.getOrElse(metric.from,
      throw IllegalArgumentException(s"Metric '${metric.name}': no input series is marked '${metric.from}'"))
    val toStep = outputMarkSteps.getOrElse(metric.to,
      throw IllegalArgumentException(s"Metric '${metric.name}': no output series is marked '${metric.to}'"))
    require(fromStep <= toStep,
      s"Metric '${metric.name}': its output series '${metric.to}' comes before its input series '${metric.from}'")
  }

  /** The index of the first step that sends a metric's `from` series, which the suite paces. */
  val measuredStepIndex: Int = metrics.map(metric => inputMarkSteps(metric.from)).min

  /** Maps each mark of a direction to the index of its step, rejecting a mark used twice. */
  private def markSteps(direction: String, marksOf: Step => Seq[String]): Map[String, Int] = {
    val marks = for {
      (step, index) <- steps.zipWithIndex
      mark <- marksOf(step)
    } yield mark -> index
    for (mark <- duplicates(marks.map(_._1))) {
      throw IllegalArgumentException(s"The $direction mark '$mark' is used more than once")
    }
    marks.toMap
  }

  private def duplicates(names: Seq[String]): Iterable[String] =
    names.groupBy(identity).collect { case (name, occurrences) if occurrences.size > 1 => name }
}

/**
 * A latency case: a scenario played on a rig of the app.
 *
 * @param tracks Resource path of the tracks template, whose device variables the suite fills in.
 * @param composition Resource path of the composition.
 * @param appConfig Resource path of a custom app config; the app gets its defaults otherwise.
 */
case class PerfCase(tracks: String, composition: String, appConfig: Option[String], scenario: Scenario)

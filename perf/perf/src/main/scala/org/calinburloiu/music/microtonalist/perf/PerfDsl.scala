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

package org.calinburloiu.music.microtonalist.perf

import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * The language the latency cases are written in, e.g.
 * {{{
 *   input("pedal")(CcMidiMsg(0, 66, 127), CcMidiMsg(0, 66, 0)) ~> output("tuning")(sysEx)
 *   metric("pedal", "tuning")
 * }}}
 */
object PerfDsl {

  /** An input series marked `mark`. */
  def input(mark: String)(messages: MidiMsg*): InputSeries = InputSeries(Some(mark), messages)

  /** An unmarked input series. */
  def input(messages: MidiMsg*): InputSeries = InputSeries(None, messages)

  /** An output series marked `mark`. */
  def output(mark: String)(expected: Expected*): OutputSeries = OutputSeries(Some(mark), expected)

  /** An unmarked output series. */
  def output(expected: Expected*): OutputSeries = OutputSeries(None, expected)

  /** Expects any message that `pattern` is defined at, described by `description` in failures. */
  def matching(description: String)(pattern: PartialFunction[MidiMsg, Unit]): Expected =
    Expected.Matching(description, pattern)

  /** The metric from the input series marked `from` to the output series marked `to`, named `from→to`. */
  def metric(from: String, to: String): Metric = Metric(s"$from→$to", from, to)

  /** A step with several series on each side. */
  def step(inputs: InputSeries*)(outputs: OutputSeries*): Step = Step(inputs, outputs)

  extension (input: InputSeries) {
    /** A step that sends `input` and waits for `output`. */
    def ~>(output: OutputSeries): Step = Step(Seq(input), Seq(output))
  }

  /** Expects exactly the message given where an [[Expected]] is due. */
  given Conversion[MidiMsg, Expected] = Expected.Exactly(_)
}

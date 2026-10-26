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

package org.calinburloiu.music.microtonalist.perf.results

import org.calinburloiu.music.microtonalist.perf.PerfDevices
import org.calinburloiu.music.microtonalist.perf.env.{JvmInfo, MachineInfo, OsInfo}
import org.calinburloiu.music.microtonalist.perf.run.CaseOutcome
import org.calinburloiu.music.microtonalist.perf.stats.*
import org.calinburloiu.music.scmidi.MidiDeviceId

import java.time.Instant

/** Sample run results for the tests. */
object RunResultFixtures {

  val Commit: String = "a8ba00dfd5f5d66743e93e6266f902f9feca7bce"

  def metric(name: String, median: Long): MetricResult = MetricResult(
    name = name,
    from = name.takeWhile(_ != '→'),
    to = name.dropWhile(_ != '→').drop(1),
    statistics = MetricStatistics(count = 3000, min = 200000, mean = 312345.6, stdDev = 45678.9, median = median,
      p90 = 400000, p95 = 450000, p99 = 512345, max = 900000),
    histogram = Histogram(1.01, Seq(HistogramBin(1000, 3), HistogramBin(1010, 2997))),
    launches = Seq(LaunchStatistics(1000, median, 512345, 900000))
  )

  val sampleRun: RunResult = RunResult(
    startTime = Instant.parse("2026-10-26T10:15:00Z"),
    endTime = Instant.parse("2026-10-26T10:34:12Z"),
    app = AppBuild(version = "1.6.0-SNAPSHOT", commit = Commit, dirty = None, scalaVersion = "3.9.0",
      jarSha256 = "abc123"),
    suite = SuiteBuild(commit = Some("0123456789abcdef0123456789abcdef01234567"), dirty = Some(false)),
    jvm = JvmInfo(vendor = "Eclipse Adoptium", version = "25.0.1+8-LTS", options = Seq.empty),
    machine = MachineInfo(id = "f00d", name = "Mac16,8 · Apple M4 Pro", model = "Mac16,8", cpu = "Apple M4 Pro",
      physicalCores = 14, logicalCores = 14, memoryBytes = 51539607552L, os = OsInfo("macOS", "26.6.2"),
      powerSource = Some("AC Power"), lowPowerMode = Some(false),
      midiDevices = PerfDevices(MidiDeviceId("CoreMIDI4J - IAC 1", "Apple Inc."),
        MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."))),
    config = RunConfigSummary(launches = 3, warmupIterations = 10000, timedIterations = 1000, paceMillis = 50),
    floor = CaseOutcome.Passed(Seq(metric("loopback→loopback", 98765).copy(name = "floor"))),
    cases = Seq(
      CaseResult(name = "The MTS Octave 1-byte tuner should change the tuning on a pedal",
        tracks = Some("tracks/mts.mtlist.tracks"), composition = Some("compositions/two-tunings.mtlist"),
        outcome = CaseOutcome.Passed(Seq(metric("pedal→tuning", 312345)))),
      CaseResult(name = "The MPE tuner should pass a note through", tracks = Some("tracks/mpe.mtlist.tracks"),
        composition = Some("compositions/two-tunings.mtlist"),
        outcome = CaseOutcome.Failed("Launch 1: timed iteration 3, step 1: Timed out")),
      CaseResult(name = "The MIDI loopback should carry an MTS SysEx", tracks = None, composition = None,
        outcome = CaseOutcome.Passed(Seq(metric("tuning→tuning", 150000))))
    )
  )
}

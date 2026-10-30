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
import org.calinburloiu.music.microtonalist.perf.midilatency.app.{AppLauncher, ScratchRig}
import org.calinburloiu.music.microtonalist.perf.midilatency.midi.MidiPath
import org.calinburloiu.music.microtonalist.perf.midilatency.results.CaseResult
import org.calinburloiu.music.microtonalist.perf.midilatency.{Metric, PerfCase, PerfDevices, Readiness, Scenario}
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc}

import java.nio.file.{Path, Paths}
import scala.util.control.NonFatal

/**
 * A latency run: it measures the floor of the setup, then the cases one after another, each on a rig of its own in a
 * directory under `scratchDir`.
 *
 * @param floorPath The loopback of the app's input device, which the floor is measured over with no app running.
 * @param appPath From the app's input device to its output device.
 * @param resourceText Reads a resource of a case, by its path.
 */
final class PerfRun(floorPath: MidiPath,
                    appPath: MidiPath,
                    devices: PerfDevices,
                    settings: MeasurementSettings,
                    launcher: AppLauncher,
                    resourceText: String => String,
                    scratchDir: Path) {

  private var caseCount: Int = 0

  /**
   * Measures the suite's own latency, sending a message on the app's input device and receiving it back from the same
   * device, which a loopback device like an IAC bus does, with no app running. Its metric is named `floor`.
   */
  def measureFloor(): CaseOutcome =
    // With no app to relaunch, one launch is enough
    CaseRunner(floorPath, settings.copy(launches = 1)).run(PerfRun.FloorScenario, _ => NoApp)

  /** Measures `perfCase`, named `name`; a case that fails to run, e.g. for a missing resource, is a failed case. */
  def runCase(name: String, perfCase: PerfCase): CaseResult = {
    caseCount += 1
    val caseDir = scratchDir.resolve(f"$caseCount%02d-${slug(name)}")
    val outcome = try {
      measure(perfCase, caseDir)
    } catch {
      // The run records a case that can't even be set up as failed, like any other
      case NonFatal(exception) => CaseOutcome.Failed(s"The case failed to run: $exception")
    }

    CaseResult(name, Some(perfCase.tracks), Some(perfCase.composition), outcome)
  }

  private def measure(perfCase: PerfCase, caseDir: Path): CaseOutcome = {
    val rig = ScratchRig.prepare(
      dir = caseDir,
      compositionFileName = Paths.get(perfCase.composition).getFileName.toString,
      composition = resourceText(perfCase.composition),
      tracksTemplate = resourceText(perfCase.tracks),
      appConfig = perfCase.appConfig.map(resourceText),
      devices = devices
    )
    CaseRunner(appPath, settings).run(perfCase.scenario,
      launchNumber => launcher.launch(rig, caseDir.resolve(s"launch-$launchNumber.log")))
  }

  private def slug(name: String): String =
    name.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
}

object PerfRun {
  /** Sends a message on the loopback and expects it back; any message would do. */
  private val FloorScenario: Scenario = Scenario(
    readiness = Readiness.Immediately,
    steps = Seq(input("loopback")(CcMidiMsg(0, MidiCc.ModulationMsb, 0)) ~>
      output("loopback")(CcMidiMsg(0, MidiCc.ModulationMsb, 0))),
    metrics = Seq(Metric("floor", from = "loopback", to = "loopback"))
  )
}

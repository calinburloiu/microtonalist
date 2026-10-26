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

package org.calinburloiu.music.microtonalist.perf.midilatency.cases

import org.calinburloiu.music.microtonalist.perf.midilatency.*
import org.calinburloiu.music.microtonalist.perf.midilatency.app.{JarAppLauncher, JarBuildInfo}
import org.calinburloiu.music.microtonalist.perf.midilatency.config.{PerfConfig, RunParameters}
import org.calinburloiu.music.microtonalist.perf.midilatency.env.*
import org.calinburloiu.music.microtonalist.perf.midilatency.midi.DeviceMidiPath
import org.calinburloiu.music.microtonalist.perf.midilatency.results.*
import org.calinburloiu.music.microtonalist.perf.midilatency.run.{CaseOutcome, PerfRun}
import org.calinburloiu.music.scmidi.javamidi.JavaMidiManager
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Outcome}
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.collection.mutable
import scala.util.Using

/**
 * The base of the latency cases: it runs them against the app's JAR given with `-Djar=<path>`, and cancels them without
 * one, so that the normal test suite never launches the app nor needs MIDI devices.
 *
 * It is the composition root of a run: before the first case it describes the app, the suite, the JVM and the machine,
 * opens the configured MIDI devices and measures the floor; after the last one it writes the run file and prints the
 * summary. See `perf/perf-midi-latency/docs/ARCHITECTURE.md`.
 */
abstract class LatencySuite extends AnyWordSpec with Matchers with BeforeAndAfterAllConfigMap {

  private var activeRun: Option[ActiveRun] = None
  @volatile private var currentTestName: String = ""

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    val repoRoot = Paths.get(sys.props.getOrElse("microtonalist.perf.repoRoot", sys.props("user.dir")))
    val parameters = RunParameters.fromConfigMap(configMap.toMap, repoRoot)
    activeRun = parameters.jar.map(jar => ActiveRun.start(jar, parameters, repoRoot))
  }

  override protected def withFixture(test: NoArgTest): Outcome = {
    currentTestName = test.name
    super.withFixture(test)
  }

  /**
   * Measures a latency case named after the running test.
   *
   * @param tracks Resource path of the tracks template.
   * @param composition Resource path of the composition.
   * @param appConfig Resource path of a custom app config.
   */
  protected def perfCase(tracks: String,
                         composition: String,
                         readiness: Readiness,
                         appConfig: Option[String] = None)(steps: Step*)(metrics: Metric*): Unit = {
    // Built and checked first, so that the normal suite validates every case
    val latencyCase = PerfCase(tracks, composition, appConfig, Scenario(readiness, steps, metrics))
    for (path <- Seq(tracks, composition) ++ appConfig) {
      assert(getClass.getResource(s"/$path") != null, s"No resource $path in the latency suite")
    }
    assume(activeRun.isDefined, NoJarMessage)

    activeRun.get.runCase(currentTestName, latencyCase).outcome match {
      case CaseOutcome.Failed(reason) => fail(reason)
      case CaseOutcome.Passed(_) =>
    }
  }

  /**
   * Measures a loopback case named after the running test: the scenario over the loopback of the app's input device,
   * with no app running, which tells the setup's own cost for the messages of a flow.
   */
  protected def loopbackCase(steps: Step*)(metrics: Metric*): Unit = {
    val scenario = Scenario(Readiness.Immediately, steps, metrics)
    assume(activeRun.isDefined, NoJarMessage)

    activeRun.get.runLoopbackCase(currentTestName, scenario).outcome match {
      case CaseOutcome.Failed(reason) => fail(reason)
      case CaseOutcome.Passed(_) =>
    }
  }

  override protected def afterAll(configMap: ConfigMap): Unit = activeRun.foreach(_.finish())

  private val NoJarMessage = "No -Djar=<app JAR> given: the latency cases need the app"
}

/** A run in progress, from its start to its run file. */
private final class ActiveRun(perfRun: PerfRun,
                              closeables: Seq[AutoCloseable],
                              resultsDir: Path,
                              scratchDir: Path,
                              describe: (Instant, CaseOutcome, Seq[CaseResult]) => RunResult) {

  private val startTime = Instant.now()
  private val floor = perfRun.measureFloor()
  private val results = mutable.ArrayBuffer[CaseResult]()

  def runCase(name: String, perfCase: PerfCase): CaseResult = record(perfRun.runCase(name, perfCase))

  def runLoopbackCase(name: String, scenario: Scenario): CaseResult = record(perfRun.runLoopbackCase(name, scenario))

  private def record(result: CaseResult): CaseResult = {
    results += result
    result
  }

  def finish(): Unit = {
    try {
      val run = describe(startTime, floor, results.toSeq)
      val file = ResultsStore(resultsDir).write(run)
      println(SummaryTable.render(run))
      println(s"Run file: $file")
      println(s"Raw samples, which git ignores: ${resultsDir.resolve(ResultsStore.samplesPath(run))}")
      println(s"Scratch directory, with the app's logs: $scratchDir")
    } finally {
      closeables.foreach(_.close())
    }
  }
}

private object ActiveRun {

  def start(jar: Path, parameters: RunParameters, repoRoot: Path): ActiveRun = {
    val config = parameters.applyTo(PerfConfig.parse(
      Json.parse(resourceText(PerfConfig.ResourceName)),
      resource(PerfConfig.LocalResourceName).map(Json.parse),
      repoRoot
    ))
    val devices = config.devices
    val java = config.java.getOrElse(Paths.get(sys.props("java.home"), "bin", "java"))
    val repo = GitRepo(repoRoot, ProcessCommandRunner)
    val app = AppBuild.describe(jar, JarBuildInfo.read(jar), parameters.commit, repo.resolve)
    // The results of earlier runs, not yet committed, don't change what the suite measures
    val resultsInRepo = Option.when(config.resultsDir.startsWith(repoRoot))(repoRoot.relativize(config.resultsDir))
    val suite = SuiteBuild(repo.head, repo.isDirty(excluding = resultsInRepo.map(_.toString).toSeq))
    val jvm = JvmInfo.probe(java, config.jvmOptions, ProcessCommandRunner)
    val machine = MachineProbe.forOs(sys.props("os.name"), ProcessCommandRunner).probe(config.machineName, devices)
    val measurement = config.measurement
    val runConfig = RunConfigSummary(measurement.launches, measurement.warmupIterations, measurement.timedIterations,
      measurement.pace.toMillis, measurement.maxMessageRate)

    val manager = JavaMidiManager()
    try {
      val floorPath = DeviceMidiPath.open(manager, devices.input, devices.input)
      val appPath = DeviceMidiPath.open(manager, devices.input, devices.output)
      val scratchDir = Files.createTempDirectory("microtonalist-perf-")
      val perfRun = PerfRun(floorPath, appPath, devices, measurement, JarAppLauncher(java, config.jvmOptions, jar),
        resourceText, scratchDir)
      // The loopback cases keep using the floor's path, so it stays open until the run's end
      ActiveRun(perfRun, Seq(floorPath, appPath, manager), config.resultsDir, scratchDir,
        (startTime, floor, cases) =>
          RunResult(parameters.name, startTime, Instant.now(), app, suite, jvm, machine, runConfig, floor, cases))
    } catch {
      case exception: Throwable =>
        manager.close()
        throw exception
    }
  }

  private def resource(path: String): Option[String] = Option(getClass.getResourceAsStream(s"/$path")).map { stream =>
    Using.resource(stream)(input => String(input.readAllBytes(), StandardCharsets.UTF_8))
  }

  private def resourceText(path: String): String =
    resource(path).getOrElse(throw PerfSetupException(s"No resource $path in the latency suite"))
}

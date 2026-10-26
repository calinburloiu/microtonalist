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
import org.calinburloiu.music.microtonalist.perf.app.{AppLauncher, PreparedRig}
import org.calinburloiu.music.microtonalist.perf.midi.MidiPath
import org.calinburloiu.music.microtonalist.perf.{PerfCase, PerfDevices, Readiness, Scenario}
import org.calinburloiu.music.scmidi.message.{NoteOffMidiMsg, NoteOnMidiMsg}
import org.calinburloiu.music.scmidi.{MidiDeviceId, MidiNote, MidiReceiver}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

class PerfRunTest extends AnyWordSpec with Matchers {

  private val settings = MeasurementSettings(launches = 1, warmupIterations = 2, timedIterations = 3, pace = 0.millis,
    timeouts = Timeouts(readiness = 2.seconds, probeInterval = 50.millis, quiet = 30.millis, step = 200.millis,
      exit = 100.millis))

  private val devices = PerfDevices(MidiDeviceId("Bus 1", "Acme"), MidiDeviceId("Bus 2", "Acme"))

  private val resources = Map(
    "tracks/fake.mtlist.tracks" -> """{"in": "{{input.name}}", "out": "{{output.name}}"}""",
    "compositions/two.mtlist" -> "{}"
  )

  private val noteOn = NoteOnMidiMsg(0, MidiNote(64))
  private val noteOff = NoteOffMidiMsg(0, MidiNote(64))

  private def perfCase(expected: NoteOnMidiMsg | NoteOffMidiMsg): PerfCase = PerfCase(
    tracks = "tracks/fake.mtlist.tracks",
    composition = "compositions/two.mtlist",
    appConfig = None,
    scenario = Scenario(Readiness.Probe(Seq(noteOff), noteOff),
      Seq(input("noteOn")(noteOn) ~>
        output("noteOn")(expected)), Seq(metric("noteOn", "noteOn")))
  )

  private abstract class Fixture {
    val floorPath: FakeApp = FakeApp()
    floorPath.launch()
    val appPath: FakeApp = FakeApp()
    val rigs: mutable.Buffer[PreparedRig] = mutable.ArrayBuffer()
    val launcher: AppLauncher = (rig: PreparedRig, _: Path) => {
      rigs += rig
      appPath.launch()
    }
    val scratchDir: Path = Files.createTempDirectory("perf-run")
    val run: PerfRun = PerfRun(floorPath, appPath, devices, settings, launcher, resources, scratchDir)
  }

  "PerfRun" should {
    "measure the floor over the loopback" in new Fixture {
      // When
      private val outcome = run.measureFloor()

      // Then
      outcome shouldBe a[CaseOutcome.Passed]
      private val metrics = outcome.asInstanceOf[CaseOutcome.Passed].metrics
      metrics.map(_.name) shouldEqual Seq("floor")
      metrics.head.statistics.count shouldEqual 3
      appPath.launchCount.get shouldEqual 0
    }

    "run a loopback case without an app" in new Fixture {
      // Given
      private val scenario = Scenario(Readiness.Immediately, Seq(input("noteOn")(noteOn) ~> output("noteOn")(noteOn)),
        Seq(metric("noteOn", "noteOn")))

      // When
      private val result = run.runLoopbackCase("The loopback should carry a Note On", scenario)

      // Then
      result.name shouldEqual "The loopback should carry a Note On"
      result.tracks shouldBe empty
      result.composition shouldBe empty
      result.outcome shouldBe a[CaseOutcome.Passed]
      result.outcome.asInstanceOf[CaseOutcome.Passed].metrics.head.launches should have size 1
      appPath.launchCount.get shouldEqual 0
      rigs shouldBe empty
    }

    "record a loopback case that fails to run as failed" in new Fixture {
      // Given
      private val brokenPath: MidiPath = new MidiPath {
        override def sender: MidiReceiver = (_, _) => ()

        override def attach(receiver: MidiReceiver): Unit = throw IllegalStateException("The device is gone")

        override def detach(receiver: MidiReceiver): Unit = ()
      }
      private val brokenRun = PerfRun(brokenPath, appPath, devices, settings, launcher, resources, scratchDir)
      private val scenario = Scenario(Readiness.Immediately, Seq(input("noteOn")(noteOn) ~> output("noteOn")(noteOn)),
        Seq(metric("noteOn", "noteOn")))

      // When
      private val result = brokenRun.runLoopbackCase("The loopback should carry a Note On", scenario)

      // Then
      result.outcome shouldBe a[CaseOutcome.Failed]
      result.outcome.asInstanceOf[CaseOutcome.Failed].reason should include("The device is gone")
    }

    "run a case on a scratch rig with the devices filled in" in new Fixture {
      // When
      private val result = run.runCase("The fake should echo", perfCase(noteOn))

      // Then
      result.name shouldEqual "The fake should echo"
      result.tracks shouldEqual Some("tracks/fake.mtlist.tracks")
      result.composition shouldEqual Some("compositions/two.mtlist")
      result.outcome shouldBe a[CaseOutcome.Passed]
      rigs should have size 1
      rigs.head.composition.getFileName.toString shouldEqual "two.mtlist"
      rigs.head.composition.startsWith(scratchDir) shouldBe true
      Files.readString(rigs.head.tracks) shouldEqual """{"in": "Bus 1", "out": "Bus 2"}"""
    }

    "record a case that can't be set up as failed" in new Fixture {
      // Given
      private val missingTracks = perfCase(noteOn).copy(tracks = "tracks/missing.mtlist.tracks")

      // When
      private val result = run.runCase("The fake should have tracks", missingTracks)

      // Then
      result.name shouldEqual "The fake should have tracks"
      result.tracks shouldEqual Some("tracks/missing.mtlist.tracks")
      result.outcome shouldBe a[CaseOutcome.Failed]
      result.outcome.asInstanceOf[CaseOutcome.Failed].reason should include("tracks/missing.mtlist.tracks")
      appPath.launchCount.get shouldEqual 0
    }

    "go on with the next case after a failed one" in new Fixture {
      // When
      private val failed = run.runCase("The fake should not echo", perfCase(noteOff))
      private val passed = run.runCase("The fake should echo", perfCase(noteOn))

      // Then
      failed.outcome shouldBe a[CaseOutcome.Failed]
      passed.outcome shouldBe a[CaseOutcome.Passed]
      rigs.map(_.composition.getParent).distinct should have size 2
    }
  }
}

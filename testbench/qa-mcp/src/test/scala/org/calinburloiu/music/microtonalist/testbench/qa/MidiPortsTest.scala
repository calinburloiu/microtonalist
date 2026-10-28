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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.*
import scala.util.Random

class MidiPortsTest extends AnyWordSpec with Matchers {

  private val keys: QaDevice = QaDevice("keys", input = true, output = false)
  private val synth: QaDevice = QaDevice("synth", input = false, output = true)
  private val both: QaDevice = QaDevice("dev", input = true, output = true)

  private abstract class Fixture(toolExitTimeout: FiniteDuration = 5.seconds) {
    val run: QaRun = QaRun.create(Files.createTempDirectory("qa"),
      new Random {
        override def nextInt(n: Int): Int = 0x3F9C
      })
    val launcher: FakeProcessLauncher = FakeProcessLauncher()
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC)
    val ports: MidiPorts = MidiPorts(run, launcher, clock, toolExitTimeout)

    def openAll(unplugged: Set[String] = Set.empty): Unit = ports.open(Seq(keys, synth, both), unplugged)
  }

  "MidiPorts.open" should {
    "open the tools of each device" in new Fixture {
      // When
      openAll()

      // Then
      launcher.specs.map(_.command) shouldBe Seq(
        Seq("sendmidi", "virt", "qa-3f9c-keys", "--"), Seq("receivemidi", "virt", "qa-3f9c-synth", "ts", "nn"),
        Seq("sendmidi", "virt", "qa-3f9c-dev", "--"), Seq("receivemidi", "virt", "qa-3f9c-dev", "ts", "nn"))
      launcher.specs.head shouldBe ProcessSpec(Seq("sendmidi", "virt", "qa-3f9c-keys", "--"),
        Some(run.dir.resolve("midi-tools.log")), pipeInput = true)
      launcher.specs(1).output shouldBe Some(run.dir.resolve("midi-out-synth.txt"))
      launcher.specs(1).pipeInput shouldBe false
      Seq("keys", "synth", "dev").map(ports.isPlugged) shouldBe Seq(true, true, true)
      ports.processes shouldBe launcher.processes
    }

    "leave a device unplugged at startup" in new Fixture {
      // When
      openAll(unplugged = Set("synth"))

      // Then
      launcher.specs.map(_.command.head) shouldBe Seq("sendmidi", "sendmidi", "receivemidi")
      ports.isPlugged("synth") shouldBe false
      ports.isPlugged("keys") shouldBe true
    }

    "refuse to leave unplugged a device it isn't given, starting nothing" in new Fixture {
      // When / Then
      (the[QaException] thrownBy openAll(unplugged = Set("drums"))).getMessage shouldBe
        "unknown device drums; the QA devices are keys, synth, dev"
      launcher.specs shouldBe empty
    }

    "say so when a tool isn't installed, stopping the tools it started" in new Fixture {
      // Given
      launcher.startFailure = spec =>
        Option.when(spec.command.head == "receivemidi")(IOException("Cannot run program \"receivemidi\""))

      // When / Then
      (the[QaException] thrownBy ports.open(Seq(keys, both), Set.empty)).getMessage shouldBe
        "receivemidi isn't installed; see docs/development/qa.md"
      launcher.processes.map(_.isAlive) shouldBe Seq(false, false)
      ports.processes shouldBe empty
      ports.isPlugged("keys") shouldBe false
    }
  }

  "MidiPorts.unplug and replug" should {
    "unplug both tools of a device, and replug them with the same output file" in new Fixture {
      // Given
      openAll()

      // When
      ports.unplug("dev")

      // Then
      launcher.process(2).terminateCount shouldBe 1
      launcher.process(3).terminateCount shouldBe 1
      launcher.process(2).inputClosed shouldBe true
      ports.isPlugged("dev") shouldBe false
      ports.processes shouldBe launcher.processes.take(2)

      // When
      ports.replug("dev")

      // Then
      launcher.specs.drop(4) shouldBe launcher.specs.slice(2, 4)
      launcher.specs.last.output shouldBe Some(run.dir.resolve("midi-out-dev.txt"))
      ports.isPlugged("dev") shouldBe true
      ports.processes shouldBe launcher.processes.take(2) ++ launcher.processes.drop(4)
    }

    "kill a tool that ignores SIGTERM" in new Fixture(toolExitTimeout = 50.millis) {
      // Given
      launcher.newProcess = (spec, pid) => FakeProcess(spec, pid, ignoresTerminate = true)
      openAll()

      // When
      ports.unplug("synth")

      // Then
      launcher.process(1).terminateCount shouldBe 1
      launcher.process(1).killCount shouldBe 1
      launcher.process(1).isAlive shouldBe false
    }

    "plug in a device left unplugged at startup" in new Fixture {
      // Given
      openAll(unplugged = Set("synth"))

      // When
      ports.replug("synth")

      // Then
      launcher.specs.last.command shouldBe Seq("receivemidi", "virt", "qa-3f9c-synth", "ts", "nn")
      ports.isPlugged("synth") shouldBe true
    }

    "refuse to unplug an unplugged device, and to replug a plugged one" in new Fixture {
      // Given
      openAll(unplugged = Set("synth"))

      // When / Then
      (the[QaException] thrownBy ports.unplug("synth")).getMessage shouldBe "synth is already unplugged"
      (the[QaException] thrownBy ports.replug("keys")).getMessage shouldBe "keys is already plugged in"
    }

    "refuse an unknown device" in new Fixture {
      // Given
      openAll()

      // When / Then
      a[QaException] should be thrownBy ports.unplug("drums")
      a[QaException] should be thrownBy ports.replug("drums")
      a[QaException] should be thrownBy ports.isPlugged("drums")
    }

    "refuse any device before it opens" in new Fixture {
      // When / Then
      (the[QaException] thrownBy ports.isPlugged("keys")).getMessage shouldBe
        "unknown device keys; there are no QA devices"
    }
  }

  "MidiPorts.send" should {
    "send the lines to sendmidi and log them" in new Fixture {
      // Given
      openAll()

      // When
      val midiIn: Path = ports.send("keys", Seq("ch 1 cc 66 127"))

      // Then
      launcher.process(0).written shouldBe "ch 1 cc 66 127\n"
      midiIn shouldBe run.dir.resolve("midi-in-keys.txt")
      Files.readString(midiIn) shouldBe "12:00:00.000 ch 1 cc 66 127\n"
    }

    "append the lines of each send" in new Fixture {
      // Given
      openAll()
      ports.send("dev", Seq("ch 1 cc 66 127", "ch 1 cc 66 0"))

      // When
      ports.send("dev", Seq("ch 2 on 60 100"))

      // Then
      launcher.process(2).written shouldBe "ch 1 cc 66 127\nch 1 cc 66 0\nch 2 on 60 100\n"
      Files.readString(run.dir.resolve("midi-in-dev.txt")) shouldBe
        "12:00:00.000 ch 1 cc 66 127\n12:00:00.000 ch 1 cc 66 0\n12:00:00.000 ch 2 on 60 100\n"
    }

    "refuse a bad line, sending and logging nothing" in new Fixture {
      // Given
      openAll()

      // When / Then
      a[QaException] should be thrownBy ports.send("keys", Seq("ch 1 cc 66 127", "dev IAC"))
      launcher.process(0).written shouldBe ""
      Files.exists(run.dir.resolve("midi-in-keys.txt")) shouldBe false
    }

    "refuse to send to an output, an unplugged or an unknown device" in new Fixture {
      // Given
      openAll(unplugged = Set("dev"))

      // When / Then
      (the[QaException] thrownBy ports.send("synth", Seq("ch 1 cc 66 127"))).getMessage shouldBe
        "synth isn't an input device"
      (the[QaException] thrownBy ports.send("dev", Seq("ch 1 cc 66 127"))).getMessage shouldBe
        "dev is unplugged; call replug first"
      (the[QaException] thrownBy ports.send("drums", Seq("ch 1 cc 66 127"))).getMessage shouldBe
        "unknown device drums; the QA devices are keys, synth, dev"
    }

    "say so when sendmidi has exited" in new Fixture {
      // Given
      openAll()
      launcher.process(0).exit(1)

      // When / Then
      (the[QaException] thrownBy ports.send("keys", Seq("ch 1 cc 66 127"))).getMessage shouldBe
        "sendmidi for keys exited; see midi-tools.log"
    }
  }

  "MidiPorts.stopAll" should {
    "close each sendmidi's input, then terminate every tool" in new Fixture {
      // Given
      openAll(unplugged = Set("keys"))

      // When
      ports.stopAll()

      // Then
      launcher.processes.map(_.terminateCount) shouldBe Seq(1, 1, 1)
      launcher.process(1).inputClosed shouldBe true
      ports.processes shouldBe empty
      Seq("keys", "synth", "dev").map(ports.isPlugged) shouldBe Seq(false, false, false)
    }

    "terminate a sendmidi that has already exited" in new Fixture {
      // Given
      openAll()
      launcher.process(0).exit(1)

      // When
      ports.stopAll()

      // Then
      launcher.processes.map(_.terminateCount) shouldBe Seq(1, 1, 1, 1)
    }

    "refuse to open the ports again before they stop" in new Fixture {
      // Given
      openAll()

      // When / Then
      an[IllegalStateException] should be thrownBy ports.open(Seq(keys), Set.empty)
      launcher.specs should have size 4
    }

    "let the ports open again" in new Fixture {
      // Given
      openAll()
      ports.stopAll()

      // When
      ports.open(Seq(keys), Set.empty)

      // Then
      ports.processes shouldBe Seq(launcher.processes.last)
      a[QaException] should be thrownBy ports.isPlugged("synth")
    }
  }
}

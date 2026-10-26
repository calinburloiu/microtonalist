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

package org.calinburloiu.music.microtonalist.perf.midilatency.env

import org.calinburloiu.music.microtonalist.perf.midilatency.{PerfDevices, PerfSetupException, Sha256}
import org.calinburloiu.music.scmidi.MidiDeviceId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Path

class MacMachineProbeTest extends AnyWordSpec with Matchers {

  private val Uuid = "0C1D2E3F-4A5B-6C7D-8E9F-A0B1C2D3E4F5"

  private val devices = PerfDevices(MidiDeviceId("CoreMIDI4J - IAC 1", "Apple Inc."),
    MidiDeviceId("CoreMIDI4J - IAC 2", "Apple Inc."))

  private val outputs: Map[Seq[String], String] = Map(
    Seq("ioreg", "-rd1", "-c", "IOPlatformExpertDevice") ->
      s"""+-o J614sAP  <class IOPlatformExpertDevice, id 0x100000201, registered, matched, active, busy 0 (5 ms), retain 39>
         |    {
         |      "model" = <"Mac16,8">
         |      "IOPlatformUUID" = "$Uuid"
         |    }
         |""".stripMargin,
    Seq("sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.physicalcpu", "hw.logicalcpu", "hw.memsize") ->
      "Mac16,8\nApple M4 Pro\n14\n14\n51539607552\n",
    Seq("sw_vers", "-productName") -> "macOS\n",
    Seq("sw_vers", "-productVersion") -> "26.6.2\n",
    Seq("pmset", "-g", "ps") ->
      "Now drawing from 'AC Power'\n -InternalBattery-0 (id=21692515)\t100%; charged; 0:00 remaining present: true\n",
    Seq("pmset", "-g") ->
      "System-wide power settings:\nCurrently in use:\n standby              1\n powermode            0\n"
  )

  private class CannedRunner(outputs: Map[Seq[String], String], failing: Set[Seq[String]] = Set.empty)
    extends CommandRunner {
    override def run(command: Seq[String], directory: Option[Path]): CommandResult =
      if (failing.contains(command)) CommandResult(1, "failed") else CommandResult(0, outputs(command))
  }

  private def probe(outputs: Map[Seq[String], String] = outputs, name: Option[String] = Some("Studio")): MachineInfo =
    MacMachineProbe(CannedRunner(outputs)).probe(name, devices)

  "MacMachineProbe" should {
    "identify the machine by the hash of its hardware UUID, not the UUID" in {
      // When
      val info = probe()

      // Then
      info.id shouldEqual Sha256.ofString(Uuid)
      info.id should not include Uuid
    }

    "read the model, CPU, cores and memory" in {
      // When
      val info = probe()

      // Then
      info.model shouldEqual "Mac16,8"
      info.cpu shouldEqual "Apple M4 Pro"
      info.physicalCores shouldEqual 14
      info.logicalCores shouldEqual 14
      info.memoryBytes shouldEqual 51539607552L
    }

    "read the OS" in {
      // When / Then
      probe().os shouldEqual OsInfo("macOS", "26.6.2")
    }

    "read the power source and Low Power Mode" in {
      // When
      val info = probe()
      val lowPower = probe(outputs.updated(Seq("pmset", "-g"), " lowpowermode         1\n"))
      val unknown = probe(outputs.updated(Seq("pmset", "-g"), " standby              1\n"))

      // Then
      info.powerSource shouldEqual Some("AC Power")
      info.lowPowerMode shouldEqual Some(false)
      lowPower.lowPowerMode shouldEqual Some(true)
      unknown.lowPowerMode shouldBe empty
    }

    "keep the configured name and the MIDI devices" in {
      // When
      val info = probe()

      // Then
      info.name shouldEqual "Studio"
      info.midiDevices shouldEqual devices
    }

    "name the machine by its model and CPU when no name is configured" in {
      // When / Then
      probe(name = None).name shouldEqual "Mac16,8 · Apple M4 Pro"
    }

    "fail clearly when a command fails" in {
      // Given
      val runner = CannedRunner(outputs, failing = Set(Seq("sw_vers", "-productName")))

      // When
      val exception = the[PerfSetupException] thrownBy MacMachineProbe(runner).probe(None, devices)

      // Then
      exception.getMessage should include("sw_vers -productName")
    }
  }
}

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

package org.calinburloiu.music.microtonalist.perf.env

import org.calinburloiu.music.microtonalist.perf.{PerfDevices, PerfSetupException, Sha256}

/** Describes a Mac with the commands of macOS: `ioreg`, `sysctl`, `sw_vers` and `pmset`. */
final class MacMachineProbe(runner: CommandRunner) extends MachineProbe {

  import MacMachineProbe.*

  override def probe(name: Option[String], devices: PerfDevices): MachineInfo = {
    val uuid = UuidLine.findFirstMatchIn(run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))
      .map(_.group(1))
      .getOrElse(throw PerfSetupException("ioreg didn't tell the hardware UUID"))
    val hardware = run("sysctl", "-n", "hw.model", "machdep.cpu.brand_string", "hw.physicalcpu", "hw.logicalcpu",
      "hw.memsize").linesIterator.toIndexedSeq
    if (hardware.size < 5) {
      throw PerfSetupException(s"sysctl didn't tell the hardware: ${hardware.mkString(", ")}")
    }
    val Seq(model, cpu, physicalCores, logicalCores, memoryBytes) = hardware.take(5).map(_.trim): @unchecked
    val powerSettings = run("pmset", "-g")

    MachineInfo(
      // The hash identifies the machine across runs without publishing its hardware UUID
      id = Sha256.ofString(uuid),
      name = name.getOrElse(s"$model · $cpu"),
      model = model,
      cpu = cpu,
      physicalCores = physicalCores.toInt,
      logicalCores = logicalCores.toInt,
      memoryBytes = memoryBytes.toLong,
      os = OsInfo(run("sw_vers", "-productName").trim, run("sw_vers", "-productVersion").trim),
      powerSource = PowerSourceLine.findFirstMatchIn(run("pmset", "-g", "ps")).map(_.group(1)),
      // macOS 12 and later name it `powermode`, where 1 is Low Power; older ones `lowpowermode`
      lowPowerMode = LowPowerModeLine.findFirstMatchIn(powerSettings).map(_.group(1) == "1"),
      midiDevices = devices
    )
  }

  private def run(command: String*): String = {
    val result = runner.run(command)
    if (result.exitCode != 0) {
      throw PerfSetupException(s"`${command.mkString(" ")}` failed (exit code ${result.exitCode}): ${result.output}")
    }
    result.output
  }
}

private object MacMachineProbe {
  private val UuidLine = """"IOPlatformUUID" = "([^"]+)"""".r
  private val PowerSourceLine = """Now drawing from '([^']+)'""".r
  private val LowPowerModeLine = """(?m)^\s*(?:lowpowermode|powermode)\s+(\d+)""".r
}

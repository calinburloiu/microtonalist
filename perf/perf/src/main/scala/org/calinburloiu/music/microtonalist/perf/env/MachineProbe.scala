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

import org.calinburloiu.music.microtonalist.perf.{PerfDevices, PerfSetupException}

/** Describes the machine a run measures on; each operating system has its own. */
trait MachineProbe {
  /**
   * Describes the machine.
   *
   * @param name The configured name, if any.
   * @throws org.calinburloiu.music.microtonalist.perf.PerfSetupException if a command it runs fails.
   */
  def probe(name: Option[String], devices: PerfDevices): MachineInfo
}

object MachineProbe {
  /**
   * The probe of the operating system named `osName`, as the `os.name` system property tells it.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.PerfSetupException for an OS without a probe.
   */
  def forOs(osName: String, runner: CommandRunner): MachineProbe =
    if (osName.startsWith("Mac")) MacMachineProbe(runner)
    else throw PerfSetupException(s"No machine probe for $osName yet")
}

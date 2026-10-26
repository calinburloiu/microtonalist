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

import org.calinburloiu.music.microtonalist.perf.PerfDevices

/** The operating system of a machine. */
case class OsInfo(name: String, version: String)

/**
 * The machine a run measures on, whose performance the latencies depend on.
 *
 * @param id Identifies the machine across runs without publishing a hardware identifier, e.g. a hash of it.
 * @param name A friendly label for the report: the configured one, or the model and CPU.
 * @param memoryBytes The RAM.
 * @param powerSource Where the power comes from, e.g. `AC Power`, which changes a laptop's CPU speed.
 * @param lowPowerMode Whether the OS slows the machine down to save energy.
 * @param midiDevices The devices the suite sends to and receives from.
 */
case class MachineInfo(id: String,
                       name: String,
                       model: String,
                       cpu: String,
                       physicalCores: Int,
                       logicalCores: Int,
                       memoryBytes: Long,
                       os: OsInfo,
                       powerSource: Option[String],
                       lowPowerMode: Option[Boolean],
                       midiDevices: PerfDevices)

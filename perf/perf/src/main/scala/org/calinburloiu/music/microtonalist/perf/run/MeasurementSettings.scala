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

import scala.concurrent.duration.FiniteDuration

/**
 * How long the suite waits for each thing.
 *
 * @param readiness For a launched app to be ready.
 * @param probeInterval Between two sends of a readiness probe.
 * @param quiet Without any message, to consider the output drained.
 * @param step For the output of a step.
 * @param exit For a stopped app to exit, before killing it.
 */
case class Timeouts(readiness: FiniteDuration,
                    probeInterval: FiniteDuration,
                    quiet: FiniteDuration,
                    step: FiniteDuration,
                    exit: FiniteDuration)

/**
 * How a case is measured.
 *
 * @param launches How many times the app is launched, one after another, since JIT decisions differ between JVMs.
 * @param warmupIterations Untimed iterations, back to back, before the timed ones of each launch.
 * @param timedIterations Timed iterations of each launch.
 * @param pace The period on which the measured step of the timed iterations starts; zero runs them back to back.
 */
case class MeasurementSettings(launches: Int,
                               warmupIterations: Int,
                               timedIterations: Int,
                               pace: FiniteDuration,
                               timeouts: Timeouts)

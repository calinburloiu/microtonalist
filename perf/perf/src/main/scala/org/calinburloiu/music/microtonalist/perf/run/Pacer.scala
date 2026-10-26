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

import java.util.concurrent.{Executors, ScheduledExecutorService, Semaphore, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/**
 * Paces the measured step of the timed iterations at a fixed rate, by waiting on a scheduler's ticks rather than
 * sleeping. A tick missed while an iteration ran is dropped, so that iterations never start back to back.
 *
 * Only one thread may await its ticks.
 */
final class Pacer(period: FiniteDuration) extends AutoCloseable {
  require(period.toNanos > 0, s"The period must be positive, but was $period")

  private val ticks = Semaphore(0)
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable =>
    val thread = Thread(runnable, "perf-pacer")
    thread.setDaemon(true)
    thread
  }
  scheduler.scheduleAtFixedRate(() => ticks.release(), period.toNanos, period.toNanos, TimeUnit.NANOSECONDS)

  /** Waits for the next tick. */
  def awaitNextTick(): Unit = {
    ticks.drainPermits()
    ticks.acquire()
  }

  /** Stops the ticks. */
  override def close(): Unit = scheduler.shutdownNow()
}

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

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

/** A launch of the app under measurement. */
trait RunningApp {
  /** Whether the app still runs. */
  def isAlive: Boolean

  /** The app's exit code, once it exited. */
  def exitCode: Option[Int]

  /** Where the app's output goes, if anywhere. */
  def logFile: Option[Path]

  /** Asks the app to exit, and kills it if it doesn't within `timeout`. */
  def stop(timeout: FiniteDuration): Unit
}

/** No app at all, for a measurement of the suite's own setup such as the floor. */
object NoApp extends RunningApp {
  override def isAlive: Boolean = true

  override def exitCode: Option[Int] = None

  override def logFile: Option[Path] = None

  override def stop(timeout: FiniteDuration): Unit = ()
}

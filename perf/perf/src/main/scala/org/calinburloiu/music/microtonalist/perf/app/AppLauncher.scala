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

package org.calinburloiu.music.microtonalist.perf.app

import org.calinburloiu.music.microtonalist.perf.run.RunningApp

import java.nio.file.Path

/** Launches the app on a prepared rig. */
trait AppLauncher {
  /** Launches the app on `rig`, writing its output to `logFile`. */
  def launch(rig: PreparedRig, logFile: Path): RunningApp
}

/**
 * Launches the app from its JAR in a process of its own: `java <jvmOptions> -jar <jar> <composition> <app config>`.
 *
 * @param java The `java` executable of the JDK to run the app with.
 */
final class JarAppLauncher(java: Path, jvmOptions: Seq[String], jar: Path) extends AppLauncher {

  /** The command line that launches the app on `rig`. */
  def command(rig: PreparedRig): Seq[String] =
    (java.toString +: jvmOptions) ++ ("-jar" +: Seq(jar, rig.composition, rig.appConfig).map(_.toAbsolutePath.toString))

  override def launch(rig: PreparedRig, logFile: Path): RunningApp = ProcessApp.start(command(rig), logFile)
}

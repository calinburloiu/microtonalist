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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

class JarAppLauncherTest extends AnyWordSpec with Matchers {

  "JarAppLauncher" should {
    "put the JVM options before the JAR and the arguments after it" in {
      // Given
      val launcher = JarAppLauncher(Paths.get("/jdk/bin/java"), Seq("-Xmx1g", "-XX:+UseZGC"), Paths.get("/a/app.jar"))
      val rig = PreparedRig(Paths.get("/s/two.mtlist"), Paths.get("/s/two.mtlist.tracks"), Paths.get("/s/app.conf"))

      // When
      val command = launcher.command(rig)

      // Then
      command shouldEqual Seq("/jdk/bin/java", "-Xmx1g", "-XX:+UseZGC", "-jar", "/a/app.jar", "/s/two.mtlist",
        "/s/app.conf")
    }
  }
}

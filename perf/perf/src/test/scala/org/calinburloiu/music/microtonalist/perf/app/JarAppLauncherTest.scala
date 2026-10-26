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

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt

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

    "launch the app in a process, with its output in the log" in {
      // Given
      // A shell stands in for `java`, printing the arguments it gets after its options
      val launcher = JarAppLauncher(Paths.get("sh"), Seq("-c", "echo \"$@\"", "sh"), Paths.get("/a/app.jar"))
      val rig = PreparedRig(Paths.get("/s/two.mtlist"), Paths.get("/s/two.mtlist.tracks"), Paths.get("/s/app.conf"))
      val log = Files.createTempDirectory("launcher").resolve("app.log")

      // When
      val app = launcher.launch(rig, log)

      // Then
      app shouldBe a[ProcessApp]
      app.asInstanceOf[ProcessApp].awaitExit(10.seconds) shouldBe true
      Files.readString(log) shouldEqual "-jar /a/app.jar /s/two.mtlist /s/app.conf\n"
    }
  }
}

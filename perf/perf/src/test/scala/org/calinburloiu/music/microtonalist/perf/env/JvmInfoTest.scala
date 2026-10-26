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

import org.calinburloiu.music.microtonalist.perf.PerfSetupException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Path, Paths}

class JvmInfoTest extends AnyWordSpec with Matchers {

  private val settingsOutput =
    """Property settings:
      |    file.encoding = UTF-8
      |    java.class.path =
      |        /a.jar
      |    java.runtime.version = 25.0.1+8-LTS
      |    java.vendor = Eclipse Adoptium
      |
      |openjdk version "25.0.1" 2025-10-21 LTS
      |""".stripMargin

  private def cannedRunner(result: CommandResult): CommandRunner = (_: Seq[String], _: Option[Path]) => result

  "JvmInfo" should {
    "read the vendor and version of a JDK" in {
      // When
      val info = JvmInfo.probe(Paths.get("java"), Seq("-Xmx1g"), cannedRunner(CommandResult(0, settingsOutput)))

      // Then
      info shouldEqual JvmInfo(vendor = "Eclipse Adoptium", version = "25.0.1+8-LTS", options = Seq("-Xmx1g"))
    }

    "probe the running JDK" in {
      // Given
      val java = Paths.get(System.getProperty("java.home"), "bin", "java")

      // When
      val info = JvmInfo.probe(java, Seq.empty, ProcessCommandRunner)

      // Then
      info.vendor shouldEqual System.getProperty("java.vendor")
      info.version shouldEqual System.getProperty("java.runtime.version")
    }

    "fail when the JDK can't run" in {
      // When / Then
      a[PerfSetupException] should be thrownBy
        JvmInfo.probe(Paths.get("java"), Seq.empty, cannedRunner(CommandResult(127, "not found")))
    }
  }
}

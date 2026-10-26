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

package org.calinburloiu.music.microtonalist.perf.results

import org.calinburloiu.music.microtonalist.perf.app.JarBuildInfo
import org.calinburloiu.music.microtonalist.perf.{PerfSetupException, Sha256}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class AppBuildTest extends AnyWordSpec with Matchers {

  private val Commit = "a8ba00dfd5f5d66743e93e6266f902f9feca7bce"

  private val jar: Path = {
    val file = Files.createTempFile("app", ".jar")
    Files.writeString(file, "abc")
    file
  }

  private val resolveKnown: String => Option[String] = revision => Option.when(Commit.startsWith(revision))(Commit)

  "AppBuild" should {
    "take the commit and dirty flag from the JAR" in {
      // Given
      val info = JarBuildInfo("1.6.0", "3.9.0", Some(Commit), Some(true))

      // When
      val build = AppBuild.describe(jar, info, commitArgument = Some("ffffffff"), resolveKnown)

      // Then
      build shouldEqual AppBuild("1.6.0", Commit, Some(true), "3.9.0", Sha256.ofString("abc"))
    }

    "take the commit of an older JAR from the argument, expanded" in {
      // Given
      val info = JarBuildInfo("1.6.0", "3.9.0", None, None)

      // When
      val build = AppBuild.describe(jar, info, commitArgument = Some("a8ba00df"), resolveKnown)

      // Then
      build.commit shouldEqual Commit
      build.dirty shouldBe empty
    }

    "keep an argument that git can't expand" in {
      // Given
      val info = JarBuildInfo("1.6.0", "3.9.0", None, None)

      // When / Then
      AppBuild.describe(jar, info, commitArgument = Some("0badc0de"), resolveKnown).commit shouldEqual "0badc0de"
    }

    "fail without a commit" in {
      // Given
      val info = JarBuildInfo("1.6.0", "3.9.0", None, None)

      // When
      val exception = the[PerfSetupException] thrownBy AppBuild.describe(jar, info, None, resolveKnown)

      // Then
      exception.getMessage should include("-Dcommit=")
    }
  }
}

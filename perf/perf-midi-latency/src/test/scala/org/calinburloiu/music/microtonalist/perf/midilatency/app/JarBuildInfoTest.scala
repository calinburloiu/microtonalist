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

package org.calinburloiu.music.microtonalist.perf.midilatency.app

import org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.util.Using

class JarBuildInfoTest extends AnyWordSpec with Matchers {

  private val Commit = "a8ba00dfd5f5d66743e93e6266f902f9feca7bce"

  /**
   * Builds a JAR holding a Java stand-in for the app's `BuildInfo` object, with the given methods, compiled with the
   * running JDK's compiler.
   */
  private def buildInfoJar(methods: String*): Path = {
    val dir = Files.createTempDirectory("build-info")
    val source = dir.resolve("BuildInfo$.java")
    Files.writeString(source,
      s"""package org.calinburloiu.music.microtonalist;
         |public final class BuildInfo$$ {
         |  public static final BuildInfo$$ MODULE$$ = new BuildInfo$$();
         |  private BuildInfo$$() {}
         |  ${methods.mkString("\n  ")}
         |}
         |""".stripMargin)
    val classes = Files.createDirectories(dir.resolve("classes"))
    ToolProvider.getSystemJavaCompiler.run(null, null, null, "-d", classes.toString, source.toString) shouldEqual 0

    val entryName = "org/calinburloiu/music/microtonalist/BuildInfo$.class"
    val jar = dir.resolve("app.jar")
    Using.resource(JarOutputStream(Files.newOutputStream(jar))) { out =>
      out.putNextEntry(JarEntry(entryName))
      out.write(Files.readAllBytes(classes.resolve(entryName)))
      out.closeEntry()
    }
    jar
  }

  private val version = """public String version() { return "1.6.0-SNAPSHOT"; }"""
  private val scalaVersion = """public String scalaVersion() { return "3.9.0"; }"""

  "JarBuildInfo" should {
    "read the version, Scala version, commit and dirty flag" in {
      // Given
      val jar = buildInfoJar(version, scalaVersion, s"""public String gitCommit() { return "$Commit"; }""",
        "public boolean gitDirty() { return true; }")

      // When / Then
      JarBuildInfo.read(jar) shouldEqual JarBuildInfo("1.6.0-SNAPSHOT", "3.9.0", Some(Commit), Some(true))
    }

    "read an older JAR without a commit" in {
      // Given
      val jar = buildInfoJar(version, scalaVersion)

      // When / Then
      JarBuildInfo.read(jar) shouldEqual JarBuildInfo("1.6.0-SNAPSHOT", "3.9.0", None, None)
    }

    "treat an empty commit as unknown" in {
      // Given
      val jar = buildInfoJar(version, scalaVersion, """public String gitCommit() { return ""; }""",
        "public boolean gitDirty() { return false; }")

      // When / Then
      JarBuildInfo.read(jar) shouldEqual JarBuildInfo("1.6.0-SNAPSHOT", "3.9.0", None, None)
    }

    "fail on a JAR without BuildInfo" in {
      // Given
      val jar = Files.createTempFile("empty", ".jar")
      Using.resource(JarOutputStream(Files.newOutputStream(jar)))(_ => ())

      // When / Then
      a[PerfSetupException] should be thrownBy JarBuildInfo.read(jar)
    }
  }
}

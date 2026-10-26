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

import java.net.URLClassLoader
import java.nio.file.Path
import scala.util.Using

/**
 * What the app's `BuildInfo` in a JAR says about the build.
 *
 * @param commit The commit the JAR was built from; `None` for a JAR built before `BuildInfo` recorded it, or outside
 *   git.
 * @param dirty Whether tracked files differed from the commit; `None` when the commit is unknown.
 */
case class JarBuildInfo(version: String, scalaVersion: String, commit: Option[String], dirty: Option[Boolean])

object JarBuildInfo {

  /**
   * Reads the `BuildInfo` of the app's JAR, in a class loader of its own, without running the app.
   *
   * @throws org.calinburloiu.music.microtonalist.perf.midilatency.PerfSetupException if the JAR has no `BuildInfo`.
   */
  def read(jar: Path): JarBuildInfo = {
    // The platform class loader as parent keeps the suite's classes, and its Scala library, out of the JAR's way
    Using.resource(URLClassLoader(Array(jar.toUri.toURL), ClassLoader.getPlatformClassLoader)) { loader =>
      val buildInfoClass = try {
        Class.forName(BuildInfoClassName, true, loader)
      } catch {
        case exception: ClassNotFoundException =>
          throw PerfSetupException(s"$jar has no $BuildInfoClassName: is it the app's JAR?", exception)
      }
      val buildInfo = buildInfoClass.getField("MODULE$").get(null)

      def value(name: String): Option[AnyRef] =
        buildInfoClass.getMethods.find(method => method.getName == name && method.getParameterCount == 0)
          .map(_.invoke(buildInfo))

      val commit = value("gitCommit").map(_.toString).filter(_.nonEmpty)
      JarBuildInfo(
        version = value("version").map(_.toString).getOrElse("unknown"),
        scalaVersion = value("scalaVersion").map(_.toString).getOrElse("unknown"),
        commit = commit,
        dirty = commit.flatMap(_ => value("gitDirty").map(_.asInstanceOf[java.lang.Boolean].booleanValue))
      )
    }
  }

  private val BuildInfoClassName = "org.calinburloiu.music.microtonalist.BuildInfo$"
}

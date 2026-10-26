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

import sbt.*

import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/**
 * The git identity of the working tree a build runs on, which the app's `BuildInfo` records so that a JAR tells which
 * commit it was built from.
 */
object BuildIdentity {

  /** The commit `HEAD` points to, or `""` when git can't tell, e.g. outside a git repository. */
  def gitCommit(repoRoot: File): String = git(repoRoot, "rev-parse", "HEAD").getOrElse("")

  /** Whether the tracked files differ from `HEAD`; `false` when git can't tell. */
  def gitDirty(repoRoot: File): Boolean =
    git(repoRoot, "status", "--porcelain", "--untracked-files=no").exists(_.nonEmpty)

  private def git(repoRoot: File, args: String*): Option[String] = Try {
    Process("git" +: args, repoRoot).!!(ProcessLogger(_ => ())).trim
  }.toOption
}

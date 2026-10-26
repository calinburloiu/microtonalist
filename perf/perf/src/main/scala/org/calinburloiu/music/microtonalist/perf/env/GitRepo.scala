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

import java.nio.file.Path

/** A git repository, through the `git` command; each query is `None` when git can't answer it. */
final class GitRepo(root: Path, runner: CommandRunner) {

  /** The commit `HEAD` points to. */
  def head: Option[String] = git("rev-parse", "HEAD").map(_.trim)

  /** Whether the tracked files differ from `HEAD`; untracked files don't count. */
  def isDirty: Option[Boolean] = git("status", "--porcelain", "--untracked-files=no").map(_.trim.nonEmpty)

  /** The full hash of the commit `revision` names, e.g. a short hash. */
  def resolve(revision: String): Option[String] =
    git("rev-parse", "--verify", "--quiet", s"$revision^{commit}").map(_.trim)

  private def git(args: String*): Option[String] = {
    val result = runner.run("git" +: args, Some(root))
    Option.when(result.exitCode == 0)(result.output)
  }
}

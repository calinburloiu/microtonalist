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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

class GitRepoTest extends AnyWordSpec with Matchers {

  private abstract class Fixture {
    val root: Path = Files.createTempDirectory("git-repo")
    val file: Path = root.resolve("file.txt")
    Files.writeString(file, "one")
    git("init", "-q")
    git("add", "file.txt")
    git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "First")

    val repo: GitRepo = GitRepo(root, ProcessCommandRunner)

    def git(args: String*): Unit = {
      val result = ProcessCommandRunner.run("git" +: args, Some(root))
      withClue(result.output)(result.exitCode shouldEqual 0)
    }
  }

  "GitRepo" should {
    "tell the commit of HEAD" in new Fixture {
      // When / Then
      repo.head.get should fullyMatch regex "[0-9a-f]{40}"
    }

    "tell whether tracked files changed" in new Fixture {
      // Given
      private val clean = repo.isDirty()
      Files.writeString(root.resolve("untracked.txt"), "ignored")
      private val withUntracked = repo.isDirty()

      // When
      Files.writeString(file, "two")

      // Then
      clean shouldEqual Some(false)
      withUntracked shouldEqual Some(false)
      repo.isDirty() shouldEqual Some(true)
    }

    "ignore changes under the excluded paths" in new Fixture {
      // Given
      private val index = root.resolve("results/index.json")
      Files.createDirectories(index.getParent)
      Files.writeString(index, "{}")
      git("add", "results/index.json")
      git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Results")

      // When
      Files.writeString(index, """{"runs": []}""")

      // Then
      repo.isDirty(excluding = Seq("results")) shouldEqual Some(false)
      repo.isDirty() shouldEqual Some(true)
    }

    "resolve a short commit" in new Fixture {
      // When / Then
      repo.resolve(repo.head.get.take(8)) shouldEqual repo.head
      repo.resolve("0000000") shouldBe empty
    }

    "know nothing outside a repository" in {
      // Given
      val repo = GitRepo(Files.createTempDirectory("not-a-repo"), ProcessCommandRunner)

      // When / Then
      repo.head shouldBe empty
      repo.isDirty() shouldBe empty
    }
  }
}

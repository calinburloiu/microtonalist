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

import java.nio.file.Files

class CommandRunnerTest extends AnyWordSpec with Matchers {

  "ProcessCommandRunner" should {
    "return the output and the exit code of a command" in {
      // When / Then
      ProcessCommandRunner.run(Seq("echo", "hello")) shouldEqual CommandResult(0, "hello\n")
    }

    "merge the error output into the output" in {
      // When / Then
      ProcessCommandRunner.run(Seq("sh", "-c", "echo oops >&2; exit 3")) shouldEqual CommandResult(3, "oops\n")
    }

    "run the command in the given directory" in {
      // Given
      val dir = Files.createTempDirectory("command-runner")

      // When
      val result = ProcessCommandRunner.run(Seq("pwd"), Some(dir))

      // Then
      result.output.trim shouldEqual dir.toRealPath().toString
    }
  }
}

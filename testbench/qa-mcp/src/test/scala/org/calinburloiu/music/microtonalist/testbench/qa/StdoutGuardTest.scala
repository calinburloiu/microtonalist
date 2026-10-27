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

package org.calinburloiu.music.microtonalist.testbench.qa

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

class StdoutGuardTest extends AnyWordSpec with Matchers {

  "StdoutGuard.install" should {
    "send System.out to System.err and return the real stdout" in {
      // Given
      val (originalOut, originalErr) = (System.out, System.err)
      val outBytes = ByteArrayOutputStream()
      val errBytes = ByteArrayOutputStream()
      val out = PrintStream(outBytes, true, UTF_8)
      val err = PrintStream(errBytes, true, UTF_8)
      System.setOut(out)
      System.setErr(err)

      val realStdout =
        try {
          // When
          val stdout = StdoutGuard.install()
          System.out.print("<stray>")
          stdout.print("<protocol>")
          stdout
        } finally {
          System.setOut(originalOut)
          System.setErr(originalErr)
        }

      // Then
      realStdout should be theSameInstanceAs out
      // Other suites may print meanwhile, hence `include`.
      errBytes.toString(UTF_8) should include("<stray>")
      outBytes.toString(UTF_8) should include("<protocol>")
      outBytes.toString(UTF_8) should not include "<stray>"
    }
  }
}

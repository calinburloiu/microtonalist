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

import java.io.PrintStream

/** Keeps stray output off the standard output, which carries the MCP protocol. */
object StdoutGuard {
  /**
   * Points `System.out` at `System.err`, so that whatever prints to it goes to the standard error, and returns the real
   * standard output, for the protocol only.
   *
   * Call it first thing in `main`, before Scala's `Console` captures `System.out`.
   */
  def install(): PrintStream = {
    val stdout = System.out
    System.setOut(System.err)
    stdout
  }
}

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

package org.calinburloiu.music.microtonalist.testbench.driver

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch

/** Captures what the driver reports on `System.err`. */
object StdErr {
  /** Captures `System.err` while `body` runs, and counts down `printed` on each line written to it. */
  def capturing(printed: CountDownLatch)(body: ByteArrayOutputStream => Unit): Unit = {
    val captured = ByteArrayOutputStream()
    val capturing = new PrintStream(captured, true, UTF_8) {
      override def println(line: String): Unit = {
        super.println(line)
        printed.countDown()
      }
    }
    val stdErr = System.err
    System.setErr(capturing)
    try {
      body(captured)
    } finally {
      System.setErr(stdErr)
    }
  }
}

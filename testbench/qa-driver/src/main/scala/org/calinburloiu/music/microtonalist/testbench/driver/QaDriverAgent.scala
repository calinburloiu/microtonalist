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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.GuiPlatform

import java.nio.file.Path
import scala.util.control.NonFatal

/** The entry point of the QA driver, a Java agent that the QA server loads into the app's JVM with `-javaagent`. */
object QaDriverAgent {
  /**
   * Starts the driver's [[DriverLink]] on the daemon thread `qa-driver`, and returns at once, so that the app starts.
   *
   * @param args the path of the Unix domain socket that the QA server listens on
   */
  def premain(args: String): Unit = start {
    val fx = FxExecutor()
    DriverLink(Path.of(args), RequestHandler(fx, newInput(GuiPlatform.current, fx)), fx)
  }

  /**
   * Starts the link that `newLink` creates on the daemon thread `qa-driver`.
   *
   * If `newLink` fails, e.g. on an app without JavaFX, reports it on `System.err`, which goes to the app's log, and
   * rethrows it, which aborts the JVM, so that the QA server says at once that the app exited.
   */
  private[driver] def start(newLink: => Runnable): Unit = {
    val link =
      try {
        newLink
      } catch {
        case e @ (NonFatal(_) | _: LinkageError) =>
          System.err.println(s"qa-driver: can't start: $e. The driver drives only a JavaFX app, whose JAR holds " +
            "JavaFX, play-json and the Scala library.")
          throw e
      }
    val thread = Thread(link, "qa-driver")
    thread.setDaemon(true)
    thread.start()
  }

  /** The input that drives the app on the given platform. */
  private[driver] def newInput(platform: GuiPlatform, fx: FxExecutor): Input = platform match {
    case GuiPlatform.Headless => RobotInput(fx)
    case GuiPlatform.Desktop => EventInput(fx)
  }
}

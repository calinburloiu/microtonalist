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

import java.nio.file.Path

/**
 * Runs the QA MCP server over stdio, on a new run in [[QaRun.DefaultBaseDir]], until its standard input ends.
 *
 * Usage: `--app-jar <path> --driver-jar <path>`, the app's fat JAR and the QA driver's JAR.
 */
object QaMcpMain {
  private val Usage: String = "Usage: microtonalist-qa-mcp --app-jar <path> --driver-jar <path>"

  /** Runs the server, then exits with 0 once its input ends, or with 2 on arguments other than the usage's. */
  def main(args: Array[String]): Unit = {
    // First, so that nothing but the protocol reaches the standard output.
    val stdout = StdoutGuard.install()
    val jars = parseArgs(args.toList).getOrElse {
      System.err.println(Usage)
      sys.exit(2)
    }

    val run = QaRun.create(QaRun.DefaultBaseDir)
    // Before the sweep, so that a run never lacks its server's record while other servers sweep.
    ProcessRecords.writeServer(run)
    ProcessRecords.sweep(QaRun.DefaultBaseDir)
    val session = QaSession(run, JdkProcessLauncher(), jars, AppLauncher.CurrentJava)
    Runtime.getRuntime.addShutdownHook(Thread(() => session.shutdown()))

    val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    QaMcpServer.start(session, System.in, stdout, version).join()
    session.shutdown()
    sys.exit(0)
  }

  private def parseArgs(args: List[String]): Option[QaJars] = args match {
    case "--app-jar" :: app :: "--driver-jar" :: driver :: Nil => Some(jarsOf(app, driver))
    case "--driver-jar" :: driver :: "--app-jar" :: app :: Nil => Some(jarsOf(app, driver))
    case _ => None
  }

  private def jarsOf(app: String, driver: String): QaJars = {
    QaJars(Path.of(app).toAbsolutePath, Path.of(driver).toAbsolutePath)
  }
}

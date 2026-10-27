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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, GuiPlatform}
import play.api.libs.json.JsObject

import java.io.IOException
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * The JARs that the QA server starts the app from.
 *
 * @param app the app's fat JAR
 * @param driver the QA driver's JAR, loaded as a Java agent
 */
final case class QaJars(app: Path, driver: Path)

/**
 * How a stopped app ended.
 *
 * @param exitCode the app's exit code
 * @param killed whether it had to be killed with `SIGKILL`, since it ignored `SIGTERM`
 */
final case class StopResult(exitCode: Int, killed: Boolean)

/**
 * Starts the app of a QA run, with the QA driver as its Java agent, and connects to the driver.
 *
 * @param java the `java` program that runs the app, e.g. [[AppLauncher.CurrentJava]]
 * @param startupTimeout how long the app may take to show a window
 */
final class AppLauncher(run: QaRun, launcher: ProcessLauncher, jars: QaJars, java: Path,
                        startupTimeout: FiniteDuration = 60.seconds) {

  import AppLauncher.*

  private val appLog: Path = run.file("app.log")

  /**
   * Starts the app on a composition, with the run's `qa.conf`, which points its library at the run directory, and
   * answers once the app shows a window and its driver has connected.
   *
   * The app's output is appended to the run's `app.log`. A driver that names another process is refused.
   *
   * @throws QaException if the app can't be started, exits first, or shows no window in time, after stopping it
   */
  def start(composition: Path, platform: GuiPlatform): RunningApp = {
    val config = run.file("qa.conf")
    Files.writeString(config, s"core {\n  libraryBaseUrl = \"${run.dir.toUri}\"\n}\n", UTF_8)

    Files.deleteIfExists(run.socket)
    Using.resource(ServerSocketChannel.open(StandardProtocolFamily.UNIX)) { server =>
      server.bind(UnixDomainSocketAddress.of(run.socket))
      val command = Seq(java.toString) ++ Option.when(platform == GuiPlatform.Headless)(HeadlessProperty) ++
        Seq(s"-javaagent:${jars.driver}=${run.socket}", "-jar", jars.app.toString, composition.toString,
          config.toString)
      val process =
        try {
          launcher.start(ProcessSpec(command, Some(appLog)))
        } catch {
          case e: IOException => throw QaException(s"can't start the app: ${e.getMessage}")
        }

      RunningApp(process, platform, awaitDriver(server, process), appLog)
    }
  }

  private def awaitDriver(server: ServerSocketChannel, process: LaunchedProcess): DriverClient = {
    val connected = CompletableFuture[DriverClient]()
    Thread.ofVirtual().start(() => acceptDrivers(server, process.pid, connected))

    try {
      CompletableFuture.anyOf(connected, process.onExit).get(startupTimeout.toMillis, TimeUnit.MILLISECONDS)
    } catch {
      case _: TimeoutException => ()
    }

    // Cancelling makes a driver that says hello from now on find the future done, and close its connection.
    if (connected.cancel(false)) {
      if (process.onExit.isDone) {
        throw QaException(s"the app exited with code ${process.onExit.join()} before showing a window; see $appLog")
      }
      stopProcess(process, StopTimeout)
      throw QaException(s"no window after ${DriverClient.describe(startupTimeout)}; see $appLog")
    }
    connected.join()
  }

  /** Accepts connections until the server closes, greeting each on a thread of its own. */
  private def acceptDrivers(server: ServerSocketChannel, pid: Long, connected: CompletableFuture[DriverClient])
      : Unit = {
    try {
      while (true) {
        val client = DriverClient(server.accept())
        Thread.ofVirtual().start(() => greet(client, pid, connected))
      }
    } catch {
      case _: IOException => () // The server closed once the app started or failed to.
    }
  }

  private def greet(client: DriverClient, pid: Long, connected: CompletableFuture[DriverClient]): Unit = {
    val isTheApp =
      try {
        client.hello().pid == pid
      } catch {
        case NonFatal(_) => false
      }
    if (!isTheApp || !connected.complete(client)) {
      client.close()
    }
  }
}

object AppLauncher {
  /** The `java` program of the JVM that runs the QA server. */
  val CurrentJava: Path = Path.of(System.getProperty("java.home"), "bin", "java")

  private val HeadlessProperty: String = "-Dglass.platform=Headless"

  /** How long [[AppLauncher]] waits for an app that showed no window to exit on `SIGTERM`, before killing it. */
  private val StopTimeout: FiniteDuration = 10.seconds

  /** Stops a process with `SIGTERM`, then with `SIGKILL` if it hasn't exited within `timeout`. */
  private[qa] def stopProcess(process: LaunchedProcess, timeout: FiniteDuration): StopResult = {
    process.terminate()
    try {
      StopResult(process.onExit.get(timeout.toMillis, TimeUnit.MILLISECONDS), killed = false)
    } catch {
      case _: TimeoutException =>
        process.kill()
        StopResult(process.onExit.join(), killed = true)
    }
  }
}

/**
 * The app of a QA run, which [[AppLauncher]] started, and the connection to its driver.
 *
 * @param process the app's process
 * @param platform the JavaFX platform that the app runs on
 * @param appLog the file that holds the app's output
 */
final class RunningApp(val process: LaunchedProcess, val platform: GuiPlatform, client: DriverClient, appLog: Path) {

  /**
   * Passes a request to the driver and returns its result.
   *
   * @throws QaException with the driver's message if the request failed, or pointing at `app.log` if the app exited
   */
  def request(request: DriverRequest): JsObject = {
    try {
      client.request(request)
    } catch {
      case e: IOException =>
        // The connection closes as the app exits, a little before the app's exit is known.
        val exitCode =
          try {
            Some(process.onExit.get(RunningApp.ExitWait.toMillis, TimeUnit.MILLISECONDS))
          } catch {
            case _: TimeoutException => None
          }
        throw exitCode.fold(QaException(s"the connection to the driver failed: $e; see $appLog")) { code =>
          QaException(s"the app exited with code $code; see $appLog")
        }
    }
  }

  /** Stops the app with `SIGTERM` and waits for it to exit; after `timeout`, kills it with `SIGKILL`. */
  def stop(timeout: FiniteDuration = 10.seconds): StopResult = {
    client.close()
    AppLauncher.stopProcess(process, timeout)
  }

  /** Asks the app to exit, with `SIGTERM`, without waiting for it. */
  def terminate(): Unit = {
    client.close()
    process.terminate()
  }
}

private object RunningApp {
  /** How long a failed request waits for the app's exit, to tell an exited app from a broken connection. */
  private val ExitWait: FiniteDuration = 5.seconds
}

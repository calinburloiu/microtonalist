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

import javafx.stage.Window
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.*

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter, StringWriter, Writer}
import java.net.UnixDomainSocketAddress
import java.nio.channels.{Channels, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, ExecutionException, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * The driver's link to the QA server: once the app shows a window, connects to the server's Unix domain socket, says
 * [[Hello]], then answers the server's requests one at a time, until the socket closes.
 *
 * It reports its own failures on `System.err`, since the driver uses no logging library.
 *
 * @param socket the path of the socket that the server listens on
 * @param handler carries out the requests
 * @param fx runs code on the JavaFX Application Thread
 * @param isWindowShown whether the app shows a window, through `fx`
 */
final class DriverLink(socket: Path, handler: RequestHandler, fx: FxExecutor,
                       isWindowShown: FxExecutor => Boolean = DriverLink.isAnyWindowShown) extends Runnable {

  import DriverLink.*

  override def run(): Unit = {
    try {
      awaitWindow()
      Using.resource(SocketChannel.open(UnixDomainSocketAddress.of(socket))) { channel =>
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), UTF_8))
        val writer = OutputStreamWriter(Channels.newOutputStream(channel), UTF_8)
        writeLine(writer, Protocol.encode(Hello(ProcessHandle.current.pid, GuiPlatform.current)))
        Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
          writeLine(writer, Protocol.encode(answer(line)))
        }
      }
    } catch {
      case NonFatal(e) =>
        val stackTrace = StringWriter()
        e.printStackTrace(PrintWriter(stackTrace))
        System.err.println(s"qa-driver: the link to $socket failed: $stackTrace")
    }
  }

  /**
   * Polls until the JavaFX toolkit runs and the app shows a window.
   *
   * @throws DriverException if the check fails otherwise than while the toolkit starts, e.g. on an app without JavaFX
   */
  private def awaitWindow(): Unit = {
    val shown = CompletableFuture[Unit]()
    val scheduler = Executors.newSingleThreadScheduledExecutor { runnable =>
      val thread = Thread(runnable, "qa-driver-poll")
      thread.setDaemon(true)
      thread
    }

    try {
      scheduler.scheduleWithFixedDelay(() => poll(shown), 0, PollPeriod.toMillis, TimeUnit.MILLISECONDS)
      shown.get()
    } catch {
      case e: ExecutionException =>
        throw DriverException(s"can't tell whether the app shows a window: ${e.getCause}. The driver drives only a " +
          "JavaFX app.")
    } finally {
      scheduler.shutdownNow()
    }
  }

  /** Completes `shown` once a window shows, or with the failure of a check, which would end the polling silently. */
  private def poll(shown: CompletableFuture[Unit]): Unit = {
    try {
      if (isWindowShownYet) {
        shown.complete(())
      }
    } catch {
      case e @ (NonFatal(_) | _: LinkageError) => shown.completeExceptionally(e)
    }
  }

  private def isWindowShownYet: Boolean = {
    try {
      isWindowShown(fx)
    } catch {
      // The toolkit isn't initialized yet, or its thread is busy starting the app.
      case _: IllegalStateException | _: DriverException => false
    }
  }

  private def answer(line: String): DriverResponse = Protocol.decodeEnvelope(line) match {
    case Left(failed) => failed
    case Right(Envelope(id, request)) =>
      try {
        DriverResponse.Ok(id, handler.handle(request))
      } catch {
        case e: DriverException => DriverResponse.Failed(id, e.getMessage)
      }
  }

  private def writeLine(writer: Writer, line: String): Unit = {
    writer.write(line + "\n")
    writer.flush()
  }
}

private object DriverLink {
  /** How often the link checks whether the app shows a window. */
  val PollPeriod: FiniteDuration = 200.millis

  /** Whether the app shows a window; throws an `IllegalStateException` while the JavaFX toolkit isn't running. */
  def isAnyWindowShown(fx: FxExecutor): Boolean = fx(!Window.getWindows.isEmpty)
}

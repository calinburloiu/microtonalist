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

package org.calinburloiu.music.microtonalist.perf.run

import org.calinburloiu.music.microtonalist.perf.midi.MidiPath
import org.calinburloiu.music.scmidi.MidiReceiver
import org.calinburloiu.music.scmidi.message.MidiMsg

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CopyOnWriteArrayList, ExecutorService, Executors}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * An in-process stand-in for the app, for testing the suite without MIDI devices: a [[MidiPath]] whose sender hands
 * each message to `respond` and delivers the replies to the attached receivers from a background thread, as a MIDI
 * device would, but only while a launch of it runs.
 *
 * @param respond The app's replies to a message; it echoes it by default.
 */
class FakeApp(respond: MidiMsg => Seq[MidiMsg] = FakeApp.echo) extends MidiPath with AutoCloseable {

  /** How many of the next messages a launch drops, as an app does before it opens its input. */
  val ignoredMessages: AtomicInteger = AtomicInteger(0)

  /** When defined, a launch reports at once that the app exited with this code. */
  @volatile var exitCode: Option[Int] = None

  /** What a launch sends as it starts, such as a tuner's reset messages. */
  @volatile var startupOutput: Seq[MidiMsg] = Seq.empty

  /** How many messages the running app received, including the ignored ones. */
  val receivedCount: AtomicInteger = AtomicInteger(0)
  /** How many times the app was launched. */
  val launchCount: AtomicInteger = AtomicInteger(0)
  /** How many times the app was stopped. */
  val stopCount: AtomicInteger = AtomicInteger(0)

  /** The log file every launch reports. */
  val logFile: Path = Paths.get("fake-app.log")

  @volatile private var running: Boolean = false
  private val receivers = CopyOnWriteArrayList[MidiReceiver]()
  private val deliverer: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
    val thread = Thread(runnable, "fake-app")
    thread.setDaemon(true)
    thread
  }

  override val sender: MidiReceiver = (message, _) => {
    if (running) {
      receivedCount.incrementAndGet()
      if (ignoredMessages.getAndUpdate(count => Math.max(count - 1, 0)) == 0) {
        respond(message).foreach(deliver)
      }
    }
  }

  override def attach(receiver: MidiReceiver): Unit = receivers.add(receiver)

  override def detach(receiver: MidiReceiver): Unit = receivers.remove(receiver)

  /** Launches the app. */
  def launch(): RunningApp = {
    launchCount.incrementAndGet()
    val launchExitCode = exitCode
    running = launchExitCode.isEmpty
    if (running) {
      startupOutput.foreach(deliver)
    }

    new RunningApp {
      override def isAlive: Boolean = running

      override def exitCode: Option[Int] = launchExitCode

      override def logFile: Option[Path] = Some(FakeApp.this.logFile)

      override def stop(timeout: FiniteDuration): Unit = {
        running = false
        stopCount.incrementAndGet()
      }
    }
  }

  override def close(): Unit = deliverer.shutdownNow()

  private def deliver(message: MidiMsg): Unit = deliverer.execute { () =>
    receivers.asScala.foreach(_.send(message))
  }
}

object FakeApp {
  /** Replies to each message with the message itself. */
  val echo: MidiMsg => Seq[MidiMsg] = message => Seq(message)
}

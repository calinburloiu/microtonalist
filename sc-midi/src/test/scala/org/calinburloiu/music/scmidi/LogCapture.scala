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

package org.calinburloiu.music.scmidi

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Captures log events in tests, which otherwise log nothing (see `project/test-resources/logback-test.xml`).
 */
object LogCapture {

  /**
   * Runs `body` with the logger named `loggerName` and its descendants enabled down to DEBUG, and returns the result
   * of `body` together with the events those loggers emitted meanwhile on the calling thread.
   *
   * Suites may run in parallel in the same JVM, so the events of other threads are ignored, and each suite should
   * capture a logger of its own, since the level of the logger is restored once `body` completes.
   *
   * @param loggerName Name of the logger to capture, typically the fully qualified name of the class under test;
   *                   logback treats the loggers of its nested classes as descendants, so they are captured too.
   */
  def capturing[R](loggerName: String)(body: => R): (R, Seq[ILoggingEvent]) = {
    awaitSlf4jInitialization()
    val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
    val threadName = Thread.currentThread.getName
    val events = ConcurrentLinkedQueue[ILoggingEvent]()
    val appender = new AppenderBase[ILoggingEvent] {
      override def append(event: ILoggingEvent): Unit = {
        if (event.getThreadName == threadName) {
          events.add(event)
        }
      }
    }
    appender.setContext(logger.getLoggerContext)
    appender.start()

    val previousLevel = logger.getLevel
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    val result = try {
      body
    } finally {
      logger.setLevel(previousLevel)
      logger.detachAppender(appender)
      appender.stop()
    }

    (result, events.asScala.toSeq)
  }

  /**
   * Waits until SLF4J is initialized. While one thread initializes it, SLF4J hands the other threads substitute
   * loggers, which are not logback loggers and which replay their events later, on the initializing thread. SLF4J 2
   * initializes under the monitor of `LoggerFactory`, so acquiring that monitor waits for the initialization to
   * complete, or runs it on this thread if it has not started yet.
   */
  private def awaitSlf4jInitialization(): Unit = classOf[LoggerFactory].synchronized {
    LoggerFactory.getILoggerFactory
  }

  extension (events: Seq[ILoggingEvent]) {
    /** The formatted messages of the events logged at `level`, in order. */
    def messagesAt(level: Level): Seq[String] = events.filter(_.getLevel == level).map(_.getFormattedMessage)

    /** The formatted message of each event logged at `level`, in order, with the message of its exception if any. */
    def failuresAt(level: Level): Seq[(String, Option[String])] = events.filter(_.getLevel == level).map { event =>
      (event.getFormattedMessage, Option(event.getThrowableProxy).map(_.getMessage))
    }
  }
}

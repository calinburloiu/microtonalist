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

package org.calinburloiu.music.microtonalist

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent, ThrowableProxy}
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.StatusUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger

import scala.jdk.CollectionConverters.*

/** Tests the production logging configuration, `logback.xml` from the main resources. */
class LogbackConfigTest extends AnyWordSpec with Matchers {

  private val LoggerName = "org.calinburloiu.music.microtonalist.tuner.TrackManager"
  private val Reset = "\u001b[0;39m"

  /** Configures a new logger context with the production `logback.xml`. */
  private def configure(): LoggerContext = {
    val context = LoggerContext()
    val configurator = JoranConfigurator()
    configurator.setContext(context)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    context
  }

  /** Lays out a console log of the given level, and optionally exception, with the production configuration. */
  private def layOutConsoleLog(level: Level, throwable: Option[Throwable] = None): String = {
    val appender = configure().getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDOUT")
      .asInstanceOf[ConsoleAppender[ILoggingEvent]]
    val layout = appender.getEncoder.asInstanceOf[PatternLayoutEncoder].getLayout
    val event = LoggingEvent()
    event.setLoggerName(LoggerName)
    event.setLevel(level)
    event.setMessage("Something happened")
    throwable.foreach(t => event.setThrowableProxy(ThrowableProxy(t)))
    layout.doLayout(event)
  }

  "the production logback.xml" should {
    "configure logging without warnings or errors" in {
      // When
      val context = configure()

      // Then
      withClue(context.getStatusManager.getCopyOfStatusList.asScala.mkString("\n")) {
        StatusUtil(context).isWarningOrErrorFree(0) shouldBe true
      }
    }

    "color the whole console log by its level" in {
      // When
      val output = layOutConsoleLog(Level.ERROR)

      // Then
      output should startWith("\u001b[38;5;196m")
      output should endWith(s" - Something happened${System.lineSeparator}$Reset")
    }

    "color the stack trace of a console log like the rest of it" in {
      // When
      val output = layOutConsoleLog(Level.ERROR, Some(RuntimeException("Boom")))

      // Then
      output should startWith("\u001b[38;5;196m")
      output.indexOf(Reset) shouldEqual output.length - Reset.length
      "RuntimeException: Boom".r.findAllIn(output).size shouldEqual 1
    }

    "print the full logger name in console logs" in {
      // When
      val output = layOutConsoleLog(Level.INFO)

      // Then
      output should include(s" INFO  $LoggerName - ")
    }
  }
}

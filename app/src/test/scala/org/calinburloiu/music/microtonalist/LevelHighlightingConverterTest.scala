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

import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext, PatternLayout}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LevelHighlightingConverterTest extends AnyWordSpec with Matchers {

  private val Reset = "\u001b[0;39m"

  /** Lays out the level of an event of the given level with the converter under test wrapped around it. */
  private def layOutLevel(level: Level): String = {
    val layout = PatternLayout()
    layout.setContext(LoggerContext())
    layout.getInstanceConverterMap.put("levelHighlight", () => LevelHighlightingConverter())
    layout.setPattern("%levelHighlight(%level)")
    layout.start()

    val event = LoggingEvent()
    event.setLevel(level)
    layout.doLayout(event)
  }

  "a LevelHighlightingConverter" should {
    "color ERROR red" in {
      // When
      val output = layOutLevel(Level.ERROR)

      // Then
      output shouldEqual s"\u001b[38;5;196mERROR$Reset"
    }

    "color WARN orange" in {
      // When
      val output = layOutLevel(Level.WARN)

      // Then
      output shouldEqual s"\u001b[38;5;166mWARN$Reset"
    }

    "keep INFO in the default color" in {
      // When
      val output = layOutLevel(Level.INFO)

      // Then
      output shouldEqual s"\u001b[39mINFO$Reset"
    }

    "color DEBUG and TRACE gray" in {
      // When
      val debugOutput = layOutLevel(Level.DEBUG)
      val traceOutput = layOutLevel(Level.TRACE)

      // Then
      debugOutput shouldEqual s"\u001b[38;5;243mDEBUG$Reset"
      traceOutput shouldEqual s"\u001b[38;5;243mTRACE$Reset"
    }
  }
}

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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.color.{ANSIConstants, ForegroundCompositeConverterBase}

/**
 * Logback converter that colors its content by the event's level: red for ERROR, orange for WARN, the terminal's
 * default color for INFO and gray for DEBUG and TRACE.
 *
 * The colors read well on both dark and light terminals: they come from the 256-color palette, which terminal themes
 * don't change, and their medium luminance contrasts with both a dark and a light background.
 */
class LevelHighlightingConverter extends ForegroundCompositeConverterBase[ILoggingEvent] {
  override protected def getForegroundColorCode(event: ILoggingEvent): String = event.getLevel.toInt match {
    case Level.ERROR_INT => "38;5;196" // #FF0000
    case Level.WARN_INT => "38;5;166" // #D75F00
    case Level.INFO_INT => ANSIConstants.DEFAULT_FG
    case _ => "38;5;243" // #767676
  }
}

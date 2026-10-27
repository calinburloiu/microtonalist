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

package org.calinburloiu.music.microtonalist.testbench.driver.protocol

/** The JavaFX platform that the app under test runs on. */
enum GuiPlatform {
  /** JavaFX's headless platform, which shows no window. */
  case Headless

  /** The operating system's own windowing platform. */
  case Desktop
}

/** Finds the platform that a JVM runs on. */
object GuiPlatform {

  /**
   * The platform selected by the given value of the `glass.platform` system property.
   *
   * @param property the property's value, if set
   * @return [[GuiPlatform.Headless]] iff the value is exactly `Headless`, else [[GuiPlatform.Desktop]]
   */
  def fromGlassPlatform(property: Option[String]): GuiPlatform = {
    if (property.contains("Headless")) Headless else Desktop
  }

  /** The platform selected by the `glass.platform` system property of this JVM. */
  def current: GuiPlatform = fromGlassPlatform(sys.props.get("glass.platform"))
}

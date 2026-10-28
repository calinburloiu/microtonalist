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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GuiPlatformTest extends AnyWordSpec with Matchers {

  "GuiPlatform.fromGlassPlatform" should {
    "be headless for Headless" in {
      // When / Then
      GuiPlatform.fromGlassPlatform(Some("Headless")) shouldBe GuiPlatform.Headless
    }

    "be the desktop otherwise" in {
      // When / Then
      GuiPlatform.fromGlassPlatform(None) shouldBe GuiPlatform.Desktop
      GuiPlatform.fromGlassPlatform(Some("mac")) shouldBe GuiPlatform.Desktop
    }
  }

  "GuiPlatform.current" should {
    "be headless in the test JVM" in {
      // When / Then
      GuiPlatform.current shouldBe GuiPlatform.Headless
    }
  }
}

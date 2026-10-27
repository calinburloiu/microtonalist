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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.GuiPlatform
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch

class QaDriverAgentTest extends AnyWordSpec with Matchers {
  "QaDriverAgent.newInput" should {
    "drive the headless platform with the Robot" in {
      // When / Then
      QaDriverAgent.newInput(GuiPlatform.Headless, FxExecutor()) shouldBe a[RobotInput]
    }

    "drive the desktop platform with events" in {
      // When / Then
      QaDriverAgent.newInput(GuiPlatform.Desktop, FxExecutor()) shouldBe an[EventInput]
    }
  }

  "QaDriverAgent.start" should {
    "report a class missing from the app on standard error, and rethrow it" in {
      // Given
      val printed = CountDownLatch(1)

      StdErr.capturing(printed) { captured =>
        // When
        val thrown = the[NoClassDefFoundError] thrownBy
          QaDriverAgent.start(throw NoClassDefFoundError("javafx/event/EventTarget"))

        // Then
        thrown.getMessage shouldBe "javafx/event/EventTarget"
        val report = captured.toString(UTF_8)
        report should startWith("qa-driver: can't start")
        report should include("javafx/event/EventTarget")
        report should include("only a JavaFX app")
      }
    }
  }
}

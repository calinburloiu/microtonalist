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

import javafx.scene.input.Clipboard
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{ReadClipboard, WriteClipboard}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsNull, JsObject, Json}

class ClipboardTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  private def handle(request: DriverRequest): JsObject = {
    val executor = FxExecutor()
    RequestHandler(executor, RobotInput(executor)).handle(request)
  }

  "the clipboard" should {
    "read what was written" in {
      // When
      handle(WriteClipboard("C Septimal"))

      // Then
      handle(ReadClipboard) shouldBe Json.obj("text" -> "C Septimal")
    }

    "read null when it holds no text" in {
      // Given
      fx(Clipboard.getSystemClipboard.clear())

      // When / Then
      handle(ReadClipboard) shouldBe Json.obj("text" -> JsNull)
    }
  }
}

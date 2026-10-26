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

import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{Click, Key}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.MouseButtonName.Primary
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec

class RobotInputTest extends AnyWordSpec with InputBehaviors with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  private def handler: RequestHandler = {
    val executor = FxExecutor()
    RequestHandler(executor, RobotInput(executor))
  }

  "RobotInput" should {
    behave like drivesTheStandIn(RobotInput(_))
  }

  "RequestHandler" should {
    "reject an unknown key" in {
      // When / Then
      (the[DriverException] thrownBy handler.handle(Key("Hyper+N"))).getMessage shouldBe
        """unknown key "Hyper" in "Hyper+N""""
    }

    "reject a click count below one" in {
      // When / Then
      (the[DriverException] thrownBy handler.handle(Click("Play", None, Primary, 0))).getMessage shouldBe
        "the click count must be at least 1, not 0"
    }
  }
}

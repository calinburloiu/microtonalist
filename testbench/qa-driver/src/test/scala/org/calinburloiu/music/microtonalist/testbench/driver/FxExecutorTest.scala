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

import javafx.application.Platform
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

class FxExecutorTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  "FxExecutor" should {
    "run the body on the JavaFX Application Thread" in {
      // When / Then
      FxExecutor()(Platform.isFxApplicationThread) shouldBe true
    }

    "fail with the body's message" in {
      // When / Then
      (the[DriverException] thrownBy FxExecutor()(throw IllegalStateException("boom"))).getMessage shouldBe "boom"
    }

    "fail with the body's exception when it has no message" in {
      // When / Then
      (the[DriverException] thrownBy FxExecutor()(throw IllegalStateException())).getMessage shouldBe
        "java.lang.IllegalStateException"
    }

    "fail when the Application Thread doesn't answer in time" in {
      // Given
      val release = CountDownLatch(1)
      FxExecutor().post(release.await())

      try {
        // When
        val exception = the[DriverException] thrownBy FxExecutor(200.millis)(())

        // Then
        exception.getMessage should include("timed out")
      } finally {
        release.countDown()
      }
    }
  }
}

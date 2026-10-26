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

import com.sun.javafx.stage.WindowHelper
import javafx.scene.control.TextField
import javafx.stage.{Stage, Window}
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{Click, Key, Type}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.MouseButtonName.Primary
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.*

class EventInputTest extends AnyWordSpec with InputBehaviors with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  /** The stand-in scene, shown and focused as the only window, and a handler that drives it with an [[EventInput]]. */
  private class StandInFixture {
    HeadlessFx.closeAllWindows()
    val standIn: StandInScene = fx(StandInScene.show())
    val executor: FxExecutor = FxExecutor()
    val handler: RequestHandler = RequestHandler(executor, EventInput(executor))
    fx(standIn.stage.requestFocus())
    executor.idle()

    def actions: Seq[String] = standIn.actions.asScala.toSeq

    def nameField: TextField = fx(standIn.stage.getScene.lookup("#name").asInstanceOf[TextField])

    def isAnyWindowFocused: Boolean = fx(Window.getWindows.asScala.exists(_.isFocused))

    def windowTitles: Seq[String] = fx(Window.getWindows.asScala.toSeq.collect { case stage: Stage => stage.getTitle })

    /** Takes the focus from every window, as switching to another app would on the desktop. */
    def unfocusAllWindows(): Unit = fx {
      // The headless platform never takes a window's focus away, and JavaFX has no public API that does, so this uses
      // OpenJFX's internal `WindowHelper`, which an upgrade of the pinned OpenJFX may break.
      Window.getWindows.asScala.foreach { window => WindowHelper.setFocused(window, false) }
    }
  }

  "EventInput" should {
    behave like drivesTheStandIn(EventInput(_))

    "not reach a covered button" in new StandInFixture {
      // When
      handler.handle(Click("Covered", None, Primary, 1))

      // Then
      actions should not contain "covered"
    }

    "type the characters of letter, digit and space keys" in new StandInFixture {
      // Given
      fx(nameField.requestFocus())

      // When
      Seq("D", "Shift+O", "DIGIT1", "SPACE").foreach { key => handler.handle(Key(key)) }

      // Then
      fx(nameField.getText) shouldBe "dO1 "
    }

    "press keys and type in the stage while no window has the focus" in new StandInFixture {
      // Given
      fx(nameField.requestFocus())
      unfocusAllWindows()
      isAnyWindowFocused shouldBe false

      // When
      handler.handle(Key("D"))
      handler.handle(Type("oina"))

      // Then
      fx(nameField.getText) shouldBe "doina"
    }

    "press a key in the dialog on top while no window has the focus" in new StandInFixture {
      // Given
      handler.handle(Click("Confirm", None, Primary, 1))
      unfocusAllWindows()
      isAnyWindowFocused shouldBe false

      // When
      handler.handle(Key("SPACE"))

      // Then
      actions shouldBe Seq("confirm")
      windowTitles should not contain "Confirm"
    }

    "fail to press a key or type while no stage shows" in new StandInFixture {
      // Given
      HeadlessFx.closeAllWindows()

      // When / Then
      (the[DriverException] thrownBy handler.handle(Key("DOWN"))).getMessage shouldBe "no stage shows"
      (the[DriverException] thrownBy handler.handle(Type("Doina"))).getMessage shouldBe "no stage shows"
    }
  }
}

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
import javafx.scene.control.{TextField, TextInputDialog, Tooltip}
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{Click, Key, Type, Windows}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.MouseButtonName.{Primary, Secondary}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsObject, JsValue}

import scala.jdk.CollectionConverters.*

/** The behaviours that every [[Input]] shares, driving the [[StandInScene]] through a [[RequestHandler]]. */
trait InputBehaviors extends Matchers { this: AnyWordSpec =>

  /** The stand-in scene, shown as the only window, and a handler that drives it with the input under test. */
  private class Fixture(newInput: FxExecutor => Input) {
    val scene: StandInScene = fx(StandInScene.show())
    val executor: FxExecutor = FxExecutor()
    val handler: RequestHandler = RequestHandler(executor, newInput(executor))

    def handle(request: DriverRequest): JsObject = handler.handle(request)

    def actions: Seq[String] = scene.actions.asScala.toSeq

    def selectedIndex: Int = fx(scene.tunings.getSelectionModel.getSelectedIndex)

    def windows: Seq[JsValue] = (handle(Windows) \ "windows").as[Seq[JsValue]]

    def stageTitles: Seq[String] = windows.flatMap { window => (window \ "title").asOpt[String] }

    def nameField: TextField = fx(scene.stage.getScene.lookup("#name").asInstanceOf[TextField])

    /** Gives the stage the focus, then waits until the Application Thread has handled it. */
    def focusStage(): Unit = {
      fx(scene.stage.requestFocus())
      executor.idle()
    }

    /**
     * Shows a dialog titled "Rename", with an empty text field, over the stage, then waits until the Application Thread
     * has handled it. The headless platform leaves the stage focused too.
     */
    def showRenameDialog(): TextInputDialog = {
      val dialog = fx {
        val dialog = TextInputDialog()
        dialog.initOwner(scene.stage)
        dialog.setTitle("Rename")
        dialog.show()
        dialog
      }
      executor.idle()
      dialog
    }

    /**
     * Shows a tooltip over the stage, then waits until the Application Thread has handled it. JavaFX marks a popup
     * focused along with its owner.
     */
    def showTooltip(): Tooltip = {
      val tooltip = fx {
        val tooltip = Tooltip("Hint")
        tooltip.show(scene.stage, scene.stage.getX + 10, scene.stage.getY + 10)
        tooltip
      }
      executor.idle()
      tooltip
    }
  }

  /** Runs the test with the stand-in scene as the only window, and closes every window after. */
  private def withStandIn(newInput: FxExecutor => Input)(test: Fixture => Unit): Unit = {
    HeadlessFx.closeAllWindows()
    try {
      test(Fixture(newInput))
    } finally {
      HeadlessFx.closeAllWindows()
    }
  }

  /** The behaviours of an input that `newInput` creates. */
  def drivesTheStandIn(newInput: FxExecutor => Input): Unit = {
    def withInput(test: Fixture => Unit): Unit = withStandIn(newInput)(test)

    "fire a button, and say which node it matched" in withInput { f =>
      // When
      val answer = f.handle(Click("Play", None, Primary, 1))

      // Then
      f.actions should contain("play")
      (answer \ "matchedBy").as[String] shouldBe "text"
      (answer \ "node").as[String] should startWith("""Button text="Play"""")
      (answer \ "disabled").as[Boolean] shouldBe false
    }

    "select a list row" in withInput { f =>
      // When
      f.handle(Click("C Septimal", None, Primary, 1))

      // Then
      f.selectedIndex shouldBe 2
    }

    "open the context menu with a secondary click" in withInput { f =>
      // When
      f.handle(Click("C Septimal", None, Secondary, 1))

      // Then
      f.windows.map { window => (window \ "type").as[String] } should contain("ContextMenu")
    }

    "double-click a row" in withInput { f =>
      // When
      f.handle(Click("C Septimal", None, Primary, 2))

      // Then
      f.actions should contain("open C Septimal")
    }

    "answer while a dialog opened with showAndWait shows" in withInput { f =>
      // When
      f.handle(Click("Confirm", None, Primary, 1))

      // Then
      f.actions should contain("confirm")
      f.stageTitles should contain("Confirm")
      fx(Platform.isNestedLoopRunning) shouldBe true

      // When
      HeadlessFx.closeAllWindows()

      // Then
      // The dialog's nested event loop ends once the Application Thread has run what closing queued.
      f.executor.idle()
      fx(Platform.isNestedLoopRunning) shouldBe false
      f.windows shouldBe empty
    }

    "not fire a disabled button, and say so" in withInput { f =>
      // When
      val answer = f.handle(Click("Locked", None, Primary, 1))

      // Then
      (answer \ "disabled").as[Boolean] shouldBe true
      f.actions should not contain "locked"
    }

    "press a key in the focused control" in withInput { f =>
      // Given
      f.focusStage()
      fx {
        f.scene.tunings.requestFocus()
        f.scene.tunings.getSelectionModel.select(0)
      }

      // When
      f.handle(Key("DOWN"))

      // Then
      f.selectedIndex shouldBe 1
    }

    "trigger an accelerator" in withInput { f =>
      // Given
      f.focusStage()

      // When
      f.handle(Key("Shortcut+N"))

      // Then
      f.actions should contain("new")
      f.stageTitles should contain("New")
    }

    "confirm with Enter a dialog that an accelerator opened with showAndWait" in withInput { f =>
      // Given
      f.focusStage()
      f.handle(Key("Shortcut+R"))
      f.stageTitles should contain("Rename")

      // When
      f.handle(Key("ENTER"))

      // Then
      f.actions should contain("renamed C Just Major")
      f.stageTitles should not contain "Rename"
    }

    "type into the focused text field" in withInput { f =>
      // Given
      f.focusStage()
      fx(f.nameField.requestFocus())

      // When
      f.handle(Type("Doina"))

      // Then
      fx(f.nameField.getText) shouldBe "Doina"
    }

    "type into the focused text field while a tooltip shows" in withInput { f =>
      // Given
      f.focusStage()
      fx(f.nameField.requestFocus())
      val tooltip = f.showTooltip()
      fx(tooltip.isShowing) shouldBe true

      // When
      f.handle(Type("Doina"))

      // Then
      fx(f.nameField.getText) shouldBe "Doina"
    }

    "type into the text field of a dialog over the stage" in withInput { f =>
      // Given
      f.focusStage()
      val dialog = f.showRenameDialog()

      // When
      f.handle(Type("Doina"))

      // Then
      fx(dialog.getEditor.getText) shouldBe "Doina"
    }

    "press a key in a dialog over the stage" in withInput { f =>
      // Given
      f.focusStage()
      val dialog = f.showRenameDialog()

      // When
      f.handle(Key("ESCAPE"))

      // Then
      f.stageTitles should not contain "Rename"
      fx(dialog.isShowing) shouldBe false
    }
  }
}

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

import javafx.event.Event
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton}
import javafx.scene.robot.Robot
import javafx.stage.Window

import scala.jdk.CollectionConverters.*

/**
 * Injects the user's input into the app.
 *
 * Every method must run on the JavaFX Application Thread. It queues the input with [[FxExecutor.post]] and returns at
 * once, so that a handler that blocks in `showAndWait` doesn't block the caller.
 */
trait Input {
  /** Clicks the centre of a node, `clickCount` times in a row, so that a count of 2 is a double-click. */
  def click(node: Node, button: MouseButton, clickCount: Int): Unit

  /**
   * Presses a key while holding its modifiers.
   *
   * @throws DriverException if no window has the focus
   */
  def key(chord: KeyChord): Unit

  /**
   * Types text, as `KEY_TYPED` events to the focus owner of the focused window, or to its scene if nothing in it has
   * the focus.
   *
   * @throws DriverException if no window has the focus
   */
  def typeText(text: String): Unit
}

object Input {
  /**
   * The window that has the focus; must run on the JavaFX Application Thread.
   *
   * @throws DriverException if none has
   */
  def focusedWindow(): Window = Window.getWindows.asScala.find(_.isFocused).getOrElse {
    throw DriverException("no window has the focus")
  }
}

/**
 * The input of the headless platform: JavaFX's `Robot`, which injects through Glass's input path, like a user would.
 *
 * @param fx queues the input on the JavaFX Application Thread
 */
final class RobotInput(fx: FxExecutor) extends Input {
  // The Robot must be created on the Application Thread, which every method runs on.
  private lazy val robot: Robot = Robot()

  override def click(node: Node, button: MouseButton, clickCount: Int): Unit = {
    val bounds = node.localToScreen(node.getLayoutBounds)
    val centre = Point2D(bounds.getCenterX, bounds.getCenterY)
    fx.post {
      robot.mouseMove(centre)
      for (_ <- 1 to clickCount) {
        robot.mouseClick(button)
      }
    }
  }

  override def key(chord: KeyChord): Unit = {
    Input.focusedWindow()
    fx.post {
      chord.modifiers.foreach(robot.keyPress)
      robot.keyPress(chord.key)
      robot.keyRelease(chord.key)
      chord.modifiers.reverseIterator.foreach(robot.keyRelease)
    }
  }

  override def typeText(text: String): Unit = {
    val scene = Input.focusedWindow().getScene
    val target = Option(scene.getFocusOwner).getOrElse(scene)
    fx.post {
      text.codePoints.forEach { codePoint =>
        Event.fireEvent(target,
          KeyEvent(KeyEvent.KEY_TYPED, Character.toString(codePoint), "", KeyCode.UNDEFINED,
            false, false, false, false))
      }
    }
  }
}

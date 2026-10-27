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

import javafx.event.{Event, EventTarget}
import javafx.geometry.Point2D
import javafx.scene.input.{KeyCode, KeyEvent, MouseButton}
import javafx.scene.robot.Robot
import javafx.scene.{Node, Scene}
import javafx.stage.{Stage, Window}

import scala.jdk.CollectionConverters.*

/**
 * Injects the user's input into the app.
 *
 * Every method must run on the JavaFX Application Thread. It queues the input with [[FxExecutor.post]] and returns at
 * once, so that a handler that blocks in `showAndWait` doesn't block the caller. An implementation may throw a
 * [[DriverException]] when the input has no target.
 */
trait Input {
  /** Clicks the centre of a node, `clickCount` times in a row, so that a count of 2 is a double-click. */
  def click(node: Node, button: MouseButton, clickCount: Int): Unit

  /** Presses a key while holding its modifiers. */
  def key(chord: KeyChord): Unit

  /** Types text, as `KEY_TYPED` events to the control that has the focus. */
  def typeText(text: String): Unit
}

/** What the inputs share: finding the window and the node that has the focus, and typing text. */
object Input {
  /**
   * The stage that has the focus, the one shown last if several have it; must run on the JavaFX Application Thread.
   *
   * The headless platform leaves a stage focused when a dialog shows over it, and its `Robot` sends keys to the one
   * shown last. A popup, focused along with its owner, is left out: its owner's scene passes it the keys.
   */
  def lastFocusedStage(): Option[Stage] = Window.getWindows.asScala.reverseIterator.collectFirst {
    case stage: Stage if stage.isFocused => stage
  }

  /**
   * The [[lastFocusedStage]]; must run on the JavaFX Application Thread.
   *
   * @throws DriverException if no window has the focus
   */
  def focusedWindow(): Window = lastFocusedStage().getOrElse {
    throw DriverException("no window has the focus")
  }

  /** The focus owner of a scene, or the scene itself if nothing in it has the focus. */
  def focusOwnerOrScene(scene: Scene): EventTarget = Option(scene.getFocusOwner).getOrElse(scene)

  /** Fires a `KEY_TYPED` event at `target` for each character of `text`; must run on the JavaFX Application Thread. */
  def fireKeyTyped(target: EventTarget, text: String): Unit = {
    text.codePoints.forEach { codePoint =>
      Event.fireEvent(target,
        KeyEvent(KeyEvent.KEY_TYPED, Character.toString(codePoint), "", KeyCode.UNDEFINED, false, false, false, false))
    }
  }
}

/**
 * The input of the headless platform: JavaFX's `Robot`, which injects through Glass's input path, like a user would.
 *
 * Since the `Robot` drops the input while no window has the focus, [[key]] and [[typeText]] throw a [[DriverException]]
 * then; [[typeText]] types to the focus owner of the [[Input.lastFocusedStage]], or to its scene if nothing in it has
 * the focus.
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
    // One step per press and release: a key whose handler blocks in `showAndWait`, such as an accelerator opening a
    // dialog, mustn't hold back the releases, which the `Robot` would otherwise keep down while the dialog shows.
    chord.modifiers.foreach { modifier => fx.post(robot.keyPress(modifier)) }
    fx.post(robot.keyPress(chord.key))
    fx.post(robot.keyRelease(chord.key))
    chord.modifiers.reverseIterator.foreach { modifier => fx.post(robot.keyRelease(modifier)) }
  }

  override def typeText(text: String): Unit = {
    val target = Input.focusOwnerOrScene(Input.focusedWindow().getScene)
    fx.post(Input.fireKeyTyped(target, text))
  }
}

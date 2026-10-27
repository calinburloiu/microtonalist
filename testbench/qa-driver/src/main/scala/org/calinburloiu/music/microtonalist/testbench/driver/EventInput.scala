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

import javafx.event.{Event, EventTarget, EventType}
import javafx.scene.input.*
import javafx.scene.{Node, Scene}
import javafx.stage.{Stage, Window}

import scala.jdk.CollectionConverters.*

/**
 * The input of the desktop platform, where JavaFX's `Robot` is OS-level: it fires JavaFX events, so that macOS sees
 * nothing and needs no grant.
 *
 * A click goes to the node that [[Picker]] picks under the node's centre, in the window shown last that holds that
 * point on screen, as an approximation of the window on top: a popup or a dialog that covers the node takes the click.
 * Keys and text go to the focus owner of the focused window, since the user may have switched to another app, else of
 * the stage shown last, such as a modal dialog on top; [[key]] and [[typeText]] throw a [[DriverException]] if no stage
 * shows.
 *
 * @param fx queues the input on the JavaFX Application Thread
 */
final class EventInput(fx: FxExecutor) extends Input {
  override def click(node: Node, button: MouseButton, clickCount: Int): Unit = {
    val scene = node.getScene
    val inScene = node.localToScene(node.getLayoutBounds)
    val onScreen = node.localToScreen(node.getLayoutBounds)
    val point = EventInput.Point(inScene.getCenterX, inScene.getCenterY, onScreen.getCenterX, onScreen.getCenterY)
    fx.post {
      val (targetScene, targetPoint) = EventInput.sceneOnTop(scene, point)
      val target: EventTarget = Picker.pick(targetScene, targetPoint.sceneX, targetPoint.sceneY).getOrElse(targetScene)
      for (count <- 1 to clickCount) {
        Event.fireEvent(target, EventInput.mouseEvent(target, MouseEvent.MOUSE_PRESSED, targetPoint, button, count))
        if (button == MouseButton.SECONDARY) {
          // The Scene raises this from the platform's own event, not from a fired MOUSE_PRESSED.
          Event.fireEvent(target, EventInput.contextMenuEvent(target, targetPoint))
        }
        Event.fireEvent(target, EventInput.mouseEvent(target, MouseEvent.MOUSE_RELEASED, targetPoint, button, count))
        Event.fireEvent(target, EventInput.mouseEvent(target, MouseEvent.MOUSE_CLICKED, targetPoint, button, count))
      }
    }
  }

  override def key(chord: KeyChord): Unit = {
    val target = EventInput.keyTarget()
    val shift = chord.modifiers.contains(KeyCode.SHIFT)
    val control = chord.modifiers.contains(KeyCode.CONTROL)
    val alt = chord.modifiers.contains(KeyCode.ALT)
    val meta = chord.modifiers.exists { code => code == KeyCode.COMMAND || code == KeyCode.META }
    val typed = if (control || alt || meta) None else EventInput.character(chord.key, shift)
    val text = typed.getOrElse("")

    def keyEvent(eventType: EventType[KeyEvent], character: String): KeyEvent =
      KeyEvent(null, target, eventType, character, text, chord.key, shift, control, alt, meta)

    fx.post {
      Event.fireEvent(target, keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED))
      typed.foreach { character => Event.fireEvent(target, keyEvent(KeyEvent.KEY_TYPED, character)) }
      Event.fireEvent(target, keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED))
    }
  }

  override def typeText(text: String): Unit = {
    val target = EventInput.keyTarget()
    fx.post(Input.fireKeyTyped(target, text))
  }
}

private object EventInput {
  /** A point in scene and in screen coordinates. */
  final case class Point(sceneX: Double, sceneY: Double, screenX: Double, screenY: Double)

  /**
   * The scene that a click at `point` of `scene` reaches, with the point in its coordinates: that of the window shown
   * last that holds the point on screen, which is `scene`'s window unless another window covers it.
   */
  def sceneOnTop(scene: Scene, point: Point): (Scene, Point) = {
    val onTop = Window.getWindows.asScala.reverseIterator.find { window =>
      window.getScene != null && point.screenX >= window.getX && point.screenX < window.getX + window.getWidth &&
        point.screenY >= window.getY && point.screenY < window.getY + window.getHeight
    }
    onTop.map(_.getScene).filter(_ ne scene).fold((scene, point)) { coverScene =>
      val window = coverScene.getWindow
      (coverScene,
        point.copy(sceneX = point.screenX - window.getX - coverScene.getX,
          sceneY = point.screenY - window.getY - coverScene.getY))
    }
  }

  def mouseEvent(target: EventTarget, eventType: EventType[MouseEvent], point: Point,
                 button: MouseButton, clickCount: Int): MouseEvent = {
    val isDown = eventType == MouseEvent.MOUSE_PRESSED
    MouseEvent(null, target, eventType, point.sceneX, point.sceneY, point.screenX, point.screenY, button, clickCount,
      false, false, false, false,
      isDown && button == MouseButton.PRIMARY, isDown && button == MouseButton.MIDDLE,
      isDown && button == MouseButton.SECONDARY,
      false, isDown && button == MouseButton.SECONDARY, true, PickResult(target, point.sceneX, point.sceneY))
  }

  def contextMenuEvent(target: EventTarget, point: Point): ContextMenuEvent =
    ContextMenuEvent(null, target, ContextMenuEvent.CONTEXT_MENU_REQUESTED, point.sceneX, point.sceneY, point.screenX,
      point.screenY, false, PickResult(target, point.sceneX, point.sceneY))

  /**
   * The focus owner of the [[Input.lastFocusedStage]], else of the last stage shown, such as a modal dialog on top, or
   * that window's scene if none.
   */
  def keyTarget(): EventTarget = {
    val windows = Window.getWindows.asScala
    val window = Input.lastFocusedStage().orElse(windows.findLast(_.isInstanceOf[Stage])).getOrElse {
      throw DriverException("no stage shows")
    }
    Input.focusOwnerOrScene(window.getScene)
  }

  /** The character that a letter, digit or space key types, Shift giving a capital letter. */
  def character(key: KeyCode, shift: Boolean): Option[String] = {
    if (key.isLetterKey) {
      Some(if (shift) key.getChar.toUpperCase else key.getChar.toLowerCase)
    } else if ((key.isDigitKey && !key.isKeypadKey) || key == KeyCode.SPACE) {
      Some(key.getChar)
    } else {
      None
    }
  }
}

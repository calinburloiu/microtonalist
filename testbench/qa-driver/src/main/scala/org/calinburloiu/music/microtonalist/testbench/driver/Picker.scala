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

import javafx.scene.{Node, Parent, Scene}

import scala.jdk.CollectionConverters.*

/** Finds the node that a mouse event at a point of a scene targets, as JavaFX's own picking does. */
object Picker {
  /**
   * The topmost node under a point that takes mouse events, or `None` if none does; must run on the JavaFX Application
   * Thread.
   *
   * Like JavaFX, it skips the invisible, disabled and mouse-transparent nodes with their descendants, and prefers a
   * later child to an earlier one, which it draws over, and a descendant to its parent.
   */
  def pick(scene: Scene, sceneX: Double, sceneY: Double): Option[Node] = pickIn(scene.getRoot, sceneX, sceneY)

  private def pickIn(node: Node, sceneX: Double, sceneY: Double): Option[Node] = {
    if (!node.isVisible || node.isDisable || node.isMouseTransparent) {
      None
    } else {
      val children = node match {
        case parent: Parent => parent.getChildrenUnmodifiable.asScala.toSeq
        case _ => Seq.empty
      }
      children.reverseIterator.flatMap { child => pickIn(child, sceneX, sceneY) }.nextOption().orElse {
        Option.when(node.contains(node.sceneToLocal(sceneX, sceneY)))(node)
      }
    }
  }
}

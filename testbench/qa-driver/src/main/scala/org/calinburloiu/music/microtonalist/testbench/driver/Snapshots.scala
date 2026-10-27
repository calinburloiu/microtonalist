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

import javafx.geometry.{Point2D, Rectangle2D}
import javafx.scene.image.{ImageView, PixelFormat, WritableImage}
import javafx.scene.paint.Color
import javafx.scene.transform.Transform
import javafx.scene.{Group, Node, Scene, SnapshotParameters}
import javafx.stage.{PopupWindow, Window}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/**
 * Renders windows and nodes to images, and encodes them as PNG; every method must run on the JavaFX Application Thread.
 */
object Snapshots {
  /**
   * Renders a window's scene, scaled by `scale`, with each showing popup that it owns, directly or through another
   * popup, pasted over it at its offset; the image has the scene's size times `scale`, rounded up.
   */
  def window(window: Window, scale: Double): WritableImage = {
    val origin = sceneOrigin(window)
    val popups = Window.getWindows.asScala.toSeq.collect {
      case popup: PopupWindow if isOwned(popup, window) => popup
    }
    val layers = (window +: popups).map { layer =>
      val offset = sceneOrigin(layer).subtract(origin).multiply(scale)
      val view = ImageView(sceneImage(layer.getScene, scale))
      view.setX(offset.getX)
      view.setY(offset.getY)
      view
    }

    val params = SnapshotParameters()
    params.setFill(Color.TRANSPARENT)
    params.setViewport(viewport(window.getScene, scale))
    Group(layers*).snapshot(params, null)
  }

  /** Renders a node, with its own transforms, scaled by `scale`. */
  def node(node: Node, scale: Double): WritableImage = {
    val params = SnapshotParameters()
    params.setTransform(Transform.scale(scale, scale))
    node.snapshot(params, null)
  }

  /** Encodes an image as PNG. */
  def png(image: WritableImage): Array[Byte] = {
    val width = image.getWidth.toInt
    val height = image.getHeight.toInt
    val pixels = new Array[Int](width * height)
    image.getPixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance, pixels, 0, width)
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, width, height, pixels, 0, width)

    val bytes = ByteArrayOutputStream()
    ImageIO.write(buffered, "png", bytes)
    bytes.toByteArray
  }

  /** Renders a scene, scaled by `scale`, filled with its own fill, or with none if it has none, as a popup. */
  private def sceneImage(scene: Scene, scale: Double): WritableImage = {
    val params = SnapshotParameters()
    params.setTransform(Transform.scale(scale, scale))
    // A null fill would paint the snapshot white.
    params.setFill(Option(scene.getFill).getOrElse(Color.TRANSPARENT))
    params.setViewport(viewport(scene, scale))
    scene.getRoot.snapshot(params, null)
  }

  /** The area of a scene, scaled by `scale` and rounded up to whole pixels. */
  private def viewport(scene: Scene, scale: Double): Rectangle2D = {
    Rectangle2D(0, 0, (scene.getWidth * scale).ceil, (scene.getHeight * scale).ceil)
  }

  /** The position of a window's scene on the screen. */
  private def sceneOrigin(window: Window): Point2D = {
    Point2D(window.getX + window.getScene.getX, window.getY + window.getScene.getY)
  }

  private def isOwned(popup: PopupWindow, window: Window): Boolean = popup.getOwnerWindow match {
    case `window` => true
    case owner: PopupWindow => isOwned(owner, window)
    case _ => false
  }
}

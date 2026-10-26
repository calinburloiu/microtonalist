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

import javafx.geometry.Side
import javafx.scene.control.{ContextMenu, MenuItem}
import javafx.stage.Window
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.Snapshot
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.JsObject

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class SnapshotsTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  private val handler: RequestHandler = {
    val executor = FxExecutor()
    RequestHandler(executor, RobotInput(executor))
  }

  /** Shows the stand-in scene as the only window, runs the test, then closes every window. */
  private def withStandIn(test: StandInScene => Unit): Unit = {
    HeadlessFx.closeAllWindows()
    val scene = fx(StandInScene.show())
    try {
      test(scene)
    } finally {
      HeadlessFx.closeAllWindows()
    }
  }

  private def handle(request: Snapshot): JsObject = handler.handle(request)

  /** The width and height that an answer states. */
  private def size(answer: JsObject): (Int, Int) = ((answer \ "width").as[Int], (answer \ "height").as[Int])

  /** The answer's PNG, decoded. */
  private def image(answer: JsObject): BufferedImage = {
    ImageIO.read(ByteArrayInputStream(Base64.getDecoder.decode((answer \ "png").as[String])))
  }

  /** Shows the list's context menu below it, and returns it. */
  private def showContextMenu(scene: StandInScene): ContextMenu = fx {
    val menu = scene.tunings.getContextMenu
    menu.show(scene.tunings, Side.BOTTOM, 0, 0)
    menu
  }

  /** The area of a popup's scene in the coordinates of the stage's scene. */
  private def areaInStage(stage: Window, popup: Window): Rectangle = fx {
    val x = popup.getX + popup.getScene.getX - stage.getX - stage.getScene.getX
    val y = popup.getY + popup.getScene.getY - stage.getY - stage.getScene.getY
    Rectangle(x.round.toInt, y.round.toInt, popup.getScene.getWidth.toInt, popup.getScene.getHeight.toInt)
  }

  /** The fraction of the pixels in `area` that differ between two images, clipped to the images' bounds. */
  private def differingFraction(first: BufferedImage, second: BufferedImage, area: Rectangle): Double = {
    val clipped = area.intersection(Rectangle(0, 0, first.getWidth, first.getHeight))
    val pixels = for {
      y <- clipped.y until clipped.y + clipped.height
      x <- clipped.x until clipped.x + clipped.width
    } yield first.getRGB(x, y) != second.getRGB(x, y)
    pixels.count(identity).toDouble / pixels.size
  }

  "a window snapshot" should {
    "have the scene's size at scale 1" in withStandIn { _ =>
      // When / Then
      size(handle(Snapshot(None, None, 1.0))) shouldBe (480, 640)
    }

    "double it at scale 2" in withStandIn { _ =>
      // When / Then
      size(handle(Snapshot(None, None, 2.0))) shouldBe (960, 1280)
    }

    "show an open context menu over the window" in withStandIn { scene =>
      // Given
      val withoutMenu = image(handle(Snapshot(None, None, 1.0)))
      val menu = showContextMenu(scene)

      // When
      val answer = handle(Snapshot(None, None, 1.0))

      // Then
      size(answer) shouldBe (480, 640)
      differingFraction(withoutMenu, image(answer), areaInStage(scene.stage, menu)) should be > 0.5
    }

    "show a popup that an owned popup owns" in withStandIn { scene =>
      // Given
      val menu = showContextMenu(scene)
      val withOneMenu = image(handle(Snapshot(None, None, 1.0)))
      val nested = fx {
        val nested = ContextMenu(MenuItem("Nested item"))
        nested.show(menu.getScene.getRoot, Side.RIGHT, 0, 0)
        nested
      }

      // When
      val withBoth = image(handle(Snapshot(None, None, 1.0)))

      // Then
      fx(nested.getOwnerWindow) shouldBe menu
      differingFraction(withOneMenu, withBoth, areaInStage(scene.stage, nested)) should be > 0.5
    }

    "be of the window given" in withStandIn { scene =>
      // Given
      val menu = showContextMenu(scene)
      val menuSize = fx((menu.getScene.getWidth.ceil.toInt, menu.getScene.getHeight.ceil.toInt))

      // When / Then
      size(handle(Snapshot(Some(1), None, 1.0))) shouldBe menuSize
    }

    "fail when no stage shows" in {
      // Given
      HeadlessFx.closeAllWindows()

      // When / Then
      (the[DriverException] thrownBy handle(Snapshot(None, None, 1.0))).getMessage shouldBe "no stage shows"
    }
  }

  "a node snapshot" should {
    "have the node's size" in withStandIn { _ =>
      // Given
      val playSize = fx {
        val bounds = Selectors.find("Play", None).node.getBoundsInParent
        (bounds.getWidth.ceil.toInt, bounds.getHeight.ceil.toInt)
      }

      // When / Then
      size(handle(Snapshot(None, Some("Play"), 1.0))) shouldBe playSize
    }
  }

  "the PNG" should {
    "read back with ImageIO at the same size" in withStandIn { _ =>
      // When
      val answer = handle(Snapshot(None, None, 1.0))

      // Then
      val png = image(answer)
      (png.getWidth, png.getHeight) shouldBe size(answer)
    }
  }

  "Snapshot" should {
    "refuse a scale outside (0, 4]" in withStandIn { _ =>
      // When / Then
      for (scale <- Seq(0.0, -1.0, 4.5, Double.NaN)) {
        (the[DriverException] thrownBy handle(Snapshot(None, None, scale))).getMessage shouldBe
          s"the scale must be in (0, 4], not $scale"
      }
    }
  }
}

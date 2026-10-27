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

import javafx.geometry.Point2D
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.image.Image
import javafx.scene.input.MouseButton
import javafx.scene.layout.StackPane
import javafx.scene.robot.Robot
import javafx.stage.Stage
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{CountDownLatch, TimeUnit}

/**
 * Tests no code of ours: it checks that JavaFX's experimental headless platform still shows, renders and takes input in
 * a forked test JVM, the canary for an OpenJFX upgrade.
 */
class HeadlessPlatformTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val ClickTimeoutSeconds: Long = 5

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  private def showButton(text: String, onAction: () => Unit): Button = {
    val button = Button(text)
    button.setOnAction(_ => onAction())
    val stage = Stage()
    stage.setScene(Scene(StackPane(button), 200, 100))
    stage.show()
    button
  }

  private def distinctArgb(image: Image): Int = {
    val reader = image.getPixelReader
    val pixels = for {
      x <- 0 until image.getWidth.toInt
      y <- 0 until image.getHeight.toInt
    } yield reader.getArgb(x, y)
    pixels.distinct.size
  }

  private def centreOnScreen(button: Button): Point2D = {
    val bounds = button.localToScreen(button.getBoundsInLocal)
    Point2D(bounds.getCenterX, bounds.getCenterY)
  }

  "the headless platform" should {
    "show a stage, render it in a snapshot and take a Robot click" in {
      // Given
      val clicked = CountDownLatch(1)
      val button = HeadlessFx.fx(showButton("Play", () => clicked.countDown()))

      // When / Then
      HeadlessFx.fx(button.getScene.getWindow.isShowing) shouldBe true
      distinctArgb(HeadlessFx.fx(button.getScene.snapshot(null))) should be > 1

      // When
      HeadlessFx.fx {
        val robot = Robot()
        robot.mouseMove(centreOnScreen(button))
        robot.mouseClick(MouseButton.PRIMARY)
      }

      // Then
      clicked.await(ClickTimeoutSeconds, TimeUnit.SECONDS) shouldBe true
    }
  }
}

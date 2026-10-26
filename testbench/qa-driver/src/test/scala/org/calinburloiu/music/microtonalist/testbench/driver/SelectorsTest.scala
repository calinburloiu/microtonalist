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

import javafx.scene.Scene
import javafx.scene.control.{Button, ListCell}
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SelectorsTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  /** Runs the test with no window showing before, and closes every window after. */
  private def withNoWindows(test: => Unit): Unit = {
    HeadlessFx.closeAllWindows()
    try {
      test
    } finally {
      HeadlessFx.closeAllWindows()
    }
  }

  /** Shows the stand-in scene as the only window, runs the test, then closes every window. */
  private def withStandIn(test: => Unit): Unit = withNoWindows {
    fx(StandInScene.show())
    test
  }

  /** Shows a stage titled `title` with the given buttons. */
  private def showStage(title: String, buttons: Button*): Stage = fx {
    val stage = Stage()
    stage.setTitle(title)
    stage.setScene(Scene(VBox(buttons*)))
    stage.show()
    stage
  }

  private def find(selector: String, window: Option[Int]): SelectorMatch = fx(Selectors.find(selector, window))

  "Selectors.find" should {
    "match by accessibleText first" in withStandIn {
      // When
      val result = find("Next tuning", None)

      // Then
      result.matchedBy shouldBe MatchKind.AccessibleText
      result.node.getAccessibleText shouldBe "Next tuning"
    }

    "prefer accessibleText to visible text" in withNoWindows {
      // Given
      val (labeled, described) = fx {
        val described = Button()
        described.setAccessibleText("Next")
        (Button("Next"), described)
      }
      showStage("Next", labeled, described)

      // When
      val result = find("Next", None)

      // Then
      result shouldBe SelectorMatch(described, MatchKind.AccessibleText)
    }

    "match by id when no accessibleText matches" in withStandIn {
      // When
      val result = find("status", None)

      // Then
      result.matchedBy shouldBe MatchKind.Id
      result.node.getId shouldBe "status"
    }

    "match by visible text last" in withStandIn {
      // When
      val result = find("Play", None)

      // Then
      result.matchedBy shouldBe MatchKind.Text
      result.node shouldBe a[Button]
    }

    "match a list row by its text" in withStandIn {
      // When
      val result = find("C Septimal", None)

      // Then
      result.node shouldBe a[ListCell[?]]
      result.matchedBy shouldBe MatchKind.Text
    }

    "fail on several matches, listing them" in withNoWindows {
      // Given
      showStage("First", fx(Button("OK")))
      showStage("Second", fx(Button("OK")))

      // When
      val message = (the[DriverException] thrownBy find("OK", None)).getMessage

      // Then
      // Whether the buttons read "focused" depends on which window has focus, which varies with suite order.
      val lines = message.linesIterator.toSeq
      lines.head shouldBe """several visible nodes match "OK" by text:"""
      lines.tail should have size 2
      lines(1) should (startWith("""  Button text="OK"""") and endWith(" in window 0"))
      lines(2) should (startWith("""  Button text="OK"""") and endWith(" in window 1"))
    }

    "fail when nothing visible matches" in withStandIn {
      // When / Then
      (the[DriverException] thrownBy find("Hidden", None)).getMessage shouldBe """no visible node matches "Hidden""""
    }

    "look only in the window given" in withNoWindows {
      // Given
      showStage("First", fx(Button("OK")))
      val secondWindow = showStage("Second", fx(Button("OK")))

      // When
      val result = find("OK", Some(1))

      // Then
      result.node.getScene.getWindow shouldBe secondWindow
    }

    "fail on a window index out of range" in withStandIn {
      // When / Then
      (the[DriverException] thrownBy find("Play", Some(9))).getMessage shouldBe "no window 9; there are 1"
    }
  }
}

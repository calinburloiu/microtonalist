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
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.DriverRequest.{Dump, Windows}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsArray, JsObject}

class SceneReaderTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

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

  private def handler: RequestHandler = {
    val executor = FxExecutor()
    RequestHandler(executor, RobotInput(executor))
  }

  private def dump(window: Option[Int]): JsObject = fx(SceneReader.dump(window))

  /** The dump lines of the first window. */
  private def lines: Seq[String] = ((dump(None) \ "windows")(0) \ "nodes").as[Seq[String]]

  "SceneReader.windows" should {
    "list the stage with its title and size" in withStandIn { _ =>
      // When
      val stage = (fx(SceneReader.windows()) \ "windows")(0)

      // Then
      (stage \ "index").as[Int] shouldBe 0
      (stage \ "type").as[String] shouldBe "Stage"
      (stage \ "title").as[String] shouldBe "Stand-in"
      ((stage \ "width").as[Double], (stage \ "height").as[Double]) shouldBe (480.0, 640.0)
      (stage \ "owner").toOption shouldBe None
    }

    "list an open context menu as a window of its own, with its owner" in withStandIn { scene =>
      // Given
      fx(scene.tunings.getContextMenu.show(scene.tunings, Side.BOTTOM, 0, 0))

      // When
      val menu = (fx(SceneReader.windows()) \ "windows")(1)

      // Then
      (menu \ "index").as[Int] shouldBe 1
      (menu \ "type").as[String] shouldBe "ContextMenu"
      (menu \ "owner").as[Int] shouldBe 0
      (menu \ "title").toOption shouldBe None
    }
  }

  "SceneReader.dump" should {
    "list the controls with their text, value and state" in withStandIn { _ =>
      // When / Then
      lines should contain allOf
        ("""Button text="Play"""", """Label #status text="C Just Major"""",
          """TextField #name value=""""", """Button accessibleText="Next tuning"""",
          """Button text="Locked" disabled""")
      lines.exists(_.matches("""\s*ListCell text="C Just Major" selected.*""")) shouldBe true
    }

    "indent a node by two spaces per shown ancestor" in withStandIn { _ =>
      // When / Then
      lines.head should startWith("ListView #tunings")
      lines should contain("""  VirtualFlow #virtual-flow""")
      lines should contain("""    ListCell text="C Septimal"""")
    }

    "mark a label cut by an ellipsis as truncated" in withStandIn { _ =>
      // When / Then
      lines.find(_.contains("#long")).get should endWith("truncated")
      lines.find(_.contains("#status")).get should not endWith "truncated"
    }

    "skip invisible nodes and empty cells" in withStandIn { _ =>
      // When / Then
      lines.exists(_.contains("Hidden")) shouldBe false
      lines.count(_.contains("ListCell")) shouldBe 3
    }

    "dump every window, each with its index, type and title" in withStandIn { scene =>
      // Given
      fx(scene.tunings.getContextMenu.show(scene.tunings, Side.BOTTOM, 0, 0))

      // When
      val windows = (dump(None) \ "windows").as[JsArray].value

      // Then
      windows.map { window => (window \ "index").as[Int] } shouldBe Seq(0, 1)
      (windows(0) \ "title").as[String] shouldBe "Stand-in"
      (windows(1) \ "type").as[String] shouldBe "ContextMenu"
      (windows(1) \ "nodes").as[Seq[String]] should contain("""MenuLabel text="Copy name"""")
    }

    "dump only the window asked for" in withStandIn { scene =>
      // Given
      fx(scene.tunings.getContextMenu.show(scene.tunings, Side.BOTTOM, 0, 0))

      // When
      val windows = (dump(Some(1)) \ "windows").as[JsArray].value

      // Then
      windows.map { window => (window \ "index").as[Int] } shouldBe Seq(1)
    }

    "fail on a window index out of range" in withStandIn { _ =>
      // When / Then
      (the[DriverException] thrownBy dump(Some(9))).getMessage shouldBe "no window 9; there are 1"
      (the[DriverException] thrownBy dump(Some(-1))).getMessage shouldBe "no window -1; there are 1"
    }
  }

  "RequestHandler" should {
    // These don't compare the answer to a second reading, since the stage may gain focus in between.
    "answer Windows with the windows" in withStandIn { _ =>
      // When
      val windows = (handler.handle(Windows) \ "windows").as[JsArray].value

      // Then
      windows.map { window => (window \ "title").as[String] } shouldBe Seq("Stand-in")
    }

    "answer Dump with the scene graph" in withStandIn { _ =>
      // When
      val windows = (handler.handle(Dump(None)) \ "windows").as[JsArray].value

      // Then
      windows.map { window => (window \ "title").as[String] } shouldBe Seq("Stand-in")
      (windows.head \ "nodes").as[Seq[String]] should contain("""Button text="Play"""")
    }
  }
}

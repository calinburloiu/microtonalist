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

import javafx.scene.Node
import javafx.scene.control.Button
import org.calinburloiu.music.microtonalist.testbench.driver.HeadlessFx.fx
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.*

class PickerTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = HeadlessFx.start()

  override protected def afterAll(): Unit = HeadlessFx.closeAllWindows()

  /** The stand-in scene, shown as the only window. */
  private class Fixture {
    HeadlessFx.closeAllWindows()
    val standIn: StandInScene = fx(StandInScene.show())

    def node(selector: String): Node = fx(Selectors.find(selector, None).node)

    /** Picks the node under the centre of `target`. */
    def pickAtCentreOf(target: Node): Option[Node] = fx {
      val bounds = target.localToScene(target.getLayoutBounds)
      Picker.pick(standIn.stage.getScene, bounds.getCenterX, bounds.getCenterY)
    }
  }

  /** The node and its ancestors, from the node up. */
  private def ancestry(node: Node): Seq[Node] = Iterator.iterate(node)(_.getParent).takeWhile(_ != null).toSeq

  "Picker.pick" should {
    "pick the deepest node under the point" in new Fixture {
      // Given
      private val play = node("Play")

      // When
      private val picked = pickAtCentreOf(play)

      // Then
      picked.map(ancestry).getOrElse(Seq.empty) should contain(play)
    }

    "pick a cover over a node, not the node" in new Fixture {
      // When
      private val picked = pickAtCentreOf(node("Covered"))

      // Then
      picked shouldBe Some(node("cover"))
    }

    "skip a mouse-transparent cover" in new Fixture {
      // Given
      private val covered = node("Covered")
      fx(node("cover").setMouseTransparent(true))

      // When
      private val picked = pickAtCentreOf(covered)

      // Then
      picked.map(ancestry).getOrElse(Seq.empty) should contain(covered)
    }

    "skip a disabled node" in new Fixture {
      // Given
      private val locked = node("Locked")

      // When
      private val picked = pickAtCentreOf(locked)

      // Then
      picked should not be empty
      ancestry(picked.get) should not contain locked
    }

    "skip an invisible node" in new Fixture {
      // Given
      private val hidden = fx(standIn.stage.getScene.getRoot.getChildrenUnmodifiable.asScala.collectFirst {
        case button: Button if button.getText == "Hidden" => button
      }.get)

      // When
      private val picked = pickAtCentreOf(hidden)

      // Then
      picked should not be empty
      ancestry(picked.get) should not contain hidden
    }

    "pick nothing outside the scene" in new Fixture {
      // When / Then
      fx(Picker.pick(standIn.stage.getScene, -10, -10)) shouldBe None
    }
  }
}

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
import javafx.scene.control.Labeled

/** Which of a node's properties a selector matched. */
enum MatchKind(val wireName: String) {
  /** The node's `accessibleText`. */
  case AccessibleText extends MatchKind("accessibleText")

  /** The node's `id`. */
  case Id extends MatchKind("id")

  /** A `Labeled`'s visible text, such as a button's, a label's or a list cell's. */
  case Text extends MatchKind("text")
}

/** The single node that a selector names, and which of its properties matched. */
final case class SelectorMatch(node: Node, matchedBy: MatchKind)

/**
 * Resolves selectors to the visible nodes they name.
 *
 * A selector is a string that a node's `accessibleText` equals; failing that, its `id`; failing that, a `Labeled`'s
 * text.
 */
object Selectors {
  /**
   * Finds the single visible node that a selector names, among the nodes of every showing window or of the one at the
   * given index; must run on the JavaFX Application Thread.
   *
   * @throws DriverException if there is no window at the given index, if no visible node matches, or if several nodes
   *   match by the first kind that matches any
   */
  def find(selector: String, window: Option[Int]): SelectorMatch = {
    val candidates = for {
      index <- SceneReader.indices(window)
      node <- SceneReader.visibleNodes(SceneReader.window(index))
    } yield (node, index)
    val firstMatches = MatchKind.values.iterator.map { kind =>
      kind -> candidates.filter { (node, _) => property(node, kind).contains(selector) }
    }.find { (_, matches) => matches.nonEmpty }

    firstMatches match {
      case None => throw DriverException(s"""no visible node matches "$selector"""")
      case Some((kind, Seq((node, _)))) => SelectorMatch(node, kind)
      case Some((kind, matches)) =>
        val lines = matches.map { (node, index) => s"  ${SceneReader.describe(node)} in window $index" }
        throw DriverException(lines.mkString(s"""several visible nodes match "$selector" by ${kind.wireName}:\n""",
          "\n", ""))
    }
  }

  private def property(node: Node, kind: MatchKind): Option[String] = kind match {
    case MatchKind.AccessibleText => Option(node.getAccessibleText)
    case MatchKind.Id => Option(node.getId)
    case MatchKind.Text => node match {
        case labeled: Labeled => Option(labeled.getText)
        case _ => None
      }
  }
}

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

import javafx.scene.canvas.Canvas
import javafx.scene.control.{Cell, Control, Labeled, TextInputControl}
import javafx.scene.text.Text
import javafx.scene.{Node, Parent}
import javafx.stage.{PopupWindow, Stage, Window}
import play.api.libs.json.{JsObject, Json}

import scala.jdk.CollectionConverters.*

/**
 * Reads the app's windows and their scene graphs.
 *
 * Windows are numbered by their index in `Window.getWindows`, the showing windows in the order they were shown; a menu,
 * a tooltip or a dialog is a window of its own. Every method must run on the JavaFX Application Thread.
 */
object SceneReader {
  /**
   * Lists the windows, as `{"windows":[{"index","type","title"?,"x","y","width","height","focused","owner"?}]}`, where
   * `title` is a stage's and `owner` is the index of a popup's owner window.
   */
  def windows(): JsObject = {
    val all = Window.getWindows.asScala.toSeq
    Json.obj("windows" -> all.zipWithIndex.map { (window, index) =>
      val owner = window match {
        case popup: PopupWindow => Json.obj("owner" -> all.indexOf(popup.getOwnerWindow))
        case _ => Json.obj()
      }
      header(window, index) ++ Json.obj(
        "x" -> window.getX,
        "y" -> window.getY,
        "width" -> window.getWidth,
        "height" -> window.getHeight,
        "focused" -> window.isFocused,
      ) ++ owner
    })
  }

  /**
   * Dumps the scene graph of every window, or of the one at the given index, as
   * `{"windows":[{"index","type","title"?,"nodes":[<line>, …]}]}`.
   *
   * The lines are the visible controls, nodes with an ID and canvases, each described by [[describe]] and indented by
   * two spaces per such ancestor.
   *
   * @throws DriverException if there is no window at the given index
   */
  def dump(window: Option[Int]): JsObject = {
    val indices = window.fold(Window.getWindows.asScala.indices) { index =>
      this.window(index)
      Seq(index)
    }
    Json.obj("windows" -> indices.map { index =>
      val shownNodes = visibleNodes(this.window(index)).filter(isShown)
      header(this.window(index), index) ++ Json.obj("nodes" -> shownNodes.map { node =>
        "  " * ancestors(node).count(isShown) + describe(node)
      })
    })
  }

  /**
   * Describes a node in one line, without indentation:
   * `<Type>[ #<id>][ text="…"][ value="…"][ accessibleText="…"][ selected][ focused][ disabled][ truncated]`.
   */
  def describe(node: Node): String = {
    val text = node match {
      case labeled: Labeled => Option(labeled.getText).filter(_.nonEmpty).map { text => s"""text="$text"""" }
      case _ => None
    }
    val value = node match {
      case input: TextInputControl => Some(s"""value="${input.getText}"""")
      case _ => None
    }
    val isSelected = node match {
      case cell: Cell[?] => cell.isSelected
      case _ => false
    }
    val isTruncated = node match {
      case labeled: Labeled => SceneReader.isTruncated(labeled)
      case _ => false
    }
    val attributes = Seq(
      Option(node.getId).map { id => s"#$id" },
      text,
      value,
      Option(node.getAccessibleText).map { accessibleText => s"""accessibleText="$accessibleText"""" },
      Option.when(isSelected)("selected"),
      Option.when(node.isFocused)("focused"),
      Option.when(node.isDisabled)("disabled"),
      Option.when(isTruncated)("truncated"),
    ).flatten

    (typeName(node.getClass) +: attributes).mkString(" ")
  }

  /** The nodes of a window's scene that are visible with every ancestor, in pre-order, leaving out the empty cells. */
  def visibleNodes(window: Window): Seq[Node] = Option(window.getScene).toSeq.flatMap { scene =>
    visibleTree(scene.getRoot)
  }

  /**
   * The window at the given index.
   *
   * @throws DriverException if there is none
   */
  def window(index: Int): Window = {
    val windows = Window.getWindows
    if (index < 0 || index >= windows.size) {
      throw DriverException(s"no window $index; there are ${windows.size}")
    }
    windows.get(index)
  }

  private def header(window: Window, index: Int): JsObject = {
    val title = window match {
      case stage: Stage => Option(stage.getTitle).fold(Json.obj()) { title => Json.obj("title" -> title) }
      case _ => Json.obj()
    }
    Json.obj("index" -> index, "type" -> typeName(window.getClass)) ++ title
  }

  private def visibleTree(node: Node): Seq[Node] = node match {
    case _ if !node.isVisible => Seq.empty
    case cell: Cell[?] if cell.isEmpty => Seq.empty
    case parent: Parent => parent +: parent.getChildrenUnmodifiable.asScala.toSeq.flatMap(visibleTree)
    case leaf => Seq(leaf)
  }

  /** Whether a node goes into the dump: a control, a node with an ID or a canvas. */
  private def isShown(node: Node): Boolean = {
    node.isInstanceOf[Control] || node.getId != null || node.isInstanceOf[Canvas]
  }

  private def ancestors(node: Node): Iterator[Node] = Iterator.iterate(node.getParent)(_.getParent).takeWhile(_ != null)

  /** The simple name of a class, or of its closest named superclass, since skins create anonymous classes. */
  private def typeName(clazz: Class[?]): String = {
    Iterator.iterate(clazz)(_.getSuperclass).find(_.getSimpleName.nonEmpty).get.getSimpleName
  }

  /** Whether a labeled shows its text cut by an ellipsis. */
  private def isTruncated(labeled: Labeled): Boolean = labeled.lookupAll(".text").asScala.exists {
    case text: Text => text.getText != labeled.getText && text.getText.endsWith(labeled.getEllipsisString)
    case _ => false
  }
}

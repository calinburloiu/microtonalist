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

import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.scene.input.{KeyCombination, MouseButton}
import javafx.scene.layout.{Background, Pane, StackPane, VBox}
import javafx.scene.paint.Color
import javafx.scene.shape.Polygon
import javafx.stage.Stage

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A stand-in for the app's GUI, which the driver's tests drive: a 480×640 stage titled "Stand-in".
 *
 * Its controls record what they do in [[actions]]:
 *
 *   - "Play" records `play`; "Next tuning", a graphic-only button, `next`; "Locked", disabled, `locked`; "Covered",
 *     under a pane that covers it, `covered`; "Hidden", invisible, `hidden`;
 *   - the list's context menu records `copy` and `rename`;
 *   - a double-click on a row of the list records `open <row text>`;
 *   - "Confirm" records `confirm` and then shows, with `showAndWait`, an Alert titled "Confirm";
 *   - `Shortcut+N` records `new` and shows an Alert titled "New".
 *
 * Construct it on the FX thread, with [[StandInScene.show]].
 */
final class StandInScene private () {
  /** What the controls did, in order. */
  val actions: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue()

  /** The list `#tunings`, with its first row selected. */
  val tunings: ListView[String] = ListView(FXCollections.observableArrayList(
    "C Just Major", "C Romanian Minor", "C Septimal"))
  tunings.setId("tunings")
  tunings.setPrefHeight(120)
  tunings.getSelectionModel.select(0)
  tunings.setContextMenu(ContextMenu(
    menuItem("Copy name", "copy"),
    menuItem("Rename…", "rename"),
  ))
  tunings.setOnMouseClicked { event =>
    if (event.getButton == MouseButton.PRIMARY && event.getClickCount == 2) {
      actions.add(s"open ${tunings.getSelectionModel.getSelectedItem}")
    }
  }

  private val nextTuning = button("", "next")
  nextTuning.setGraphic(Polygon(0, 0, 12, 6, 0, 12))
  nextTuning.setAccessibleText("Next tuning")

  private val status = Label("C Just Major")
  status.setId("status")

  private val long = Label("A text much too long to fit in sixty pixels")
  long.setId("long")
  long.setMinWidth(60)
  long.setPrefWidth(60)
  long.setMaxWidth(60)

  private val name = TextField()
  name.setId("name")

  private val hidden = button("Hidden", "hidden")
  hidden.setVisible(false)

  private val locked = button("Locked", "locked")
  locked.setDisable(true)

  private val confirm = Button("Confirm")
  confirm.setOnAction { _ =>
    actions.add("confirm")
    alert("Confirm").showAndWait()
  }

  private val cover = Pane()
  cover.setId("cover")
  cover.setBackground(Background.fill(Color.gray(0.5, 0.5)))

  /** The stage, shown. */
  val stage: Stage = Stage()
  stage.setTitle("Stand-in")
  private val scene = Scene(VBox(8, tunings, button("Play", "play"), nextTuning, status, long, name, hidden, locked,
    confirm, StackPane(button("Covered", "covered"), cover)))
  scene.getAccelerators.put(KeyCombination.keyCombination("Shortcut+N"),
    { () =>
      actions.add("new")
      alert("New").show()
    })
  stage.setScene(scene)
  stage.setWidth(480)
  stage.setHeight(640)
  stage.show()

  private def button(text: String, action: String): Button = {
    val button = Button(text)
    button.setOnAction(_ => actions.add(action))
    button
  }

  private def menuItem(text: String, action: String): MenuItem = {
    val item = MenuItem(text)
    item.setOnAction(_ => actions.add(action))
    item
  }

  private def alert(title: String): Alert = {
    val alert = Alert(AlertType.CONFIRMATION, s"$title?")
    alert.initOwner(stage)
    alert.setTitle(title)
    alert
  }
}

object StandInScene {
  /** Builds and shows the stand-in scene; call it on the FX thread, e.g. inside `HeadlessFx.fx`. */
  def show(): StandInScene = StandInScene()
}

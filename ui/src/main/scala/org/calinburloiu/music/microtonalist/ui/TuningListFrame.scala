/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.ui

import com.google.common.eventbus.Subscribe
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.tuner.{TuningChangedEvent, TuningSwitcher}

import java.awt.{BorderLayout, FlowLayout}
import java.awt.event.{KeyEvent, KeyListener}
import javax.swing._
import javax.swing.event.ListSelectionEvent

class TuningListFrame(tuningSwitch: TuningSwitcher) extends JFrame("Microtuner") with StrictLogging {

  private[this] val tuningList = tuningSwitch.tuningList

  private[this] val panel = new JPanel(new BorderLayout())

  private[this] val listModel = new AbstractListModel[String] {
    override def getSize: Int = tuningList.tunings.size

    override def getElementAt(index: Int): String = tuningList.tunings(index).name
  }

  private[this] val listComponent: JList[String] = new JList[String](listModel)

  listComponent.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  listComponent.addListSelectionListener { listSelectionEvent: ListSelectionEvent =>
    val index = listSelectionEvent.getSource.asInstanceOf[JList[String]].getSelectedIndex

    if (!listSelectionEvent.getValueIsAdjusting && index != -1) {
      tuningSwitch(index)
    }
  }
  listComponent.setSelectedIndex(0)
  listComponent.addKeyListener(new KeyListener {
    override def keyPressed(keyEvent: KeyEvent): Unit = {
      val code = keyEvent.getKeyCode

      code match {
        case KeyEvent.VK_UP =>
          if (listComponent.getSelectedIndex == 0) {
            keyEvent.consume()
            listComponent.setSelectedIndex(listComponent.getModel.getSize - 1)
          }

        case KeyEvent.VK_DOWN =>
          if (listComponent.getSelectedIndex == listComponent.getModel.getSize - 1) {
            keyEvent.consume()
            listComponent.setSelectedIndex(0)
          }

        case key if key >= KeyEvent.VK_1 && key <= KeyEvent.VK_9 =>
          val number = key - KeyEvent.VK_1
          listComponent.setSelectedIndex(number)

        // Do nothing for other keys.
        case _ =>
      }
    }

    override def keyTyped(e: KeyEvent): Unit = {}

    override def keyReleased(e: KeyEvent): Unit = {}
  })

  panel.add(new JScrollPane(listComponent), BorderLayout.CENTER)

  add(panel)
  setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
  setSize(480, 640)

  @Subscribe
  def handleTuningChanged(tuningChangedEvent: TuningChangedEvent): Unit = {
    val TuningChangedEvent(tuningIndex, oldTuningIndex) = tuningChangedEvent
    logger.debug(this.getClass.getSimpleName +
      s" received tuning change event from tuning index $oldTuningIndex to $tuningIndex")

    listComponent.setSelectedIndex(tuningIndex)
  }
}

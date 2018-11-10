package org.calinburloiu.music.microtuner

import java.awt.FlowLayout
import java.awt.event.{KeyEvent, KeyListener}
import javax.swing._
import javax.swing.event.ListSelectionEvent

import com.typesafe.scalalogging.StrictLogging


class TuningListFrame(tuningSwitch: TuningSwitch) extends JFrame("Microtuner") with StrictLogging {

  private[this] val tuningList = tuningSwitch.tuningList

  private[this] val panel = new JPanel(new FlowLayout())

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

        // Do nothing for other keys.
        case _ =>
      }
    }

    override def keyTyped(e: KeyEvent): Unit = {}

    override def keyReleased(e: KeyEvent): Unit = {}
  })

  panel.add(listComponent)

  add(panel)
  setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
  setSize(300, 300)
}

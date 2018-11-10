package org.calinburloiu.music.microtuner

import javax.sound.midi.{MidiMessage, Receiver, ShortMessage}

import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable

class PedalTuningSwitchReceiver(
  val tuningSwitch: TuningSwitch,
  val forwardReceiver: Receiver,
  val ccPrev: Int = 67,
  val ccNext: Int = 66
) extends Receiver with StrictLogging {

  private[this] val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map(ccPrev -> false, ccNext -> false)

  override def send(message: MidiMessage, timeStamp: Long): Unit = message match {
    case shortMessage: ShortMessage =>
      val command = shortMessage.getCommand
      val cc = shortMessage.getData1
      val ccValue = shortMessage.getData2

      // Filter Control Change messages used for switching and forward any other message
      if (command == ShortMessage.CONTROL_CHANGE && ccDepressed.contains(cc)) {
        if (!ccDepressed(cc) && ccValue > 0) {
          ccDepressed(cc) = true
          if (cc == ccPrev)
            tuningSwitch.prev()
          else
            tuningSwitch.next()
        } else if (ccDepressed(cc) && ccValue == 0) {
          ccDepressed(cc) = false
        }
      } else {
        // Forward
        forwardReceiver.send(message, timeStamp)
      }
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}

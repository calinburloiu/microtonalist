package org.calinburloiu.music.microtuner.midi

import com.typesafe.scalalogging.StrictLogging
import javax.sound.midi.{MidiMessage, Receiver, ShortMessage}
import org.calinburloiu.music.microtuner.TuningSwitch

import scala.collection.mutable

class PedalTuningSwitchReceiver(
  tuningSwitch: TuningSwitch,
  forwardReceiver: Receiver,
  midiInputConfig: MidiInputConfig
) extends Receiver with StrictLogging {

  private val ccPrev = midiInputConfig.ccTriggers.prevTuningCc
  private val ccNext = midiInputConfig.ccTriggers.nextTuningCc
  private val ccTriggerThreshold = midiInputConfig.ccTriggers.ccThreshold
  private val isFilteringCcTriggersInOutput: Boolean = midiInputConfig.ccTriggers.isFilteringInOutput
  private val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map(ccPrev -> false, ccNext -> false)

  override def send(message: MidiMessage, timeStamp: Long): Unit = message match {
    case shortMessage: ShortMessage =>
      val command = shortMessage.getCommand
      val cc = shortMessage.getData1
      val ccValue = shortMessage.getData2

      // Capture Control Change messages used for switching and forward any other message
      if (command == ShortMessage.CONTROL_CHANGE && ccDepressed.contains(cc)) {
        if (!ccDepressed(cc) && ccValue > ccTriggerThreshold) {
          ccDepressed(cc) = true
          if (cc == ccPrev)
            tuningSwitch.prev()
          else
            tuningSwitch.next()
        } else if (ccDepressed(cc) && ccValue <= ccTriggerThreshold) {
          ccDepressed(cc) = false
        }

        // Forward if configured so
        if (!isFilteringCcTriggersInOutput) {
          forwardReceiver.send(message, timeStamp)
        }
      } else {
        // Forward
        forwardReceiver.send(message, timeStamp)
      }
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}

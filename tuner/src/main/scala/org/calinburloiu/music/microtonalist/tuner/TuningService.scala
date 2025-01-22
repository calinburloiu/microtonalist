/*
 * Copyright 2025 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.tuner

import com.google.common.eventbus.Subscribe
import com.google.common.math.IntMath
import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.calinburloiu.music.scmidi.{MidiProcessor, ScCcMidiMessage}

import javax.sound.midi.{MidiMessage, ShortMessage}
import scala.collection.mutable

// TODO #95 Move things to separate files

/**
 * A [[TuningChanger]] decision that controls to which [[org.calinburloiu.music.microtonalist.composition.Tuning]]
 * from the tuning list should the [[Tuner]] tune.
 */
sealed trait TuningChange {
  def isChanging: Boolean
}

/**
 * A [[TuningChanger]] decision to not change the tuning.
 */
case object NoTuningChange extends TuningChange {
  override def isChanging: Boolean = false
}

case object PreviousTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}

case object NextTuningChange extends TuningChange {
  override def isChanging: Boolean = true
}

case class IndexTuningChange(index: Int) extends TuningChange {
  require(index >= 0, "Tuning index must be equal or greater than 0!")

  override def isChanging: Boolean = true
}


abstract class TuningChanger extends Plugin {
  override val familyName: String = "tuningChanger"

  def decide(message: MidiMessage): TuningChange
}

class CcTuningChanger(val previousTuningCc: Int = 67,
                      val nextTuningCc: Int = 66,
                      val threshold: Int = 0) extends TuningChanger with LazyLogging {
  override val typeName: String = "cc"

  private val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map(
    previousTuningCc -> false, nextTuningCc -> false)

  override def decide(message: MidiMessage): TuningChange = message match {
    case shortMessage: ShortMessage =>
      val command = shortMessage.getCommand
      val cc = shortMessage.getData1
      val ccValue = shortMessage.getData2

      // Capture Control Change messages used for switching and forward any other message
      if (command == ShortMessage.CONTROL_CHANGE && ccDepressed.contains(cc)) {
        if (!ccDepressed(cc) && ccValue > threshold) {
          start(cc)
        } else if (ccDepressed(cc) && ccValue <= threshold) {
          stop(cc)
        } else {
          logger.warn(s"Illegal state: ccDepressed($cc)=${ccDepressed(cc)}, ccValue=$ccValue, threshold=$threshold")
          NoTuningChange
        }
      } else {
        // No trigger detected; ignoring.
        NoTuningChange
      }
    case _ =>
      // No trigger detected; ignoring.
      NoTuningChange
  }

  private def start(cc: Int): TuningChange = {
    ccDepressed(cc) = true

    if (cc == previousTuningCc) {
      PreviousTuningChange
    } else if (cc == nextTuningCc) {
      NextTuningChange
    } else {
      NoTuningChange
    }
  }

  private def stop(cc: Int): TuningChange = {
    ccDepressed(cc) = false

    NoTuningChange
  }
}

object CcTuningChanger {
  def apply(previousTuningCc: Int, nextTuningCc: Int, threshold: Int = 0): CcTuningChanger =
    new CcTuningChanger(previousTuningCc, nextTuningCc, threshold)
}

class PedalTuningChanger(threshold: Int)
  extends CcTuningChanger(ScCcMidiMessage.SoftPedal, ScCcMidiMessage.SostenutoPedal, threshold) {
  override val typeName: String = "pedal"
}


// TODO #95 Remove extends from TuningSwitchProcessor.

/**
 *
 * @param triggersThru Whether tuning change MIDI trigger messages should pass through to the output or if they
 *                     should be filtered out.
 */
class TuningChangeProcessor(tuningService: TuningService,
                            tuningChanger: TuningChanger,
                            triggersThru: Boolean) extends MidiProcessor with TuningSwitchProcessor {

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    val tuningChange = tuningChanger.decide(message)

    tuningService.changeTuning(tuningChange)

    // Forward message if:
    //   - It's not a tuning change trigger;
    //   - triggersThru is set.
    if (!tuningChange.isChanging || triggersThru) {
      receiver.send(message, timeStamp)
    }
  }
}

// TODO #95 Logic to update tracks.
class TrackManager(private val tracks: Seq[Track]) {
  // TODO #90 Remove @Subscribe after implementing to businessync.
  @Subscribe
  def onTuningChanged(event: TuningChangedEvent): Unit = for (track <- tracks) {
    track.tune(event.tuning)
  }
}


class TuningService(session: TuningSession, businessync: Businessync) {

  def changeTuning(tuningChange: TuningChange): Unit = {
    if (tuningChange.isChanging) {
      businessync.run { () =>
        tuningChange match {
          case PreviousTuningChange => session.previousTuning()
          case NextTuningChange => session.nextTuning()
          case IndexTuningChange(index) => session.tuningIndex = index
          case NoTuningChange => // Unreachable, see above. Added to make the match exhaustive.
        }
      }
    }
  }

  // TODO #99 No need to expose this after publishing messages from domain model to GUI.
  @deprecated
  def tunings: Seq[OctaveTuning] = session.tunings
}

class TuningSession(businessync: Businessync) {
  private var _tunings: Seq[OctaveTuning] = Seq()
  private var _tuningIndex: Int = 0

  def tunings: Seq[OctaveTuning] = _tunings

  def tunings_=(value: Seq[OctaveTuning]): Unit = {
    _tunings = value

    val oldTuningIndex = _tuningIndex
    _tuningIndex = Math.min(_tuningIndex, value.size - 1)

    publish(oldTuningIndex)
  }

  def tuningIndex: Int = _tuningIndex

  def tuningIndex_=(index: Int): Unit = {
    if (index < 0 || index > _tunings.size - 1) {
      throw new IllegalArgumentException(s"Expecting tuning index to be between 0 and ${_tunings.size - 1}!")
    } else if (index != _tuningIndex) {
      val oldTuningIndex = _tuningIndex
      _tuningIndex = index

      publish(oldTuningIndex)
    }
  }

  def currentTuning: OctaveTuning = tunings.lift(tuningIndex).getOrElse(OctaveTuning.Edo12)

  def tuningCount: Int = tunings.size

  def previousTuning(): Int = {
    nextBy(-1)
  }

  def nextTuning(): Int = {
    nextBy(1)
  }

  def nextBy(step: Int): Int = {
    tuningIndex = IntMath.mod(tuningIndex + step, tuningCount)
    _tuningIndex
  }

  private def publish(oldTuningIndex: Int): Unit = if (tuningIndex != oldTuningIndex) {
    businessync.publish(TuningChangedEvent(tunings(tuningIndex), tuningIndex, oldTuningIndex))
  }
}

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

import org.calinburloiu.music.scmidi.MidiProcessor

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage
import scala.annotation.tailrec

/**
 * Processes incoming messages and decides whether to trigger a tuning change or not based on the incoming MIDI
 * messages and the given [[TuningChanger]] plugins.
 *
 * @param tuningService  The service to trigger the actual tuning change.
 * @param tuningChangers A sequence of [[TuningChanger]] plugins that decide whether the tuning should be changed or
 *                       not. The decision is of the first one that returns an effective [[TuningChange]], so this
 *                       class acts like an OR operator. Note that if none decides to trigger a change, no change
 *                       will be performed.
 * @param triggersThru   Whether tuning change MIDI trigger messages should pass through to the output or if they
 *                       should be filtered out.
 */
@NotThreadSafe
class TuningChangeProcessor(tuningService: TuningService,
                            val tuningChangers: Seq[TuningChanger],
                            val triggersThru: Boolean) extends MidiProcessor {
  require(tuningChangers.nonEmpty, "There should be at least one TuningChanger!")

  /**
   * Convenience auxiliary constructor that allows a single tuning changer.
   *
   * @see the main constructor for details.
   */
  def this(tuningService: TuningService, tuningChanger: TuningChanger, triggersThru: Boolean) = {
    this(tuningService, Seq(tuningChanger), triggersThru)
  }

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    val tuningChange = TuningChangeProcessor.computeTuningChange(message, tuningChangers.toList)

    tuningService.changeTuning(tuningChange)

    // Forward message if:
    //   - triggersThru is set;
    //   - It's not a potential trigger for a tuning change.
    if (triggersThru || !mayTrigger(message)) {
      receiver.send(message, timeStamp)
    }
  }

  private def mayTrigger(message: MidiMessage): Boolean = tuningChangers.exists(_.mayTrigger(message))
}

object TuningChangeProcessor {
  /**
   * Chooses the first [[TuningChanger]] from the given list that returns an effective [[TuningChange]] (which has
   * [[TuningChange#isChanging]] true) for the given MIDI message and returns it. If there is none,
   * [[NoTuningChange]] is returned.
   */
  @tailrec
  private def computeTuningChange(message: MidiMessage, tuningChangers: List[TuningChanger]): TuningChange = {
    if (tuningChangers.isEmpty) {
      NoTuningChange
    } else {
      val tuningChanger = tuningChangers.head
      val tuningChange = tuningChanger.decide(message)
      if (tuningChange.isChanging) {
        tuningChange
      } else {
        computeTuningChange(message, tuningChangers.tail)
      }
    }
  }
}

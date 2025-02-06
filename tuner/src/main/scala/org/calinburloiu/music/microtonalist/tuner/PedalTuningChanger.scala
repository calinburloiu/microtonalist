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

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.microtonalist.tuner.PedalTuningChanger.Cc
import org.calinburloiu.music.scmidi.ScCcMidiMessage

import javax.sound.midi.MidiMessage
import scala.collection.mutable

/**
 * PedalTuningChanger is responsible for deciding tuning changes based on MIDI pedal-like CC (Control Change) inputs,
 * similar to those of the piano pedals which return to their initial position after they are pressed.
 *
 * It uses a set of MIDI Control Change (CC) messages as triggers to effect specific tuning changes. Triggers can be
 * configured for each [[TuningChange]].
 *
 * The class tracks the state of the pedal inputs and decides whether to initiate a tuning change or not based on a
 * configurable threshold. The change is triggered as soon as the CC value increases over the [[threshold]] (pressed
 * state). After the CC value drops to the threshold value or below (released state), a new increase over the threshold
 * (pressed state) will trigger the change again.
 *
 * @param triggers     The configuration of MIDI CC triggers that determine tuning changes.
 *                     These can include triggers for previous, next tuning changes,
 *                     or specific index-based tuning changes.
 * @param threshold    The threshold value for the pedal input to determine if a pedal is pressed
 *                     or released. Values above this threshold indicate a pressed state,
 *                     while values below or equal indicate a released state. Tuning changes are
 *                     only triggered when the state transitions from released to pressed.
 * @param triggersThru Whether tuning change MIDI trigger messages should pass through to the output or
 *                     if they should be filtered out.
 */
case class PedalTuningChanger(triggers: TuningChangeTriggers[Cc],
                              threshold: Int,
                              override val triggersThru: Boolean) extends TuningChanger with LazyLogging {
  override val typeName: String = "pedal"

  /**
   * @return the CC trigger, if available, that effects a change to the next tuning.
   */
  def previousTuningCcTrigger: Option[Cc] = triggers.previous

  /**
   * @return the CC trigger, if available, that effects a change to the previous tuning.
   */
  def nextTuningCcTrigger: Option[Cc] = triggers.next

  /**
   * A mutable map that tracks the state of pedals, corresponding MIDI control change (CC) messages. A pedal may be
   * in a pressed state, `true` value, or released state, `false` value.
   */
  private val ccDepressed: mutable.Map[Int, Boolean] = mutable.Map
    .newBuilder[Int, Boolean]
    .addAll(
      Seq(triggers.previous, triggers.next, triggers.index.values)
        .flatten.map(cc => cc -> false)
    )
    .result()

  override def decide(message: MidiMessage): TuningChange = message match {
    case ScCcMidiMessage(_, cc, ccValue) if ccDepressed.contains(cc) =>
      // Capture Control Change messages used for triggering a tuning change
      val isCcPressed = isPressed(cc)
      if (!isCcPressed && ccValue > threshold) {
        press(cc)
      } else if (isCcPressed && ccValue <= threshold) {
        release(cc)
      } else {
        // Intermediary CC value with no state transition
        MayTriggerTuningChange
      }
    case _ =>
      // No trigger detected; ignoring.
      NoTuningChange
  }

  override def reset(): Unit = {
    for ((cc, _) <- ccDepressed) {
      ccDepressed(cc) = false
    }
  }

  /**
   * Determines whether the pedal for the specified Control Change (CC) is currently pressed.
   *
   * @param cc The Control Change (CC) number to check.
   * @return true if the specified CC is pressed, false otherwise.
   */
  def isPressed(cc: Int): Boolean = ccDepressed(cc)

  private def press(cc: Int): TuningChange = {
    ccDepressed(cc) = true
    triggers.tuningChangeForTrigger(cc)
  }

  private def release(cc: Int): TuningChange = {
    ccDepressed(cc) = false
    MayTriggerTuningChange
  }
}

object PedalTuningChanger {
  /**
   * Represents a Control Change (CC) number type as an alias for `Int`.
   */
  type Cc = Int

  val DefaultTuningChangeTriggers: TuningChangeTriggers[Cc] = TuningChangeTriggers(
    previous = Some(ScCcMidiMessage.SoftPedal),
    next = Some(ScCcMidiMessage.SostenutoPedal)
  )

  /**
   * Convenience factory that creates an instance of `PedalTuningChanger` with specified MIDI Control Change (CC)
   * triggers for previous and next tuning changes, and a threshold to determine pedal press or release.
   *
   * @param previousTuningCcTrigger The MIDI CC number that triggers a change to the previous tuning.
   *                                Defaults to `ScCcMidiMessage.SoftPedal`.
   * @param nextTuningCcTrigger     The MIDI CC number that triggers a change to the next tuning.
   *                                Defaults to `ScCcMidiMessage.SostenutoPedal`.
   * @param threshold               The threshold value for determining whether the CC value
   *                                represents a pedal press or release. Defaults to `0`.
   * @param triggersThru            Whether tuning change MIDI trigger messages should pass through to the output or
   *                                if they should be filtered out.
   * @return An instance of `PedalTuningChanger` configured with the specified parameters.
   */
  def apply(previousTuningCcTrigger: Int = ScCcMidiMessage.SoftPedal,
            nextTuningCcTrigger: Int = ScCcMidiMessage.SostenutoPedal,
            threshold: Int = 0,
            triggersThru: Boolean = false): PedalTuningChanger = {
    new PedalTuningChanger(
      TuningChangeTriggers(next = Some(nextTuningCcTrigger), previous = Some(previousTuningCcTrigger)),
      threshold, triggersThru
    )
  }
}

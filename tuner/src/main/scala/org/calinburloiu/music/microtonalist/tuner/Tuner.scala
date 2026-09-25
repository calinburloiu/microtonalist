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

package org.calinburloiu.music.microtonalist.tuner

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.tuner.Tuner.FamilyName
import org.calinburloiu.music.scmidi.MidiDeviceId
import org.calinburloiu.music.scmidi.message.{MidiCc, MidiMsg}

import javax.annotation.concurrent.NotThreadSafe

/**
 * [[Track]] plugin responsible for tuning an output instrument based on a specific protocol.
 *
 * The interface only provides the means to generate MIDI messages, sending them is outside the scope of this trait.
 *
 * The flow is as follows:
 *
 *   - When the tuner starts to be used with a [[Track]], the [[reset]] method must first be called to configure the
 *     output device to be used with this tuner configuration. For example, a [[Tuner]] based on pitch bend might want
 *     to set the right pitch bend sensitivity.
 *   - Whenever the current tuning changes, [[tune]] must be called with that tuning. The tuner clamps a tuning
 *     beyond its limits to them, which [[canTune]] tells in advance, and logs a warning.
 *   - All MIDI messages that pass through the [[Track]] must go through the [[process]] method which will output the
 *     right MIDI messages according to the tuner's functionality and configuration.
 *   - If necessary, [[reset]] may be called any time to clear the tuner's state and reconfigure it, while keeping
 *     its current [[tuning]]. For example, it may be called if the MIDI configuration of the output device was
 *     externally modified, and it needs to be reconfigured according to this tuner, or if the output device was
 *     turned off and on again. The method may also be called in case of a bug or issue for troubleshooting purposes
 *     to reset the system state.
 *
 * Implementations provide [[canTune]], [[onReset]] and [[onTune]], which the final [[reset]] and [[tune]] delegate
 * to, so that every tuner keeps its current tuning and restates it on reset.
 *
 * @see [[TunerProcessor]] a class that uses a [[Tuner]] instance and adds the necessary I/O operations to be able to
 *      actually tune a device.
 */
@NotThreadSafe
trait Tuner extends Plugin with StrictLogging {
  override val familyName: String = FamilyName

  /**
   * Optional MIDI output identifier for an alternative MIDI output device designated for sending the MIDI tuning
   * messages.
   *
   * If specified, this device will be used exclusively for tuning operations instead of the actual MIDI output. If
   * not, the actual MIDI output will be used.
   *
   * Note that there are [[Tuner]]s for which setting this to [[Some]] value doesn't make sense, because in their
   * case tuning values alone do not have any effect without the other messages. This is the case for tuners that use
   * pitch bend.
   */
  val altTuningOutput: Option[MidiDeviceId] = None

  private var _tuning: Tuning = Tuning.Standard

  /**
   * @return the tuning last applied by [[tune]], which the tuner keeps across [[reset]]s, or the Standard 12-EDO
   *         Tuning if it was never tuned.
   */
  final def tuning: Tuning = _tuning

  /**
   * Tells whether this tuner can apply the given tuning exactly, within the resolution of its protocol and without
   * clamping any of its offsets, in its current configuration. Tuners have different limits, such as the range of a
   * tuning value in an MTS message or the Pitch Bend Sensitivity of a tuner based on Pitch Bend, and some of these
   * limits may change as the tuner processes messages.
   *
   * [[tune]] and [[reset]] still apply a tuning for which this returns `false`, clamping its offsets to the limits of
   * the tuner, and log a warning.
   *
   * @param tuning The tuning instance that specifies the offset in cents for each of the 12 pitch classes in the
   *               octave.
   * @return `true` if the tuner would apply the tuning exactly, `false` if it would clamp it.
   */
  def canTune(tuning: Tuning): Boolean

  /**
   * Resets the internal state of the tuner to its default / initial configuration, while keeping its current
   * [[tuning]], and returns the MIDI messages that should (re)configure the output device to be usable by this tuner,
   * followed by the ones that tune it to the current tuning.
   *
   * This method ''must'' be called before using the tuner for the first time and the messages returned ''must'' be
   * sent to the output device to properly work with this tuner. For example, a tuner based on pitch bend requires
   * the output device to be configured with the correct pitch bend sensitivity. Because it restates the current
   * tuning, an output device that is reset after it (re)opens plays in that tuning again, not in 12-EDO.
   *
   * If [[canTune]] does not accept the current tuning once [[onReset]] returned the configuration to its default,
   * which may decrease a limit of the tuner, the tuner logs a warning and restates the tuning clamped to its limits.
   *
   * @return the MIDI messages returned by [[onReset]], followed by the ones returned by [[onTune]] for the current
   *         tuning.
   */
  final def reset(): Seq[MidiMsg] = {
    // `onReset` runs first, since it may change the limits that `applyTuning` checks the tuning against
    val resetMessages = onReset()
    resetMessages ++ applyTuning(_tuning, None)
  }

  /**
   * Generates MIDI messages, if any, for tuning an output instrument by using the specified tuning object, and stores
   * it as the current [[tuning]], such that MIDI notes passed via [[process]] method will be played in that tuning.
   *
   * A tuning that the tuner cannot apply exactly, for which [[canTune]] returns `false`, is clamped to the limits of
   * the tuner, which logs a warning.
   *
   * @param tuning The tuning instance that specifies the offset in cents for each of the 12 pitch classes in the
   *               octave.
   * @return the MIDI messages returned by [[onTune]] for the tuning.
   */
  final def tune(tuning: Tuning): Seq[MidiMsg] = {
    val previousTuning = _tuning
    _tuning = tuning
    applyTuning(tuning, Some(previousTuning))
  }

  /** Tunes to the given tuning with [[onTune]], after warning if the tuner has to clamp it to its limits. */
  private def applyTuning(tuning: Tuning, previousTuning: Option[Tuning]): Seq[MidiMsg] = {
    // TODO #326 Check each tuning against the tuner when the tunings are loaded, rather than only warning here
    if (!canTune(tuning)) {
      logger.warn(s"""The "$typeName" tuner cannot tune exactly to $tuning, so it clamps it to its limits.""")
    }

    onTune(tuning, previousTuning)
  }

  /**
   * Resets the internal state of the tuner to its default / initial configuration and returns the MIDI messages that
   * should (re)configure the output device, as the first part of [[reset]], which then calls [[onTune]] with the
   * current tuning.
   *
   * The state a tuner derives from its tuning must be reset as well, so that the [[onTune]] call that follows
   * rebuilds it.
   *
   * A tuner that tracks what the output device plays must first stop it: it sends a Note Off for every note it has
   * sounding and returns the controls that hold a state, such as the pedals, to their defaults, so that the output
   * device is not left with hanging notes once the state tracking them is cleared.
   *
   * @return the MIDI messages that stop what the output device plays, if the tuner tracks it, followed by the ones
   *         that should (re)configure the output device.
   */
  protected def onReset(): Seq[MidiMsg] = Seq.empty

  /**
   * Generates MIDI messages, if any, for tuning an output instrument by using the specified tuning object and
   * potentially stores state about the given tuning such that MIDI notes passed via [[process]] method will be
   * played in that tuning.
   *
   * It is called by [[tune]], after the tuning becomes the current [[tuning]], and by [[reset]] to restate the
   * current tuning. Implementations tune to the `tuning` argument.
   *
   * The tuning may be one that [[canTune]] does not accept: [[tune]] applies any tuning, and a limit of the tuner may
   * decrease after a tuning was applied, for example when [[onReset]] returns a Pitch Bend Sensitivity to its default.
   * Implementations must clamp the offsets beyond a limit rather than throw, here as whenever a limit decreases below
   * an offset of the current tuning.
   *
   * An implementation that sends only what changed from `previousTuning` must send the whole tuning when it is
   * `None`: [[reset]] restates the tuning to an output device whose tuning is unknown, for example because it was
   * turned off and on again.
   *
   * @param tuning         The tuning instance that specifies the offset in cents for each of the 12 pitch classes in
   *                       the octave.
   * @param previousTuning `Some` tuning the tuner was in before this call when called by [[tune]], or `None` when
   *                       called by [[reset]], after which the tuning of the output instrument is unknown.
   * @return the MIDI messages that tune the output instrument.
   */
  protected def onTune(tuning: Tuning, previousTuning: Option[Tuning]): Seq[MidiMsg]

  /**
   * Method called with every MIDI message of a [[Track]] that uses this tuner. Its purpose is to do any processing
   * required to those incoming MIDI messages and generates the right sequence of output MIDI messages to achieve the
   * tuner's goal.
   *
   * Usually, the goal is to tune the passed MIDI notes to the tuning provided to [[tune]] method (e.g. tuners based
   * on pitch bend will add pitch bend messages). But some tuners, such as those based on MTS, which send the tuning
   * in advance, do not alter incoming note messages, but might still want to do some processing to them based on the
   * tuner's configuration.
   *
   * @param message The incoming MIDI message to be processed.
   * @return A sequence of MIDI messages to be sent to the output device, possibly modified
   *         or generated depending on the tuner's functionality and configuration.
   */
  def process(message: MidiMsg): Seq[MidiMsg]
}

object Tuner {
  val FamilyName: String = "tuner"

  /**
   * The pedals that keep notes sounding after their Note Off, Sustain and Sostenuto, which a tuner that tracks them
   * releases on reset.
   */
  private[tuner] val NoteHoldingPedals: Seq[Int] = Seq(MidiCc.SustainPedal, MidiCc.SostenutoPedal)
}

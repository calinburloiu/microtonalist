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

import org.calinburloiu.music.microtonalist.common.Plugin
import org.calinburloiu.music.microtonalist.tuner.Tuner.FamilyName
import org.calinburloiu.music.scmidi.MidiDeviceId

import javax.annotation.concurrent.NotThreadSafe
import javax.sound.midi.MidiMessage

/**
 * [[Track]] plugin responsible for tuning an output instrument based on a specific protocol.
 *
 * The interface only provides the means to generate MIDI messages, sending them is outside the scope of this trait.
 *
 * The flow is as follows:
 *
 *   - When the tuner starts to be used with a [[Track]], the [[reset]] method must initially be called to initialize
 *     or configure the output device to be used with this tuner configuration. For example, a [[Tuner]] based on
 *     pitch bend might want to set the right pitch bend sensitivity.
 *   - Whenever the current tuning changes, [[tune]] must be called with that tuning.
 *   - All MIDI messages that pass through the [[Track]] must go through the [[process]] method which will output the
 *     right MIDI messages according to the tuner's functionality and configuration.
 *   - If necessary, [[reset]] may be called any time to clear the tuner's state and reinitialize it. For example, it
 *     may be called if the MIDI configuration of the output device was externally modified, and it needs to be
 *     reconfigured according to this tuner. The method may also be called in case of a bug or issue for
 *     troubleshooting purposes to reset the system state.
 *
 * @see [[TunerProcessor]] a class that uses a [[Tuner]] instance and adds the necessary I/O operations to be able to
 *      actually tune a device.
 */
@NotThreadSafe
trait Tuner extends Plugin {
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

  /**
   * Resets the internal state of the tuner to its default / initial configuration and returns the MIDI messages that
   * should configure / initialize the output device to be usable by this tuner.
   *
   * This method ''must'' be called before using the tuner for the first time and the messages returned ''must'' be
   * sent to the output device to properly work with this tuner. For example, a tuner based on pitch bend requires
   * the output device to be configured with the correct pitch bend sensitivity.
   *
   * @return the MIDI messages that should configure / initialize the output device.
   */
  def reset(): Seq[MidiMessage] = Seq.empty

  /**
   * Generates MIDI messages, if any, for tuning an output instrument by using the specified tuning object and
   * potentially stores state about the given tuning such that MIDI notes passed via [[process]] method will be
   * played in that tuning.
   *
   * @param tuning The tuning instance that specifies the deviation in cents for each of the 12 pitch classes in the
   *               octave.
   */
  def tune(tuning: Tuning): Seq[MidiMessage]

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
  def process(message: MidiMessage): Seq[MidiMessage]
}

object Tuner {
  val FamilyName: String = "tuner"
}

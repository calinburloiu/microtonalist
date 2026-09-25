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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.message.MidiMsg

import scala.collection.mutable

/**
 * A [[Tuner]] test double that returns canned messages and records what it is asked to tune to and to process. It is
 * not thread-safe.
 *
 * @param resetMessages    What its [[onReset]] returns, which a reset follows with the messages of the current tuning.
 * @param tuningMessages   What its [[onTune]] returns for each of these tunings; it returns nothing for any other.
 * @param processMessages  What [[process]] returns for each of these messages; it returns nothing for any other.
 * @param untunableTunings The tunings its [[canTune]] rejects; it accepts any other. A test may change them to model
 *                         a limit of the tuner that decreases after it applied a tuning.
 */
class FakeTuner(resetMessages: Seq[MidiMsg] = Seq.empty,
                tuningMessages: Map[Tuning, Seq[MidiMsg]] = Map.empty,
                processMessages: Map[MidiMsg, Seq[MidiMsg]] = Map.empty,
                var untunableTunings: Set[Tuning] = Set.empty) extends Tuner {
  override val typeName: String = "fake"

  private val _appliedTunings: mutable.Buffer[Tuning] = mutable.ArrayBuffer()
  private val _previousTunings: mutable.Buffer[Option[Tuning]] = mutable.ArrayBuffer()
  private val _processedMessages: mutable.Buffer[MidiMsg] = mutable.ArrayBuffer()

  /** The tunings passed to [[onTune]] so far, in order, including the ones a reset restates. */
  def appliedTunings: Seq[Tuning] = _appliedTunings.toSeq

  /** The previous tunings passed to [[onTune]] so far, in order, one for each of [[appliedTunings]]. */
  def previousTunings: Seq[Option[Tuning]] = _previousTunings.toSeq

  /** The messages passed to [[process]] so far, in order. */
  def processedMessages: Seq[MidiMsg] = _processedMessages.toSeq

  override def canTune(tuning: Tuning): Boolean = !untunableTunings.contains(tuning)

  override protected def onReset(): Seq[MidiMsg] = resetMessages

  override protected def onTune(tuning: Tuning, previousTuning: Option[Tuning]): Seq[MidiMsg] = {
    _appliedTunings += tuning
    _previousTunings += previousTuning
    tuningMessages.getOrElse(tuning, Seq.empty)
  }

  override def process(message: MidiMsg): Seq[MidiMsg] = {
    _processedMessages += message
    processMessages.getOrElse(message, Seq.empty)
  }
}

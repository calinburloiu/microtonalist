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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.ScMidiMessage

/**
 * Scala-idiomatic counterpart of [[javax.sound.midi.Receiver]] that consumes [[ScMidiMessage]] instances directly,
 * without first having to wrap or unwrap [[javax.sound.midi.MidiMessage]] objects.
 *
 * Implementations may be stateful (e.g. tracking the current MIDI state) or stateless (e.g. forwarding to another
 * sink). Once [[close]] is called, [[send]] should become a no-op.
 */
trait ScMidiReceiver extends AutoCloseable {
  /**
   * Sends a MIDI message to this receiver.
   *
   * @param message   the MIDI message to send.
   * @param timeStamp the time-stamp for the message, in microseconds; `-1L` indicates that time-stamping is not
   *                  supported by this receiver.
   */
  def send(message: ScMidiMessage, timeStamp: Long = -1L): Unit

  /**
   * Releases any resources held by this receiver. Subsequent calls to [[send]] become a no-op.
   */
  override def close(): Unit
}

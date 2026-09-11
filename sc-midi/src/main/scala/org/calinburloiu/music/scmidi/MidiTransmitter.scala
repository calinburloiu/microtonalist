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

/**
 * Source of MIDI messages that fans out to a sequence of [[MidiReceiver]]s.
 *
 * This is the read-only view: it exposes the current receivers and nothing else, so a consumer that only forwards
 * messages (e.g. a splitter) does not depend on how, or whether, the sequence can change. Implementations decide the
 * mutation policy:
 *
 *   - [[ImmutableMidiTransmitter]] — a value; changes produce new instances.
 *   - [[MutableMidiTransmitter]] — in-place changes, for a single thread.
 *   - [[ConcurrentMidiTransmitter]] — in-place changes from any thread.
 *
 * Unlike its Java counterpart, this trait carries no `close()`: none of the three implementations above holds
 * a resource of its own. An implementation that ever does can mix in `AutoCloseable` itself.
 *
 * @see [[javax.sound.midi.Transmitter]], which allows a single receiver only.
 */
trait MidiTransmitter {
  /**
   * The receivers messages are currently forwarded to.
   *
   * @return an immutable snapshot; later changes to the transmitter do not affect a sequence already returned.
   */
  def receivers: Seq[MidiReceiver]
}

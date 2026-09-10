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

package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * A [[MidiReceiver]] that forwards every message it gets to every receiver of a [[MidiTransmitter]].
 *
 * The caller chooses the transmitter and, with it, whether and how the receivers can change: an
 * [[ImmutableMidiTransmitter]] for a fixed fan-out, a [[MutableMidiTransmitter]] on a single thread, a
 * [[ConcurrentMidiTransmitter]] when receivers are added from other threads. The splitter does not own the
 * transmitter and never closes it.
 *
 * @param transmitter the transmitter whose receivers get every message.
 */
class MidiSplitter(val transmitter: MidiTransmitter) extends MidiReceiver {

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    transmitter.receivers.foreach(_.send(message, timeStamp))
  }
}

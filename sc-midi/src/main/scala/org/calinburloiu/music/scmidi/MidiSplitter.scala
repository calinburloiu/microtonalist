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

import javax.sound.midi.{MidiMessage, Receiver}

/**
 * `MidiSplitter` is a MIDI [[Receiver]] that forwards all MIDI messages to a configurable set of [[Receiver]]s.
 *
 * @param initialReceivers Sequence of initial [[Receiver]] instances to which MIDI messages will be sent. The
 *                         sequence may be mutated if necessary after the class instantiation.
 */
class MidiSplitter(initialReceivers: Seq[Receiver] = Seq.empty) extends AutoCloseable {

  val receiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = {
      multiTransmitter.receivers.foreach(_.send(message, timeStamp))
    }

    override def close(): Unit = {}
  }

  val multiTransmitter: MultiTransmitter = new MultiTransmitter {
    override def close(): Unit = {}
  }
  multiTransmitter.receivers = initialReceivers

  override def close(): Unit = {
    receiver.close()
    multiTransmitter.close()
  }
}

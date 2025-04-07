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

import com.typesafe.scalalogging.StrictLogging

import javax.sound.midi.{MidiMessage, Receiver}

/**
 * A [[MidiProcessor]] that connects a sequence of [[MidiProcessor]]s in a chain ending with the [[Receiver]] set.
 *
 * {{{
 *   MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> Receiver
 * }}}
 *
 * @param processors [[MidiProcessor]]s to execute in sequence
 */
class MidiSerialProcessor(processors: Seq[MidiProcessor]) extends MidiProcessor with StrictLogging {

  def this(processors: Seq[MidiProcessor], initialOutputReceiver: Receiver) = {
    this(processors)
    transmitter.receiver = initialOutputReceiver
  }

  protected override def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = {
    // If there is at least one processor, then messages will flow through processors until the output receivers due
    // to the way they are wired, so there is no need to return anything. But if processors is empty, we return the
    // input such that forwarding to the output receiver is handled by MidiProcessorReceiver.
    processors.headOption match {
      case Some(firstProcessor) =>
        firstProcessor.receiver.send(message, -1)

        Seq.empty
      case None =>
        Seq(message)
    }
  }

  override def close(): Unit = {
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
  }

  def size: Int = processors.size

  override protected def onConnect(): Unit = {
    for (i <- 1 until size) {
      processors(i - 1).transmitter.receiver = processors(i).receiver
    }
    if (size > 0) {
      processors(size - 1).transmitter.receiver = outputReceiver.orNull
    }
  }

  override protected def onDisconnect(): Unit = {}
}

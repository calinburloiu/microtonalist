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
import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}
import javax.sound.midi.{MidiMessage, Receiver}

/**
 * A [[MidiProcessor]] that connects a sequence of [[MidiProcessor]]s in a chain ending with the [[Receiver]] set on 
 * its [[Transmitter]].
 *
 * {{{
 *   MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> MidiProcessorTransmitter#receiver
 * }}}
 *
 * @param initialProcessors The initialization value for the [[MidiProcessor]]s to execute in sequence.
 */
class MidiSerialProcessor(initialProcessors: Seq[MidiProcessor],
                          initialOutputReceiver: Option[Receiver]) extends MidiProcessor, Locking, StrictLogging {
  private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

  private var _processors: Seq[MidiProcessor] = initialProcessors

  transmitter.receiver = initialOutputReceiver
  wireAll()

  protected override def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] = {
    // If there is at least one processor, then messages will flow through processors towards the output receivers due
    // to the way they are wired, so there is no need to return anything. But if processors is empty, we return the
    // input such that forwarding to the output receiver is handled by MidiProcessor#MidiProcessorReceiver.
    processors.headOption match {
      case Some(firstProcessor) =>
        firstProcessor.receiver.send(message, -1)

        Seq.empty
      case None =>
        Seq(message)
    }
  }

  /**
   * Retrieves the sequence of MIDI processors that are chained.
   *
   * @return A sequence of MIDI processors.
   */
  def processors: Seq[MidiProcessor] = withReadLock {
    _processors
  }

  /**
   * Sets the sequence of MIDI processors that are chained.
   *
   * @param processors A sequence of MIDI processors to be set.
   */
  def processors_=(processors: Seq[MidiProcessor]): Unit = withWriteLock {
    _processors = processors

    wireAll()
  }

  /**
   * Inserts a MIDI processor at the specified index in the chain of processors.
   *
   * @param index     The position at which the processor should be inserted.
   * @param processor The MIDI processor to be inserted.
   */
  def insert(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors.patch(index, Seq(processor), 0)

    wireProcessor(index)
  }

  /**
   * Appends a MIDI processor to the end of the processors chain.
   *
   * @param processor The MIDI processor to be appended.
   */
  def append(processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors :+ processor

    wireProcessor(size - 1)
  }

  /**
   * Updates the MIDI processor at the specified index in the sequence of chained processors.
   *
   * @param index     The 0-based position of the processor to be updated.
   * @param processor The new MIDI processor to replace the existing one at the specified index.
   */
  def update(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors.updated(index, processor)

    wireProcessor(index)
  }

  /**
   * Removes the specified MIDI processor from the sequence of chained processors if it exists.
   *
   * @param processor The MIDI processor to be removed.
   */
  def remove(processor: MidiProcessor): Unit = withWriteLock {
    val index = _processors.indexOf(processor)
    if (index != -1) removeAt(index)
  }

  /**
   * Removes the MIDI processor at the specified index from the sequence of chained processors.
   *
   * @param index The 0-based position of the processor to be removed.
   */
  def removeAt(index: Int): Unit = withWriteLock {
    val processor = processors(index)

    _processors = _processors.patch(index, Seq.empty, 1)

    wireProcessorToPrevious(index)

    processor.transmitter.setReceiver(null)
  }

  /**
   * Clears all MIDI processors in the chain and disconnects their transmitters.
   */
  def clear(): Unit = withWriteLock {
    _processors.foreach(_.transmitter.setReceiver(null))

    _processors = Seq.empty
  }

  /**
   * @return the number of MIDI processors in the chain.
   */
  def size: Int = processors.size

  override def close(): Unit = {
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
    _processors.foreach(_.transmitter.setReceiver(null))
  }

  override protected def onConnect(): Unit = wireOutput()

  override protected def onDisconnect(): Unit = wireOutput()

  /**
   * Wires a processor at the specified index to neighboring processors or the MIDI output as needed.
   *
   * @param index The index of the processor to wire. Must be between 0 and size - 1.
   */
  private def wireProcessor(index: Int): Unit = withWriteLock {
    require(0 <= index && index < size, s"index should be between 0 and size=$size - 1")

    if (index > 0) {
      wireProcessorToPrevious(index)
    }

    val nextIndex = index + 1
    if (nextIndex == size) {
      wireOutput()
    } else {
      wireProcessorToPrevious(nextIndex)
    }
  }

  /**
   * Wires all MIDI processors in the chain together sequentially, ensuring correct data flow
   * between adjacent processors and from the last processor to the output.
   */
  private def wireAll(): Unit = withWriteLock {
    for (i <- 1 until size) {
      wireProcessorToPrevious(i)
    }

    wireOutput()
  }

  /**
   * Wires the current processor at the specified index to the previous processor in the chain, 
   * enabling data flow between them.
   *
   * @param index The index of the processor to be connected to its predecessor. Must be between 1 and size - 1.
   */
  private def wireProcessorToPrevious(index: Int): Unit = withWriteLock {
    require(1 <= index && index < size, s"index should be between 0 and size=$size - 1")

    processors(index - 1).transmitter.receiver = Some(processors(index).receiver)
  }

  /**
   * Wires the output receiver of the last MIDI processor in the chain to the transmitter's receiver,
   * ensuring proper data flow from the processors to the MIDI output.
   */
  private def wireOutput(): Unit = withWriteLock {
    if (size > 0) {
      processors.last.transmitter.receiver = transmitter.receiver
    }
  }
}

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

import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.message.MidiMsg

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}

/**
 * A [[MidiProcessor]] that connects a sequence of [[MidiProcessor]]s in a chain ending with the receivers of its own
 * [[transmitter]].
 *
 * {{{
 *   MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> transmitter.receivers
 * }}}
 *
 * Every mutation of the chain rewires the neighbours; the last processor's transmitter always carries this
 * processor's output receivers, so a change of those propagates to it through [[onReceiversChanged]]. It is that
 * hook, rather than [[onConnect]] / [[onDisconnect]], because the whole sequence has to be mirrored and not only the
 * receivers a change adds or drops; the last processor's own transmitter then runs the connect / disconnect protocol
 * over the mirrored sequence, so each of those receivers is still initialized and cleaned up exactly once.
 *
 * Lock ordering: the hook takes this processor's lock while the transmitter's write lock is held, whereas the
 * chain modifiers take this processor's lock first and read the transmitter inside it. Mutating the chain and the
 * output receivers of the same instance from two threads at once could therefore deadlock; today both happen on the
 * business thread only. #121 gives each track one thread and removes the concern.
 *
 * @param initialProcessors      The [[MidiProcessor]]s to execute in sequence.
 * @param initialOutputReceivers The receivers of the [[transmitter]] at construction.
 */
class MidiSerialProcessor(initialProcessors: Seq[MidiProcessor],
                          initialOutputReceivers: Seq[MidiReceiver] = Seq.empty)
  extends MidiProcessor, Locking {
  private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

  private var _processors: Seq[MidiProcessor] = Seq.empty

  // The output receivers go in while the chain is still empty, so that the change hook they fire finds nothing to
  // wire; assigning the processors below is then the one and only wiring pass of the construction.
  transmitter.receivers = initialOutputReceivers
  processors = initialProcessors

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
    _processors.foreach(_.transmitter.clearReceivers())

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
    val oldProcessor = processors(index)
    oldProcessor.transmitter.clearReceivers()

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
    if (0 <= index && index < size) {
      val processor = processors(index)

      _processors = _processors.patch(index, Seq.empty, 1)

      wireProcessorToPrevious(index)
      // Note that after the remove the size is smaller with 1, that's why we check against size, not size - 1
      if (index == size) wireOutput()

      processor.transmitter.clearReceivers()
    } else if (index < 0) {
      throw IllegalArgumentException(s"index should be non-negative, but was $index")
    }
  }

  /**
   * Clears all MIDI processors in the chain and disconnects their transmitters.
   */
  def clear(): Unit = withWriteLock {
    _processors.foreach(_.transmitter.clearReceivers())

    _processors = Seq.empty
  }

  /**
   * @return the number of MIDI processors in the chain.
   */
  def size: Int = processors.size

  protected override def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = {
    // If there is at least one processor, then messages will flow through processors towards the output receivers due
    // to the way they are wired, so there is no need to return anything. But if processors is empty, we return the
    // input such that forwarding to the output receivers is handled by MidiProcessor#MidiProcessorReceiver.
    processors.headOption match {
      case Some(firstProcessor) =>
        firstProcessor.receiver.send(message, -1)

        Seq.empty
      case None =>
        Seq(message)
    }
  }

  /**
   * Mirrors this processor's output receivers onto the last processor of the chain, which then runs its own
   * connect / disconnect protocol over them.
   */
  override protected def onReceiversChanged(receivers: Seq[MidiReceiver]): Unit = wireOutput()

  /**
   * Wires a processor at the specified index to neighboring processors or the MIDI output as needed.
   *
   * @param index The index of the processor to wire. Must be between 0 and size - 1.
   */
  private def wireProcessor(_index: Int): Unit = withWriteLock {
    require(0 <= _index, s"index should be positive")
    val index = _index.min(size - 1)

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
  private def wireProcessorToPrevious(_index: Int): Unit = withWriteLock {
    require(1 <= _index, s"index should be greater or equal to 1")
    val index = _index.min(size - 1)

    processors(index - 1).transmitter.receivers = Seq(processors(index).receiver)
  }

  /**
   * Wires the output receivers of this processor's transmitter to the transmitter of the last MIDI processor in the
   * chain, ensuring proper data flow from the processors to the MIDI output.
   */
  private def wireOutput(): Unit = withWriteLock {
    if (size > 0) {
      processors.last.transmitter.receivers = transmitter.receivers
    }
  }
}

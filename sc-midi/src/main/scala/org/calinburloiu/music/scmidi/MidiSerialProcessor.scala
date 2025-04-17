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
 * A [[MidiProcessor]] that connects a sequence of [[MidiProcessor]]s in a chain ending with the [[Receiver]] set.
 *
 * {{{
 *   MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> Receiver
 * }}}
 *
 * @param initialProcessors The initialization value for the [[MidiProcessor]]s to execute in sequence.
 */
class MidiSerialProcessor(initialProcessors: Seq[MidiProcessor]) extends MidiProcessor, Locking, StrictLogging {
  private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

  private var _processors: Seq[MidiProcessor] = initialProcessors

  def this(processors: Seq[MidiProcessor], initialOutputReceiver: Receiver) = {
    this(processors)
    transmitter.receiver = Some(initialOutputReceiver)
  }

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

  def processors: Seq[MidiProcessor] = withReadLock {
    _processors
  }

  def processors_=(processors: Seq[MidiProcessor]): Unit = withWriteLock {
    _processors = processors

    wireAll()
  }

  def insert(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors.patch(index, Seq(processor), 0)

    wireProcessor(index)
  }

  def append(processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors :+ processor

    wireProcessor(size - 1)
  }

  def update(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors.updated(index, processor)

    wireProcessor(index)
  }

  def remove(processor: MidiProcessor): Unit = withWriteLock {
    val index = _processors.indexOf(processor)
    if (index != -1) removeAt(index)
  }

  def removeAt(index: Int): Unit = withWriteLock {
    val processor = processors(index)

    _processors = _processors.patch(index, Seq.empty, 1)

    wireProcessorToPrevious(index)

    processor.transmitter.setReceiver(null)
  }

  def clear(): Unit = withWriteLock {
    _processors.foreach(_.transmitter.setReceiver(null))

    _processors = Seq.empty
  }

  def size: Int = processors.size

  override def close(): Unit = {
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
    _processors.foreach(_.transmitter.setReceiver(null))
  }

  override protected def onConnect(): Unit = wireOutput()

  override protected def onDisconnect(): Unit = wireOutput()

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

  private def wireAll(): Unit = withWriteLock {
    for (i <- 1 until size) {
      wireProcessorToPrevious(i)
    }

    wireOutput()
  }

  private def wireProcessorToPrevious(index: Int): Unit = withWriteLock {
    require(1 <= index && index < size, s"index should be between 0 and size=$size - 1")

    processors(index - 1).transmitter.receiver = Some(processors(index).receiver)
  }

  private def wireOutput(): Unit = withWriteLock {
    if (size > 0) {
      processors.last.transmitter.receiver = transmitter.receiver
    }
  }
}

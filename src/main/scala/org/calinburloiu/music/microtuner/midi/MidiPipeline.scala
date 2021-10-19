package org.calinburloiu.music.microtuner.midi

import com.typesafe.scalalogging.StrictLogging

import javax.sound.midi.{MidiMessage, Receiver}
import scala.annotation.tailrec

// TODO Consider renaming to `MidiSerial` to allow a future `MidiParallel`
/**
 * MIDI [[Receiver]] that can execute a chain of [[MidiProcessor]]s.
 * @param processors [[MidiProcessor]]s to execute in sequence
 * @param outputReceiver MIDI output where the messages resulted from the chain are sent
 */
class MidiPipeline(processors: Seq[MidiProcessor], outputReceiver: Receiver) extends Receiver with StrictLogging {
  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    @tailrec
    def process(processorsLeft: Seq[MidiProcessor], messages: Seq[MidiMessage]): Seq[MidiMessage] = {
      if (processorsLeft.isEmpty) {
        messages
      } else {
        val processor = processorsLeft.head
        process(processorsLeft.tail, messages.flatMap(processor.processMessage(_, timeStamp)))
      }
    }

    val messages = process(processors, Seq(message))
    messages.foreach(outputReceiver.send(_, timeStamp))
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}

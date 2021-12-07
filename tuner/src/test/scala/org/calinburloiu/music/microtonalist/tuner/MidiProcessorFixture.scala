package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.MidiProcessor

import javax.sound.midi.{MidiMessage, Receiver, ShortMessage}
import scala.collection.mutable

/**
 * Fixture-context trait to testing a `MidiProcessor`
 */
trait MidiProcessorFixture[P <: MidiProcessor] {
  val midiProcessor: P

  private val _output: mutable.Buffer[MidiMessage] = mutable.Buffer()

  def connect(): Unit = {
    midiProcessor.receiver = new Receiver {
      override def send(message: MidiMessage, timeStamp: Long): Unit = {
        _output += message
      }

      override def close(): Unit = {}
    }
  }

  def output: Seq[MidiMessage] = _output.toSeq

  def shortMessageOutput: Seq[ShortMessage] = output.collect { case shortMessage: ShortMessage => shortMessage }

  def resetOutput(): Unit = _output.clear()
}

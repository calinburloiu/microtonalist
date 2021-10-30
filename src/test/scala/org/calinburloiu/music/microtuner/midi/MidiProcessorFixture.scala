/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner.midi

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

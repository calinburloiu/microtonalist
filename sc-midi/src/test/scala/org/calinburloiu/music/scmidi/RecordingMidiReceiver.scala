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

import org.calinburloiu.music.scmidi.message.MidiMsg

import scala.collection.mutable

/** A [[MidiReceiver]] that records every message sent to it, with its time stamp. It is not thread-safe. */
class RecordingMidiReceiver extends MidiReceiver {
  private val _messages: mutable.Buffer[(MidiMsg, Long)] = mutable.ArrayBuffer()

  /** The messages received so far, in order, each with its time stamp. */
  def messages: Seq[(MidiMsg, Long)] = _messages.toSeq

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    _messages += (message -> timeStamp)
  }
}

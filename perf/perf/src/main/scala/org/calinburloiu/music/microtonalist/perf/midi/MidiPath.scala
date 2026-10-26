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

package org.calinburloiu.music.microtonalist.perf.midi

import org.calinburloiu.music.scmidi.MidiReceiver

/** The two ends of the MIDI path the suite measures: where it sends, and where it listens. */
trait MidiPath {
  /** Sends to the start of the path. */
  def sender: MidiReceiver

  /** Starts delivering what comes out of the path to `receiver`. */
  def attach(receiver: MidiReceiver): Unit

  /** Stops delivering to `receiver`. */
  def detach(receiver: MidiReceiver): Unit
}

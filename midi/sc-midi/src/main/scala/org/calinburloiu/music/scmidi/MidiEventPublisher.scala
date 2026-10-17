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

/**
 * Receives the [[MidiEvent]]s that a [[MidiManager]] publishes, e.g. to forward them to an event bus.
 *
 * The manager calls [[publish]] synchronously, on the thread that made the change: the caller's for one of its
 * operations, a platform thread for a change in the environment. An implementation must therefore be thread-safe.
 */
trait MidiEventPublisher {

  /**
   * Publishes an event of the manager.
   *
   * @param event What changed in the MIDI environment or in one of the manager's devices.
   */
  def publish(event: MidiEvent): Unit
}

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
 * How many transmitters or receivers a MIDI device can open at once, that is, how many consumers can subscribe to
 * the messages it sends or how many producers can send messages to it.
 *
 * Java Sound encodes "unlimited" as `-1`; the Java Sound implementation maps that to [[Unlimited]], so the API never
 * carries the sentinel. A [[Limited]] count is expected to be non-negative, but the type does not enforce it.
 */
enum MidiConnectionLimit {
  /** The device opens as many connections as requested. */
  case Unlimited

  /** The device opens at most `count` connections; `Limited(0)` means it cannot be used in that direction. */
  case Limited(count: Int)

  /**
   * @return whether at least one connection can be opened, that is, whether the device can be used in the
   *         direction this limit describes.
   */
  def allowsConnections: Boolean = this match {
    case Unlimited => true
    case Limited(count) => count > 0
  }

  /** Prints as `unlimited` or as the count, which is how the `cli` lists devices. */
  override def toString: String = this match {
    case Unlimited => "unlimited"
    case Limited(count) => count.toString
  }
}

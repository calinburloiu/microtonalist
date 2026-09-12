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

package org.calinburloiu.music.scmidi.javamidi

import javax.sound.midi.MidiDevice

/**
 * A [[JavaMidiEnvironment]] test double: a test plugs and unplugs devices, then either refreshes the manager itself or
 * reports the change through [[notifyChanged]], as CoreMIDI4J does after a device is plugged in or unplugged.
 *
 * It is not thread-safe; tests drive it from a single thread.
 */
class FakeJavaMidiEnvironment extends JavaMidiEnvironment {

  private var entries: Vector[(MidiDevice.Info, () => MidiDevice)] = Vector.empty
  private var handlers: Vector[() => Unit] = Vector.empty

  /** Makes `device` present in the environment, resolvable from its info. */
  def plug(device: MidiDevice): Unit = {
    entries :+= (device.getDeviceInfo -> (() => device))
  }

  /** Makes a device described by `info` present in the environment, but failing to resolve with `failure`. */
  def plugUnresolvable(info: MidiDevice.Info, failure: Exception): Unit = {
    entries :+= (info -> (() => throw failure))
  }

  /** Removes the device described by `info` from the environment. */
  def unplug(info: MidiDevice.Info): Unit = {
    entries = entries.filterNot { case (entryInfo, _) => entryInfo eq info }
  }

  /** Reports a change of the environment to every subscribed handler. */
  def notifyChanged(): Unit = handlers.foreach(handler => handler())

  /** The number of handlers currently subscribed to environment changes. */
  def subscriberCount: Int = handlers.size

  override def deviceInfos: Seq[MidiDevice.Info] = entries.map { case (info, _) => info }

  override def deviceOf(info: MidiDevice.Info): MidiDevice = {
    entries.find { case (entryInfo, _) => entryInfo eq info } match {
      case Some((_, resolve)) => resolve()
      case None => throw IllegalArgumentException(s"$info does not describe a device of this environment")
    }
  }

  override def subscribeToEnvironmentChanged(handler: () => Unit): AutoCloseable = {
    handlers :+= handler
    () => handlers = handlers.filterNot(_ eq handler)
  }
}

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

import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util
import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.MidiDevice

/**
 * Counts the references held to each Java Sound [[MidiDevice]] instance, opening it on the first and closing it on the
 * release of the last.
 *
 * Several [[JavaMidiDeviceHandle]]s may hold the same instance: [[JavaMidiManager]] gives a device that works in both
 * directions one handle per direction, and resolves a single instance for both. Java Sound's `MidiDevice.close()`
 * closes a device outright, however many times it was opened, so a handle closing the device on its own would close it
 * under the other one. The handles of a manager therefore share one counter, which alone opens and closes their
 * devices.
 *
 * Instances are told apart by identity, not by equality. The lock of the counter is the last in the lock order of the
 * manager (refresh, manager, handle, then this one): it is only ever taken inside the lock of a handle.
 */
@ThreadSafe
private[javamidi] class JavaMidiDeviceReferenceCounter extends Locking {

  private implicit val lock: Lock = ReentrantLock()

  private val referenceCounts: util.IdentityHashMap[MidiDevice, Int] = util.IdentityHashMap()

  /**
   * Takes one reference to `javaDevice`, opening it if it is the first.
   *
   * @throws Exception whatever `MidiDevice.open()` throws, in which case no reference is taken.
   */
  def acquire(javaDevice: MidiDevice): Unit = withLock {
    val referenceCount = referenceCountOf(javaDevice)
    if (referenceCount == 0) {
      javaDevice.open()
    }
    referenceCounts.put(javaDevice, referenceCount + 1)
  }

  /**
   * Releases one reference to `javaDevice`, closing it if it was the last. With no reference held, it does nothing.
   *
   * @throws Exception whatever `MidiDevice.close()` throws, in which case the reference is released all the same.
   */
  def release(javaDevice: MidiDevice): Unit = withLock {
    referenceCountOf(javaDevice) match {
      case 0 =>
      case 1 =>
        referenceCounts.remove(javaDevice)
        javaDevice.close()
      case referenceCount =>
        referenceCounts.put(javaDevice, referenceCount - 1)
    }
  }

  /**
   * Closes `javaDevice` unless a reference is held to it, for a caller that holds none and wants the device closed as
   * far as it is up to it — closing a Java Sound device that is not open being harmless.
   *
   * @throws Exception whatever `MidiDevice.close()` throws.
   */
  def closeIfUnreferenced(javaDevice: MidiDevice): Unit = withLock {
    if (referenceCountOf(javaDevice) == 0) {
      javaDevice.close()
    }
  }

  /** @return how many references are held to `javaDevice`. */
  def referenceCountOf(javaDevice: MidiDevice): Int = withLock {
    referenceCounts.getOrDefault(javaDevice, 0)
  }
}

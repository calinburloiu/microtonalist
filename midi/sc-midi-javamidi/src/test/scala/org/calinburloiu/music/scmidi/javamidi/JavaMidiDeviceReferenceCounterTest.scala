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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.MidiUnavailableException

class JavaMidiDeviceReferenceCounterTest extends AnyWordSpec with Matchers {

  private abstract class Fixture {
    val counter: JavaMidiDeviceReferenceCounter = JavaMidiDeviceReferenceCounter()
    val device: FakeMidiDevice = FakeMidiDevice("CoreMIDI4J - FP-90")
  }

  "A new JavaMidiDeviceReferenceCounter" should {
    "count no reference to any device" in new Fixture {
      // Then
      counter.referenceCountOf(device) shouldEqual 0
    }
  }

  "acquire" should {
    "open the device on the first reference only" in new Fixture {
      // When
      counter.acquire(device)
      counter.acquire(device)

      // Then
      counter.referenceCountOf(device) shouldEqual 2
      device.openCount shouldEqual 1
      device.isOpen shouldBe true
    }

    "count the references to each device instance apart" in new Fixture {
      // Given
      val otherDevice: FakeMidiDevice = FakeMidiDevice("CoreMIDI4J - FP-90")

      // When
      counter.acquire(device)
      counter.acquire(otherDevice)

      // Then
      counter.referenceCountOf(device) shouldEqual 1
      counter.referenceCountOf(otherDevice) shouldEqual 1
      otherDevice.openCount shouldEqual 1
    }

    "take no reference when the device fails to open, so that the next acquire opens it again" in new Fixture {
      // Given
      val failure: Exception = MidiUnavailableException("The device is busy")
      device.openFailure = Some(failure)

      // When / Then
      the[MidiUnavailableException] thrownBy counter.acquire(device) shouldBe theSameInstanceAs(failure)
      counter.referenceCountOf(device) shouldEqual 0

      // When
      device.openFailure = None
      counter.acquire(device)

      // Then
      counter.referenceCountOf(device) shouldEqual 1
      device.openCount shouldEqual 2
      device.isOpen shouldBe true
    }
  }

  "release" should {
    "close the device on releasing the last reference only" in new Fixture {
      // Given
      counter.acquire(device)
      counter.acquire(device)

      // When
      counter.release(device)

      // Then
      counter.referenceCountOf(device) shouldEqual 1
      device.closeCount shouldEqual 0
      device.isOpen shouldBe true

      // When
      counter.release(device)

      // Then
      counter.referenceCountOf(device) shouldEqual 0
      device.closeCount shouldEqual 1
      device.isOpen shouldBe false
    }

    "still release the last reference when the device fails to close" in new Fixture {
      // Given
      val failure: Exception = MidiUnavailableException("The device is busy")
      counter.acquire(device)
      device.closeFailure = Some(failure)

      // When / Then
      the[MidiUnavailableException] thrownBy counter.release(device) shouldBe theSameInstanceAs(failure)
      counter.referenceCountOf(device) shouldEqual 0
    }

    "do nothing when no reference is held" in new Fixture {
      // When
      counter.release(device)

      // Then
      counter.referenceCountOf(device) shouldEqual 0
      device.closeCount shouldEqual 0
    }
  }

  "closeIfUnreferenced" should {
    "close a device no reference is held to" in new Fixture {
      // When
      counter.closeIfUnreferenced(device)

      // Then
      device.closeCount shouldEqual 1
    }

    "leave open a device a reference is held to" in new Fixture {
      // Given
      counter.acquire(device)

      // When
      counter.closeIfUnreferenced(device)

      // Then
      device.closeCount shouldEqual 0
      device.isOpen shouldBe true
      counter.referenceCountOf(device) shouldEqual 1
    }
  }
}

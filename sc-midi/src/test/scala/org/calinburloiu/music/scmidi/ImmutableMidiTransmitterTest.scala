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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImmutableMidiTransmitterTest extends AnyFlatSpec with Matchers {

  trait Fixture {
    val receiver1: MidiReceiver = NoOpMidiReceiver()
    val receiver2: MidiReceiver = NoOpMidiReceiver()
    val receiver3: MidiReceiver = NoOpMidiReceiver()

    val transmitter: ImmutableMidiTransmitter = ImmutableMidiTransmitter(Seq(receiver1, receiver2))
  }

  behavior of "constructor"

  it should "default to no receivers" in {
    // When
    val transmitter = ImmutableMidiTransmitter()

    // Then
    transmitter.receivers shouldBe empty
  }

  it should "expose the receivers it was given, in order" in new Fixture {
    // Then
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  behavior of "withReceiver"

  it should "return a new transmitter with the receiver appended and leave the original unchanged" in new Fixture {
    // When
    val result = transmitter.withReceiver(receiver3)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "allow the same receiver to be added twice" in new Fixture {
    // When
    val result = transmitter.withReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver1)
  }

  behavior of "withReceivers"

  it should "return a new transmitter with all the receivers appended, in order" in new Fixture {
    // Given
    val transmitterWithOne = ImmutableMidiTransmitter(Seq(receiver1))

    // When
    val result = transmitterWithOne.withReceivers(Seq(receiver2, receiver3))

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    transmitterWithOne.receivers shouldEqual Seq(receiver1)
  }

  behavior of "withoutReceiver"

  it should "return a new transmitter without the receiver and leave the original unchanged" in new Fixture {
    // When
    val result = transmitter.withoutReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver2)
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "remove every occurrence of the receiver" in new Fixture {
    // Given
    val transmitterWithDuplicate = ImmutableMidiTransmitter(Seq(receiver1, receiver2, receiver1))

    // When
    val result = transmitterWithDuplicate.withoutReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver2)
  }

  it should "return an equal transmitter when the receiver is absent" in new Fixture {
    // When
    val result = transmitter.withoutReceiver(receiver3)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2)
    result shouldEqual transmitter
  }

  behavior of "withoutReceivers"

  it should "return a new transmitter without any of the given receivers" in new Fixture {
    // Given
    val transmitterWithThree = ImmutableMidiTransmitter(Seq(receiver1, receiver2, receiver3))

    // When
    val result = transmitterWithThree.withoutReceivers(Seq(receiver1, receiver3))

    // Then
    result.receivers shouldEqual Seq(receiver2)
    transmitterWithThree.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
  }

  behavior of "close"

  it should "be a no-op that keeps the receivers" in new Fixture {
    // When
    transmitter.close()

    // Then
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  behavior of "equality"

  it should "hold between two transmitters with the same receivers" in new Fixture {
    // When
    val other = ImmutableMidiTransmitter(Seq(receiver1, receiver2))

    // Then
    other shouldEqual transmitter
    other.withReceiver(receiver3) should not equal transmitter
  }
}

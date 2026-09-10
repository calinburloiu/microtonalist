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

/**
 * Shared behaviours for the single-thread contract of [[MutableMidiTransmitter]], to be run by the test class of
 * each implementation (the class itself and [[ConcurrentMidiTransmitter]]) via `it should behave like`.
 */
trait MutableMidiTransmitterBehaviors {
  this: AnyFlatSpec & Matchers =>

  /**
   * Runs the single-thread contract against transmitters built by `newTransmitter`.
   *
   * @param newTransmitter factory taking the initial receivers.
   */
  def mutableMidiTransmitter(newTransmitter: Seq[MidiReceiver] => MutableMidiTransmitter): Unit = {
    trait Fixture {
      val receiver1: MidiReceiver = NoOpMidiReceiver()
      val receiver2: MidiReceiver = NoOpMidiReceiver()
      val receiver3: MidiReceiver = NoOpMidiReceiver()

      val transmitter: MutableMidiTransmitter = newTransmitter(Seq.empty)
    }

    it should "start with no receivers when given an empty sequence" in new Fixture {
      // Then
      transmitter.receivers shouldBe empty
    }

    it should "expose the initial receivers it was constructed with, in order" in new Fixture {
      // When
      val initialised = newTransmitter(Seq(receiver1, receiver2))

      // Then
      initialised.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "replace all receivers when receivers is assigned" in new Fixture {
      // Given
      transmitter.addReceiver(receiver3)

      // When
      transmitter.receivers = Seq(receiver1, receiver2)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "return a snapshot that later changes do not affect" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1)
      val snapshot = transmitter.receivers

      // When
      transmitter.addReceiver(receiver2)

      // Then
      snapshot shouldEqual Seq(receiver1)
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "append a receiver with addReceiver, preserving order and allowing duplicates" in new Fixture {
      // When
      transmitter.addReceiver(receiver1)
      transmitter.addReceiver(receiver2)
      transmitter.addReceiver(receiver1)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2, receiver1)
    }

    it should "append a sequence of receivers with addReceivers, preserving order" in new Fixture {
      // Given
      transmitter.addReceiver(receiver1)

      // When
      transmitter.addReceivers(Seq(receiver2, receiver3))

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    }

    it should "remove every occurrence of a receiver with removeReceiver" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2, receiver1, receiver3)

      // When
      transmitter.removeReceiver(receiver1)

      // Then
      transmitter.receivers shouldEqual Seq(receiver2, receiver3)
    }

    it should "leave the receivers unchanged when removeReceiver is given an absent receiver" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2)

      // When
      transmitter.removeReceiver(receiver3)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "remove all receivers with clearReceivers" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2)

      // When
      transmitter.clearReceivers()

      // Then
      transmitter.receivers shouldBe empty
    }
  }
}

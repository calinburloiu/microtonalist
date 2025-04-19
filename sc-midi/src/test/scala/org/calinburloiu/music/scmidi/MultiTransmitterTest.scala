/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.Receiver

class TestMultiTransmitter extends MultiTransmitter {
  override def close(): Unit = {}
}

class MultiTransmitterTest extends AnyFlatSpec with Matchers with Stubs {

  trait Fixture {
    val transmitter: MultiTransmitter = new TestMultiTransmitter

    val receiver1: Stub[Receiver] = stub[Receiver]
    val receiver2: Stub[Receiver] = stub[Receiver]
  }

  "constructor" should "initialize with an empty list of receivers" in new Fixture {
    transmitter.receivers shouldBe empty
  }

  "receivers" should "set and get the list of receivers" in new Fixture {
    transmitter.receivers = Seq(receiver1, receiver2)
    transmitter.receivers should contain theSameElementsAs Seq(receiver1, receiver2)
  }

  "addReceiver" should "add a single receiver to the list" in new Fixture {
    transmitter.addReceiver(receiver1)
    transmitter.receivers should contain only receiver1

    transmitter.addReceiver(receiver2)
    transmitter.receivers should contain theSameElementsAs Seq(receiver1, receiver2)
  }

  "addReceivers" should "add a sequence of new receivers to the existing list" in new Fixture {
    val receiver3: Stub[Receiver] = stub[Receiver]

    transmitter.addReceivers(Seq(receiver1, receiver2))
    transmitter.receivers should contain theSameElementsAs Seq(receiver1, receiver2)

    transmitter.addReceivers(Seq(receiver3))
    transmitter.receivers should contain theSameElementsAs Seq(receiver1, receiver2, receiver3)
  }

  "removeReceiver" should "remove a specific receiver from the list" in new Fixture {
    transmitter.addReceivers(Seq(receiver1, receiver2))
    transmitter.removeReceiver(receiver1)
    transmitter.receivers should contain only receiver2

    transmitter.removeReceiver(receiver2)
    transmitter.receivers shouldBe empty
  }

  "clear" should "remove all receivers from the list" in new Fixture {
    transmitter.addReceivers(Seq(receiver1, receiver2))
    transmitter.clearReceivers()
    transmitter.receivers shouldBe empty
  }
}

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

import scala.collection.mutable

class MutableMidiTransmitterTest extends AnyFlatSpec with Matchers with MutableMidiTransmitterBehaviors {

  /**
   * Records every sequence passed to `receivers_=`, to prove each modifier funnels through the setter. Not `private`:
   * a fixture exposes a value of this type, and Scala rejects a non-private member whose type is a private class.
   */
  class SetterProbingTransmitter(initialReceivers: Seq[MidiReceiver] = Seq.empty)
    extends MutableMidiTransmitter(initialReceivers) {

    val assignedSequences: mutable.ListBuffer[Seq[MidiReceiver]] = mutable.ListBuffer()

    override def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = {
      assignedSequences += newReceivers
      super.receivers_=(newReceivers)
    }
  }

  trait ProbeFixture {
    val receiver1: MidiReceiver = NoOpMidiReceiver()
    val receiver2: MidiReceiver = NoOpMidiReceiver()

    val probe: SetterProbingTransmitter = SetterProbingTransmitter()
  }

  behavior of "MutableMidiTransmitter"

  it should behave like mutableMidiTransmitter(initialReceivers => MutableMidiTransmitter(initialReceivers))

  it should "not call receivers_= from its constructor" in {
    // Given
    val receiver = NoOpMidiReceiver()

    // When
    val probe = SetterProbingTransmitter(Seq(receiver))

    // Then
    probe.assignedSequences shouldBe empty
    probe.receivers shouldEqual Seq(receiver)
  }

  it should "funnel every modifier through receivers_= with the resulting sequence" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)
    probe.addReceivers(Seq(receiver2, receiver1))
    probe.removeReceiver(receiver1)
    probe.clearReceivers()

    // Then
    probe.assignedSequences.toSeq shouldEqual Seq(
      Seq(receiver1),
      Seq(receiver1, receiver2, receiver1),
      Seq(receiver2),
      Seq.empty,
    )
  }
}

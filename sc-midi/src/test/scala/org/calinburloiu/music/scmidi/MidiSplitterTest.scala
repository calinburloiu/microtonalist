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
import org.scalatest.matchers.should.Matchers.shouldEqual

import javax.sound.midi.{MidiMessage, Receiver}

class MidiSplitterTest extends AnyFlatSpec, Matchers, Stubs {
  trait Fixture {
    val splitter: MidiSplitter = new MidiSplitter()

    val receiverStub1: Stub[Receiver] = stub[Receiver]
    val receiverStub2: Stub[Receiver] = stub[Receiver]
    val receiverStub3: Stub[Receiver] = stub[Receiver]
    val receiverStubs: Seq[Stub[Receiver]] = Seq(receiverStub1, receiverStub2, receiverStub3)
    receiverStubs.foreach { receiverStub =>
      receiverStub.send.returns(_ => ())
    }
  }

  "constructor" should "populate the populate the receivers with an initial sequence" in new Fixture {
    // When
    val customSplitter = new MidiSplitter(Seq(receiverStub1, receiverStub2, receiverStub3))
    // Then
    customSplitter.receivers should contain theSameElementsAs Seq(receiverStub1, receiverStub2, receiverStub3)
    splitter.receivers shouldBe empty
  }

  "receivers" should "be modifiable" in new Fixture {
    // Given
    splitter.receivers shouldBe empty

    // When
    splitter.addReceiver(receiverStub3)
    // Then
    splitter.receivers should contain theSameElementsAs Seq(receiverStub3)

    // When
    splitter.addReceivers(Seq(receiverStub1, receiverStub2))
    // Then
    splitter.receivers should contain theSameElementsAs Seq(receiverStub1, receiverStub2, receiverStub3)

    // When
    splitter.removeReceiver(receiverStub2)
    // Then
    splitter.receivers should contain theSameElementsAs Seq(receiverStub1, receiverStub3)

    // When
    splitter.receivers = Seq(receiverStub2, receiverStub3)
    // Then
    splitter.receivers should contain theSameElementsAs Seq(receiverStub2, receiverStub3)

    // When
    splitter.clearReceivers()
    // Then
    splitter.receivers shouldBe empty
  }

  "send" should "forward MIDI messages to the receivers" in new Fixture {
    // Given
    splitter.receivers = receiverStubs

    // When
    splitter.send(ScNoteOnMidiMessage(0, MidiNote.C4, 69).javaMidiMessage, 100L)
    splitter.send(ScNoteOffMidiMessage(0, MidiNote.C4, 63).javaMidiMessage, 120L)

    // Then
    for (receiverStub <- receiverStubs) {
      receiverStub.send.times shouldEqual 2

      val Seq(callForNoteOn, callForNoteOff) = receiverStub.send.calls

      callForNoteOn._1 match {
        case ScNoteOnMidiMessage(channel, note, velocity) =>
          channel shouldEqual 0
          note.number shouldEqual MidiNote.C4.number
          velocity shouldEqual 69
        case _ => fail("Expected a Note On MIDI message!")
      }
      callForNoteOn._2 shouldEqual 100L

      callForNoteOff._1 match {
        case ScNoteOffMidiMessage(channel, note, velocity) =>
          channel shouldEqual 0
          note.number shouldEqual MidiNote.C4.number
          velocity shouldEqual 63
        case _ => fail("Expected a Note Off MIDI message!")
      }
      callForNoteOff._2 shouldEqual 120L
    }
  }
}

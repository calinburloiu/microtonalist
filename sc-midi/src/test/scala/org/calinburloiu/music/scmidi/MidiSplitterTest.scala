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

import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOffMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiSplitterTest extends AnyFlatSpec, Matchers, Stubs {

  trait Fixture {
    val noteOn: MidiMsg = NoteOnMidiMsg(0, MidiNote.C4, 69)
    val noteOff: MidiMsg = NoteOffMidiMsg(0, MidiNote.C4, 63)

    val receiverStub1: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStub2: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStub3: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStubs: Seq[Stub[MidiReceiver]] = Seq(receiverStub1, receiverStub2, receiverStub3)
    receiverStubs.foreach { receiverStub =>
      receiverStub.send.returns(_ => ())
    }
  }

  behavior of "constructor"

  it should "expose the transmitter it was given" in new Fixture {
    // Given
    val transmitter: MidiTransmitter = ImmutableMidiTransmitter(receiverStubs)

    // When
    val splitter: MidiSplitter = MidiSplitter(transmitter)

    // Then
    splitter.transmitter should be theSameInstanceAs transmitter
  }

  behavior of "send"

  it should "forward every message, with its time-stamp, to every receiver of an immutable transmitter" in
    new Fixture {
      // Given
      val splitter: MidiSplitter = MidiSplitter(ImmutableMidiTransmitter(receiverStubs))

      // When
      splitter.send(noteOn, 100L)
      splitter.send(noteOff, 120L)

      // Then
      for (receiverStub <- receiverStubs) {
        receiverStub.send.calls shouldEqual Seq((noteOn, 100L), (noteOff, 120L))
      }
    }

  it should "forward to receivers added to a mutable transmitter after construction" in new Fixture {
    // Given
    val transmitter: MutableMidiTransmitter = MutableMidiTransmitter()
    val splitter: MidiSplitter = MidiSplitter(transmitter)
    splitter.send(noteOn, 100L)

    // When
    transmitter.addReceivers(Seq(receiverStub1, receiverStub2))
    splitter.send(noteOff, 120L)

    // Then
    receiverStub1.send.calls shouldEqual Seq((noteOff, 120L))
    receiverStub2.send.calls shouldEqual Seq((noteOff, 120L))
    receiverStub3.send.times shouldEqual 0
  }

  it should "forward to receivers added to a concurrent transmitter after construction" in new Fixture {
    // Given
    val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter(Seq(receiverStub1))
    val splitter: MidiSplitter = MidiSplitter(transmitter)

    // When
    transmitter.addReceiver(receiverStub2)
    transmitter.removeReceiver(receiverStub1)
    splitter.send(noteOn, 100L)

    // Then
    receiverStub1.send.times shouldEqual 0
    receiverStub2.send.calls shouldEqual Seq((noteOn, 100L))
  }

  it should "do nothing when the transmitter has no receivers" in new Fixture {
    // Given
    val splitter: MidiSplitter = MidiSplitter(MutableMidiTransmitter())

    // When / Then
    noException should be thrownBy splitter.send(noteOn, 100L)
  }
}

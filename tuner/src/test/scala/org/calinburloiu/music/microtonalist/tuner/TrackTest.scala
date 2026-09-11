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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, NoteOnMidiMsg, PitchBendMidiMsg}
import org.calinburloiu.music.scmidi.{MidiManager, MidiNote, MidiReceiver}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TrackTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)
  val inputMessage: MidiMsg = NoteOnMidiMsg(0, MidiNote.C4, 64)
  val outputMessage: MidiMsg = PitchBendMidiMsg(0, 100)

  trait Fixture {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(*).returns(Seq.empty)
    tuner.process.when(inputMessage).returns(Seq(outputMessage))

    val tuningService: TuningService = stub[TuningService]
    val spec: TrackSpec = TrackSpec("track", "Track", tuner = Some(tuner))
    // The spec has no device input and no device output, so the track never touches the manager.
    val midiManager: MidiManager = stub[MidiManager]
    val track: Track = Track(spec = spec, midiManager = midiManager, tuningService = tuningService)

    val receiver: MidiReceiver = stub[MidiReceiver]
  }

  behavior of "transmitter"

  it should "deliver the tuner's reset messages to a receiver added after the track was built" in new Fixture {
    // When
    track.transmitter.addReceiver(receiver)

    // Then
    receiver.send.verify(initMessage, -1L).once()
  }

  it should "forward the tuner's output for a message sent to the track's receiver" in new Fixture {
    // Given
    track.transmitter.addReceiver(receiver)

    // When
    track.receiver.send(inputMessage, 7L)

    // Then
    receiver.send.verify(outputMessage, *).once()
  }
}

/*
 * Copyright 2021 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtuner

import org.calinburloiu.music.microtuner.midi.{MidiProcessorFixture, PitchBendSensitivity, ScNoteOnMidiMessage}
import org.calinburloiu.music.tuning.Tuning
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.ShortMessage

class MonophonicPitchBendTunerTest extends AnyFlatSpec with Matchers {
  private val inputChannel = 2
  private val outputChannel = 3
  private val pitchBendSensitivity = PitchBendSensitivity(1)

  /** Default fixture that auto connects. Create a custom one for other cases. */
  private trait Fixture extends MidiProcessorFixture[MonophonicPitchBendTuner] {
    override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(outputChannel, pitchBendSensitivity)

    /** Alias for `midiProcessor` for better test readability. */
    val tuner: MonophonicPitchBendTuner = midiProcessor

    connect()
    reset()
  }

  behavior of "MonophonicPitchBendTuner when a receiver is set"

  it should "" in new Fixture {

  }

  it should "configure the output device on connect" in new MidiProcessorFixture[MonophonicPitchBendTuner] {
    override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(
      outputChannel, PitchBendSensitivity(3, 37)
    )
    connect()

    // TODO Assert output
  }

  it should "reset the output device to default parameters on disconnect, including the pitch bend" in
    new MidiProcessorFixture[MonophonicPitchBendTuner] {
      // Note: Only resetting those that were previously set on connect
      override val midiProcessor: MonophonicPitchBendTuner = new MonophonicPitchBendTuner(
        outputChannel, PitchBendSensitivity(3, 37))
      connect()

      // TODO Assert output
    }

  behavior of "MonophonicPitchBendTuner after initialization"

  it should "tune all notes in 12-EDO by not sending any pitch bend" in new Fixture {

  }

  it should "send pitch bend only when the tuning of C is changed" in new Fixture {

  }

  behavior of "MonophonicPitchBendTuner after a new tuning is set"
  //@formatter:off
  private val customTuning = Tuning(
    "major-ish in 72-EDO",
    0.0,    // C
    16.67,  // Db (~16/15 from C)
    0.0,    // D
    -33.33, // D# (~7/6 from C
    -16.67, // E  (~5/4 from C)
    0.0,    // F
    -16.67, // F# (~7/5 from C)
    0.0,    // G
    16.67,  // Ab
    -16.67, // A  (~5/3 from C)
    0.0,    // Bb
    -16.67  // B
  )
  //@formatter:on

  it should "not send pitch bend when C is played" in new Fixture {
    tuner.tune(customTuning)

    tuner.send(ScNoteOnMidiMessage(inputChannel, 60))
    tuner.send(ScNoteOnMidiMessage(inputChannel, 48))
    tuner.send(ScNoteOnMidiMessage(inputChannel, 84))

    outputAsShortMessages.filter(_.getCommand == ShortMessage.PITCH_BEND) shouldBe empty
  }

  it should "send pitch bend when playing a note with a different tuning than the previous one" in new Fixture {
    tuner.tune(customTuning)


  }

  it should "not send pitch bend for consecutive notes with the same tuning" in new Fixture {
    tuner.tune(customTuning)


  }

  it should "play monophonically even if no note off messages are sent" in new Fixture {
    tuner.tune(customTuning)


  }

  behavior of "MonophonicPitchBendTuner when the tuning is changed"
  private val customTuning2 = Tuning("custom2", -45.0, -34.0, -23.0, -12.0, -1, 2, 13, 24, 35, 46, 17, 34)

  it should "not send pitch bend if a note is on and its tuning does not change" in new Fixture {
    tuner.tune(customTuning2)


  }

  it should "not send pitch bend if the tuning of the last note on does not change" in new Fixture {
    tuner.tune(customTuning2)


  }

  it should "send pitch bend if a note is on and its tuning changes" in new Fixture {
    tuner.tune(customTuning2)


  }

  it should "send pitch bend if the tuning of the last note on changes" in new Fixture {
    tuner.tune(customTuning2)


  }
}

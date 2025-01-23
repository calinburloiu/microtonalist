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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.MidiProcessor

import javax.sound.midi.MidiMessage

/**
 *
 * @param triggersThru Whether tuning change MIDI trigger messages should pass through to the output or if they
 *                     should be filtered out.
 */
class TuningChangeProcessor(tuningService: TuningService,
                            tuningChanger: TuningChanger,
                            triggersThru: Boolean) extends MidiProcessor {

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    val tuningChange = tuningChanger.decide(message)

    tuningService.changeTuning(tuningChange)

    // Forward message if:
    //   - It's not a tuning change trigger;
    //   - triggersThru is set.
    if (!tuningChange.isChanging || triggersThru) {
      receiver.send(message, timeStamp)
    }
  }
}

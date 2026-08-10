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

import org.calinburloiu.music.scmidi.MidiNote

/**
 * A note together with its origin: the pair (input channel, note number).
 *
 * The input channel belongs in a note's identity because it is the carrier of per-note information in both
 * input modes — of a note's Expression Values in MPE Input Mode, and of the Polyphonic Key Pressure
 * addressed to a note in Non-MPE Input Mode — so two notes with the same note number arriving on different
 * input channels are independent notes.
 */
private[tuner] case class MpeNoteIdentity(inputChannel: Int, midiNote: MidiNote)

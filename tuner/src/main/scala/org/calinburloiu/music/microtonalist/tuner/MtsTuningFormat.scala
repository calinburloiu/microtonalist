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

package org.calinburloiu.music.microtonalist.tuner

import enumeratum.{Enum, EnumEntry}

import scala.collection.immutable

// TODO #64 Remove and make each format a unique TunerType. All MTS TunerTypes must extend an abstract MtsTunerType.
sealed abstract class MtsTuningFormat(val messageGenerator: MidiTuningMessageGenerator) extends EnumEntry

object MtsTuningFormat extends Enum[MtsTuningFormat] {
  override val values: immutable.IndexedSeq[MtsTuningFormat] = findValues

  case object NonRealTime1BOctave extends MtsTuningFormat(MidiTuningMessageGenerator.NonRealTime1BOctave)
}

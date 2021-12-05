/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.intonation.intervals

import org.calinburloiu.music.intonation.RatioInterval
import org.calinburloiu.music.intonation.RatioInterval.InfixOperator

object SagittalIntervalsRegistry {

  val Schisma5: RatioInterval = 32805 /: 32768
  val Schisma19: RatioInterval = 513 /: 512
  val Kleisma5To7: RatioInterval = 5120 /: 5103
  val Kleisma11To13: RatioInterval = 352 /: 351
  val Kleisma17: RatioInterval = 2187 /: 2176
  val Kleisma7To11: RatioInterval = 896 /: 891
  val Comma17: RatioInterval = 4131 /: 4096
  val Comma23: RatioInterval = 736 /: 729
  val Comma25: RatioInterval = 2048 /: 2025
  val Diaschisma: RatioInterval = Comma25
  val Comma19: RatioInterval = 19683 /: 19456
  val Comma5: RatioInterval = 81 /: 80
  val Comma7: RatioInterval = 64 /: 63
  val Comma55: RatioInterval = 55 /: 54
  val Comma7To11: RatioInterval = 45927 /: 45056
  val Comma13To17: RatioInterval = 52 /: 51
  val Comma29: RatioInterval = 261 /: 256
  val SDiesis5To11: RatioInterval = 45 /: 44
  val SDiesis7To13: RatioInterval = 1701 /: 1664
  val SDiesis11To17: RatioInterval = 1408 /: 1377
  val SDiesis25: RatioInterval = 6561 /: 6400
  val SDiesis5To13: RatioInterval = 40 /: 39
  val MDiesis35: RatioInterval = 36 /: 35
  val MDiesis13: RatioInterval = 1053 /: 1024
  val MDiesis125: RatioInterval = 250 /: 243
  val MDiesis11: RatioInterval = 33 /: 32
  val LDiesis11: RatioInterval = 729 /: 704
  val LDiesis35: RatioInterval = 8505 /: 8192
  val LDiesis125: RatioInterval = 531441 /: 512000
}

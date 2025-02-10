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

import com.sun.media.sound.SoftTuning
import org.calinburloiu.music.microtonalist.composition.OctaveTuning
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.SysexMessage
import scala.language.implicitConversions

/**
 * This test suite uses an internal Java class: [[com.sun.media.sound.SoftTuning]]. If you see compiler errors
 * related to it in IntelliJ, you need to add the following in the run configuration of the test in VM options:
 *
 * {{{
 * --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED
 * }}}
 *
 * Note that the test is already configured in SBT via `.sbtopts` file in project root.
 */
class MtsMessageGeneratorTest extends AnyFunSuite with Matchers {

  def getTuning(sysexMessage: SysexMessage): Array[Double] = {
    val data = sysexMessage.getMessage
    val softTuning = new SoftTuning(data)

    softTuning.getTuning
  }

  test("Octave1ByteNonRealTime") {
    val tuning = OctaveTuning("t", 0.1, 0.9, 2.4, 3.0, 4.2, 4.6, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val expectedTuning = OctaveTuning("t", 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val messageGenerator = MtsMessageGenerator.Octave1ByteNonRealTime
    messageGenerator should be theSameInstanceAs MtsMessageGenerator.Octave1ByteNonRealTime

    val tuningValues = getTuning(messageGenerator.generate(tuning))
    for (i <- tuningValues.indices) {
      assert(tuningValues(i) - 100 * i === expectedTuning.deviations(i % 12))
    }
  }
}

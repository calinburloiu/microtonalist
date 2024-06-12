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
import org.calinburloiu.music.microtonalist.core.OctaveTuning
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.SysexMessage
import scala.language.implicitConversions

class MidiTuningMessageGeneratorTest extends AnyFunSuite with Matchers {

  def getTuning(sysexMessage: SysexMessage): Array[Double] = {
    val data = sysexMessage.getMessage
    // You might see compiler errors related to com.sun and this class.
    // SoftTuning requires adding --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED flag when running the VM.
    // For SBT this is done .sbtopts in project root.
    // To make tests work in IntelliJ follow these steps:
    // https://javaworklife.wordpress.com/2019/05/20/java11-intellij-running-tests-with-add-exports-in-intellij/
    val softTuning = new SoftTuning(data)

    softTuning.getTuning
  }

  // TODO @calinburloiu Ignoring. It was using deprecated Java API.
  test("MidiTuningMessageGenerator.NonRealTime1BOctave") {
    val tuning = OctaveTuning("t", 0.1, 0.9, 2.4, 3.0, 4.2, 4.6, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val expectedTuning = OctaveTuning("t", 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val messageGenerator = MtsTuningFormat.NonRealTime1BOctave.messageGenerator
    messageGenerator should be theSameInstanceAs MidiTuningMessageGenerator.NonRealTime1BOctave

    val tuningValues = getTuning(messageGenerator.generate(tuning))
    for (i <- tuningValues.indices) {
      assert(tuningValues(i) - 100 * i === expectedTuning.deviations(i % 12))
    }
  }
}

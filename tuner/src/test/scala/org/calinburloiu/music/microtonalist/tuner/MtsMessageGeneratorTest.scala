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
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  private val tuning = Tuning.fromOffsets("Just 12",
    Seq(0.1, 11.73, 3.91, 15.64, -13.69, -1.96, -17.49, 1.96, 13.69, -15.64, -31.18, -11.73))
  private val expected1ByteOffsets: Seq[Double] = Seq(
    0.0, 12.0, 4.0, 16.0, -14.0, -2.0, -17.0, 2.0, 14.0, -16.0, -31.0, -12.0)

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  def assertTuning(messageGenerator: MtsMessageGenerator, expectedDeviations: Seq[Double]): Unit = {
    val sysexMessage = messageGenerator.generate(tuning)
    val data = sysexMessage.getMessage
    val softTuning = new SoftTuning(data)
    val tuningValues = softTuning.getTuning

    for (i <- tuningValues.indices) {
      (tuningValues(i) - 100 * i) shouldEqual expectedDeviations(i % 12)
    }
  }

  test("Octave1ByteNonRealTime") {
    assertTuning(MtsMessageGenerator.Octave1ByteNonRealTime, expected1ByteOffsets)
  }

  test("Octave2ByteNonRealTime") {
    assertTuning(MtsMessageGenerator.Octave2ByteNonRealTime, tuning.offsets)
  }

  test("Octave1ByteRealTime") {
    assertTuning(MtsMessageGenerator.Octave1ByteRealTime, expected1ByteOffsets)
  }

  test("Octave2ByteRealTime") {
    assertTuning(MtsMessageGenerator.Octave2ByteRealTime, tuning.offsets)
  }
}

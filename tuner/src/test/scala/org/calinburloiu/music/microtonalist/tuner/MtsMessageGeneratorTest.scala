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

import com.sun.media.sound.SoftTuning
import org.calinburloiu.music.scmidi.message.SysExMidiMsg
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
class MtsMessageGeneratorTest extends AnyWordSpec with Matchers {

  private val tuning = Tuning.fromOffsets("Just 12",
    Seq(0.1, 11.73, 3.91, 15.64, -13.69, -1.96, -17.49, 1.96, 13.69, -15.64, -31.18, -11.73))
  private val expected1ByteOffsets: Seq[Double] = Seq(
    0.0, 12.0, 4.0, 16.0, -14.0, -2.0, -17.0, 2.0, 14.0, -16.0, -31.0, -12.0)

  private val epsilon: Double = 2e-2
  private implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(epsilon)

  def assertTuning(messageGenerator: MtsMessageGenerator, expectedOffsets: Seq[Double],
                   tuning: Tuning = tuning): Unit = {
    val sysExMessage = messageGenerator.generate(tuning)
    val data = sysExMessage.data.toArray
    data.head shouldEqual SysExMidiMsg.StatusByte
    data.last shouldEqual SysExMidiMsg.EndOfExclusiveByte
    val softTuning = new SoftTuning(data)
    val tuningValues = softTuning.getTuning

    for (i <- tuningValues.indices) {
      (tuningValues(i) - 100 * i) shouldEqual expectedOffsets(i % 12)
    }
  }

  /** A tuning whose offsets are all 0, except for B, which has the given one. */
  private def tuningWithB(offset: Double): Tuning = Tuning.fromOffsets(s"B = $offset", Seq.fill(11)(0.0) :+ offset)

  "Octave1ByteNonRealTime" should {
    "generate a non-real-time Octave 1-byte tuning message" in {
      assertTuning(MtsMessageGenerator.Octave1ByteNonRealTime, expected1ByteOffsets)
    }

    "encode a tuning whose offsets round to the bounds of the 1-byte range" in {
      // Given
      val tuning = Tuning.fromOffsets("bounds", Seq.fill(6)(Seq(-64.49, 63.49)).flatten)

      // When / Then
      MtsMessageGenerator.Octave1ByteNonRealTime.canEncode(tuning) shouldBe true
    }

    "not encode a tuning with an offset rounding below the 1-byte range" in {
      // When / Then
      MtsMessageGenerator.Octave1ByteNonRealTime.canEncode(tuningWithB(-64.51)) shouldBe false
    }

    "not encode a tuning with an offset rounding above the 1-byte range" in {
      // When / Then
      MtsMessageGenerator.Octave1ByteNonRealTime.canEncode(tuningWithB(63.5)) shouldBe false
    }
  }

  "Octave2ByteNonRealTime" should {
    "generate a non-real-time Octave 2-byte tuning message" in {
      assertTuning(MtsMessageGenerator.Octave2ByteNonRealTime, tuning.offsets)
    }

    "encode a tuning whose offsets are on the bounds of the 2-byte range" in {
      // Given
      val tuning = Tuning.fromOffsets("bounds", Seq.fill(6)(Seq(-100.0, 100.0)).flatten)

      // When / Then
      MtsMessageGenerator.Octave2ByteNonRealTime.canEncode(tuning) shouldBe true
    }

    "not encode a tuning with an offset below the 2-byte range" in {
      // When / Then
      MtsMessageGenerator.Octave2ByteNonRealTime.canEncode(tuningWithB(-100.01)) shouldBe false
    }

    "not encode a tuning with an offset above the 2-byte range" in {
      // When / Then
      MtsMessageGenerator.Octave2ByteNonRealTime.canEncode(tuningWithB(100.01)) shouldBe false
    }

    "clamp the offsets beyond the 2-byte range to it" in {
      // Given
      val tuning = Tuning.fromOffsets("beyond", Seq.fill(6)(Seq(-150.0, 150.0)).flatten)

      // When / Then
      assertTuning(MtsMessageGenerator.Octave2ByteNonRealTime, Seq.fill(6)(Seq(-100.0, 100.0)).flatten, tuning)
    }
  }

  "Octave1ByteRealTime" should {
    "generate a real-time Octave 1-byte tuning message" in {
      assertTuning(MtsMessageGenerator.Octave1ByteRealTime, expected1ByteOffsets)
    }
  }

  "Octave2ByteRealTime" should {
    "generate a real-time Octave 2-byte tuning message" in {
      assertTuning(MtsMessageGenerator.Octave2ByteRealTime, tuning.offsets)
    }
  }
}

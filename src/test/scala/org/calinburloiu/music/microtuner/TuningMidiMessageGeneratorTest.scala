package org.calinburloiu.music.microtuner

import javax.sound.midi.SysexMessage

import com.sun.media.sound.SoftTuning
import org.calinburloiu.music.tuning.Tuning
import org.scalatest.FunSuite

import scala.language.implicitConversions

class TuningMidiMessageGeneratorTest extends FunSuite {

  def getTuning(sysexMessage: SysexMessage): Array[Double] = {
    val data = sysexMessage.getMessage
    val softTuning = new SoftTuning(data)

    softTuning.getTuning
  }

  test("NonRealTime1BOctaveTuningMidiMessageGenerator") {
    val tuning = Tuning("t", 0.1, 0.9, 2.4, 3.0, 4.2, 4.6, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val expectedTuning = Tuning("t", 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)
    val tuningValues = getTuning(NonRealTime1BOctaveTuningMidiMessageGenerator.generate(tuning))

    for (i <- tuningValues.indices) {
      assert(tuningValues(i) - 100*i === expectedTuning.deviations(i % 12))
    }
  }
}

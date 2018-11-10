package org.calinburloiu.music.tuning

import org.calinburloiu.music.intonation.{CentsInterval, CentsScale, Interval, Scale}
import org.scalatest.{FunSuite, Matchers}

import PianoKeyboardTuningUtils._

class PrecisionTest extends FunSuite with Matchers {

  test("changing interval class does not alter quarter tones mapping when using halfTolerance") {
    val scaleCents = CentsScale(0.0, 150.0, 300.0, 500.0, 700.0, 850.0, 1000.0)

    // Change the interval class from CentsInterval to an Interval by using the realValue.
    // This will lead to a Double precision error, e.g. 150.0 will not be precisely the same.
    val convertedIntervals = scaleCents.intervals.map { centsInterval: CentsInterval =>
      Interval(centsInterval.realValue)
    }
    val convertedScale = Scale(convertedIntervals.head, convertedIntervals.tail: _*)

    val autoTuningMapper = new AutoTuningMapper(AutoTuningMapperConfig(
      mapQuarterTonesLow = true,
      halfTolerance = 0.5e-2
    ))
    val tuning = autoTuningMapper(0, convertedScale)

    // When setting halfTolerance to 0.0, the quarter tones are mapped to D and A, instead of
    // D flat and A flat, respectively.
    tuning.c should not be empty
    tuning.dFlat should not be empty
    tuning.d shouldBe empty
    tuning.eFlat should not be empty
    tuning.f should not be empty
    tuning.g should not be empty
    tuning.aFlat should not be empty
    tuning.a shouldBe empty
    tuning.bFlat should not be empty
  }
}

package org.calinburloiu.music.intonation

import Converters._
import org.calinburloiu.music.tuning.PitchClassConfig
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSuite, Matchers}

class ConvertersTest extends FunSuite with TableDrivenPropertyChecks with Matchers {

  private val epsilon: Double = 1e-2
  private implicit val doubleEquality: Equality[Double] =
    TolerantNumerics.tolerantDoubleEquality(epsilon)

  val commonTable = Table(
    ("real value",  "cents",  "Hz",   "pitch class / deviation"),
    (0.75,          -498.04,  330.0,  PitchClass(7, 1.96)),
    (1.0,           0.0,      440.0,  PitchClass(0)),
    (1.25,          386.31,   550.0,  PitchClass(4, -13.69)),
    (1.5,           701.96,   660.0,  PitchClass(7, 1.96)),
    (2.0,           1200.0,   880.0,  PitchClass(0)),
    (4.0,           2400.0,   1760.0, PitchClass(0))
  )

  test("common conversions between real values, cents, Hz and pitch classes") {
    implicit val pitchClassConfig: PitchClassConfig = PitchClassConfig(mapQuarterTonesLow = true, 0.0)

    forAll(commonTable) { (realValue, cents, hz, pitchClass) =>
      fromRealValueToCents(realValue) should equal(cents)
      fromCentsToRealValue(cents) should equal(realValue)
      fromCentsToHz(cents, 440) should equal(hz)
      fromHzToCents(hz, 440) should equal(cents)

      val PitchClass(semitone, deviation) = PitchClass.fromCents(cents)
      semitone should equal (pitchClass.semitone)
      deviation should equal (pitchClass.deviation)
    }
  }

  test("fromRealValueToCents fails") {
    val table = Table("realValue", 0, -1)
    forAll(table) { realValue =>
      assertThrows[IllegalArgumentException](fromRealValueToCents(realValue))
    }
  }

  test("fromRatioToCents succeeds") {
    val table = Table(
      ("numerator", "denominator",  "cents"),
      (3,           4,              -498.04),
      (1,           1,              0.0),
      (5,           4,              386.31),
      (3,           2,              701.96),
      (2,           1,              1200.0),
      (4,           1,              2400.0)
    )

    forAll(table) { (numerator, denominator, cents) =>
      fromRatioToCents(numerator, denominator) shouldEqual cents
    }
  }

  test("fromRatioToCents fails") {
    val table = Table(
      ("numerator", "denominator"),
      (3,           0),
      (1,           -1),
      (0,           4),
      (-3,           2),
    )

    forAll(table) { (numerator, denominator) =>
      assertThrows[IllegalArgumentException](fromRatioToCents(numerator, denominator))
    }
  }

  test("fromCentsToHz fails") {
    val table = Table("baseFreqHz", 0, -1)
    forAll(table) { baseFreqHz =>
      assertThrows[IllegalArgumentException](fromCentsToHz(100.0, baseFreqHz))
    }
  }

  test("fromHzToCents fails") {
    val table = Table("(base)FreqHz", 0, -1)
    forAll(table) { value =>
      assertThrows[IllegalArgumentException](fromHzToCents(100.0, value))
      assertThrows[IllegalArgumentException](fromHzToCents(value, 100.0))
    }
  }
}

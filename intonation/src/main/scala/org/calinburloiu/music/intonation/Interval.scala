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

package org.calinburloiu.music.intonation

import com.google.common.math.{DoubleMath, IntMath}

import java.lang.Math.{floor, pow}
import scala.language.implicitConversions
import scala.util.Try

/**
 * Trait for any of the following interval types: [[RealInterval]], [[RatioInterval]], [[CentsInterval]] and
 * [[EdoInterval]].
 *
 * When applying a binary operation such is `+` or `-` on intervals of different types, the following result can be obtained:
 *
 * * A [[CentsInterval]] with any interval produces a [[CentsInterval]].
 * * A [[RealInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * * A [[RatioInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * * An [[EdoInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * *
 */
sealed trait Interval extends Ordered[Interval] {
  def realValue: Double

  def cents: Double = fromRealValueToCents(realValue)

  def isNormalized: Boolean

  def normalize: Interval

  def normalizationLogFactor: Double = -floor(DoubleMath.log2(realValue))

  def normalizationFactor: Double = pow(2, normalizationLogFactor)

  def +(operand: Interval): Interval

  def -(operand: Interval): Interval

  def *(n: Int): Interval

  def invert: Interval

  def toStringLengthInterval: Interval

  def isUnison: Boolean

  def toRealInterval: RealInterval = RealInterval(realValue)

  def toCentsInterval: CentsInterval = CentsInterval(cents)
}

object Interval {
  def fromScalaTuningInterval(intervalValue: String): Option[Interval] = {
    if (intervalValue.contains(".")) {
      val maybeCents = Try(intervalValue.toDouble).toOption

      maybeCents.map(CentsInterval.apply)
    } else {
      val ratioArray = intervalValue.split("/")
      val maybeNumerator = Try(ratioArray(0).toInt).toOption
      val maybeDenominator = if (ratioArray.size == 1) Some(1) else Try(ratioArray(1).toInt).toOption

      for {
        numerator <- maybeNumerator
        denominator <- maybeDenominator
      } yield RatioInterval(numerator, denominator)
    }
  }
}

case class RealInterval(override val realValue: Double) extends Interval {
  require(realValue > 0.0 &&
    realValue != Double.PositiveInfinity && realValue != Double.NaN,
    s"Expecting a positive finite real value for the interval, but got $realValue")

  override def isNormalized: Boolean = realValue >= 1 && realValue < 2

  override def normalize: RealInterval =
    if (isNormalized) this else new RealInterval(realValue * normalizationFactor)

  def +(operand: RealInterval): RealInterval = RealInterval(this.realValue * operand.realValue)

  override def +(operand: Interval): Interval = operand match {
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case realInterval: RealInterval => this + realInterval
    case interval: Interval => this + interval.toRealInterval
  }

  def -(operand: RealInterval): RealInterval = RealInterval(this.realValue / operand.realValue)

  override def -(operand: Interval): Interval = operand match {
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case realInterval: RealInterval => this - realInterval
    case interval: Interval => this - interval.toRealInterval
  }

  override def *(n: Int): RealInterval = RealInterval(Math.pow(realValue, n))

  override def invert: RealInterval = {
    require(this >= RealInterval.Unison && this <= RealInterval.Octave,
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    RealInterval.Octave - this
  }

  override def toStringLengthInterval: RealInterval = RealInterval(1.0 / this.realValue)

  override def isUnison: Boolean = {
    realValue == 1
  }

  override def toRealInterval: RealInterval = this

  override def compare(that: Interval): Int = this.realValue.compareTo(that.realValue)

  override def toString = s"RealInterval($realValue, $cents ¢)"
}

object RealInterval {
  val Unison: RealInterval = RealInterval(1.0)
  val Octave: RealInterval = RealInterval(2.0)

  def apply(realValue: Double): RealInterval = new RealInterval(realValue)
}

case class RatioInterval(numerator: Int, denominator: Int) extends Interval {
  require(numerator > 0, s"Expecting a positive value for the numerator, but got $numerator")
  require(denominator > 0, s"Expecting a positive value for the denominator, but got $denominator")

  override def realValue: Double = numerator.toDouble / denominator

  override def isNormalized: Boolean = numerator < 2 * denominator && numerator >= denominator

  override def normalize: RatioInterval = {
    if (isNormalized) {
      this
    } else {
      val (a, b) = if (normalizationLogFactor > 0)
        (numerator * pow(2, normalizationLogFactor).toInt, denominator)
      else
        (numerator, denominator * pow(2, -normalizationLogFactor).toInt)
      val gcd = IntMath.gcd(a, b)

      RatioInterval(a / gcd, b / gcd)
    }
  }

  def +(that: RatioInterval): RatioInterval = {
    val a = this.numerator * that.numerator
    val b = this.denominator * that.denominator
    val gcd = IntMath.gcd(a, b)

    RatioInterval(a / gcd, b / gcd)
  }

  override def +(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case ratioInterval: RatioInterval => this + ratioInterval
    case interval: Interval => this.toRealInterval + interval
  }

  def -(that: RatioInterval): RatioInterval = {
    val a = this.numerator * that.denominator
    val b = this.denominator * that.numerator
    val gcd = IntMath.gcd(a, b)

    RatioInterval(a / gcd, b / gcd)
  }

  override def -(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case ratioInterval: RatioInterval => this - ratioInterval
    case interval: Interval => this.toRealInterval - interval
  }

  override def *(n: Int): RatioInterval = RatioInterval(Math.pow(numerator, n).toInt, Math.pow(denominator, n).toInt)

  override def invert: RatioInterval = {
    require(this >= RatioInterval.Unison && this <= RatioInterval.Octave,
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    RatioInterval.Octave - this
  }

  override def toStringLengthInterval: Interval = RatioInterval(this.denominator, this.numerator)

  override def isUnison: Boolean = {
    numerator == 1 && denominator == 1
  }

  override def compare(that: Interval): Int = this.realValue.compareTo(that.realValue)

  override def toString: String = s"$numerator/$denominator"
}

object RatioInterval {
  val Unison: RatioInterval = RatioInterval(1, 1)
  val Octave: RatioInterval = RatioInterval(2, 1)

  implicit def fromPair(pair: (Int, Int)): RatioInterval = RatioInterval(pair._1, pair._2)

  implicit class InfixOperator(denominator: Int) {
    // Using a right-associative infix operator in order to make it precede + and - operators.
    def /:(numerator: Int): RatioInterval = RatioInterval(numerator, denominator)
  }
}

case class CentsInterval(override val cents: Double) extends Interval {
  override def realValue: Double = fromCentsToRealValue(cents)

  override def isNormalized: Boolean = cents >= 0.0 && cents < 1200.0

  override def normalize: CentsInterval = {
    if (isNormalized) {
      this
    } else {
      CentsInterval(mod(cents, 1200))
    }
  }

  def +(that: CentsInterval): CentsInterval = CentsInterval(this.cents + that.cents)

  override def +(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case centsInterval: CentsInterval => this + centsInterval
    case interval: Interval => this + interval.toCentsInterval
  }

  def -(that: CentsInterval): CentsInterval = CentsInterval(this.cents - that.cents)

  override def -(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case centsInterval: CentsInterval => this - centsInterval
    case interval: Interval => this - interval.toCentsInterval
  }

  override def *(n: Int): CentsInterval = CentsInterval(n * cents)

  override def invert: CentsInterval = {
    require(this >= CentsInterval.Unison && this <= CentsInterval.Octave,
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    CentsInterval.Octave - this
  }

  override def toStringLengthInterval: CentsInterval = CentsInterval(-cents)

  override def isUnison: Boolean = cents == 0.0

  override def toCentsInterval: CentsInterval = this

  override def compare(that: Interval): Int = this.cents.compareTo(that.cents)

  override def toString: String = s"$cents ¢"
}

object CentsInterval {
  val Unison: CentsInterval = CentsInterval(0.0)
  val Octave: CentsInterval = CentsInterval(1200.0)

  implicit class PostfixOperator(centsValue: Double) {
    def cent: CentsInterval = CentsInterval(centsValue)

    def cents: CentsInterval = CentsInterval(centsValue)
  }
}

case class EdoInterval(edo: Int, count: Int) extends Interval {
  require(edo > 0, s"Expecting a positive value for edo, but got $edo")

  override def realValue: Double = fromEdoToRealValue(edo, count)

  override def cents: Double = fromEdoToCents(edo, count)

  override def isNormalized: Boolean = count >= 0 && count <= edo

  override def normalize: EdoInterval = {
    if (isNormalized) {
      this
    } else {
      EdoInterval(edo, IntMath.mod(count, edo))
    }
  }

  def +(that: EdoInterval): EdoInterval = EdoInterval(edo, this.count + that.count)

  override def +(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case edoInterval: EdoInterval => this + edoInterval
    case interval: Interval => this.toRealInterval + interval
  }

  def -(that: EdoInterval): EdoInterval = EdoInterval(edo, this.count - that.count)

  override def -(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case edoInterval: EdoInterval => this - edoInterval
    case interval: Interval => this.toRealInterval - interval
  }

  override def *(n: Int): EdoInterval = EdoInterval(edo, n * count)

  override def invert: EdoInterval = {
    require(this >= EdoInterval.unisonFor(edo) && this <= EdoInterval.octaveFor(edo),
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    EdoInterval.octaveFor(edo) - this
  }

  override def toStringLengthInterval: EdoInterval = EdoInterval(edo, -count)

  override def isUnison: Boolean = count == 0

  override def compare(that: Interval): Int = that match {
    case EdoInterval(`edo`, thatCount) => this.count.compareTo(thatCount)
    case interval: Interval => this.realValue.compareTo(interval.realValue)
  }
}

object EdoInterval {
  def unisonFor(edo: Int): EdoInterval = EdoInterval(edo, 0)

  def octaveFor(edo: Int): EdoInterval = EdoInterval(edo, edo)
}

case class EdoIntervalFactory(edo: Int) {
  require(edo > 0, s"Expecting a positive value for edo, but got $edo")

  def apply(count: Int): EdoInterval = EdoInterval(edo, count)
}

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
 * All operations exposed by the public API are performed in the musical logarithmic space, not in the frequency space.
 * Humans perceive intervals logarithmically, every increase by an octave doubles the frequency.
 *
 * When applying a binary operation such is `+` or `-` on intervals of different types, the following result can be
 * obtained:
 *
 * * A [[CentsInterval]] with any interval produces a [[CentsInterval]].
 * * A [[RealInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * * A [[RatioInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * * An [[EdoInterval]] with any interval except a [[CentsInterval]] produces a [[RealInterval]].
 * *
 */
sealed trait Interval extends Ordered[Interval] {
  /**
   * @return interval's frequency ratio as a decimal number
   */
  def realValue: Double

  /**
   * @return the interval expressed in cents
   */
  def cents: Double = fromRealValueToCents(realValue)

  /**
   * @return true if the interval is between unison (inclusive) and octave (exclusive)
   */
  def isNormalized: Boolean

  /**
   * Changes interval's octave such that it's between unison (inclusive) and octave (exclusive).
   *
   * @return the normalized interval
   */
  def normalize: Interval

  /**
   * @return the factor that needs to be multiplied to the frequency ratio to obtain a normalized ratio
   * @see [[Interval.normalize]]
   */
  def normalizationFactor: Double = pow(2, normalizationLogFactor)

  /**
   * @return the logarithm of the factor that needs to be multiplied to the frequency ratio to obtain a normalized ratio
   * @see [[Interval.normalize]]
   */
  def normalizationLogFactor: Double = -floor(DoubleMath.log2(realValue))

  /**
   * Adds `this` interval with the given one musically, in logarithmic space.
   *
   * @param that the interval to add to `this`
   * @return the sum interval
   */
  def +(that: Interval): Interval

  /**
   * Subtracts from `this` interval the given one musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  def -(that: Interval): Interval

  /**
   * Performs a musical multiplication of `this` by adding it to itself `n` times.
   *
   * @param n the multiplier
   * @return the product interval
   */
  def *(n: Int): Interval

  /**
   * @return the inversion of `this` interval
   * @throws IllegalArgumentException if `this` is not normalized
   * @see [[Interval.normalize]]
   */
  def invert: Interval

  /**
   * Computes the interval corresponding to the string ratio needed to achieve `this` musical interval on a vibrating
   * string. That interval is the inverse of `this`' fraction, i.e. `1.0 / realValue`.
   *
   * @return the interval of the string length ratio
   */
  def toStringLengthInterval: Interval

  /**
   * @return true if `this` interval is a unison or false otherwise
   */
  def isUnison: Boolean

  /**
   * Converts `this` interval to a [[RealInterval]].
   */
  def toRealInterval: RealInterval = RealInterval(realValue)

  /**
   * Converts `this` interval to a [[CentsInterval]].
   */
  def toCentsInterval: CentsInterval = CentsInterval(cents)
}

object Interval {
  /**
   * Parses an interval expressed in the format present in Scala application tuning files (`*.scl`).
   *
   * Scala application tuning files encodes intervals by using the following convention:
   *
   * * An integer or a fraction is an interval expressed as a just intonation frequency ratio. E.g. `2` or `2/1` are
   * octaves, `3/2` is a perfect fifth.
   * * A decimal number that contains a dot is an interval expressed in cents. E.g. 150.0 is neutral second, 700.0 is
   * a perfect fifth in 12-EDO.
   *
   * @param intervalValue interval value that follows Scala application's convention
   * @return `Some` [[RatioInterval]] or [[CentsInterval]] if it was successfully parsed, `None` is it was invalid
   */
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

/**
 * An [[Interval]] expressed as a frequency ratio in a generic decimal formal.
 *
 * This is the most generic interval format that can express anything, but it lacks just intonation or EDO precision.
 *
 * @param realValue the positive frequency ratio in decimal format
 */
case class RealInterval(override val realValue: Double) extends Interval {
  require(realValue > 0.0 &&
    realValue != Double.PositiveInfinity && realValue != Double.NaN,
    s"Expecting a positive finite real value for the interval, but got $realValue")

  override def isNormalized: Boolean = realValue >= 1 && realValue < 2

  override def normalize: RealInterval =
    if (isNormalized) this else new RealInterval(realValue * normalizationFactor)

  /**
   * Adds `this` interval with the given [[RealInterval]] musically, in logarithmic space.
   *
   * @param that the interval to add to `this`
   * @return the sum interval
   */
  def +(that: RealInterval): RealInterval = RealInterval(this.realValue * that.realValue)

  override def +(that: Interval): Interval = that match {
    case realInterval: RealInterval => this + realInterval
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case interval: Interval => this + interval.toRealInterval
  }

  /**
   * Subtracts from `this` interval the given [[RealInterval]] musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  def -(that: RealInterval): RealInterval = RealInterval(this.realValue / that.realValue)

  override def -(that: Interval): Interval = that match {
    case realInterval: RealInterval => this - realInterval
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case interval: Interval => this - interval.toRealInterval
  }

  override def *(n: Int): RealInterval = RealInterval(Math.pow(realValue, n))

  override def invert: RealInterval = {
    require(this >= RealInterval.Unison && this <= RealInterval.Octave,
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    RealInterval.Octave - this
  }

  override def toStringLengthInterval: RealInterval = RealInterval(1.0 / this.realValue)

  override def isUnison: Boolean = realValue == 1.0

  override def toRealInterval: RealInterval = this

  override def compare(that: Interval): Int = this.realValue.compareTo(that.realValue)

  override def toString = s"RealInterval($realValue, $cents ¢)"
}

object RealInterval {
  val Unison: RealInterval = RealInterval(1.0)
  val Octave: RealInterval = RealInterval(2.0)
}

/**
 * An [[Interval]] expressed as a just intonation frequency ratio (fraction).
 *
 * Although precise, this type of interval cannot express any interval such as temperaments.
 *
 * @param numerator   positive integer fraction numerator
 * @param denominator positive integer fraction denominator
 */
case class RatioInterval(numerator: Int, denominator: Int) extends Interval {
  require(numerator > 0, s"Expecting a positive value for the numerator, but got $numerator")
  require(denominator > 0, s"Expecting a positive value for the denominator, but got $denominator")

  override def realValue: Double = numerator.toDouble / denominator

  override def isNormalized: Boolean = numerator >= denominator && numerator < 2 * denominator

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

  /**
   * Adds `this` interval with the given [[RatioInterval]] musically, in logarithmic space.
   *
   * @param that the interval to add to `this`
   * @return the sum interval
   */
  def +(that: RatioInterval): RatioInterval = {
    val a = this.numerator * that.numerator
    val b = this.denominator * that.denominator
    val gcd = IntMath.gcd(a, b)

    RatioInterval(a / gcd, b / gcd)
  }

  override def +(that: Interval): Interval = that match {
    case ratioInterval: RatioInterval => this + ratioInterval
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case interval: Interval => this.toRealInterval + interval
  }

  /**
   * Subtracts from `this` interval the given [[RatioInterval]] musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  def -(that: RatioInterval): RatioInterval = {
    val a = this.numerator * that.denominator
    val b = this.denominator * that.numerator
    val gcd = IntMath.gcd(a, b)

    RatioInterval(a / gcd, b / gcd)
  }

  override def -(that: Interval): Interval = that match {
    case ratioInterval: RatioInterval => this - ratioInterval
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
    case interval: Interval => this.toRealInterval - interval
  }

  override def *(n: Int): RatioInterval = RatioInterval(Math.pow(numerator, n).toInt, Math.pow(denominator, n).toInt)

  override def invert: RatioInterval = {
    require(this >= RatioInterval.Unison && this <= RatioInterval.Octave,
      s"Expecting this to be between an unison and an octave, inclusively, but got $this")

    RatioInterval.Octave - this
  }

  override def toStringLengthInterval: Interval = RatioInterval(this.denominator, this.numerator)

  override def isUnison: Boolean = numerator == 1 && denominator == 1

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

/**
 * An [[Interval]] expressed as a decimal value in cents.
 *
 * This interval type can approximate any interval, but it lacks just intonation and EDO precision.
 *
 * Note that this interval type is not equivalent to 1200-EDO, because it allows fractions of a cent.
 *
 * @param cents a decimal value in cents
 */
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

  /**
   * Adds `this` interval with the given [[CentsInterval]] musically, in logarithmic space.
   *
   * @param that the interval to add to `this`
   * @return the sum interval
   */
  def +(that: CentsInterval): CentsInterval = CentsInterval(this.cents + that.cents)

  override def +(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this + centsInterval
    case interval: Interval => this + interval.toCentsInterval
  }

  /**
   * Subtracts from `this` interval the given [[CentsInterval]] musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  def -(that: CentsInterval): CentsInterval = CentsInterval(this.cents - that.cents)

  /**
   * Subtracts from `this` interval the given [[CentsInterval]] musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  override def -(that: Interval): Interval = that match {
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

/**
 * An [[Interval]] expressed as equal divisions of the octave (EDO).
 *
 * Note that by using this interval type with `edo = 1200` you are not achieving the exact same effect as
 * [[CentsInterval]], because the later allows fractions of a cent, while this type only allows integers for `count`.
 *
 * @param edo   the number of divisions used to express an octave
 * @param count the number of division used to express the interval
 */
case class EdoInterval(edo: Int, count: Int) extends Interval {
  require(edo > 0, s"Expecting a positive value for edo, but got $edo")

  override def realValue: Double = fromEdoToRealValue(edo, count)

  override def cents: Double = fromEdoToCents(edo, count)

  override def isNormalized: Boolean = count >= 0 && count < edo

  override def normalize: EdoInterval = {
    if (isNormalized) {
      this
    } else {
      EdoInterval(edo, IntMath.mod(count, edo))
    }
  }

  /**
   * Adds `this` interval with the given [[EdoInterval]] musically, in logarithmic space.
   *
   * @param that the interval to add to `this`
   * @return the sum interval
   */
  def +(that: EdoInterval): EdoInterval = EdoInterval(edo, this.count + that.count)

  override def +(that: Interval): Interval = that match {
    case edoInterval: EdoInterval => this + edoInterval
    case centsInterval: CentsInterval => this.toCentsInterval + centsInterval
    case interval: Interval => this.toRealInterval + interval
  }

  /**
   * Subtracts from `this` interval the given [[EdoInterval]] musically, in logarithmic space.
   *
   * @param that the interval to subtract from `this`
   * @return the difference interval
   */
  def -(that: EdoInterval): EdoInterval = EdoInterval(edo, this.count - that.count)

  override def -(that: Interval): Interval = that match {
    case edoInterval: EdoInterval => this - edoInterval
    case centsInterval: CentsInterval => this.toCentsInterval - centsInterval
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
  /**
   * Creates an [[EdoInterval]] by specifying a count value relative to an approximation in the given `edo` of the
   * standard tuning (12-EDO).
   *
   * @param edo                     the number of divisions used to express an octave
   * @param countRelativeToStandard a pair of integers where the first is the number of 12-EDO semitones approximated
   *                                in this EDO (by rounding, 0.5 goes up), and the second is the deviation in
   *                                divisions (in this EDO) from the approximated semitone.
   * @return a new [[EdoInterval]]
   */
  def apply(edo: Int, countRelativeToStandard: (Int, Int)): EdoInterval = {
    val (semitones, relativeCount) = countRelativeToStandard
    val count = Math.round(semitones.toDouble / 12.0 * edo).toInt + relativeCount

    EdoInterval(edo, count)
  }

  /**
   * @param edo the number of divisions used to express a unison
   * @return the unison in the given EDO
   */
  def unisonFor(edo: Int): EdoInterval = EdoInterval(edo, 0)

  /**
   * @param edo the number of divisions used to express an octave
   * @return the octave in the given EDO
   */
  def octaveFor(edo: Int): EdoInterval = EdoInterval(edo, edo)
}

/**
 * Convenience factory for intervals in a given interval.
 *
 * Creating an [[EdoInterval]] always requires specifying `edo` which can be tiring. An instance of this factory
 * holds that values and will only ask for the `count` value.
 *
 * @param edo the number of divisions used to express an octave
 */
case class EdoIntervalFactory(edo: Int) {
  require(edo > 0, s"Expecting a positive value for edo, but got $edo")

  /**
   * Creates an [[EdoInterval]] by specifying the number of division used to express the interval.
   *
   * @param count the number of division used to express the interval
   * @return a new [[EdoInterval]]
   */
  def apply(count: Int): EdoInterval = EdoInterval(edo, count)

  /**
   * Creates an [[EdoInterval]] by specifying a count value relative to an approximation in the given `edo` of the
   * standard tuning (12-EDO).
   *
   * @param semitones     the number of 12-EDO semitones approximated in this EDO (by rounding, 0.5 goes up)
   * @param relativeCount the deviation in divisions (in this EDO) from the approximated semitone
   * @return a new [[EdoInterval]]
   */
  def apply(semitones: Int, relativeCount: Int): EdoInterval = EdoInterval(edo, (semitones, relativeCount))

  lazy val unison: EdoInterval = EdoInterval(edo, 0)

  lazy val octave: EdoInterval = EdoInterval(edo, edo)
}

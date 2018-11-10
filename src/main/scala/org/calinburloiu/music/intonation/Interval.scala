package org.calinburloiu.music.intonation

import java.lang.Math.{floor, pow}

import com.google.common.base.Preconditions._
import com.google.common.math.{DoubleMath, IntMath}

import scala.language.implicitConversions
import scala.util.Try

class Interval(val realValue: Double) extends Ordered[Interval] {
  checkArgument(realValue > 0.0 &&
    realValue != Double.PositiveInfinity && realValue != Double.NaN,
    "Expecting a positive finite real value for the interval, but got %s", realValue)

  def cents: Double = Converters.fromRealValueToCents(realValue)

  def isNormalized: Boolean = realValue >= 1 && realValue < 2

  def normalize: Interval =
    if (isNormalized) this else new Interval(realValue * normalizationFactor)

  def +(operand: Interval): Interval = Interval(this.realValue * operand.realValue)

  def -(operand: Interval): Interval = Interval(this.realValue / operand.realValue)

  def invert: Interval = {
    checkArgument(this >= Interval.UNISON && this <= Interval.OCTAVE,
      "Expecting this to between an unison and an octave, inclusively, but got %s", this)

    Interval.OCTAVE - this
  }

  def toStringLengths: Interval = Interval(1.0 / this.realValue)

  def isUnison: Boolean = {
    realValue == 1
  }

  def normalizationFactorExp: Double = -floor(DoubleMath.log2(realValue))

  def normalizationFactor: Double = pow(2, normalizationFactorExp)

  def canEqual(other: Any): Boolean = other.isInstanceOf[Interval]

  override def equals(other: Any): Boolean = other match {
    case that: Interval =>
      (that canEqual this) &&
        realValue == that.realValue
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(realValue)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def compare(that: Interval): Int = this.realValue.compareTo(that.realValue)

  override def toString = s"Interval($realValue, $cents ¢)"
}

object Interval {

  val UNISON = Interval(1.0)
  val OCTAVE = Interval(2.0)

  def apply(realValue: Double): Interval = new Interval(realValue)

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


case class RatioInterval(
  numerator: Int,
  denominator: Int
) extends Interval(numerator.toDouble / denominator.toDouble) {

  override def normalize: RatioInterval = {
    if (isNormalized) {
      this
    } else {
      val (a, b) = if (normalizationFactorExp > 0)
        (numerator * pow(2, normalizationFactorExp).toInt, denominator)
      else
        (numerator, denominator * pow(2, -normalizationFactorExp).toInt)
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
    case ratioInterval: RatioInterval => this + ratioInterval
    case interval: Interval => super.+(interval)
  }

  def -(that: RatioInterval): RatioInterval = {
    val a = this.numerator * that.denominator
    val b = this.denominator * that.numerator
    val gcd = IntMath.gcd(a, b)

    RatioInterval(a / gcd, b / gcd)
  }

  override def -(that: Interval): Interval = that match {
    case ratioInterval: RatioInterval => this - ratioInterval
    case interval: Interval => super.-(interval)
  }

  override def invert: RatioInterval = {
    checkArgument(this >= RatioInterval.UNISON && this <= RatioInterval.OCTAVE,
      "Expecting this to between an unison and an octave, inclusively, but got %s", this)

    RatioInterval.OCTAVE - this
  }


  override def toStringLengths: Interval = RatioInterval(this.denominator, this.numerator)

  override def isUnison: Boolean = {
    numerator == 1 && denominator == 1
  }

  override def toString: String = s"$numerator/$denominator"
}

object RatioInterval {

  val UNISON = RatioInterval(1, 1)
  val OCTAVE = RatioInterval(2, 1)

  implicit def fromPair(pair: (Int, Int)): RatioInterval = RatioInterval(pair._1, pair._2)

  // TODO Don't forget to change Ints to Longs here as well!
  implicit class InfixOperator(denominator: Int) {

    // Using a right-associative infix operator in order to make it precede + and - operators.
    def /:(numerator: Int): RatioInterval = RatioInterval(numerator, denominator)
  }
}


case class CentsInterval(
  override val cents: Double
) extends Interval(Converters.fromCentsToRealValue(cents)) {

  override def normalize: CentsInterval = {
    if (isNormalized) {
      this
    } else {
      if (cents >= 0) CentsInterval(cents % 1200.0) else CentsInterval(1200.0 + cents % 1200.0)
    }
  }

  def +(that: CentsInterval): CentsInterval = CentsInterval(this.cents + that.cents)

  override def +(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this + centsInterval
    case interval: Interval => super.+(interval)
  }

  def -(that: CentsInterval): CentsInterval = CentsInterval(this.cents - that.cents)

  override def -(that: Interval): Interval = that match {
    case centsInterval: CentsInterval => this - centsInterval
    case interval: Interval => super.-(interval)
  }

  override def invert: CentsInterval = {
    checkArgument(this >= CentsInterval.UNISON && this <= CentsInterval.OCTAVE,
      "Expecting this to between an unison and an octave, inclusively, but got %s", this)

    CentsInterval.OCTAVE - this
  }


  override def toStringLengths: Interval = CentsInterval(-this.cents)

  override def isUnison: Boolean = {
    cents == 0.0
  }

  override def toString: String = s"$cents ¢"
}

object CentsInterval {

  val UNISON = CentsInterval(0.0)
  val OCTAVE = CentsInterval(1200.0)

  implicit class PostfixOperator(centsValue: Double) {

    def cent: CentsInterval = CentsInterval(centsValue)
    def cents: CentsInterval = CentsInterval(centsValue)
  }
}

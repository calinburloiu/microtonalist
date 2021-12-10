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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.intonation._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import scala.language.implicitConversions

class ScalaTuningFileFormatTest extends AnyFlatSpec with Matchers {

  "Reading a .scl file with invalid header" should "throw InvalidScalaTuningFileException" in testFailure(
    "description",
    Some("it should have at least 2 lines")
  )

  "Reading a .scl file with invalid pitch count" should "throw InvalidScalaTuningFileException" in testFailure(
    """description
      |three
      |100.0
      |204.2""",
    Some("the number of pitches is not a number")
  )

  "Reading a .scl file with a non-number pitch" should "throw InvalidScalaTuningFileException" in testFailure(
    """Scale with pitches in cents
      |3
      |100.2
      |x
      |298.1269""",
    Some("the value of pitch with index")
  )

  "Reading a .scl file with a negative cents value" should "throw InvalidScalaTuningFileException" in testFailure(
    """Scale with pitches in cents
      |3
      |100.2
      |-201.451
      |298.1269""",
    Some("the value of pitch with index")
  )

  "Reading a .scl file with a negative ratio value" should "throw InvalidScalaTuningFileException" in testFailure(
    """Scale with pitches in cents
      |3
      |100.2
      |-9/8
      |298.1269""",
    Some("the value of pitch with index")
  )

  "Reading a .scl file with a high numerator for the ratio value" should "throw InvalidScalaTuningFileException" in
    testFailure(
      """Scale with pitches in cents
        |3
        |100.2
        |2147483648/8
        |298.1269""",
      Some("the value of pitch with index")
    )

  "Reading a .scl file with a high denominator for the ratio value" should "throw InvalidScalaTuningFileException" in
    testFailure(
      """Scale with pitches in cents
        |3
        |100.2
        |2147483/2147483648
        |298.1269""",
      Some("the value of pitch with index")
    )

  "Reading a .scl file with pitches in cents" should "correctly create a CentsScale object" in testSuccess(
    """Scale with pitches in cents
      |3
      |100.2
      |201.451
      |298.1269""",
    CentsScale("Scale with pitches in cents", 0.0, 100.2, 201.451, 298.1269)
  )

  "Reading a .scl file with pitches as ratios" should "correctly create a RatiosScale object" in testSuccess(
    """Scale with pitches as ratios
      |12
      |16/15
      |9/8
      |6/5
      |5/4
      |4/3
      |7/5
      |3/2
      |8/5
      |5/3
      |7/4
      |15/8
      |2""",
    RatiosScale("Scale with pitches as ratios", (1, 1), (16, 15), (9, 8), (6, 5), (5, 4), (4, 3),
      (7, 5), (3, 2), (8, 5), (5, 3), (7, 4), (15, 8), (2, 1))
  )

  "Reading a .scl file with mixed pitches, both as ratios and in cents" should
    "correctly create a Scale object, which contain both CentsPitches and RatioPitches" in
    testSuccess(
      """Scale with pitches as both ratios and cents
        |4
        |100.2
        |9/8
        |300.
        |5/4""",
      Scale("Scale with pitches as both ratios and cents", RealInterval.Unison,
        CentsInterval(100.2), RatioInterval(9, 8), CentsInterval(300.0), RatioInterval(5, 4))
    )

  "While reading a .scl file, comment lines" should "be ignored" in testSuccess(
    """! scale.scl
      |!
      |Scale with pitches as both ratios and cents!
      |!4 pitches
      |4
      |! Approximately a semitone
      |100.2cents
      |!	(here is a tab) a just intonation whole tone
      |9/8
      |!3 hundred cents here!
      |300.0
      |!6/5
      |! 32/27
      |!300.0
      |! 301.02
      |5/4
      |! THE END! See you soon!""",
    Scale("Scale with pitches as both ratios and cents!", RealInterval.Unison,
      CentsInterval(100.2), RatioInterval(9, 8), CentsInterval(300.0), RatioInterval(5, 4))
  )

  "While reading a .scl file, anything after a valid pitch value should be ignored" should "be ignored" in testSuccess(
    """Scale with pitches as both ratios and cents!
      |4
      |100.2cents
      |9/8	(here is a tab) a just intonation whole tone
      |300.0 3 hundred cents here!
      |5/4aPureMajorThird""",
    Scale("Scale with pitches as both ratios and cents!", RealInterval.Unison,
      CentsInterval(100.2), RatioInterval(9, 8), CentsInterval(300.0), RatioInterval(5, 4))
  )

  "While reading a .scl file, any white space around a pitch" should "be ignored" in testSuccess(
    """Scale with pitches as both ratios and cents!
      |4
      | 100.2
      |    9/8
      |		300.0 3
      |	5/4""",
    Scale("Scale with pitches as both ratios and cents!", RealInterval.Unison,
      CentsInterval(100.2), RatioInterval(9, 8), CentsInterval(300.0), RatioInterval(5, 4))
  )

  "Reading a .scl file with less pitches than expected" should
    "throw InvalidScalaTuningFileException" in testFailure(
    """Scale with pitches as both ratios and cents
      |4
      |100.2
      |9/8""",
    Some("were present in the file")
  )

  "When reading a .scl file with more pitches than expected, the unexpected pitches" should
    "be ignored" in
    testSuccess(
      """Scale with pitches as both ratios and cents
        |2
        |100.2
        |9/8
        |300.0 3
        |5/4""",
      Scale("Scale with pitches as both ratios and cents", RealInterval.Unison,
        CentsInterval(100.2), RatioInterval(9, 8))
    )

  "Reading a .scl file with zero pitches" should "be acceptable, it implies the base note" +
    "(1/1, 0 cents)" in
    testSuccess(
      """Scale with 0 pitches
        |0""",
      Scale("Scale with 0 pitches", CentsInterval.Unison)
    )

  def testFailure(scalaTuningFileString: String, maybeMessageContained: Option[String] = None): Unit = {
    val input = scalaTuningFileString.stripMargin.toInputStream

    val caught = intercept[InvalidScalaTuningFileException] {
      ScalaTuningFileFormat.read(input)
    }

    maybeMessageContained match {
      case None => // succeed
      case Some(messageContained) =>
        caught.getMessage should include(messageContained)
    }
  }

  def testSuccess(scalaTuningFileString: String, expectedScale: Scale[Interval]): Unit = {
    val input = scalaTuningFileString.stripMargin.toInputStream

    val actualScale = ScalaTuningFileFormat.read(input)

    actualScale.getClass shouldEqual expectedScale.getClass
    actualScale shouldEqual expectedScale
  }

  implicit class Implicit(str: String) {
    implicit def toInputStream: InputStream =
      new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8))
  }

}

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

import org.calinburloiu.music.intonation.{Interval, RatioInterval, Scale}
import org.scalatest.Assertion
import org.scalatest.flatspec.{AnyFlatSpec, AsyncFlatSpec}
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Format, Json, Reads}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.Failure

class DeferrableReadTest extends AsyncFlatSpec with Matchers {
  case class Person(name: String, age: Int)
  case class Import(`import`: String)
  case class Profile(id: String,
                     person: DeferrableRead[Person, Import],
                     friends: Seq[DeferrableRead[Person, Import]])

  val personFormat: Format[Person] = Json.format[Person]
  val importFormat: Format[Import] = Json.format[Import]
  implicit val personDeferrableFormat: Format[DeferrableRead[Person, Import]] =
    DeferrableRead.format(personFormat, importFormat)
  implicit val profileFormat: Format[Profile] = Json.format[Profile]

  behavior of DeferrableRead.getClass.getSimpleName

  it should "read a JSON with non-deferred data" in {
    // Given
    val json = Json.obj(
      "id" -> "123",
      "person" -> Json.obj(
        "name" -> "John",
        "age" -> 25
      ),
      "friends" -> Json.arr(
        Json.obj(
          "name" -> "George",
          "age" -> 26
        ),
        Json.obj(
          "name" -> "Paul",
          "age" -> 24
        )
      )
    )

    // When
    val profile = json.as[Profile]
    // Then
    profile shouldEqual Profile(
      id = "123",
      person = AlreadyRead(Person("John", 25)),
      friends = Seq(AlreadyRead(Person("George", 26)), AlreadyRead(Person("Paul", 24)))
    )
  }

  private val profile = Profile(
    id = "123",
    person = DeferredRead(Import("https://example.org/persons/john")),
    friends = Seq(
      DeferredRead(Import("https://example.org/persons/george")),
      AlreadyRead(Person("Mary", 19)),
      DeferredRead(Import("https://example.org/persons/paul"))
    )
  )
  private val jsonProfile = Json.obj(
    "id" -> "123",
    "person" -> Json.obj(
      "import" -> "https://example.org/persons/john"
    ),
    "friends" -> Json.arr(
      Json.obj(
        "import" -> "https://example.org/persons/george"
      ),
      Json.obj(
        "name" -> "Mary",
        "age" -> 19
      ),
      Json.obj(
        "import" -> "https://example.org/persons/paul"
      )
    )
  )

  it should "read a JSON with deferred data and then load that data" in {
    // When
    val actualProfile = jsonProfile.as[Profile]
    // Then
    actualProfile shouldEqual profile

    val futures = ArrayBuffer[Future[Assertion]]()

    // Successfully load data
    val john = Person("John", 25)
    futures += actualProfile.person.load { placeholder =>
      placeholder.`import` shouldEqual "https://example.org/persons/john"
      Future(john)
    }.map { person => person shouldEqual john }
    actualProfile.person.futureValue.map { person => person shouldEqual john }

    // Loading the second time has no effect
    futures += actualProfile.person.load { _ => Future(Person("Max", 50)) }
      .map { person => person shouldEqual john }

    // Loading already loaded data does nothing
    val mary = Person("Mary", 19)
    actualProfile.friends(1).load { _ => Future(Person("Susan", 37)) }
      .map { person => person shouldEqual mary }
    actualProfile.friends(1).futureValue.map { person => person shouldEqual mary }

    // Immediately fail to load data
    val exception = intercept[RuntimeException] {
      actualProfile.friends.head.load { placeholder =>
        placeholder.`import` shouldEqual "https://example.org/persons/george"
        throw new RuntimeException("epic failure")
      }
    }
    exception.getMessage shouldEqual "epic failure"

    actualProfile.friends(2).status shouldEqual DeferrableReadStatus.Unloaded
    assertThrows[NoSuchElementException] { actualProfile.friends(2).value }

    // Eventually fail to load data
    actualProfile.friends(2).load { placeholder =>
      placeholder.`import` shouldEqual "https://example.org/persons/paul"
      Future { throw new RuntimeException("another epic failure") }
    }.onComplete {
      case Failure(exception) => exception.getMessage shouldEqual "another epic failure"
    }

    Future.sequence(futures).map { v =>
      actualProfile.person.value shouldEqual john
      actualProfile.person.status shouldEqual DeferrableReadStatus.Loaded

      actualProfile.friends(1).value shouldEqual mary
      actualProfile.friends(1).status shouldEqual DeferrableReadStatus.Loaded

      val exception = intercept[RuntimeException] { actualProfile.friends(2).value }
      actualProfile.friends(2).status shouldEqual DeferrableReadStatus.FailedLoad(exception)

      val exception2 = intercept[RuntimeException] { actualProfile.friends.head.value }
      actualProfile.friends.head.status shouldEqual DeferrableReadStatus.FailedLoad(exception2)

      v.head
    }
  }

  it should "write an object with deferred data as JSON" in {
    profileFormat.writes(profile) shouldEqual jsonProfile
  }
}

class RefTest extends AnyFlatSpec with Matchers {

  implicit val scaleReads: Reads[Scale[Interval]] = JsonScaleFormat.jsonAllScaleReads

  "refReads" should "read a JSON containing only a reference as an UnresolvedRef" in {
    // Arrange
    val unresolvedRefJson = Json.obj(
      "ref" -> "/path/to/scale.scl"
    )

    // Act
    val unresolvedRefResult = Ref.refReads[Scale[Interval]].reads(unresolvedRefJson)

    // Assert
    unresolvedRefResult.asOpt should contain(UnresolvedRef("/path/to/scale.scl"))
  }

  it should "read a JSON without a reference as a NoRef " +
    "and correctly parse the containing value" in {
    // Arrange
    val scaleJson = Json.obj(
      "name" -> "minor triad",
      "intervals" -> Json.arr("6/5", "3/2")
    )

    // Act
    val noRefResult = Ref.refReads[Scale[Interval]].reads(scaleJson)

    // Assert
    noRefResult.asOpt should contain(
      NoRef(Scale("minor triad", RatioInterval(1, 1), RatioInterval(6, 5), RatioInterval(3, 2)))
    )
  }

  it should "read a JSON containing both a reference and the expected value as a ResolvedRef" in {
    // Arrange
    val resolvedRefJson = Json.obj(
      "ref" -> "/path/to/scale.scl",
      "name" -> "minor triad",
      "intervals" -> Json.arr("6/5", "3/2")
    )

    // Act
    val resolvedRefResult = Ref.refReads[Scale[Interval]].reads(resolvedRefJson)

    // Assert
    resolvedRefResult.asOpt should contain(
      ResolvedRef(
        "/path/to/scale.scl",
        Scale("minor triad", RatioInterval(1, 1), RatioInterval(6, 5), RatioInterval(3, 2))
      )
    )
  }

  it should "fail to read a JSON which either does not contain a reference either " +
    "it does not conform to the expected value" in {
    // Arrange
    val badJson = Json.obj("name" -> "John", "age" -> 20)
    val badJsonWithRef = Json.obj("ref" -> "/a/b/c", "name" -> "John", "age" -> 20)

    // Act
    val badJsonResult = Ref.refReads[Scale[Interval]].reads(badJson)
    val badJsonWithRefResult = Ref.refReads[Scale[Interval]].reads(badJsonWithRef)

    // Assert
    badJsonResult.asOpt should be(empty)
    badJsonWithRefResult.asOpt should be(empty)
  }
}

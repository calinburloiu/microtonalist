/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Format, Json}

import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success}

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

  behavior of classOf[DeferrableRead[?, ?]].getSimpleName

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

    val futures = ArrayBuffer[Future[Any]]()

    // Successfully load data
    val john = Person("John", 25)
    futures += actualProfile.person.load { placeholder =>
      placeholder.`import` shouldEqual "https://example.org/persons/john"
      Future(john)
    }.flatMap { person =>
      person shouldEqual john

      actualProfile.person.futureValue
    }.flatMap { person =>
      person shouldEqual john

      // Loading the second time has no effect
      actualProfile.person.load { _ => Future(Person("Max", 50)) }
    }.map { person =>
      person shouldEqual john
    }

    // Loading already loaded data does nothing
    val mary = Person("Mary", 19)
    futures += actualProfile.friends(1)
      .load { _ => Future(Person("Susan", 37)) }
      .flatMap { person =>
        person shouldEqual mary
        actualProfile.friends(1).futureValue
      }.map { person => person shouldEqual mary }

    // Immediately fail to load data
    val exception = intercept[RuntimeException] {
      futures += actualProfile.friends.head.load { placeholder =>
        placeholder.`import` shouldEqual "https://example.org/persons/george"
        throw new RuntimeException("epic failure")
      }
    }
    exception.getMessage shouldEqual "epic failure"

    actualProfile.friends(2).status shouldEqual DeferrableReadStatus.Unloaded
    assertThrows[NoSuchElementException] {
      actualProfile.friends(2).value
    }

    // Eventually fail to load data
    futures += actualProfile.friends(2).load { placeholder =>
      placeholder.`import` shouldEqual "https://example.org/persons/paul"
      Future {
        throw new RuntimeException("another epic failure")
      }
    }.transform {
      case Failure(exception) => Success(exception.getMessage shouldEqual "another epic failure")
      case _ => fail("expected a failure")
    }

    Future.sequence(futures).map { v =>
      actualProfile.person.value shouldEqual john
      actualProfile.person.status shouldEqual DeferrableReadStatus.Loaded

      actualProfile.friends(1).value shouldEqual mary
      actualProfile.friends(1).status shouldEqual DeferrableReadStatus.Loaded

      val exception1 = intercept[RuntimeException] {
        actualProfile.friends(2).value
      }
      actualProfile.friends(2).status shouldEqual DeferrableReadStatus.FailedLoad(exception1)

      val exception2 = intercept[RuntimeException] {
        actualProfile.friends.head.value
      }
      actualProfile.friends.head.status shouldEqual DeferrableReadStatus.FailedLoad(exception2)
    }
  }

  it should "write an object with deferred data as JSON" in {
    profileFormat.writes(profile) shouldEqual jsonProfile
  }

  "reading" should "be blocked while writing via load" in {
    val actualProfile = jsonProfile.as[Profile]
    val lock: Lock = new ReentrantLock()
    val john = Person("John", 25)
    actualProfile.person.load { _ =>
      lock.lock()
      Future(john)
    }

    val value = Future {
      actualProfile.person.futureValue
    }.flatten
    value.isCompleted shouldBe false

    lock.unlock()
    value.map { v => v shouldEqual john }
  }
}

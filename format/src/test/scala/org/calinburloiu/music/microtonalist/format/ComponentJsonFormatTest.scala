/*
 * Copyright 2024 Calin-Andrei Burloiu
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

import org.scalatest.{BeforeAndAfterEach, Inside}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsError, JsNull, JsString, JsSuccess, JsValue, Json, JsonValidationError}

class ComponentJsonFormatTest extends AnyFlatSpec with Matchers with Inside {
  private sealed trait Animals

  private case class Domestic(cat: String, dog: String) extends Animals

  private case class Forest(fox: String, rabbit: String, squirrel: String, wolf: String) extends Animals

  private case class Jungle(lion: String, snake: String) extends Animals

  private case object Sea extends Animals

  private val FamilyNameAnimals = "animals"
  private val TypeNameDomestic = "domestic"
  private val TypeNameForest = "forest"
  private val TypeNameJungle = "jungle"
  private val TypeNameSea = "sea"

  private val format: ComponentJsonFormat[Animals] = createFormat(Some(TypeNameDomestic))
  format.rootGlobalSettings = Json.obj(
    "animals" -> Json.obj(
      "domestic" -> Json.obj(
        "dog" -> "Scooby"
      ),
      "forest" -> Json.obj(
        "fox" -> "Jumpy",
        "rabbit" -> "Gump",
        "wolf" -> "Daos"
      )
    )
  )

  private def createFormat(defaultTypeName: Option[String]): ComponentJsonFormat[Animals] = new ComponentJsonFormat(
    FamilyNameAnimals,
    Seq(
      ComponentJsonFormat.TypeSpec.withSettings[Domestic](TypeNameDomestic, Json.format[Domestic], classOf[Domestic]),
      ComponentJsonFormat.TypeSpec.withSettings[Forest](TypeNameForest, Json.format[Forest], classOf[Forest], Json.obj(
        "rabbit" -> "Bugs Bunny",
        "squirrel" -> "Nutz",
        "wolf" -> "White Fang"
      )),
      ComponentJsonFormat.TypeSpec.withSettings[Jungle](TypeNameJungle, Json.format[Jungle], classOf[Jungle], Json.obj(
        "lion" -> "King",
        "snake" -> "Monty"
      )),
      ComponentJsonFormat.TypeSpec.withNoSettings(TypeNameSea, Sea)
    ),
    defaultTypeName
  )

  private def assertReads(json: JsValue, result: Animals): Unit =
    format.reads(json) should matchPattern { case JsSuccess(`result`, _) => }

  private def assertReadsFailure(json: JsValue,
                                 error: JsonValidationError,
                                 customFormat: ComponentJsonFormat[Animals] = format): Unit =
    customFormat.reads(json) should matchPattern { case JsError(Seq((_, Seq(`error`)))) => }

  "reads" should "parse a component with type property and all properties given" in {
    val json = Json.obj(
      "type" -> TypeNameForest,
      "fox" -> "Lady",
      "rabbit" -> "Snowy",
      "squirrel" -> "Cheeks",
      "wolf" -> "Jack"
    )
    assertReads(json, Forest("Lady", "Snowy", "Cheeks", "Jack"))
  }

  it should "parse a component without a type property and use the default type provided" in {
    val json = Json.obj("cat" -> "Mitzi", "dog" -> "Pamela")
    assertReads(json, Domestic("Mitzi", "Pamela"))
  }

  it should "fail to parse a component without a type property when the format does not provide a default type" in {
    val format2 = createFormat(defaultTypeName = None)
    val json = Json.obj("cat" -> "Mitzi", "dog" -> "Pamela")
    assertReadsFailure(json, ComponentJsonFormat.MissingTypeError, format2)
  }

  it should "parse a component expressed as a type string when all settings have defaults" in {
    assertReads(Json.toJson(TypeNameJungle), Jungle("King", "Monty"))
  }

  it should "fail to parse a component expressed as a type string when not all settings have defaults" in {
    format.reads(Json.toJson(TypeNameDomestic)).isError shouldBe true
  }

  it should "fail to parse components with unknown type" in {
    assertReadsFailure(Json.toJson("foo"), ComponentJsonFormat.UnrecognizedTypeError)
    assertReadsFailure(Json.obj("type" -> "bar", "x" -> 3), ComponentJsonFormat.UnrecognizedTypeError)
  }

  it should "parse components whose missing settings are taken from defaults or global settings" in {
    val domesticJson = Json.obj(
      "type" -> TypeNameDomestic,
      "cat" -> "Tom"
    )
    // dog is taken from global settings
    assertReads(domesticJson, Domestic("Tom", "Scooby"))

    val forestJson = Json.obj(
      "type" -> TypeNameForest,
      "fox" -> "Sebastian",
      "rabbit" -> "Norris",
      "squirrel" -> "Mark"
    )
    // wolf is taken from global settings
    assertReads(forestJson, Forest("Sebastian", "Norris", "Mark", "Daos"))

    val jungleJson = Json.obj(
      "type" -> TypeNameJungle,
      "lion" -> "Chris"
    )
    // snake is taken from defaults
    assertReads(jungleJson, Jungle("Chris", "Monty"))
  }

  it should "fail to parse a component that is not a type name string or valid object" in {
    assertReadsFailure(Json.toJson(3), ComponentJsonFormat.InvalidError)
    assertReadsFailure(JsNull, ComponentJsonFormat.InvalidError)
    assertReadsFailure(Json.arr(1, 2), ComponentJsonFormat.InvalidError)
  }

  it should "parse a component with no settings" in {
    assertReads(JsString(TypeNameSea), Sea)
    assertReads(Json.obj("type" -> TypeNameSea), Sea)
  }

  "writes" should "serialize as JSON Scala component objects" in {
    val domestic = Domestic("Tom", "Scooby")
    format.writes(domestic) shouldEqual Json.obj(
      "type" -> TypeNameDomestic,
      "cat" -> "Tom",
      "dog" -> "Scooby"
    )

    format.writes(Sea) shouldEqual JsString(TypeNameSea)
  }
}

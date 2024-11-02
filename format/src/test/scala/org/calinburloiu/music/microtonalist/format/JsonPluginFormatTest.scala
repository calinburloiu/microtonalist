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

import play.api.libs.json._

object JsonPluginFormatTest {
  sealed trait Animals

  case class Domestic(cat: String, dog: String) extends Animals

  case class Forest(fox: String, rabbit: String, squirrel: String, wolf: String) extends Animals

  case class Jungle(lion: String, snake: String) extends Animals

  case object Sea extends Animals
}

class JsonPluginFormatTest extends JsonFormatTestUtils {

  import JsonPluginFormatTest._

  private val FamilyNameAnimals = "animals"
  private val TypeNameDomestic = "domestic"
  private val TypeNameForest = "forest"
  private val TypeNameJungle = "jungle"
  private val TypeNameSea = "sea"

  private val rootGlobalSettings: JsObject = Json.obj(
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
  private val jsonPluginFormat: JsonPluginFormat[Animals] = createJsonPluginFormat(Some(TypeNameDomestic))
  private val format: Format[Animals] = jsonPluginFormat.formatWithRootGlobalSettings(rootGlobalSettings)

  private def createJsonPluginFormat(defaultTypeName: Option[String]): JsonPluginFormat[Animals] =
    JsonPluginFormat.apply(
      FamilyNameAnimals,
      Seq(
        JsonPluginFormat.TypeSpec.withSettings[Domestic](TypeNameDomestic, Json.format[Domestic], classOf[Domestic]),
        JsonPluginFormat.TypeSpec.withSettings[Forest](TypeNameForest, Json.format[Forest], classOf[Forest], Json
          .obj(
            "rabbit" -> "Bugs Bunny",
            "squirrel" -> "Nutz",
            "wolf" -> "White Fang"
          )),
        JsonPluginFormat.TypeSpec.withSettings[Jungle](TypeNameJungle, Json.format[Jungle], classOf[Jungle], Json
          .obj(
            "lion" -> "King",
            "snake" -> "Monty"
          )),
        JsonPluginFormat.TypeSpec.withoutSettings(TypeNameSea, Sea)
      ),
      defaultTypeName
    )

  "reading" should "parse a plugin with type property and all properties given" in {
    val json = Json.obj(
      "type" -> TypeNameForest,
      "fox" -> "Lady",
      "rabbit" -> "Snowy",
      "squirrel" -> "Cheeks",
      "wolf" -> "Jack"
    )
    assertReads(format, json, Forest("Lady", "Snowy", "Cheeks", "Jack"))
  }

  it should "parse a plugin without a type property and use the default type provided" in {
    val json = Json.obj("cat" -> "Mitzi", "dog" -> "Pamela")
    assertReads(format, json, Domestic("Mitzi", "Pamela"))
  }

  it should "fail to parse a plugin without a type property when the format does not provide a default type" in {
    val format2 = createJsonPluginFormat(defaultTypeName = None).format
    val json = Json.obj("cat" -> "Mitzi", "dog" -> "Pamela")
    assertReadsSingleFailure(format2, json, JsonPluginFormat.MissingTypeError)
  }

  it should "parse a plugin expressed as a type string when all settings have defaults" in {
    assertReads(format, Json.toJson(TypeNameJungle), Jungle("King", "Monty"))
  }

  it should "fail to parse a plugin expressed as a type string when not all settings have defaults" in {
    format.reads(Json.toJson(TypeNameDomestic)).isError shouldBe true
  }

  it should "fail to parse plugins with unknown type" in {
    assertReadsSingleFailure(format, Json.toJson("foo"), JsonPluginFormat.UnrecognizedTypeError)
    assertReadsSingleFailure(format, Json.obj("type" -> "bar", "x" -> 3), JsonPluginFormat.UnrecognizedTypeError)
  }

  it should "parse plugins whose missing settings are taken from defaults or global settings" in {
    val domesticJson = Json.obj(
      "type" -> TypeNameDomestic,
      "cat" -> "Tom"
    )
    // dog is taken from global settings
    assertReads(format, domesticJson, Domestic("Tom", "Scooby"))

    val forestJson = Json.obj(
      "type" -> TypeNameForest,
      "fox" -> "Sebastian",
      "rabbit" -> "Norris",
      "squirrel" -> "Mark"
    )
    // wolf is taken from global settings
    assertReads(format, forestJson, Forest("Sebastian", "Norris", "Mark", "Daos"))

    val jungleJson = Json.obj(
      "type" -> TypeNameJungle,
      "lion" -> "Chris"
    )
    // snake is taken from defaults
    assertReads(format, jungleJson, Jungle("Chris", "Monty"))
  }

  it should "fail to parse a plugin that is not a type name string or valid object" in {
    assertReadsSingleFailure(format, Json.toJson(3), JsonPluginFormat.InvalidError)
    assertReadsSingleFailure(format, JsNull, JsonPluginFormat.InvalidError)
    assertReadsSingleFailure(format, Json.arr(1, 2), JsonPluginFormat.InvalidError)
  }

  it should "parse a plugin with no settings" in {
    assertReads(format, JsString(TypeNameSea), Sea)
    assertReads(format, Json.obj("type" -> TypeNameSea), Sea)
  }

  "writing" should "serialize as JSON Scala plugin objects" in {
    val domestic = Domestic("Tom", "Scooby")
    format.writes(domestic) shouldEqual Json.obj(
      "type" -> TypeNameDomestic,
      "cat" -> "Tom",
      "dog" -> "Scooby"
    )

    format.writes(Sea) shouldEqual JsString(TypeNameSea)
  }

  "readDefaultPlugin" should "deserialize a default plugin from the global settings" in {
    jsonPluginFormat.readDefaultPlugin(rootGlobalSettings).asOpt shouldBe empty

    val rootGlobalSettings2: JsObject = Json.obj(
      "animals" -> Json.obj(
        "domestic" -> Json.obj(
          "cat" -> "Felix",
          "dog" -> "Scrappy"
        )
      )
    )
    jsonPluginFormat.readDefaultPlugin(rootGlobalSettings2).asOpt should contain(JsonPluginFormatTest.Domestic(
      cat = "Felix",
      dog = "Scrappy"
    ))
  }
}

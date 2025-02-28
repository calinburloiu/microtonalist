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

package org.calinburloiu.music.microtonalist.config

import com.typesafe.config.ConfigFactory
import org.calinburloiu.music.microtonalist.config.ConfigSerDe._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class ConfigSerDeTest extends AnyFlatSpec with Matchers {

  "createHoconValue" should "create HOCON singleton values" in {
    createHoconValue(10).unwrapped() shouldEqual 10
    createHoconValue(3.14).unwrapped() shouldEqual 3.14
    createHoconValue(true).unwrapped() shouldEqual true
    createHoconValue("blah").unwrapped() shouldEqual "blah"
  }

  it should "create HOCON lists from scala Seq" in {
    createHoconValue(Seq(1, 2, 3)).unwrapped() shouldEqual Seq(1, 2, 3).asJava
    createHoconValue(Seq("John", "Doe")).unwrapped() shouldEqual Seq("John", "Doe").asJava
    createHoconValue(Seq(1.5, "apples")).unwrapped() shouldEqual Seq(1.5, "apples").asJava

    val result = createHoconValue(Seq(1, Seq("John", "Doe"))).unwrapped().asInstanceOf[java.util.List[?]]
    result.get(0) shouldEqual 1
    result.get(1) shouldEqual Seq("John", "Doe").asJava
  }

  it should "create HOCON objects from scala Map" in {
    val m1 = Map("a" -> 1, "b" -> 3)
    createHoconValue(m1).unwrapped() shouldEqual m1.asJava

    val result = createHoconValue(Map("x" -> 10, "y" -> m1, "z" -> Seq(1, 5))).unwrapped()
      .asInstanceOf[java.util.Map[_, _]]
    result.get("x") shouldEqual 10
    result.get("y") shouldEqual m1.asJava
    result.get("z") shouldEqual Seq(1, 5).asJava
  }

  "withAnyRefValue" should "not change HOCON object if requesting to replace a value that already exists" in {
    val m1 = Map("a" -> 1, "b" -> 3).asJava
    val hocon = ConfigFactory.parseMap(m1)

    hocon.withAnyRefValue("b", 3) shouldBe theSameInstanceAs(hocon)
  }

  it should "change HOCON object with a new value" in {
    val m1 = Map("a" -> 1, "b" -> 3).asJava
    val hocon = ConfigFactory.parseMap(m1)

    val newHocon = hocon.withAnyRefValue("b", 4)
    newHocon shouldNot be theSameInstanceAs hocon
    newHocon.getInt("b") shouldEqual 4
  }
}

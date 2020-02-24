package org.calinburloiu.music.microtuner

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.calinburloiu.music.microtuner.ConfigSerDe._
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class ConfigSerDeTest extends FlatSpec with Matchers {

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

    val result = createHoconValue(Seq(1, Seq("John", "Doe"))).unwrapped().asInstanceOf[java.util.List[_]]
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

    hocon.withAnyRefValue("b", 3) shouldBe theSameInstanceAs (hocon)
  }

  it should "change HOCON object with a new value" in {
    val m1 = Map("a" -> 1, "b" -> 3).asJava
    val hocon = ConfigFactory.parseMap(m1)

    val newHocon = hocon.withAnyRefValue("b", 4)
    newHocon shouldNot be theSameInstanceAs hocon
    newHocon.getInt("b") shouldEqual 4
  }
}

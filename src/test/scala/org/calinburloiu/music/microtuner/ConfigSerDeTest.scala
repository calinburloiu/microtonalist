package org.calinburloiu.music.microtuner

import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class ConfigSerDeTest extends FlatSpec with Matchers {

  "createHoconValue" should "create HOCON singleton values" in {
    ConfigSerDe.createHoconValue(10).unwrapped() shouldEqual 10
    ConfigSerDe.createHoconValue(3.14).unwrapped() shouldEqual 3.14
    ConfigSerDe.createHoconValue(true).unwrapped() shouldEqual true
    ConfigSerDe.createHoconValue("blah").unwrapped() shouldEqual "blah"
  }

  it should "create HOCON lists from scala Seq" in {
    ConfigSerDe.createHoconValue(Seq(1, 2, 3)).unwrapped() shouldEqual Seq(1, 2, 3).asJava
    ConfigSerDe.createHoconValue(Seq("John", "Doe")).unwrapped() shouldEqual Seq("John", "Doe").asJava
    ConfigSerDe.createHoconValue(Seq(1.5, "apples")).unwrapped() shouldEqual Seq(1.5, "apples").asJava

    val result = ConfigSerDe.createHoconValue(Seq(1, Seq("John", "Doe"))).unwrapped().asInstanceOf[java.util.List[_]]
    result.get(0) shouldEqual 1
    result.get(1) shouldEqual Seq("John", "Doe").asJava
  }

  it should "create HOCON objects from scala Map" in {
    val m1 = Map("a" -> 1, "b" -> 3)
    ConfigSerDe.createHoconValue(m1).unwrapped() shouldEqual m1.asJava

    val result = ConfigSerDe.createHoconValue(Map("x" -> 10, "y" -> m1, "z" -> Seq(1, 5))).unwrapped()
      .asInstanceOf[java.util.Map[_, _]]
    result.get("x") shouldEqual 10
    result.get("y") shouldEqual m1.asJava
    result.get("z") shouldEqual Seq(1, 5).asJava
  }
}

package org.calinburloiu.music.microtuner

import com.typesafe.config.{Config, ConfigValue, ConfigValueFactory}

import scala.collection.JavaConverters._

object ConfigSerDe {

  def createHoconValue(value: Any): ConfigValue = value match {
    case seq: Seq[_] => ConfigValueFactory.fromIterable(seq.map { v: Any => createHoconValue(v) }.asJava)
    case map: Map[_, _] =>
      val convertedMap = map.map { case (k: Any, v: Any) =>
        (k.toString, createHoconValue(v))
      }.asJava
      ConfigValueFactory.fromMap(convertedMap)
    case any: Any => ConfigValueFactory.fromAnyRef(any)
  }

  implicit class HoconConfigExtension(hoconConfig: Config) {

    def withAnyRefValue(path: String, value: AnyRef): Config = value match {
      case _: Seq[_] | _: Map[_, _] =>
        hoconConfig.withValue(path, createHoconValue(value))
      case _: Any =>
        if (hoconConfig.getAnyRef(path) != value) {
          hoconConfig.withValue(path, ConfigValueFactory.fromAnyRef(value))
        }

        hoconConfig
    }
  }
}

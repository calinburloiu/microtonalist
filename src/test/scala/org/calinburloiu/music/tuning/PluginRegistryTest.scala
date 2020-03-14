/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.tuning

import org.calinburloiu.music.plugin.{PluginConfig, PluginRegistry, PluginLocatorException}
import org.scalatest.{FlatSpec, Matchers}

class PluginRegistryTest extends FlatSpec with Matchers {

  val registry = new TuningMapperRegistry

  it should "get a registered TuningMapper by its pluginId" in {
    val factory = registry.get("auto")
    val tuningMapper = factory.create(Some(AutoTuningMapperConfig(false)))
    tuningMapper.getClass shouldEqual classOf[AutoTuningMapper]

    // Check if the plugin instance is cached for a particular config
    tuningMapper should be theSameInstanceAs factory.create(Some(AutoTuningMapperConfig(false)))

    // Trying to create a plugin by using a bad config should throw IllegalArgumentException
    assertThrows[IllegalArgumentException](factory.create(Some(new PluginConfig {})))
  }

  it should "throw PluginLocatorException if an implementation is not found" in {
    val caught = intercept[PluginLocatorException] {
      registry.get("blah_blah_blah")
    }
    caught.getCause.getClass shouldEqual classOf[ClassNotFoundException]
  }

  it should "get a registered TuningMapper by its class fully classified name" in {
    val fqn = classOf[AutoTuningMapperFactory].getCanonicalName
    val factory = registry.get(fqn)
    val tuningMapper = factory.create(Some(AutoTuningMapperConfig(false)))
    tuningMapper.getClass shouldEqual classOf[AutoTuningMapper]
  }

  it should "get an unregistered TuningMapper by its class fully classified name" in {
    val customRegistry = new PluginRegistry[TuningMapper] {
      override def registeredPluginFactories = Seq(new AutoTuningMapperFactory())
    }

    val fqn = classOf[AutoTuningMapperFactory].getCanonicalName
    val factory = customRegistry.get(fqn)
    val tuningMapper = factory.create(Some(AutoTuningMapperConfig(false)))
    tuningMapper.getClass shouldEqual classOf[AutoTuningMapper]
  }
}

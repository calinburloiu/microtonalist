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

import com.typesafe.config.{ConfigFactory, Config => HoconConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

abstract class SubConfigTest[C <: Configured, SCM <: SubConfigManager[C]] extends AnyFlatSpec with Matchers {

  def configResource: String = SubConfigTest.defaultConfigResource

  lazy val initialMainHoconConfig: HoconConfig = ConfigFactory.parseResources(getClass, configResource).resolve()
  lazy val mainConfigManager: MainConfigManager = MainConfigManager(initialMainHoconConfig)

  val subConfigManager: SCM

  def subConfig: C = subConfigManager.config

  val expectedSubConfigRead: C

  val subConfigsToWrite: Seq[C]
  lazy val subConfigsToWriteCount: Int = subConfigsToWrite.size

  getClass.getSimpleName should "correctly read HOCON sub-config" in {
    subConfig shouldEqual expectedSubConfigRead
    mainConfigManager.isDirty shouldBe false
  }

  for (i <- 0 until subConfigsToWriteCount) {
    it should s"correctly read after writing HOCON sub-config ${i + 1} of $subConfigsToWriteCount" in {
      subConfigManager.notifyConfigChanged(subConfigsToWrite(i))
      mainConfigManager.isDirty shouldBe true
      subConfig shouldEqual subConfigsToWrite(i)
    }
  }
}

object SubConfigTest {
  val defaultConfigResource: String = "/microtonalist.conf"
  val defaultConfigResourceWithDefaults: String = "/microtonalist-defaults.conf"
}

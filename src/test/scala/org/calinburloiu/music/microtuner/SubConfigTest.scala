package org.calinburloiu.music.microtuner

import com.typesafe.config.{Config => HoconConfig, ConfigFactory}
import org.scalatest.{FlatSpec, Matchers}

abstract class SubConfigTest[C <: Configured, SCM <: SubConfigManager[C]] extends FlatSpec with Matchers {

  val configResource: String = "/microtonalist.conf"

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
    it should s"correctly read after writing HOCON sub-config ${i+1} of $subConfigsToWriteCount" in {
      subConfigManager.notifyConfigChanged(subConfigsToWrite(i))
      mainConfigManager.isDirty shouldBe true
      subConfig shouldEqual subConfigsToWrite(i)
    }
  }
}

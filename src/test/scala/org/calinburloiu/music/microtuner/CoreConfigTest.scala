package org.calinburloiu.music.microtuner

import java.nio.file.Paths

class CoreConfigTest extends SubConfigTest[CoreConfig, CoreConfigManager] {

  override lazy val subConfigManager: CoreConfigManager = mainConfigManager.coreConfigManager

  override lazy val expectedSubConfigRead: CoreConfig = CoreConfig(
    scaleLibraryPath = Paths.get("/Users/johnny/Music/microtonalist/lib/scales/"),
    metaConfig = MetaConfig(
      saveIntervalMillis = 2000,
      saveOnExit = false
    )
  )

  override lazy val subConfigsToWrite: Seq[CoreConfig] = Seq(
    expectedSubConfigRead.copy(
      metaConfig = MetaConfig(
        saveIntervalMillis = 500,
        saveOnExit = true
      )
    ),
    expectedSubConfigRead.copy(
      scaleLibraryPath = Paths.get("/tmp/scales")
    )
  )
}

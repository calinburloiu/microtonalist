package org.calinburloiu.music.microtuner

import java.nio.file.Path

case class CoreConfig(
  scaleLibraryPath: Path,
  metaConfig: MetaConfig
)

case class MetaConfig(
  saveIntervalMillis: Int,
  saveOnExit: Boolean
)

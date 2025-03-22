/*
 * Copyright 2025 Calin-Andrei Burloiu
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

import java.net.URI

class CoreConfigTest extends SubConfigTest[CoreConfig, CoreConfigManager] {

  override lazy val subConfigManager: CoreConfigManager = mainConfigManager.coreConfigManager

  override lazy val expectedSubConfigRead: CoreConfig = CoreConfig(
    libraryBaseUri = new URI("file:///Users/johnny/Music/microtonalist/lib/scales/"),
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
      libraryBaseUri = new URI("file:///tmp/scales/")
    )
  )
}

class CoreConfigDefaultsTest extends CoreConfigTest {

  override def configResource: String = SubConfigTest.defaultConfigResourceWithDefaults

  override lazy val expectedSubConfigRead: CoreConfig = CoreConfig(
    libraryBaseUri = CoreConfig.defaultLibraryUri,
    metaConfig = MetaConfig(
      saveIntervalMillis = 5000,
      saveOnExit = true
    )
  )

  override lazy val subConfigsToWrite: Seq[CoreConfig] = Seq()
}

/*
 * Copyright 2024 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.microtonalist.composition.SoftChromaticGenusMapping

object JsonSoftChromaticGenusMappingPluginFormat extends JsonPluginFormat[SoftChromaticGenusMapping] {

  override val familyName: String = SoftChromaticGenusMapping.familyName

  override val specs: JsonPluginFormat.TypeSpecs[SoftChromaticGenusMapping] = SoftChromaticGenusMapping.All
    .map { plugin: SoftChromaticGenusMapping => JsonPluginFormat.TypeSpec.withoutSettings(plugin) }

  override val defaultTypeName: Option[String] = Some(SoftChromaticGenusMapping.Off.typeName)
}

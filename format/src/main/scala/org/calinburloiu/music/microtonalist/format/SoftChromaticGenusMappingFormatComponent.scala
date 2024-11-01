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

import org.calinburloiu.music.microtonalist.core.SoftChromaticGenusMapping

object SoftChromaticGenusMappingFormatComponent extends JsonFormatComponentFactory[SoftChromaticGenusMapping] {

  override val familyName: String = "softChromaticGenusMapping"

  override val specs: JsonFormatComponent.TypeSpecs[SoftChromaticGenusMapping] = Seq(
    JsonFormatComponent.TypeSpec.withoutSettings(SoftChromaticGenusMapping.Off.typeName, SoftChromaticGenusMapping.Off),
    JsonFormatComponent.TypeSpec.withoutSettings(SoftChromaticGenusMapping.Strict.typeName, SoftChromaticGenusMapping.Strict),
    JsonFormatComponent.TypeSpec.withoutSettings(SoftChromaticGenusMapping.PseudoChromatic.typeName, SoftChromaticGenusMapping.PseudoChromatic)
  )

  override val defaultTypeName: Option[String] = Some(SoftChromaticGenusMapping.Off.typeName)
}

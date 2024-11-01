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
import play.api.libs.json.{JsString, Reads}

class JsonSoftChromaticGenusMappingPluginFormatTest extends JsonFormatTestUtils {

  private val jsonPluginFormat = JsonSoftChromaticGenusMappingPluginFormat
  private val reads: Reads[SoftChromaticGenusMapping] = jsonPluginFormat.reads

  behavior of "SoftChromaticGenusMapping.Off JSON plugin format"

  it should "deserialize SoftChromaticGenusMapping.Off" in {
    assertReads(reads, JsString("off"), SoftChromaticGenusMapping.Off)
  }

  it should "serialize SoftChromaticGenusMapping.Off" in {
    jsonPluginFormat.writes.writes(SoftChromaticGenusMapping.Off) shouldEqual JsString("off")
  }

  behavior of "SoftChromaticGenusMapping.Strict JSON plugin format"

  it should "deserialize SoftChromaticGenusMapping.Strict" in {
    assertReads(reads, JsString("strict"), SoftChromaticGenusMapping.Strict)
  }

  it should "serialize SoftChromaticGenusMapping.Strict" in {
    jsonPluginFormat.writes.writes(SoftChromaticGenusMapping.Strict) shouldEqual JsString("strict")
  }

  behavior of "SoftChromaticGenusMapping.PseudoChromatic JSON plugin format"

  it should "deserialize SoftChromaticGenusMapping.PseudoChromatic" in {
    assertReads(reads, JsString("pseudoChromatic"), SoftChromaticGenusMapping.PseudoChromatic)
  }

  it should "serialize SoftChromaticGenusMapping.PseudoChromatic" in {
    jsonPluginFormat.writes.writes(SoftChromaticGenusMapping.PseudoChromatic) shouldEqual JsString("pseudoChromatic")
  }
}

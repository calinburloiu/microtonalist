/*
 * Copyright 2026 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.format.JsonPluginFormat.{TypeSpec, TypeSpecs}
import org.calinburloiu.music.microtonalist.tuner.Tuner

/**
 * JSON format for [[Tuner]] plugins, supporting MPE, Monophonic Pitch Bend, and MTS tuners.
 */
object JsonTunerPluginFormat extends JsonPluginFormat[Tuner] {

  override val familyName: String = Tuner.FamilyName

  override val specs: TypeSpecs[Tuner] = Seq[TypeSpec[? <: Tuner]](
    JsonMpeTunerPluginFormating.spec,
    JsonMonophonicPitchBendTunerPluginFormating.spec,
  ) ++ JsonMtsTunerPluginFormating.specs
}

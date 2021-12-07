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

package org.calinburloiu.music.microtonalist.core

import org.calinburloiu.music.intonation._

case class ScaleList(name: String,
                     tuningRef: TuningRef,
                     modulations: Seq[Modulation],
                     tuningReducer: TuningReducer,
                     globalFill: ScaleMapping)

case class Modulation(transposition: Interval,
                      scaleMapping: ScaleMapping,
                      // TODO extension probably needs to be renamed to alterations, overrides or something
                      extension: Option[ScaleMapping] = None)

case class ScaleMapping(scale: Scale[Interval],
                        tuningMapper: TuningMapper) {

  def tuningFor(transposition: Interval, ref: TuningRef): PartialTuning = {
    tuningMapper.mapScale(scale.transpose(transposition), ref)
  }
}

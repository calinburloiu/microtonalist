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

// TODO #4 Document classes in this file

case class Composition(name: String,
                       intonationStandard: IntonationStandard,
                       tuningRef: TuningRef,
                       tuningSpecs: Seq[TuningSpec],
                       tuningReducer: TuningReducer,
                       // TODO #58 Allow incomplete globalFills: tunings can be incomplete; fill them with standard
                       //  tunings.
                       globalFill: Option[TuningSpec])

case class TuningSpec(transposition: Interval,
                      scaleMapping: ScaleMapping)

case class ScaleMapping(scale: Scale[Interval],
                        tuningMapper: TuningMapper) {

  def tuningFor(transposition: Interval, ref: TuningRef): PartialTuning = {
    tuningMapper.mapScale(scale, transposition, ref)
  }
}

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

/**
 * A collection of scales to be mapped to tunings.
 *
 * @param intonationStandard Specifies how intervals from the composition are expressed or interpreted.
 * @param tuningReference    Establishes the relation between the composition base pitch and a pitch class from the
 *                           keyboard instrument.
 * @param tuningSpecs        A sequence of specifications that defines each tuning based on scales.
 * @param tuningReducer      Strategy for reducing the number of tunings by merging them together.
 * @param globalFill         Specification for a tuning that should be used as a fallback for the unused pitch
 *                           classes of the keyboard instrument.
 * @param metadata           Additional information about the composition.
 */
case class Composition(intonationStandard: IntonationStandard,
                       tuningReference: TuningReference,
                       tuningSpecs: Seq[TuningSpec],
                       tuningReducer: TuningReducer,
                       globalFill: Option[TuningSpec] = None,
                       metadata: Option[CompositionMetadata] = None)

/**
 * Defines a tuning based on a scale.
 *
 * @param transposition How should the scale be transposed with respect to the base pitch.
 * @param scale         Scale to be tuned and mapped to a keyboard instrument.
 * @param tuningMapper  Strategy used for mapping a scale to a keyboard instrument.
 */
case class TuningSpec(transposition: Interval,
                      scale: Scale[Interval],
                      tuningMapper: TuningMapper) {

  def tuningFor(ref: TuningReference): PartialTuning = {
    tuningMapper.mapScale(scale, ref, transposition)
  }
}

/**
 * Metadata object used for additional information about a Microtonalist app composition file.
 *
 * Note that not all properties from the JSON-Schema are parsed and available here, only those that are used or are
 * likely to be needed.
 *
 * @param name         Composition name.
 * @param composerName Name of the composer. You may use [[None]] if the composer is anonymous or unknown.
 * @param authorName   The person or organization who created this Microtonalist app tuning file.
 */
case class CompositionMetadata(name: Option[String],
                               composerName: Option[String] = None,
                               authorName: Option[String] = None)

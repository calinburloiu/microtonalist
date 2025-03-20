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

package org.calinburloiu.music.microtonalist.composition

import org.calinburloiu.music.intonation.*
import org.calinburloiu.music.microtonalist.tuner.Tuning

import java.net.URI

/**
 * A collection of scales to be mapped to tunings.
 *
 * @param uri                Optional URI associated with the composition which might have been used to load it from.
 * @param intonationStandard Specifies how intervals from the composition are expressed or interpreted.
 * @param tuningReference    Establishes the relation between the composition base pitch and a pitch class from the
 *                           keyboard instrument.
 * @param tuningSpecs        A sequence of specifications that defines each tuning based on scales.
 * @param tuningReducer      Strategy for reducing the number of tunings by merging them together.
 * @param fill               Specifies filling configuration for the tunings used in the composition.
 * @param metadata           Additional information about the composition.
 * @param tracksUriOverride  Optional override for the URI of the tracks file used for configuring tracks. If not
 *                           provided the URI will be computed by appending `.tracks` to the path of the composition
 *                           URI.
 */
case class Composition(uri: Option[URI],
                       intonationStandard: IntonationStandard,
                       tuningReference: TuningReference,
                       tuningSpecs: Seq[TuningSpec],
                       tuningReducer: TuningReducer,
                       fill: FillSpec = FillSpec(),
                       metadata: Option[CompositionMetadata] = None,
                       tracksUriOverride: Option[URI] = None) {

  def tracksUri: Option[URI] = tracksUriOverride.orElse {
    uri.map { uriValue =>
      uriValue.resolve(s"${uriValue.getPath}.${Composition.TracksFileExtension}")
    }
  }
}

object Composition {
  private val TracksFileExtension: String = "tracks"
}


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

  def tuningFor(ref: TuningReference): Tuning = {
    tuningMapper.mapScale(scale, ref, transposition)
  }
}


/**
 * Specifies the configuration for local fill strategies in tuning sequence.
 *
 * @param backFillEnabled   Indicates whether local back-fill functionality is enabled. Defaults to `true`.
 * @param foreFillEnabled   Indicates whether local fore-fill is enabled. Defaults to `true`.
 * @param memoryFillEnabled Indicates whether local memory-fill functionality is enabled. Defaults to `false`.
 * @see [[FillSpec]] for details about filling in general or local filling strategies in particular.
 */
case class LocalFillSpec(backFillEnabled: Boolean = true,
                         foreFillEnabled: Boolean = true,
                         memoryFillEnabled: Boolean = false)

/**
 * Specifies filling configuration for the tunings used in the composition.
 *
 * The fill controls the way missing tuning values are filled in cases where they are not explicitly provided by scales.
 *
 * There are multiple kinds of fills:
 *
 *   - ''Local fill'': applies tuning values from tunings in spatial or temporal proximity. Spatial proximity refers to
 *     tunings that are close in the sequence, while temporal proximity to tunings that were recently applied. There
 *     are multiple kinds/strategies of local fill:
 *       1. ''Back-fill'': applies tuning values spatially that come from preceding tunings from the sequence.
 *       1. ''Fore-fill'': applies tuning values spatially that come from succeeding tunings from the sequence.
 *       1. ''Memory-fill'': applies tuning values temporally that come from recently tuned tunings.
 *   - ''Global fill'': is applied in the end after all other local strategies have been applied and attempts to fill
 *     the gaps with a custom tuning from a given global fill scale.
 *
 * Filling Attempts to minimize the number of notes retuned when switching to another tuning. When one plays a
 * piano with sustain pedal and the tuning is changed, a large number of notes retuned could result in an unwanted
 * effect.
 *
 * @param global Specification for a tuning that should be used as a final fallback for the unused pitch classes of
 *               the keyboard instrument for all tunings from the sequence.
 * @param local  A configuration that specifies local filling strategies, such as enabling or disabling fore-fill,
 *               back-fill, or memory-based fill.
 */
case class FillSpec(local: LocalFillSpec = LocalFillSpec(),
                    global: Option[TuningSpec] = None)


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

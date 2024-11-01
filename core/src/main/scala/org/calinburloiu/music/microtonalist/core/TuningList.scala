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

import com.google.common.base.Preconditions.checkElementIndex
import com.typesafe.scalalogging.StrictLogging

case class TuningList(tunings: Seq[OctaveTuning]) extends Iterable[OctaveTuning] {

  def apply(index: Int): OctaveTuning = {
    checkElementIndex(index, size)

    tunings(index)
  }

  override def size: Int = tunings.size

  override def iterator: Iterator[OctaveTuning] = tunings.iterator
}

object TuningList extends StrictLogging {

  def fromComposition(composition: Composition): TuningList = {
    val globalFillTuning = composition.globalFill.map(_.tuningFor(composition.tuningReference))
      .getOrElse(PartialTuning.StandardTuningOctave)
    val partialTunings = composition.tuningSpecs.map(_.tuningFor(composition.tuningReference))

    composition.tuningReducer.reduceTunings(partialTunings, globalFillTuning)
  }
}

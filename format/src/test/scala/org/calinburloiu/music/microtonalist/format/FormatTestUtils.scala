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

package org.calinburloiu.music.microtonalist.format

import org.calinburloiu.music.intonation.{Interval, Scale}
import org.calinburloiu.music.microtonalist.common.CommonTestUtils
import org.calinburloiu.music.microtonalist.composition.Composition

/**
 * Utilities used for testing the I/O formats.
 */
object FormatTestUtils {

  def readCompositionFromResources(resourcePathString: String, compositionRepo: CompositionRepo): Composition = {
    compositionRepo.read(CommonTestUtils.uriOfResource(resourcePathString))
  }

  def readScaleFromResources(resourcePathString: String,
                             scaleRepo: ScaleRepo,
                             context: Option[ScaleFormatContext] = None): Scale[Interval] = {
    scaleRepo.read(CommonTestUtils.uriOfResource(resourcePathString), context)
  }
}

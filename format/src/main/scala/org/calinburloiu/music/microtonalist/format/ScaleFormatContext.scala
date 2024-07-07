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

import org.calinburloiu.music.intonation.IntonationStandard

/**
 * Information passed when a scale is read/written in the context of a composition file.
 *
 * The context information may fill missing properties from the embedded scale or override them.
 *
 * For example:
 *
 *   - A scale embedded in a composition file may be concisely defined as an array of intervals, but without the
 *     intonation standard from the context the scale would not have been interpreted correctly because the numeric
 *     values for the intervals could be cents or the number of EDOs.
 *   - In a composition file, a tuning spec may choose to override the name of the scale with a different one.
 *   - In a composition file, a tuning spec may choose to use a scale that has a different intonation standard. Its
 *     intervals will be converted to the intonation standard from the context.
 *
 * @param name               Optional name that may either override the one already present in the scale to be read,
 *                           or fill this property if it's missing.
 * @param intonationStandard The intonation standard of the composition file that may either be used to convert the
 *                           scale read to it, if it has a different one, or use it, if an embedded scale omits it.
 */
case class ScaleFormatContext(name: Option[String] = None, intonationStandard: Option[IntonationStandard] = None)

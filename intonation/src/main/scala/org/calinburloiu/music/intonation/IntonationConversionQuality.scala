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

package org.calinburloiu.music.intonation

/**
 * Rates the conversion of an interval or scale from an `IntonationStandard` to another.
 */
enum IntonationConversionQuality {
  /** No conversion is performed. */
  case NoConversion

  /** A conversion is performed without any impact on intonation precision quality. */
  case Lossless

  /** A conversion is performed with a degradation of intonation precision quality. */
  case Lossy

  /** The conversion is not possible. */
  case Impossible
}

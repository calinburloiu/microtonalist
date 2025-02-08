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

import play.api.libs.json.{JsonValidationError, Reads}

object JsonConstraints {

  /**
   * Defines an exclusively minimum value for a Reads.
   *
   * @see [[Reads#min]]
   */
  def exclusiveMin[O](m: O)(implicit reads: Reads[O], ord: Ordering[O]): Reads[O] =
    Reads.filterNot[O](JsonValidationError("error.exclusiveMin", m))(ord.lteq(_, m))(reads)

  /**
   * Defines an exclusively maximum value for a Reads.
   *
   * @see [[Reads#max]]
   */
  def exclusiveMax[O](m: O)(implicit reads: Reads[O], ord: Ordering[O]): Reads[O] =
    Reads.filterNot[O](JsonValidationError("error.exclusiveMax", m))(ord.gteq(_, m))(reads)
}

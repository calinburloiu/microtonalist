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

package org.calinburloiu.music.microtonalist.core

/**
 * Instances of this trait are pluggable components that allow users to choose between multiple options identifies by
 * [[typeName]] withing a certain context identified by [[familyName]].
 *
 * E.g. [[TuningMapper]] is the ''family'' of the plugin with `familyName` `tuningMapper` and [[AutoTuningMapper]] is
 * the ''type'' of the plugin with `typeName` `"auto"`. The user may choose another type of the same family, such as
 * [[ManualTuningMapper]].
 */
trait Plugin {
  val familyName: String
  val typeName: String
}

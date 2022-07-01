/*
 * Copyright 2022 Calin-Andrei Burloiu
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

import org.calinburloiu.music.microtonalist.core.MergeTuningReducer
import play.api.libs.json.Format

object MergeTuningReducerComponentFormatSpec extends ComponentFormatSpec[MergeTuningReducer] {
  override val typeName: String = "merge"
  override val javaClass: Class[MergeTuningReducer] = classOf[MergeTuningReducer]
  override val format: Option[Format[MergeTuningReducer]] = None
  override val default: Option[MergeTuningReducer] = Some(MergeTuningReducer())
}

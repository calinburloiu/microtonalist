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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.composition.OctaveTuning

class TuningService(session: TuningSession, businessync: Businessync) {

  // TODO #99 No need to expose this after publishing messages from domain model to GUI.
  @deprecated
  def tunings: Seq[OctaveTuning] = session.tunings

  def changeTuning(tuningChange: TuningChange): Unit = {
    if (tuningChange.isChanging) {
      businessync.run { () =>
        tuningChange match {
          case PreviousTuningChange => session.previousTuning()
          case NextTuningChange => session.nextTuning()
          case IndexTuningChange(index) => session.tuningIndex = index
          case NoTuningChange => // Unreachable, see above. Added to make the match exhaustive.
        }
      }
    }
  }
}

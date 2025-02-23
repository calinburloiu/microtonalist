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

import javax.annotation.concurrent.ThreadSafe

/**
 * Service that exposes tuning capabilities to the application layer by allowing things like setting the sequence of
 * tunings and the current tuning from that sequence.
 *
 * The service makes sure that all operations are executed on the business thread.
 *
 * @param session     Object where all mutable operations are performed.
 * @param businessync Provides thread communication capabilities.
 */
@ThreadSafe
class TuningService(session: TuningSession, businessync: Businessync) {

  /**
   * Retrieves the sequence of tunings currently set in the tuning session.
   *
   * @return a sequence of tuning objects representing the available tunings.
   */
  @deprecated("To be removed; not thread-safe!")
  def tunings: Seq[Tuning] = session.tunings

  /**
   * Changes the current tuning with the given operation object by selecting one of the tunings from the tuning sequence
   * stored in the session.
   *
   * @param tuningChange An operation object that describes how the tuning should be changed.
   */
  def changeTuning(tuningChange: EffectiveTuningChange): Unit = businessync.run { () =>
    tuningChange match {
      case PreviousTuningChange => session.previousTuning()
      case NextTuningChange => session.nextTuning()
      case IndexTuningChange(index) => session.tuningIndex = index
    }
  }
}

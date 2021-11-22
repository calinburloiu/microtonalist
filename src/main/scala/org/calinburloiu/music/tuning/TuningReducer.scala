/*
 * Copyright 2020 Calin-Andrei Burloiu
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

package org.calinburloiu.music.tuning

/**
 * Merges one or more partial tunings into ideally less final tunings, minimizing the number of tuning switches a
 * musician must perform while playing.
 *
 * The final tuning list must contain [[OctaveTuning]] objects, not [[PartialTuning]], so they must be complete. If some
 * tunings will be incomplete after the reduce process a [[IncompleteTuningsException]] is thrown.
 */
trait TuningReducer {
  def reduceTunings(tunings: Seq[PartialTuning],
                    globalFillTuning: PartialTuning = PartialTuning.StandardTuningOctave): TuningList
}

object TuningReducer {

  /** A [[MergeTuningReducer]]. */
  val Default: MergeTuningReducer = new MergeTuningReducer
}

/**
 * Exception thrown by [[TuningReducer]] if the final tuning list has some tunings that are incomplete.
 */
class IncompleteTuningsException(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)

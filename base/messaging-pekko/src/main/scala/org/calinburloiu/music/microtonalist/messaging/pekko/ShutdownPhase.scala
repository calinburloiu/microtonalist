/*
 * Copyright 2026 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.messaging.pekko

import org.apache.pekko.actor.CoordinatedShutdown

/**
 * A phase of [[PekkoMessagingRuntime]]'s shutdown, which runs its tasks once the previous phase's have completed. The
 * pinned mailboxes stop after both phases.
 *
 * @param pekkoPhase the phase of Pekko's `CoordinatedShutdown` that runs the tasks
 */
enum ShutdownPhase(private[pekko] val pekkoPhase: String) {
  /** The first phase, for finishing the work in progress while the mailboxes still run, e.g. closing the rig. */
  case FinishWork extends ShutdownPhase(CoordinatedShutdown.PhaseServiceRequestsDone)

  /** The phase after [[FinishWork]], for releasing what the work used, e.g. the MIDI devices. */
  case ReleaseResources extends ShutdownPhase(CoordinatedShutdown.PhaseServiceStop)
}

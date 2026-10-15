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

package org.calinburloiu.music.microtonalist.rig

import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.tuner.TuningService
import org.calinburloiu.music.scmidi.MidiManager

/**
 * Composition root of the `rig` module: wires the rig session, its service and the [[RigManager]] around the
 * [[org.calinburloiu.music.scmidi.MidiManager]] it is given, building the session and the service lazily.
 *
 * Building it registers the [[RigManager]] on the bus, so that the tracks follow the tuning events of the session
 * behind `tuningService`.
 *
 * @param businessync The event bus and business thread the session and the service run on.
 * @param rigRepo Where [[RigSession]] loads the rig from.
 * @param midiManager Opens the MIDI devices of the tracks. The caller owns it and closes it after this module; it is
 *   not closed by [[close]].
 * @param tuningService Where the tracks send the tuning changes that their triggers decide.
 */
class RigModule(businessync: Businessync,
                rigRepo: RigRepo,
                midiManager: MidiManager,
                tuningService: TuningService) extends AutoCloseable {

  /** Thread-safe facade over this module's [[RigSession]] for the application and UI layers. */
  lazy val rigService: RigService = new RigService(rigSession, businessync)

  private lazy val rigManager = new RigManager(midiManager, tuningService)
  businessync.register(rigManager)

  private lazy val rigSession = new RigSession(rigManager, rigRepo, businessync)

  override def close(): Unit = {
    rigManager.close()
  }
}

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
import org.calinburloiu.music.scmidi.MidiManager

/**
 * Composition root of the `tuner` module: lazily wires the sessions, the services and the [[RigManager]] around the
 * [[MidiManager]] it is given.
 *
 * @param businessync The event bus and business thread the sessions and services run on.
 * @param rigRepo Where [[RigSession]] loads tracks from.
 * @param midiManager Opens the MIDI devices of the tracks. The caller owns it and closes it after this module; it is
 *   not closed by [[close]].
 */
class TunerModule(businessync: Businessync,
                  rigRepo: RigRepo,
                  midiManager: MidiManager) extends AutoCloseable {

  /** Thread-safe facade over [[tuningSession]] for the application and UI layers. */
  lazy val tuningService: TuningService = new TuningService(tuningSession, businessync)

  /** Holds the sequence of tunings and the current tuning index; may only be touched on the business thread. */
  lazy val tuningSession: TuningSession = new TuningSession(businessync)

  /** Thread-safe facade over this module's [[RigSession]] for the application and UI layers. */
  lazy val rigService: RigService = new RigService(rigSession, businessync)

  private lazy val rigManager = new RigManager(midiManager, tuningService)
  businessync.register(rigManager)

  private lazy val rigSession = new RigSession(rigManager, rigRepo, businessync)

  override def close(): Unit = {
    rigManager.close()
  }
}

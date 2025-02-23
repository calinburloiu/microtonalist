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

class TunerModule(businessync: Businessync) extends AutoCloseable {

  lazy val tuningService: TuningService = new TuningService(tuningSession, businessync)

  lazy val tuningSession: TuningSession = new TuningSession(businessync)

  lazy val trackService: TrackService = new TrackService(trackSession, businessync)

  private lazy val midiManager = new MidiManager(businessync)

  private lazy val trackManager = new TrackManager(midiManager, tuningService)
  businessync.register(trackManager)

  private lazy val trackSession = new TrackSession(trackManager, businessync)

  override def close(): Unit = {
    trackManager.close()
    midiManager.close()
  }
}

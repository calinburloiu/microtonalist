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

/**
 * Composition root of the `tuner` module: lazily wires the tuning session and its service.
 *
 * @param businessync The event bus and business thread the session and the service run on.
 */
class TunerModule(businessync: Businessync) {

  /** Thread-safe facade over [[tuningSession]] for the application and UI layers. */
  lazy val tuningService: TuningService = new TuningService(tuningSession, businessync)

  /** Holds the sequence of tunings and the current tuning index; may only be touched on the business thread. */
  lazy val tuningSession: TuningSession = new TuningSession(businessync)
}

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

package org.calinburloiu.music.microtonalist.perf.midilatency.cases

import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Suite}

/**
 * The end-to-end MIDI latency of the app with each tuner, from a Note On in to the Note On out, and from a pedal in to
 * the tuning change out (issue #394).
 *
 * It runs the latency suites in one run, which writes one run file; `-Dsuites` and `-Dcases` select some of them, see
 * [[org.calinburloiu.music.microtonalist.perf.midilatency.config.RunParameters]]. Every case through the app plays on a
 * track with a `pedal` tuning changer, over a composition of two tunings that differ on the notes played. Each
 * iteration leaves the app as it found it, and each readiness probe leaves the app as an iteration does, so that the
 * expected output is the same in every iteration.
 */
class MidiLatencyPerfTest private (session: LatencySession, suites: IndexedSeq[LatencySuite])
  extends Suite with BeforeAndAfterAllConfigMap {

  private def this(session: LatencySession) = this(session,
    Vector(
      MtsTunerLatencyTest(session),
      MonophonicPitchBendTunerLatencyTest(session),
      MpeTunerLatencyTest(session)
    ))

  def this() = this(LatencySession())

  override def nestedSuites: IndexedSeq[Suite] = suites.filter(suite => session.selectsSuite(suite.id))

  override protected def beforeAll(configMap: ConfigMap): Unit = session.start(configMap, suites.map(_.id))

  override protected def afterAll(configMap: ConfigMap): Unit = session.finish()
}

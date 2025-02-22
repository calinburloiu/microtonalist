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
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class TrackServiceTest extends AnyFlatSpec with Matchers with MockFactory {

  trait Fixture {
    val sessionStub: TrackSession = stub[TrackSession]
    val businessyncStub: Businessync = stub[Businessync]

    (businessyncStub.run _).when(*).onCall { (fn: () => Unit) =>
      fn()
    }

    val trackService = new TrackService(sessionStub, businessyncStub)

    protected def makeTrackSpec(name: String): TrackSpec =
      TrackSpec(name + "-id", name, None, Seq.empty, None, None)
  }

  "open" should "call open in TrackSession with the given URI" in new Fixture {
    // Given
    val uri = new URI("http://example.com/composition.mtlist.tracks")

    // When
    trackService.open(uri)

    // Then
    (sessionStub.open _).verify(uri).once()
  }

  "replaceAllTracks" should "update tracks in the trackSession with the provided TrackSpecs" in new Fixture {
    // Given
    val trackSpecs: TrackSpecs = TrackSpecs(Seq(makeTrackSpec("track1"), makeTrackSpec("track2")))

    // When
    trackService.replaceAllTracks(trackSpecs)

    // Then
    (sessionStub.tracks_= _).verify(trackSpecs).once()
  }
}
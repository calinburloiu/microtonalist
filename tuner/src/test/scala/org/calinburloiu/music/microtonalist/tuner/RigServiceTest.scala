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

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI

class RigServiceTest extends AnyWordSpec with Matchers with MockFactory {

  trait Fixture {
    val sessionStub: RigSession = stub[RigSession]
    val businessync: Businessync = Businessync(EventBus())

    val rigService = new RigService(sessionStub, businessync)

    protected def makeTrackSpec(name: String): TrackSpec =
      TrackSpec(name + "-id", name, None, Seq.empty, None, None)
  }

  "open" should {
    "call open in RigSession with the given URI" in new Fixture {
      // Given
      val uri = new URI("http://example.com/composition.mtlist.tracks")

      // When
      rigService.open(uri)

      // Then
      sessionStub.open.verify(uri).once()
    }
  }

  "replaceAllTracks" should {
    "update tracks in the rigSession with the provided RigSpec" in new Fixture {
      // Given
      val rigSpec: RigSpec = RigSpec(Seq(makeTrackSpec("track1"), makeTrackSpec("track2")))

      // When
      rigService.replaceAllTracks(rigSpec)

      // Then
      sessionStub.tracks_=.verify(rigSpec).once()
    }
  }
}

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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class RigSessionTest extends AnyWordSpec with Matchers with MockFactory {

  private val uri = new URI("http://example.com/composition.mtlist.tracks")

  private val sampleTracks = RigSpec(Seq(
    makeTrack("Piano"), makeTrack("Synth"), makeTrack("Bass"), makeTrack("Percussion", withTrackNo = false)))

  abstract class Fixture(tracks: RigSpec = RigSpec.Empty) {
    val rigManagerMock: RigManager = mock[RigManager]
    val rigRepo: RigRepo = stub[RigRepo]
    val businessyncMock: Businessync = mock[Businessync]

    val rigSession = new RigSession(rigManagerMock, rigRepo, businessyncMock)

    rigRepo.readRigAsync.when(uri).returns(Future.successful(sampleTracks))

    if (tracks.nonEmpty) {
      rigManagerMock.replaceAllTracks.expects(sampleTracks)
      businessyncMock.publish.expects(RigReplacedEvent(sampleTracks))

      rigSession.tracks = tracks
    }
  }

  def makeTrack(id: String, withTrackNo: Boolean = true): TrackSpec = {
    val name = if (withTrackNo) s"\\## $id" else id
    TrackSpec(id, name, None, Seq.empty, None, None)
  }

  "constructor" should {
    "initialize uri and tracks as empty" in new Fixture {
      rigSession.uri shouldBe empty
      rigSession.tracks.isEmpty shouldBe true
      rigSession.tracks.nonEmpty shouldBe false
      rigSession.isOpen shouldBe false
    }
  }

  "open" should {
    "load tracks from the given URI" in new Fixture {
      // Expect
      rigManagerMock.replaceAllTracks.expects(sampleTracks)
      businessyncMock.publish.expects(RigReplacedEvent(sampleTracks))
      businessyncMock.publish.expects(argAssert { (event: RigOpenedEvent) =>
        event.uri shouldBe uri
        event.tracks shouldBe sampleTracks
      })

      // When
      Await.result(rigSession.open(uri), 500 milliseconds)

      // Then
      rigSession.uri should contain(uri)
      rigRepo.readRigAsync.verify(uri).once()

      rigSession.isOpen shouldBe true
    }
  }

  "close" should {
    "unload tracks and clear the state" in new Fixture {
      // Expect
      rigManagerMock.replaceAllTracks.expects(sampleTracks)
      businessyncMock.publish.expects(RigReplacedEvent(sampleTracks))
      businessyncMock.publish.expects(argAssert { (event: RigOpenedEvent) =>
        event.uri shouldBe uri
        event.tracks shouldBe sampleTracks
      })

      Await.result(rigSession.open(uri), 500 milliseconds)

      // Expect
      rigManagerMock.replaceAllTracks.expects(RigSpec.Empty)
      businessyncMock.publish.expects(RigReplacedEvent(RigSpec.Empty))
      businessyncMock.publish.expects(RigClosedEvent(Some(uri)))

      // When
      rigSession.close()

      // Then
      rigSession.uri shouldBe empty
      rigSession.tracks.tracks shouldBe empty
      rigSession.isOpen shouldBe false

      // Expect
      businessyncMock.publish.expects(RigClosedEvent(None))

      // When
      rigSession.close()
    }
  }

  "tracks setter" should {
    "replace all tracks" in new Fixture(sampleTracks) {
      // Expectations for the mocks and the tracks are set in the Fixture
      rigSession.tracks shouldEqual sampleTracks

      rigSession.tracks("Piano") shouldEqual sampleTracks.tracks.head
      rigSession.tracks(1) shouldEqual sampleTracks.tracks(1)
    }
  }

  "indexOf" should {
    "return the index of the track with the given ID" in new Fixture(sampleTracks) {
      rigSession.indexOf("Piano") shouldEqual 0
      rigSession.indexOf("Synth") shouldEqual 1
      rigSession.indexOf("Bass") shouldEqual 2

      rigSession.indexOf("Non-existent track") shouldEqual -1
    }
  }

  "nameOf" should {
    "return the name of the track with the given ID" in new Fixture(sampleTracks) {
      rigSession.nameOf("Piano") should contain("#1 Piano")
      rigSession.nameOf("Percussion") should contain("Percussion")

      rigSession.nameOf("Non-existent track") shouldBe empty
    }
  }

  "contains" should {
    "return true if the track with the given ID exists" in new Fixture(sampleTracks) {
      rigSession.contains("Piano") shouldBe true
      rigSession.contains("Percussion") shouldBe true

      rigSession.contains("Non-existent track") shouldBe false
    }
  }

  "trackCount" should {
    "return the number of tracks" in new Fixture(sampleTracks) {
      rigSession.trackCount shouldEqual 4
    }
  }

  "getTrack" should {
    "return the track with the given ID" in new Fixture(sampleTracks) {
      rigSession.getTrack("Piano") should contain(sampleTracks.tracks.head)
      rigSession.getTrack("Percussion") should contain(sampleTracks.tracks(3))

      rigSession.getTrack("Non-existent track") shouldBe empty
    }

    "return the track with the given index" in new Fixture(sampleTracks) {
      rigSession.getTrack(0) should contain(sampleTracks.tracks.head)
      rigSession.getTrack(3) should contain(sampleTracks.tracks(3))
    }
  }

  "addTrackBefore" should {
    "add a track before the track with the given ID" in new Fixture(sampleTracks) {
      // Given
      val stringsTrack: TrackSpec = makeTrack("Strings")
      val leadTrack: TrackSpec = makeTrack("Lead")
      val fluteTrack: TrackSpec = makeTrack("Flute")
      val flute2Track: TrackSpec = makeTrack("Flute 2")

      // Expect
      businessyncMock.publish.expects(TrackAddedEvent(stringsTrack, Some("Piano")))
      businessyncMock.publish.expects(TrackAddedEvent(leadTrack, Some("Bass")))
      businessyncMock.publish.expects(TrackAddedEvent(fluteTrack, None))
      businessyncMock.publish.expects(TrackAddedEvent(flute2Track, None))

      // When
      rigSession.addTrackBefore(stringsTrack, "Piano")
      rigSession.addTrackBefore(leadTrack, Some("Bass"))
      rigSession.addTrackBefore(fluteTrack, None)
      rigSession.addTrack(flute2Track)

      // Then
      rigSession.tracks.ids shouldEqual Seq(
        "Strings", "Piano", "Synth", "Lead", "Bass", "Percussion", "Flute", "Flute 2")
    }

    "not add if the ID already exists" in new Fixture(sampleTracks) {
      // Given
      val count: Int = rigSession.trackCount
      val newBassTrack: TrackSpec = makeTrack("Bass")
      // When
      rigSession.addTrack(newBassTrack)
      // Then
      rigSession.trackCount shouldEqual count
    }

    "add at the end if beforeId does not exist" in new Fixture(sampleTracks) {
      // Given
      val stringsTrack: TrackSpec = makeTrack("Strings")
      // Expect
      businessyncMock.publish.expects(TrackAddedEvent(stringsTrack, None))
      // When
      rigSession.addTrackBefore(stringsTrack, "Non-existent track")
      // Then
      rigSession.getTrack(rigSession.trackCount - 1) should contain(stringsTrack)
    }
  }

  "updateTrack" should {
    "update the track with the given ID" in new Fixture(sampleTracks) {
      // Given
      val nameBeforeUpdate: String = rigSession.getTrack("Piano").get.name
      val newPianoTrack: TrackSpec = makeTrack("Piano", withTrackNo = false)
      // Expect
      businessyncMock.publish.expects(TrackUpdatedEvent(newPianoTrack))
      // When
      rigSession.updateTrack(newPianoTrack)
      // Then
      rigSession.getTrack("Piano").get.name should not equal nameBeforeUpdate
    }

    "do nothing if track ID does not exist" in new Fixture(sampleTracks) {
      // Given
      val newTrack: TrackSpec = makeTrack("Non-existent track")
      // Expect
      businessyncMock.publish.expects(*).never()
      // When
      rigSession.updateTrack(newTrack)
      // Then
      rigSession.contains("Non-existent track") shouldBe false
    }

    "not publish event if a track with the same reference already exists" in new Fixture(sampleTracks) {
      // Given
      val track: TrackSpec = sampleTracks.tracks.head
      // Expect
      businessyncMock.publish.expects(*).never()
      // When
      rigSession.updateTrack(track)
    }
  }

  "moveTrackBefore" should {
    "move a track before another tracks with given ID" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(TrackMovedEvent("Bass", Some("Piano")))
      // When
      rigSession.moveTrackBefore("Bass", "Piano")
      // Then
      rigSession.tracks.ids shouldEqual Seq("Bass", "Piano", "Synth", "Percussion")
    }

    "do nothing if the ID of the track to move does not exist" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(*).never()
      // When
      rigSession.moveTrackBefore("Non-existent track", "Bass")
      rigSession.moveTrackToEnd("Non-existent track")
    }

    "move the track to the end if before ID does not exist" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(TrackMovedEvent("Bass", None))
      // When
      rigSession.moveTrackBefore("Bass", "Non-existent track")
      // Then
      rigSession.tracks.ids shouldEqual Seq("Piano", "Synth", "Percussion", "Bass")
    }
  }

  "moveTrackToEnd" should {
    "move a track to the end" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(TrackMovedEvent("Bass", None))
      // When
      rigSession.moveTrackToEnd("Bass")
      // Then
      rigSession.tracks.ids shouldEqual Seq("Piano", "Synth", "Percussion", "Bass")
    }
  }

  "removeTrack" should {
    "remove a track with the given ID" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(TrackRemovedEvent("Synth"))
      // When
      rigSession.removeTrack("Synth")
      // Then
      rigSession.getTrack("Synth") shouldBe empty
    }

    "do nothing if the ID does not exist" in new Fixture(sampleTracks) {
      // Expect
      businessyncMock.publish.expects(*).never()
      // When
      rigSession.removeTrack("Non-existent track")
    }
  }
}

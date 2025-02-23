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

class TrackSessionTest extends AnyFlatSpec with Matchers with MockFactory {

  private val sampleTracks = TrackSpecs(Seq(
    makeTrack("Piano"), makeTrack("Synth"), makeTrack("Bass"), makeTrack("Percussion", withTrackNo = false)))

  abstract class Fixture(tracks: TrackSpecs = TrackSpecs()) {
    val trackManagerMock: TrackManager = mock[TrackManager]
    val businessyncMock: Businessync = mock[Businessync]

    val trackSession = new TrackSession(trackManagerMock, businessyncMock)

    if (tracks.nonEmpty) {
      (trackManagerMock.replaceAllTracks _).expects(sampleTracks)
      (businessyncMock.publish _).expects(TracksReplacedEvent(sampleTracks))

      trackSession.tracks = tracks
    }
  }

  def makeTrack(id: String, withTrackNo: Boolean = true): TrackSpec = {
    val name = if (withTrackNo) s"\\## $id" else id
    TrackSpec(id, name, None, Seq.empty, None, None)
  }

  "constructor" should "initialize uri and tracks as empty" in new Fixture {
    trackSession.uri shouldBe empty
    trackSession.tracks.isEmpty shouldBe true
    trackSession.tracks.nonEmpty shouldBe false
    trackSession.isOpened shouldBe false
  }

  "open" should "load tracks from the given URI" in new Fixture {
    // Given
    val uri = new URI("http://example.com/composition.mtlist.tracks")

    // Expect
    (businessyncMock.publish _).expects(argAssert { event: TracksOpenedEvent =>
      event.uri shouldBe uri
      // TODO #118 Check tracks
    })

    // When
    trackSession.open(uri)

    // Then
    trackSession.uri should contain(uri)
    // TODO #118 Check repo is called

    trackSession.isOpened shouldBe true
  }

  "close" should "unload tracks and clear the state" in new Fixture {
    // Given
    val uri = new URI("http://example.com/composition.mtlist.tracks")

    // Expect
    (businessyncMock.publish _).expects(argAssert { event: TracksOpenedEvent =>
      event.uri shouldBe uri
      // TODO #118 Check tracks
    })

    trackSession.open(uri)

    // Expect
    (businessyncMock.publish _).expects(TracksClosedEvent(Some(uri)))

    // When
    trackSession.close()

    // Then
    trackSession.uri shouldBe empty
    trackSession.tracks.tracks shouldBe empty
    trackSession.isOpened shouldBe false

    // Expect
    (businessyncMock.publish _).expects(TracksClosedEvent(None))

    // When
    trackSession.close()
  }

  "tracks setter" should "replace all tracks" in new Fixture(sampleTracks) {
    // Expectations for the mocks and the tracks are set in the Fixture
    trackSession.tracks shouldEqual sampleTracks

    trackSession.tracks("Piano") shouldEqual sampleTracks.tracks.head
    trackSession.tracks(1) shouldEqual sampleTracks.tracks(1)
  }

  "indexOf" should "return the index of the track with the given ID" in new Fixture(sampleTracks) {
    trackSession.indexOf("Piano") shouldEqual 0
    trackSession.indexOf("Synth") shouldEqual 1
    trackSession.indexOf("Bass") shouldEqual 2

    trackSession.indexOf("Non-existent track") shouldEqual -1
  }

  "nameOf" should "return the name of the track with the given ID" in new Fixture(sampleTracks) {
    trackSession.nameOf("Piano") should contain("#1 Piano")
    trackSession.nameOf("Percussion") should contain("Percussion")

    trackSession.nameOf("Non-existent track") shouldBe empty
  }

  "contains" should "return true if the track with the given ID exists" in new Fixture(sampleTracks) {
    trackSession.contains("Piano") shouldBe true
    trackSession.contains("Percussion") shouldBe true

    trackSession.contains("Non-existent track") shouldBe false
  }

  "trackCount" should "return the number of tracks" in new Fixture(sampleTracks) {
    trackSession.trackCount shouldEqual 4
  }

  "getTrack" should "return the track with the given ID" in new Fixture(sampleTracks) {
    trackSession.getTrack("Piano") should contain(sampleTracks.tracks.head)
    trackSession.getTrack("Percussion") should contain(sampleTracks.tracks(3))

    trackSession.getTrack("Non-existent track") shouldBe empty
  }

  it should "return the track with the given index" in new Fixture(sampleTracks) {
    trackSession.getTrack(0) should contain(sampleTracks.tracks.head)
    trackSession.getTrack(3) should contain(sampleTracks.tracks(3))
  }

  "addTrackBefore" should "add a track before the track with the given ID" in new Fixture(sampleTracks) {
    // Given
    val stringsTrack: TrackSpec = makeTrack("Strings")
    val leadTrack: TrackSpec = makeTrack("Lead")
    val fluteTrack: TrackSpec = makeTrack("Flute")
    val flute2Track: TrackSpec = makeTrack("Flute 2")

    // Expect
    (businessyncMock.publish _).expects(TrackAddedEvent(stringsTrack, Some("Piano")))
    (businessyncMock.publish _).expects(TrackAddedEvent(leadTrack, Some("Bass")))
    (businessyncMock.publish _).expects(TrackAddedEvent(fluteTrack, None))
    (businessyncMock.publish _).expects(TrackAddedEvent(flute2Track, None))

    // When
    trackSession.addTrackBefore(stringsTrack, "Piano")
    trackSession.addTrackBefore(leadTrack, Some("Bass"))
    trackSession.addTrackBefore(fluteTrack, None)
    trackSession.addTrack(flute2Track)

    // Then
    trackSession.tracks.ids shouldEqual Seq(
      "Strings", "Piano", "Synth", "Lead", "Bass", "Percussion", "Flute", "Flute 2")
  }

  it should "not add if the ID already exists" in new Fixture(sampleTracks) {
    // Given
    val count: Int = trackSession.trackCount
    val newBassTrack: TrackSpec = makeTrack("Bass")
    // When
    trackSession.addTrack(newBassTrack)
    // Then
    trackSession.trackCount shouldEqual count
  }

  it should "add at the end if beforeId does not exist" in new Fixture(sampleTracks) {
    // Given
    val stringsTrack: TrackSpec = makeTrack("Strings")
    // Expect
    (businessyncMock.publish _).expects(TrackAddedEvent(stringsTrack, None))
    // When
    trackSession.addTrackBefore(stringsTrack, "Non-existent track")
    // Then
    trackSession.getTrack(trackSession.trackCount - 1) should contain(stringsTrack)
  }

  "updateTrack" should "update the track with the given ID" in new Fixture(sampleTracks) {
    // Given
    val nameBeforeUpdate: String = trackSession.getTrack("Piano").get.name
    val newPianoTrack: TrackSpec = makeTrack("Piano", withTrackNo = false)
    // Expect
    (businessyncMock.publish _).expects(TrackUpdatedEvent(newPianoTrack))
    // When
    trackSession.updateTrack(newPianoTrack)
    // Then
    trackSession.getTrack("Piano").get.name should not equal nameBeforeUpdate
  }

  it should "do nothing if track ID does not exist" in new Fixture(sampleTracks) {
    // Given
    val newTrack: TrackSpec = makeTrack("Non-existent track")
    // Expect
    (businessyncMock.publish _).expects(*).never()
    // When
    trackSession.updateTrack(newTrack)
    // Then
    trackSession.contains("Non-existent track") shouldBe false
  }

  it should "not publish event if a track with the same reference already exists" in new Fixture(sampleTracks) {
    // Given
    val track: TrackSpec = sampleTracks.tracks.head
    // Expect
    (businessyncMock.publish _).expects(*).never()
    // When
    trackSession.updateTrack(track)
  }

  "moveTrackBefore" should "move a track before another tracks with given ID" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(TrackMovedEvent("Bass", Some("Piano")))
    // When
    trackSession.moveTrackBefore("Bass", "Piano")
    // Then
    trackSession.tracks.ids shouldEqual Seq("Bass", "Piano", "Synth", "Percussion")
  }

  it should "do nothing if the ID of the track to move does not exist" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(*).never()
    // When
    trackSession.moveTrackBefore("Non-existent track", "Bass")
    trackSession.moveTrackToEnd("Non-existent track")
  }

  it should "move the track to the end if before ID does not exist" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(TrackMovedEvent("Bass", None))
    // When
    trackSession.moveTrackBefore("Bass", "Non-existent track")
    // Then
    trackSession.tracks.ids shouldEqual Seq("Piano", "Synth", "Percussion", "Bass")
  }

  "moveTrackToEnd" should "move a track to the end" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(TrackMovedEvent("Bass", None))
    // When
    trackSession.moveTrackToEnd("Bass")
    // Then
    trackSession.tracks.ids shouldEqual Seq("Piano", "Synth", "Percussion", "Bass")
  }

  "removeTrack" should "remove a track with the given ID" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(TrackRemovedEvent("Synth"))
    // When
    trackSession.removeTrack("Synth")
    // Then
    trackSession.getTrack("Synth") shouldBe empty
  }

  it should "do nothing if the ID does not exist" in new Fixture(sampleTracks) {
    // Expect
    (businessyncMock.publish _).expects(*).never()
    // When
    trackSession.removeTrack("Non-existent track")
  }
}

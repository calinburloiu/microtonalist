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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocator.ChannelGroup
import org.calinburloiu.music.scmidi.MidiNote
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MpeChannelAllocatorTest extends AnyFlatSpec with Matchers {

  // Lower Zone with 15 members: PCG=12, EG=3, channels 1..15
  private def allocator15: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 15))

  // Lower Zone with 7 members: PCG=5, EG=2, channels 1..7
  private def allocator7: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 7))

  // Lower Zone with 4 members: PCG=2, EG=2, channels 1..4
  private def allocator4: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 4))

  // Lower Zone with 3 members: PCG=1, EG=2, channels 1..3
  private def allocator3: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 3))

  // Lower Zone with 2 members: PCG=1, EG=1, channels 1..2
  private def allocator2: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 2))

  // Lower Zone with 1 member: PCG=1, EG=0, channels 1..1
  private def allocator1: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 1))

  import MidiNote.{A4, B4, C4, C5, D4, E4, F4, G4}

  private val C3: MidiNote = C4 - 12
  private val C6: MidiNote = C5 + 12
  private val C7: MidiNote = C5 + 24

  // High expressive pitch bend in cents (> 50 cents threshold)
  private val highPitchBendCents: Double = 100.0
  // Low expressive pitch bend in cents (< 50 cents threshold)
  private val lowPitchBendCents: Double = 25.0

  private def assertDroppedNotes(droppedNotes: Option[DroppedNotes], expectedNotes: Seq[MidiNote]): Unit = {
    droppedNotes should not be empty
    droppedNotes.get.notes should contain theSameElementsAs expectedNotes
  }

  behavior of "MpeChannelAllocator - Step 1: Allocate in Pitch Class Group"

  it should "allocate first note to an unoccupied Pitch Class Group channel" in {
    // Given
    val alloc = allocator15
    // When
    val result = alloc.allocate(C4)
    // Then
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.activeNotes(result.channel) should contain theSameElementsAs Set(C4)
  }

  it should "allocate notes with distinct pitch classes to their own Pitch Class Group channels" in {
    // Given
    val alloc = allocator15
    // When
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(D4)
    val r3 = alloc.allocate(E4)
    // Then
    r1.channel should not equal r2.channel
    r2.channel should not equal r3.channel
    r1.channel should not equal r3.channel
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r3.channel) shouldBe Some(ChannelGroup.PitchClass)
  }

  it should "fill all 12 Pitch Class Group channels with distinct pitch classes (zone with 15 members)" in {
    // Given
    val alloc = allocator15
    // When
    val channels = (0 until 12).map { pc =>
      alloc.allocate(C4 + pc).channel
    }
    // Then
    channels.distinct.size shouldBe 12
    channels.foreach(ch => alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass))
  }

  it should "fill all Pitch Class Group channels with distinct pitch classes (zone with 7 members)" in {
    // Given
    val alloc = allocator7
    // When
    // PCG=5 for 7 members
    val channels = (0 until 5).map { pc =>
      alloc.allocate(C4 + pc).channel
    }
    // Then
    channels.distinct.size shouldBe 5
    channels.foreach(ch => alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass))
  }

  it should "prefer unoccupied channel with oldest last Note Off" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocate(C4) // ch1
    val r2 = alloc.allocate(D4) // ch2
    val ch1 = r1.channel
    val ch2 = r2.channel
    alloc.release(C4, ch1) // older
    alloc.release(D4, ch2) // newer

    // Both are unoccupied and HAVE been used.
    // We want it to pick ch1.
    // But there are also ch3..ch12 which have NEVER been used (lastNoteOffTime=0).
    // If we want it to pick ch1, we must ensure ch3..ch12 are NOT available.
    // So let's fill them first.
    (3 to 15).foreach { i => alloc.allocate(C4 + i) }

    // When
    // Now ch1, ch2 are unoccupied. ch3..15 are occupied.
    // r3 should pick ch1.
    val r3 = alloc.allocate(E4)
    // Then
    r3.channel shouldBe ch1
  }

  it should "prefer unoccupied channel that was never used over used and released" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocate(C4)
    val ch1 = r1.channel
    alloc.release(C4, ch1)
    // ch1 was used and released. Others never used.
    // never used (lastNoteOffTime=0) should be preferred over used (lastNoteOffTime>0)
    // When
    val r2 = alloc.allocate(D4)
    // Then
    r2.channel should not be ch1
  }

  behavior of "MpeChannelAllocator - Step 2: Allocate in Expression Group"

  it should "allocate second note with same pitch class to Expression Group" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocate(C4)
    // When
    val r2 = alloc.allocate(C5) // same pitch class C
    // Then
    r1.channel should not equal r2.channel
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
  }

  it should "share channel when Expression Group has only one member and third note with same pitch class arrives" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    // When
    // Both groups full for pitch class C, third note must share
    val r3 = alloc.allocate(C3) // same pitch class
    // Then
    (r3.channel == r1.channel || r3.channel == r2.channel) shouldBe true
    r1.channel should not equal r2.channel
  }

  it should "allocate third note with same pitch class to another Expression Group channel when available" in {
    // Given
    val alloc = allocator15 // EG=3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    // When
    val r3 = alloc.allocate(C3)
    // Then
    Set(r1.channel, r2.channel, r3.channel).size shouldBe 3
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.channelGroupOf(r3.channel) shouldBe Some(ChannelGroup.Expression)
  }

  it should "allocate note with new pitch class to Expression Group when Pitch Class Group is full" in {
    // Given
    val alloc = allocator7 // PCG=5, EG=2
    // Fill PCG with 5 distinct pitch classes
    (0 until 5).foreach(pc => alloc.allocate(C4 + pc))
    // When
    // 6th distinct pitch class goes to EG
    val r = alloc.allocate(C4 + 5)
    // Then
    alloc.channelGroupOf(r.channel) shouldBe Some(ChannelGroup.Expression)
  }

  behavior of "MpeChannelAllocator - Step 3: Share channel"

  it should "share channel with same pitch class when both groups are full" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // When
    // All 3 channels occupied, 4th C note must share
    val r4 = alloc.allocate(C6)
    // Then
    Set(r1.channel, r2.channel, r3.channel) should contain(r4.channel)
  }

  it should "prefer channel with lowest active note count when sharing" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // When
    // Add another note to r1's channel
    alloc.allocate(C6) // goes to channel with fewest notes
    // Then
    alloc.activeChannelCount shouldBe 2
    alloc.activeNotes(r1.channel).size shouldEqual 2
    alloc.activeNotes(r2.channel).size shouldEqual 2
  }

  it should "prefer the oldest onset among occupied candidates when note counts are equal" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    // Use preferredChannel to put the oldest onset on the highest channel number,
    // breaking the correlation between onset order and channel number.
    alloc.allocate(C4, preferredChannel = Some(3)) // ch 3, oldest onset
    alloc.allocate(C5, preferredChannel = Some(2)) // ch 2
    alloc.allocate(C3, preferredChannel = Some(1)) // ch 1, newest onset
    // All have 1 note, all lastNoteOffTime=0, so criteria (a), (b), (d) tie; only onset (c) discriminates.
    // Oldest onset is on ch 3 (highest number). If criterion (c) were broken and fell through to the
    // channel-number default (e), ch 1 would be picked instead.
    // When
    val r4 = alloc.allocate(C6)
    // Then
    r4.channel shouldBe 3
  }

  it should "prefer the oldest onset over an older last Note Off among occupied candidates" in {
    // Given
    // Build two occupied pitch-class-C channels whose onset and last-Note-Off orderings DISAGREE:
    //   ch1: onset = 3 (early), last Note Off = 6 (late)
    //   ch2: onset = 5 (late),  last Note Off = 4 (early)
    // Paper criterion (c) onset precedes (d) Note Off, so the older-onset ch1 must win.
    val alloc = allocator2 // PCG=1, EG=1, channels 1..2
    val rB = alloc.allocate(C4, preferredChannel = Some(1)) // t1: ch1 onset=1
    val rOther = alloc.allocate(C5, preferredChannel = Some(2)) // t2: ch2 onset=2
    alloc.allocate(C6, preferredChannel = Some(1)) // t3: shares ch1 -> ch1 onset=3
    alloc.release(C5, rOther.channel) // t4: ch2 empties, Note Off=4
    alloc.allocate(C5, preferredChannel = Some(2)) // t5: ch2 onset=5
    alloc.release(C4, rB.channel) // t6: ch1 keeps C6, Note Off=6, onset stays 3
    // When
    val result = alloc.allocate(C7) // t7: must share by oldest onset
    // Then
    result.channel shouldBe rB.channel
  }

  it should "break a tie by oldest last Note Off rather than the preferred input channel" in {
    // Given
    // Two unoccupied previously-used channels; ch1 has the older last Note Off.
    // The preferred (input) channel is ch2, but criterion (d) outranks the (e) input-channel default.
    val alloc = allocator2
    val r1 = alloc.allocate(C4, preferredChannel = Some(1)) // t1: ch1
    val r2 = alloc.allocate(D4, preferredChannel = Some(2)) // t2: ch2
    alloc.release(C4, r1.channel) // t3: ch1 Note Off=3
    alloc.release(D4, r2.channel) // t4: ch2 Note Off=4
    // When
    val result = alloc.allocate(E4, preferredChannel = Some(2)) // ch2 preferred, but ch1 idle longer
    // Then
    result.channel shouldBe r1.channel
  }

  it should "ignore a released channel's stale onset and prefer the oldest last Note Off" in {
    // Given
    // Both candidates are unoccupied and previously used. Their original onset order (ch1<ch2)
    // disagrees with their Note Off order (ch2<ch1), but removeNote clears _lastOnsetTime to 0L on
    // release, so both now carry onset 0 and criterion (c) cannot discriminate. The paper treats an
    // unoccupied channel as having no onset, so criterion (d) governs and the older-Note-Off ch2 wins.
    // (If onset were not cleared, criterion (c) would wrongly pick ch1 — this test guards that.)
    val alloc = allocator2
    val r1 = alloc.allocate(C4, preferredChannel = Some(1)) // t1: ch1 onset=1
    val r2 = alloc.allocate(D4, preferredChannel = Some(2)) // t2: ch2 onset=2
    alloc.release(D4, r2.channel) // t3: ch2 Note Off=3
    alloc.release(C4, r1.channel) // t4: ch1 Note Off=4
    // When
    val result = alloc.allocate(E4) // no preferred channel
    // Then
    result.channel shouldBe r2.channel
  }

  it should "prefer channel without high expressive pitch bend when sharing" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    // Both channels have C. Make r1 high bend.
    alloc.updateExpressivePitchBend(r1.channel, highPitchBendCents)
    // When
    // Third C should share with r2 (no high bend)
    val r3 = alloc.allocate(C3)
    // Then
    r3.channel shouldBe r2.channel
  }

  it should "share when Expression Group is full but PCG has same pitch class" in {
    // Given
    val alloc = allocator7 // PCG=5, EG=2
    // Put C in PCG
    val r1 = alloc.allocate(C4)
    // Put C in EG (2 channels)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // Fill remaining PCG with other pitch classes
    alloc.allocate(D4)
    alloc.allocate(E4)
    alloc.allocate(F4)
    alloc.allocate(G4)
    // When
    // EG is full, PCG has C on r1's channel. New C should share with existing C channel
    val r4 = alloc.allocate(C6)
    // Then
    val cChannels = Set(r1.channel, r2.channel, r3.channel)
    cChannels should contain(r4.channel)
  }

  it should "share in Expression Group when PCG doesn't have the pitch class" in {
    // Given
    val alloc = allocator7 // PCG=5, EG=2
    // Fill PCG with 5 distinct pitch classes (not including A)
    (0 until 5).foreach(pc => alloc.allocate(C4 + pc))
    // Put A in EG
    val rA1 = alloc.allocate(A4)
    val rA2 = alloc.allocate(MidiNote(A4 + 12)) // A5
    // When
    // All channels full. New A should share with existing A in EG
    val rA3 = alloc.allocate(MidiNote(A4 - 12)) // A3
    // Then
    Set(rA1.channel, rA2.channel) should contain(rA3.channel)
  }

  behavior of "MpeChannelAllocator - Step 4: Free a channel - Channel Exhaustion"

  it should "free a channel when all channels occupied and new pitch class needs a channel" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, 3 channels
    alloc.allocate(C4) // ch1
    alloc.allocate(E4) // ch2
    alloc.allocate(G4) // ch3
    // When
    // All channels occupied with different pitch classes. New pitch class A needs a channel.
    val result = alloc.allocate(A4)
    // Then
    result.droppedNotes should not be empty
  }

  it should "exclude highest-pitched and lowest-pitched note channels when freeing" in {
    // Given
    val alloc = allocator3 // 3 channels
    alloc.allocate(C4) // lowest
    alloc.allocate(E4) // middle
    alloc.allocate(G4) // highest
    // When
    val result = alloc.allocate(A4)
    // Then
    // E4 channel should be freed (not C4 or G4)
    assertDroppedNotes(result.droppedNotes, Seq(E4))
  }

  it should "select channel with oldest last onset among remaining candidates" in {
    // Given
    val alloc = allocator3
    alloc.allocate(C4) // oldest onset
    alloc.allocate(E4) // middle onset
    alloc.allocate(B4) // newest onset, also highest
    // When
    // C4 is lowest, B4 is highest. E4 is the only candidate.
    val result = alloc.allocate(A4)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(E4))
  }

  it should "assign the new note to the freed channel" in {
    // Given
    val alloc = allocator3
    alloc.allocate(C4)
    alloc.allocate(E4)
    alloc.allocate(G4)
    // When
    val result = alloc.allocate(A4)
    // Then
    result.droppedNotes should not be empty
    // The new note should be on the freed channel
    alloc.activeNotes(result.channel) should contain(A4)
  }

  it should "place only the new note on the freed channel and clear the old pitch class" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    alloc.allocate(C4) // lowest
    alloc.allocate(E4) // middle -> will be freed
    alloc.allocate(G4) // highest
    // When
    val result = alloc.allocate(A4)
    // Then
    alloc.activeNotes(result.channel) should contain theSameElementsAs Set(A4)
    alloc.channelPitchClass(result.channel) shouldBe Some(A4.pitchClass)
  }

  it should "free the channel holding the lowest note when both candidates are boundary channels" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    alloc.allocate(G4) // highest
    alloc.allocate(C4) // lowest
    // When
    val result = alloc.allocate(E4)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    alloc.activeNotes(result.channel) should contain theSameElementsAs Set(E4)
  }

  it should "free the non-boundary channel when one channel holds both the highest and lowest notes" in {
    // Given
    // ch1 ends up holding C4 (lowest) and C6 (highest), both pitch class C; ch2 holds the middle E5.
    val alloc = allocator2 // PCG=1, EG=1
    alloc.allocate(C4, preferredChannel = Some(1)) // ch1, pitch class C, PCG
    alloc.allocate(E4 + 12, preferredChannel = Some(2)) // ch2, E5 (middle), EG
    alloc.allocate(C6, preferredChannel = Some(1)) // shares ch1 -> ch1 = {C4, C6}
    // When
    val result = alloc.allocate(A4) // new pitch class -> free a channel
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(E4 + 12))
  }

  it should "free the channel without a high expressive pitch bend among freeing candidates" in {
    // Given
    val alloc = allocator4 // PCG=2, EG=2, channels 1..4
    alloc.allocate(C4) // lowest (boundary)
    val rMidHigh = alloc.allocate(E4) // candidate, will get a high bend
    alloc.allocate(G4) // candidate, no bend
    alloc.allocate(B4) // highest (boundary)
    alloc.updateExpressivePitchBend(rMidHigh.channel, highPitchBendCents) // E4 channel: high bend
    // When
    val result = alloc.allocate(A4) // new pitch class -> free a channel
    // Then
    // Criterion (a): avoid freeing the high-bend channel (E4); free the no-bend channel (G4).
    assertDroppedNotes(result.droppedNotes, Seq(G4))
  }

  it should "free the only occupied channel when the zone has a single member channel" in {
    // Given
    val alloc = allocator1 // single member channel
    alloc.allocate(C4)
    // When
    val result = alloc.allocate(E4) // new pitch class -> must free the sole channel
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    alloc.activeNotes(result.channel) should contain theSameElementsAs Set(E4)
  }

  behavior of "MpeChannelAllocator - Free a channel - High Expressive Pitch Bend"

  it should "drop other notes when a note on a shared channel develops high expressive pitch bend" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // All channels have C. Add another C to share
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    // When
    // Now update pitch bend on shared channel to high value
    val droppedNotes = alloc.updateExpressivePitchBend(sharedChannel, highPitchBendCents)
    // Then
    droppedNotes should not be empty
    alloc.activeNotes(sharedChannel) should contain theSameElementsAs Set(C6)
  }

  it should "not drop notes when expressive pitch bend is below threshold" in {
    // Given
    val alloc = allocator3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    // When
    val droppedNotes = alloc.updateExpressivePitchBend(sharedChannel, lowPitchBendCents)
    // Then
    droppedNotes shouldBe empty
  }

  it should "drop existing notes when new note with high expressive pitch bend is assigned to occupied channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    alloc.allocate(C5)
    // When
    // Both channels occupied with C. Third C must share.
    val result = alloc.allocate(C3, expressivePitchBendCents = highPitchBendCents)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    result.channel shouldBe r1.channel
    alloc.activeNotes(result.channel) should contain theSameElementsAs Set(C3)
  }

  it should "not drop notes when new note with low expressive pitch bend is assigned to occupied channel" in {
    // Given
    val alloc = allocator2
    alloc.allocate(C4)
    alloc.allocate(C5)
    // When
    val result = alloc.allocate(C3, expressivePitchBendCents = lowPitchBendCents)
    // Then
    result.droppedNotes shouldBe empty
  }

  it should "free channel when new note is assigned to channel with existing high-bend note" in {
    // Given
    val alloc = allocator2
    val r1 = alloc.allocate(C4, expressivePitchBendCents = highPitchBendCents)
    alloc.allocate(D4)
    // When
    // Third C must share. r1 has high bend.
    val result = alloc.allocate(C3)
    // Then
    result.channel shouldEqual r1.channel
    assertDroppedNotes(result.droppedNotes, Seq(C4))
  }

  it should "not free channel when new note is assigned to channel with existing low-bend note" in {
    // Given
    val alloc = allocator2
    alloc.allocate(C4, expressivePitchBendCents = lowPitchBendCents)
    alloc.allocate(C5)
    // When
    val result = alloc.allocate(C3)
    // Then
    result.droppedNotes shouldBe empty
  }

  it should "ensure a note with high expressive pitch bend is always sole note on its channel" in {
    // Given
    val alloc = allocator3
    alloc.allocate(C4)
    alloc.allocate(C5)
    alloc.allocate(C3)
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    // When
    alloc.updateExpressivePitchBend(sharedChannel, highPitchBendCents)
    // Then
    alloc.activeNotes(sharedChannel) should contain theSameElementsAs Set(C6)
  }

  behavior of "MpeChannelAllocator - Channel release"

  it should "make channel available for reuse when all notes have ended" in {
    // Given
    val alloc = allocator2
    val r1 = alloc.allocate(C4)
    alloc.allocate(E4)
    // When
    alloc.release(C4, r1.channel)
    // Then
    alloc.isChannelOccupied(r1.channel) shouldBe false
    // New note can reuse the channel
    val r2 = alloc.allocate(D4)
    r2.channel shouldBe r1.channel
    r2.droppedNotes shouldBe empty
  }

  it should "keep channel occupied until all notes receive Note Off" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    alloc.allocate(C5) // goes to EG
    val r2 = alloc.allocate(C3) // must share
    val sharedChannel = r2.channel
    alloc.activeNotes(sharedChannel).size should be > 1
    // When
    alloc.release(C3, sharedChannel)
    // Then
    alloc.isChannelOccupied(sharedChannel) shouldBe true
  }

  behavior of "MpeChannelAllocator - MPE Input"

  it should "preserve input channel assignment when it doesn't violate constraints" in {
    // Given
    val alloc = allocator15
    // When
    val result = alloc.allocate(C4, preferredChannel = Some(5))
    // Then
    result.channel shouldBe 5
  }

  it should "override input channel when it would violate pitch-class invariant" in {
    // Given
    val alloc = allocator15
    alloc.allocate(D4, preferredChannel = Some(5)) // D on channel 5
    // When
    // Try to put C on channel 5 - violates pitch-class invariant
    val result = alloc.allocate(C4, preferredChannel = Some(5))
    // Then
    result.channel should not be 5
    // It should pick another channel (Pitch Class Group)
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.PitchClass)
  }

  it should "ensure unoccupied channels have no group" in {
    // Given
    val alloc = allocator15
    // Then
    (1 to 15).foreach { c => alloc.channelGroupOf(c) shouldBe None }

    // When
    val r1 = alloc.allocate(C4)
    val ch = r1.channel
    // Then
    alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass)

    // When
    alloc.release(C4, ch)
    // Then
    alloc.channelGroupOf(ch) shouldBe None
  }
}

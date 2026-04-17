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
    droppedNotes.map(_.notes).getOrElse(Seq.empty) should contain theSameElementsAs expectedNotes
  }

  // --- 3.1 Basic Allocation (Pitch Class Group) ---

  behavior of "MpeChannelAllocator - Basic Allocation"

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

  // --- 3.2 Expression Group Allocation ---

  behavior of "MpeChannelAllocator - Expression Group Allocation"

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

  // --- 3.3 Channel Sharing ---

  behavior of "MpeChannelAllocator - Channel Sharing"

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

  it should "prefer channel with oldest last Note Off when note counts are equal" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    val r1 = alloc.allocate(C4) // ch, time=1
    val r2 = alloc.allocate(C5) // ch, time=2
    val r3 = alloc.allocate(C3) // ch, time=3

    // Release r2 first (gets older Note Off time), then r3
    alloc.release(C5, r2.channel)
    val ch2 = r2.channel
    alloc.release(C3, r3.channel)
    val ch3 = r3.channel

    // Re-add notes to those channels
    alloc.allocate(C5) // goes to ch2 (older note off)
    alloc.allocate(C3) // goes to ch3

    // All channels have 1 note each.
    // ch2's last Note Off was at time 4.
    // ch3's last Note Off was at time 5.
    // ch1 never had a Note Off (lastNoteOffTime=0).
    // 0 is the oldest/smallest value, so ch1 is preferred.
    // When
    val r4 = alloc.allocate(C6)
    // Then
    r4.channel shouldBe r1.channel
  }

  it should "prefer channel with oldest last onset time when counts and last note off are equal" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    // Use preferredChannel to put the oldest onset on the highest channel number,
    // breaking the correlation between onset order and channel number.
    alloc.allocate(C4, preferredChannel = Some(3)) // ch 3, oldest onset
    alloc.allocate(C5, preferredChannel = Some(2)) // ch 2
    alloc.allocate(C3, preferredChannel = Some(1)) // ch 1, newest onset
    // All have 1 note, all lastNoteOffTime=0. Oldest onset is on ch 3 (highest number).
    // If onset-time tiebreaker were broken and fell through to channel number, ch 1 would be picked.
    // When
    val r4 = alloc.allocate(C6)
    // Then
    r4.channel shouldBe 3
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

  // --- 3.4 Note Dropping — Channel Exhaustion ---

  behavior of "MpeChannelAllocator - Note Dropping (Channel Exhaustion)"

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

  // --- 3.5 Note Dropping — High Expressive Pitch Bend ---

  behavior of "MpeChannelAllocator - Note Dropping (High Expressive Pitch Bend)"

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

  // --- 3.6 Channel Release ---

  behavior of "MpeChannelAllocator - Channel Release"

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

  // --- 3.7 MPE Input — Preserving Input Channel ---

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

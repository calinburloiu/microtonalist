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

  // High pitch bend value (> 50 cents with 48 semitones sensitivity)
  // 50 cents / (48*100 cents) * 8191 ≈ 85.3, so use 100
  private val highPitchBend: Int = 200
  private val lowPitchBend: Int = 50

  // --- 3.1 Basic Allocation (Pitch Class Group) ---

  behavior of "MpeChannelAllocator - Basic Allocation"

  it should "allocate first note to an unoccupied Pitch Class Group channel" in {
    val alloc = allocator15
    val result = alloc.allocate(C4)
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe ChannelGroup.PitchClass
    alloc.activeNotes(result.channel).map(_.midiNote) should contain theSameElementsAs Seq(C4)
  }

  it should "allocate notes with distinct pitch classes to their own Pitch Class Group channels" in {
    val alloc = allocator15
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(D4)
    val r3 = alloc.allocate(E4)
    r1.channel should not equal r2.channel
    r2.channel should not equal r3.channel
    r1.channel should not equal r3.channel
    alloc.channelGroupOf(r1.channel) shouldBe ChannelGroup.PitchClass
    alloc.channelGroupOf(r2.channel) shouldBe ChannelGroup.PitchClass
    alloc.channelGroupOf(r3.channel) shouldBe ChannelGroup.PitchClass
  }

  it should "fill all 12 Pitch Class Group channels with distinct pitch classes (zone with 15 members)" in {
    val alloc = allocator15
    val channels = (0 until 12).map { pc =>
      alloc.allocate(C4 + pc).channel
    }
    channels.distinct.size shouldBe 12
    channels.foreach(ch => alloc.channelGroupOf(ch) shouldBe ChannelGroup.PitchClass)
  }

  it should "fill all Pitch Class Group channels with distinct pitch classes (zone with 7 members)" in {
    val alloc = allocator7
    // PCG=5 for 7 members
    val channels = (0 until 5).map { pc =>
      alloc.allocate(C4 + pc).channel
    }
    channels.distinct.size shouldBe 5
    channels.foreach(ch => alloc.channelGroupOf(ch) shouldBe ChannelGroup.PitchClass)
  }

  // --- 3.2 Expression Group Allocation ---

  behavior of "MpeChannelAllocator - Expression Group Allocation"

  it should "allocate second note with same pitch class to Expression Group" in {
    val alloc = allocator15
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5) // same pitch class C
    r1.channel should not equal r2.channel
    alloc.channelGroupOf(r1.channel) shouldBe ChannelGroup.PitchClass
    alloc.channelGroupOf(r2.channel) shouldBe ChannelGroup.Expression
  }

  it should "share channel when Expression Group has only one member and third note with same pitch class arrives" in {
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    // Both groups full for pitch class C, third note must share
    val r3 = alloc.allocate(C3) // same pitch class
    (r3.channel == r1.channel || r3.channel == r2.channel) shouldBe true
    r1.channel should not equal r2.channel
  }

  it should "allocate third note with same pitch class to another Expression Group channel when available" in {
    val alloc = allocator15 // EG=3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    Set(r1.channel, r2.channel, r3.channel).size shouldBe 3
    alloc.channelGroupOf(r1.channel) shouldBe ChannelGroup.PitchClass
    alloc.channelGroupOf(r2.channel) shouldBe ChannelGroup.Expression
    alloc.channelGroupOf(r3.channel) shouldBe ChannelGroup.Expression
  }

  it should "allocate note with new pitch class to Expression Group when Pitch Class Group is full" in {
    val alloc = allocator7 // PCG=5, EG=2
    // Fill PCG with 5 distinct pitch classes
    (0 until 5).foreach(pc => alloc.allocate(C4 + pc))
    // 6th distinct pitch class goes to EG
    val r = alloc.allocate(C4 + 5)
    alloc.channelGroupOf(r.channel) shouldBe ChannelGroup.Expression
  }

  // --- 3.3 Channel Sharing ---

  behavior of "MpeChannelAllocator - Channel Sharing"

  it should "share channel with same pitch class when both groups are full" in {
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // All 3 channels occupied, 4th C note must share
    val r4 = alloc.allocate(C6)
    Set(r1.channel, r2.channel, r3.channel) should contain(r4.channel)
  }

  it should "prefer channel with lowest active note count when sharing" in {
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // Add another note to r1's channel
    alloc.allocate(C6) // goes to channel with fewest notes
    alloc.activeChannelCount shouldBe 2
    alloc.activeNotes(r1.channel).size shouldEqual 2
    alloc.activeNotes(r2.channel).size shouldEqual 2
  }

  it should "prefer channel with oldest last Note Off when note counts are equal" in {
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
    val r2b = alloc.allocate(C5) // goes to ch2 (older note off)
    val r3b = alloc.allocate(C3) // goes to ch3

    // All channels have 1 note each. r1 never had a Note Off (lastNoteOffTime=0),
    // ch2 had the oldest Note Off. Among equal note counts, prefer oldest Note Off.
    val r4 = alloc.allocate(C6)
    // r1 has lastNoteOffTime=0 (never released), which is the oldest/smallest
    r4.channel shouldBe r1.channel
  }

  it should "share when Expression Group is full but PCG has same pitch class" in {
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
    // EG is full, PCG has C on r1's channel. New C should share with existing C channel
    val r4 = alloc.allocate(C6)
    val cChannels = Set(r1.channel, r2.channel, r3.channel)
    cChannels should contain(r4.channel)
  }

  it should "share in Expression Group when PCG doesn't have the pitch class" in {
    val alloc = allocator7 // PCG=5, EG=2
    // Fill PCG with 5 distinct pitch classes (not including A)
    (0 until 5).foreach(pc => alloc.allocate(C4 + pc))
    // Put A in EG
    val rA1 = alloc.allocate(A4)
    val rA2 = alloc.allocate(MidiNote(A4 + 12)) // A5
    // All channels full. New A should share with existing A in EG
    val rA3 = alloc.allocate(MidiNote(A4 - 12)) // A3
    Set(rA1.channel, rA2.channel) should contain(rA3.channel)
  }

  // --- 3.4 Note Dropping — Channel Exhaustion ---

  behavior of "MpeChannelAllocator - Note Dropping (Channel Exhaustion)"

  it should "free a channel when all channels occupied and new pitch class needs a channel" in {
    val alloc = allocator3 // PCG=1, EG=2, 3 channels
    alloc.allocate(C4) // ch1
    alloc.allocate(E4) // ch2
    alloc.allocate(G4) // ch3
    // All channels occupied with different pitch classes. New pitch class A needs a channel.
    val result = alloc.allocate(A4)
    result.droppedNotes should not be empty
  }

  it should "exclude highest-pitched and lowest-pitched note channels when freeing" in {
    val alloc = allocator3 // 3 channels
    alloc.allocate(C4) // lowest
    alloc.allocate(E4) // middle
    alloc.allocate(G4) // highest
    val result = alloc.allocate(A4)
    // E4 channel should be freed (not C4 or G4)
    result.droppedNotes.map(_.midiNote) should contain theSameElementsAs Seq(E4)
  }

  it should "select channel with oldest last onset among remaining candidates" in {
    val alloc = allocator3
    alloc.allocate(C4) // oldest onset
    alloc.allocate(E4) // middle onset
    alloc.allocate(B4) // newest onset, also highest
    // C4 is lowest, B4 is highest. E4 is the only candidate.
    val result = alloc.allocate(A4)
    result.droppedNotes.map(_.midiNote) should contain theSameElementsAs Seq(E4)
  }

  it should "assign the new note to the freed channel" in {
    val alloc = allocator3
    alloc.allocate(C4)
    alloc.allocate(E4)
    alloc.allocate(G4)
    val result = alloc.allocate(A4)
    result.droppedNotes should not be empty
    // The new note should be on the freed channel
    alloc.activeNotes(result.channel).map(_.midiNote) should contain(A4)
  }

  // --- 3.5 Note Dropping — High Expressive Pitch Bend ---

  behavior of "MpeChannelAllocator - Note Dropping (High Expressive Pitch Bend)"

  it should "drop other notes when a note on a shared channel develops high expressive pitch bend" in {
    val alloc = allocator3 // PCG=1, EG=2
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    // All channels have C. Add another C to share
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    // Now update pitch bend on shared channel to high value
    val dropped = alloc.updateExpressivePitchBend(sharedChannel, highPitchBend)
    dropped should not be empty
    alloc.activeNotes(sharedChannel).size shouldBe 1
  }

  it should "not drop notes when expressive pitch bend is below threshold" in {
    val alloc = allocator3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    val dropped = alloc.updateExpressivePitchBend(sharedChannel, lowPitchBend)
    dropped shouldBe empty
  }

  it should "drop existing notes when new note with high expressive pitch bend is assigned to occupied channel" in {
    val alloc = allocator2 // PCG=1, EG=1
    alloc.allocate(C4)
    alloc.allocate(C5)
    // Both channels occupied with C. Third C must share.
    val result = alloc.allocate(C3, expressivePitchBend = highPitchBend)
    result.droppedNotes should not be empty
    alloc.activeNotes(result.channel).size shouldBe 1
  }

  it should "not drop notes when new note with low expressive pitch bend is assigned to occupied channel" in {
    val alloc = allocator2
    alloc.allocate(C4)
    alloc.allocate(C5)
    val result = alloc.allocate(C3, expressivePitchBend = lowPitchBend)
    result.droppedNotes shouldBe empty
  }

  it should "free channel when new note is assigned to channel with existing high-bend note" in {
    val alloc = allocator2
    val r1 = alloc.allocate(C4, expressivePitchBend = highPitchBend)
    alloc.allocate(D4)
    // Third C must share. r1 has high bend.
    val result = alloc.allocate(C3)
    result.channel shouldEqual r1.channel
    result.droppedNotes.map(_.midiNote) should contain theSameElementsAs Seq(C4)
  }

  it should "not free channel when new note is assigned to channel with existing low-bend note" in {
    val alloc = allocator2
    alloc.allocate(C4, expressivePitchBend = lowPitchBend)
    alloc.allocate(C5)
    val result = alloc.allocate(C3)
    result.droppedNotes shouldBe empty
  }

  it should "ensure a note with high expressive pitch bend is always sole note on its channel" in {
    val alloc = allocator3
    val r1 = alloc.allocate(C4)
    val r2 = alloc.allocate(C5)
    val r3 = alloc.allocate(C3)
    val r4 = alloc.allocate(C6)
    val sharedChannel = r4.channel
    alloc.updateExpressivePitchBend(sharedChannel, highPitchBend)
    alloc.activeNotes(sharedChannel).size shouldBe 1
  }

  // --- 3.6 Channel Release ---

  behavior of "MpeChannelAllocator - Channel Release"

  it should "make channel available for reuse when all notes have ended" in {
    val alloc = allocator2
    val r1 = alloc.allocate(C4)
    alloc.allocate(E4)
    alloc.release(C4, r1.channel)
    alloc.isChannelOccupied(r1.channel) shouldBe false
    // New note can reuse the channel
    val r2 = alloc.allocate(D4)
    r2.channel shouldBe r1.channel
    r2.droppedNotes shouldBe empty
  }

  it should "keep channel occupied until all notes receive Note Off" in {
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocate(C4)
    alloc.allocate(C5) // goes to EG
    val r2 = alloc.allocate(C3) // must share
    val sharedChannel = r2.channel
    alloc.activeNotes(sharedChannel).size should be > 1
    alloc.release(C3, sharedChannel)
    alloc.isChannelOccupied(sharedChannel) shouldBe true
  }

  // --- 3.7 MPE Input — Preserving Input Channel ---

  behavior of "MpeChannelAllocator - MPE Input"

  it should "preserve input channel assignment when it doesn't violate constraints" in {
    val alloc = allocator15
    val result = alloc.allocate(C4, preferredChannel = Some(5))
    result.channel shouldBe 5
  }

  it should "override input channel when it would violate pitch-class invariant" in {
    val alloc = allocator15
    alloc.allocate(D4, preferredChannel = Some(5)) // D on channel 5
    // Try to put C on channel 5 - violates pitch-class invariant
    val result = alloc.allocate(C4, preferredChannel = Some(5))
    result.channel should not be 5
  }
}

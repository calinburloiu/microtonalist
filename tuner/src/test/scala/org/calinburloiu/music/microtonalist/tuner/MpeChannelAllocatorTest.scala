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
import org.calinburloiu.music.scmidi.{MidiNote, PitchClass}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Keep tests organized in sections delimited by `behavior of`. Each section name starts with the name of the class.
 * When adding a new test, choose the most appropriate section and if required create a new section.
 */
class MpeChannelAllocatorTest extends AnyFlatSpec with Matchers with OptionValues {

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

  // High Expression Pitch Bend in cents (> 50 cents threshold)
  private val highPitchBendCents: Double = 100.0
  // Low Expression Pitch Bend in cents (< 50 cents threshold)
  private val lowPitchBendCents: Double = 25.0

  /**
   * Note-centric shorthands for the call sites this suite was originally written against. Each note is
   * allocated as the identity `(inputChannel, midiNote)`; the input channel defaults to 0 and is passed
   * explicitly only where a test later addresses that note through an update method.
   */
  extension (alloc: MpeChannelAllocator) {
    private def allocateNote(midiNote: MidiNote,
                             expressionPitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                             preferredChannel: Option[Int] = None,
                             inputChannel: Int = 0): MpeAllocationResult =
      alloc.allocate(MpeNoteIdentity(inputChannel, midiNote),
        Some(ImmutableMpeExpression(expressionPitchBendCents)), preferredChannel)

    private def releaseNote(midiNote: MidiNote, inputChannel: Int = 0): Option[MpeReleaseResult] =
      alloc.release(MpeNoteIdentity(inputChannel, midiNote))

    private def activeMidiNotes(channel: Int): Set[MidiNote] = alloc.activeNotes(channel).map(_.midiNote)
  }

  private def assertDroppedNotes(droppedNotes: Option[MpeDroppedNotes], expectedNotes: Seq[MidiNote]): Unit = {
    droppedNotes should not be empty
    droppedNotes.get.notes.map(_.noteIdentity.midiNote) should contain theSameElementsAs expectedNotes
  }

  behavior of "MpeChannelAllocator - Step 1: Allocate in Pitch Class Group"

  it should "allocate first note to an unoccupied Pitch Class Group channel" in {
    // Given
    val alloc = allocator15
    // When
    val result = alloc.allocateNote(C4)
    // Then
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(C4)
  }

  it should "allocate notes with distinct pitch classes to their own Pitch Class Group channels" in {
    // Given
    val alloc = allocator15
    // When
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(D4)
    val r3 = alloc.allocateNote(E4)
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
      alloc.allocateNote(C4 + pc).channel
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
      alloc.allocateNote(C4 + pc).channel
    }
    // Then
    channels.distinct.size shouldBe 5
    channels.foreach(ch => alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass))
  }

  it should "prefer unoccupied channel with oldest last Note Off" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocateNote(C4) // ch1
    val r2 = alloc.allocateNote(D4) // ch2
    val ch1 = r1.channel
    val ch2 = r2.channel
    alloc.releaseNote(C4) // older
    alloc.releaseNote(D4) // newer

    // Both are unoccupied and HAVE been used.
    // We want it to pick ch1.
    // But there are also ch3..ch12 which have NEVER been used (lastNoteOffTime=0).
    // If we want it to pick ch1, we must ensure ch3..ch12 are NOT available.
    // So let's fill them first.
    // Each filler note gets its own input channel: C4 + 4 is E4, and reusing input channel 0 for it
    // would make it the same Note Identity as the E4 allocated below, turning that into a duplicate
    // Note On instead of the fresh allocation this test is about.
    (3 to 15).foreach { i => alloc.allocateNote(C4 + i, inputChannel = i) }

    // When
    // Now ch1, ch2 are unoccupied. ch3..15 are occupied.
    // r3 should pick ch1.
    val r3 = alloc.allocateNote(E4)
    // Then
    r3.channel shouldBe ch1
  }

  it should "prefer unoccupied channel that was never used over used and released" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocateNote(C4)
    val ch1 = r1.channel
    alloc.releaseNote(C4)
    // ch1 was used and released. Others never used.
    // never used (lastNoteOffTime=0) should be preferred over used (lastNoteOffTime>0)
    // When
    val r2 = alloc.allocateNote(D4)
    // Then
    r2.channel should not be ch1
  }

  it should "break a tie by oldest last Note Off rather than the preferred input channel" in {
    // Given
    // Two previously-used channels are released, so both are unoccupied candidates for the Pitch Class
    // Group; ch1 has the older last Note Off. The preferred (input) channel is ch2, but criterion (d)
    // outranks the (e) input-channel default.
    val alloc = allocator2
    val r1 = alloc.allocateNote(C4, preferredChannel = Some(1)) // t1: ch1
    val r2 = alloc.allocateNote(D4, preferredChannel = Some(2)) // t2: ch2
    r1.channel shouldBe 1
    r2.channel shouldBe 2
    alloc.releaseNote(C4) // t3: ch1 Note Off=3
    alloc.releaseNote(D4) // t4: ch2 Note Off=4
    alloc.isChannelOccupied(r1.channel) shouldBe false
    alloc.isChannelOccupied(r2.channel) shouldBe false
    // When
    val result = alloc.allocateNote(E4, preferredChannel = Some(2)) // ch2 preferred, but ch1 idle longer
    // Then
    result.channel shouldBe r1.channel
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.PitchClass)
  }

  it should "ignore a released channel's stale onset and prefer the oldest last Note Off" in {
    // Given
    // Both candidates are unoccupied and previously used. Their original onset order (ch1<ch2) disagrees
    // with their Note Off order (ch2<ch1), but releasing a channel clears its onset to 0, so both now
    // carry onset 0 and criterion (c) cannot discriminate. The paper treats an unoccupied channel as
    // having no onset, so criterion (d) governs and the older-Note-Off ch2 wins. (If onset were not
    // cleared, criterion (c) would wrongly pick ch1 — this test guards that.)
    val alloc = allocator2
    val r1 = alloc.allocateNote(C4, preferredChannel = Some(1)) // t1: ch1 onset=1
    val r2 = alloc.allocateNote(D4, preferredChannel = Some(2)) // t2: ch2 onset=2
    r1.channel shouldBe 1
    r2.channel shouldBe 2
    alloc.releaseNote(D4) // t3: ch2 Note Off=3
    alloc.releaseNote(C4) // t4: ch1 Note Off=4
    alloc.isChannelOccupied(r1.channel) shouldBe false
    alloc.isChannelOccupied(r2.channel) shouldBe false
    // When
    val result = alloc.allocateNote(E4) // no preferred channel
    // Then
    result.channel shouldBe r2.channel
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(E4)
  }

  behavior of "MpeChannelAllocator - Step 2: Allocate in Expression Group"

  it should "allocate second note with same pitch class to Expression Group" in {
    // Given
    val alloc = allocator15
    val r1 = alloc.allocateNote(C4)
    // When
    val r2 = alloc.allocateNote(C5) // same pitch class C
    // Then
    r1.channel should not equal r2.channel
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
  }

  it should "share channel when Expression Group has only one member and third note with same pitch class arrives" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(C5)
    // When
    // Both groups full for pitch class C, third note must share
    val r3 = alloc.allocateNote(C3) // same pitch class
    // Then
    (r3.channel == r1.channel || r3.channel == r2.channel) shouldBe true
    r1.channel should not equal r2.channel
  }

  it should "allocate third note with same pitch class to another Expression Group channel when available" in {
    // Given
    val alloc = allocator15 // EG=3
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(C5)
    // When
    val r3 = alloc.allocateNote(C3)
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
    (0 until 5).foreach(pc => alloc.allocateNote(C4 + pc))
    // When
    // 6th distinct pitch class goes to EG
    val r = alloc.allocateNote(C4 + 5)
    // Then
    alloc.channelGroupOf(r.channel) shouldBe Some(ChannelGroup.Expression)
  }

  it should "break a tie by oldest last Note Off rather than the preferred input channel" in {
    // Given
    // Pitch class C fills the single Pitch Class Group channel, so further C notes route to the
    // Expression Group. Two Expression Group channels are released, so both are unoccupied candidates;
    // ch2 has the older last Note Off. The preferred (input) channel is ch3, but criterion (d) outranks
    // the (e) input-channel default.
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocateNote(C4, preferredChannel = Some(1)) // ch1, Pitch Class Group
    val r2 = alloc.allocateNote(C5, preferredChannel = Some(2)) // ch2, Expression Group
    val r3 = alloc.allocateNote(C3, preferredChannel = Some(3)) // ch3, Expression Group
    r1.channel shouldBe 1
    r2.channel shouldBe 2
    r3.channel shouldBe 3
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.channelGroupOf(r3.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.releaseNote(C5) // ch2 Note Off (older)
    alloc.releaseNote(C3) // ch3 Note Off (newer)
    // When
    val result = alloc.allocateNote(C6, preferredChannel = Some(3)) // ch3 preferred, but ch2 idle longer
    // Then
    result.channel shouldBe r2.channel
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.Expression)
  }

  it should "ignore a released channel's stale onset and prefer the oldest last Note Off" in {
    // Given
    // Pitch class C fills the single Pitch Class Group channel, so further C notes route to the
    // Expression Group. The two Expression Group channels' onset order (ch2<ch3) disagrees with their
    // Note Off order (ch3<ch2), but releasing a channel clears its onset to 0, so criterion (c) cannot
    // discriminate and criterion (d) governs: the older-Note-Off ch3 wins. (If onset were not cleared,
    // criterion (c) would wrongly pick ch2 — this test guards that.)
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocateNote(C4, preferredChannel = Some(1)) // ch1, Pitch Class Group
    val r2 = alloc.allocateNote(C5, preferredChannel = Some(2)) // ch2, Expression Group, onset older
    val r3 = alloc.allocateNote(C3, preferredChannel = Some(3)) // ch3, Expression Group, onset newer
    r1.channel shouldBe 1
    r2.channel shouldBe 2
    r3.channel shouldBe 3
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.channelGroupOf(r3.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.releaseNote(C3) // ch3 Note Off (older)
    alloc.releaseNote(C5) // ch2 Note Off (newer)
    // When
    val result = alloc.allocateNote(C6) // no preferred channel
    // Then
    result.channel shouldBe r3.channel
    result.droppedNotes shouldBe empty
    alloc.channelGroupOf(result.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(C6)
  }

  behavior of "MpeChannelAllocator - Step 3: Share channel"

  it should "share channel with same pitch class when both groups are full" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(C5)
    val r3 = alloc.allocateNote(C3)
    // When
    // All 3 channels occupied, 4th C note must share
    val r4 = alloc.allocateNote(C6)
    // Then
    Set(r1.channel, r2.channel, r3.channel) should contain(r4.channel)
  }

  it should "prefer channel with lowest active note count when sharing" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(C5)
    val r3 = alloc.allocateNote(C3)
    // When
    // Add another note to r1's channel
    alloc.allocateNote(C6) // goes to channel with fewest notes
    // Then
    alloc.activeChannelCount shouldBe 2
    alloc.activeNotes(r1.channel).size shouldEqual 2
    alloc.activeNotes(r2.channel).size shouldEqual 2
  }

  it should "prefer the oldest onset among occupied candidates when note counts are equal" in {
    // Given
    // Use preferredChannel to put the oldest onset on the highest channel number, breaking the
    // correlation between onset order and channel number. All three channels then hold one pitch-class-C
    // note with lastNoteOffTime=0, so criteria (a), (b), (d) tie and only onset (c) discriminates. The
    // oldest onset is on ch3 (highest number); if criterion (c) were broken and fell through to the
    // channel-number default (e), ch1 would be picked instead.
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocateNote(C4, preferredChannel = Some(3)) // ch3, oldest onset
    val r2 = alloc.allocateNote(C5, preferredChannel = Some(2)) // ch2
    val r3 = alloc.allocateNote(C3, preferredChannel = Some(1)) // ch1, newest onset
    r1.channel shouldBe 3
    r2.channel shouldBe 2
    r3.channel shouldBe 1
    // When
    val r4 = alloc.allocateNote(C6)
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
    val rB = alloc.allocateNote(C4, preferredChannel = Some(1)) // t1: ch1 onset=1
    val rOther = alloc.allocateNote(C5, preferredChannel = Some(2)) // t2: ch2 onset=2
    rB.channel shouldBe 1
    rOther.channel shouldBe 2
    val rShare = alloc.allocateNote(C6, preferredChannel = Some(1)) // t3: shares ch1 -> ch1 onset=3
    rShare.channel shouldBe rB.channel
    alloc.releaseNote(C5) // t4: ch2 empties, Note Off=4
    val rReuse = alloc.allocateNote(C5, preferredChannel = Some(2)) // t5: ch2 onset=5
    rReuse.channel shouldBe rOther.channel
    alloc.releaseNote(C4) // t6: ch1 keeps C6, Note Off=6, onset stays 3
    // When
    val result = alloc.allocateNote(C7) // t7: must share by oldest onset
    // Then
    result.channel shouldBe rB.channel
  }

  it should "prefer channel without high expression pitch bend when sharing" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocateNote(C4, inputChannel = 1)
    val r2 = alloc.allocateNote(C5, inputChannel = 2)
    // Both notes are pitch class C on distinct channels; r1 then develops a high bend.
    r1.channel should not equal r2.channel
    alloc.channelPitchClass(r1.channel) shouldBe Some(PitchClass.C)
    alloc.channelPitchClass(r2.channel) shouldBe Some(PitchClass.C)
    alloc.updateExpressionPitchBend(1, highPitchBendCents)
    // When
    // The third C must share; it should avoid the high-bend r1 and share r2.
    val r3 = alloc.allocateNote(C3, inputChannel = 3)
    // Then
    r3.channel shouldBe r2.channel
  }

  it should "share when Expression Group is full but PCG has same pitch class" in {
    // Given
    val alloc = allocator7 // PCG=5, EG=2
    // C in the Pitch Class Group; two more C notes in the Expression Group.
    val r1 = alloc.allocateNote(C4)
    val r2 = alloc.allocateNote(C5)
    val r3 = alloc.allocateNote(C3)
    alloc.channelGroupOf(r1.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(r2.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.channelGroupOf(r3.channel) shouldBe Some(ChannelGroup.Expression)
    // Fill the remaining Pitch Class Group channels with other pitch classes.
    val rOthers = Seq(D4, E4, F4, G4).map(alloc.allocateNote(_).channel)
    rOthers.foreach(ch => alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass))
    // When
    // Expression Group is full and the Pitch Class Group holds C on r1's channel; the new C must share.
    val r4 = alloc.allocateNote(C6)
    // Then
    val cChannels = Set(r1.channel, r2.channel, r3.channel)
    cChannels should contain(r4.channel)
  }

  it should "share in Expression Group when PCG doesn't have the pitch class" in {
    // Given
    val alloc = allocator7 // PCG=5, EG=2
    // Fill the Pitch Class Group with 5 distinct pitch classes (not including A).
    val pcgChannels = (0 until 5).map(pc => alloc.allocateNote(C4 + pc).channel)
    pcgChannels.foreach(ch => alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass))
    // Put A in the Expression Group (the Pitch Class Group is full of other pitch classes).
    val rA1 = alloc.allocateNote(A4)
    val rA2 = alloc.allocateNote(MidiNote(A4 + 12)) // A5
    alloc.channelGroupOf(rA1.channel) shouldBe Some(ChannelGroup.Expression)
    alloc.channelGroupOf(rA2.channel) shouldBe Some(ChannelGroup.Expression)
    // When
    // All channels full. New A should share with existing A in the Expression Group.
    val rA3 = alloc.allocateNote(MidiNote(A4 - 12)) // A3
    // Then
    Set(rA1.channel, rA2.channel) should contain(rA3.channel)
  }

  behavior of "MpeChannelAllocator - Step 4: Free a channel - Channel Exhaustion"

  it should "free a channel when all channels occupied and new pitch class needs a channel" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2, 3 channels
    alloc.allocateNote(C4) // ch1
    alloc.allocateNote(E4) // ch2
    alloc.allocateNote(G4) // ch3
    // When
    // All channels occupied with different pitch classes. New pitch class A needs a channel.
    val result = alloc.allocateNote(A4)
    // Then
    result.droppedNotes should not be empty
  }

  it should "exclude highest-pitched and lowest-pitched note channels when freeing" in {
    // Given
    val alloc = allocator3 // 3 channels
    alloc.allocateNote(C4) // lowest
    alloc.allocateNote(E4) // middle
    alloc.allocateNote(G4) // highest
    // When
    val result = alloc.allocateNote(A4)
    // Then
    // E4 channel should be freed (not C4 or G4)
    assertDroppedNotes(result.droppedNotes, Seq(E4))
  }

  it should "select channel with oldest last onset among remaining candidates" in {
    // Given
    val alloc = allocator3
    alloc.allocateNote(C4) // oldest onset
    alloc.allocateNote(E4) // middle onset
    alloc.allocateNote(B4) // newest onset, also highest
    // When
    // C4 is lowest, B4 is highest. E4 is the only candidate.
    val result = alloc.allocateNote(A4)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(E4))
  }

  it should "assign the new note to the freed channel" in {
    // Given
    val alloc = allocator3
    alloc.allocateNote(C4)
    alloc.allocateNote(E4)
    alloc.allocateNote(G4)
    // When
    val result = alloc.allocateNote(A4)
    // Then
    result.droppedNotes should not be empty
    // The new note should be on the freed channel
    alloc.activeMidiNotes(result.channel) should contain(A4)
  }

  it should "place only the new note on the freed channel and clear the old pitch class" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    alloc.allocateNote(C4) // lowest
    alloc.allocateNote(E4) // middle -> will be freed
    alloc.allocateNote(G4) // highest
    // When
    val result = alloc.allocateNote(A4)
    // Then
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(A4)
    alloc.channelPitchClass(result.channel) shouldBe Some(A4.pitchClass)
  }

  it should "free the channel holding the lowest note when both candidates are boundary channels" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    alloc.allocateNote(G4) // highest
    alloc.allocateNote(C4) // lowest
    // When
    val result = alloc.allocateNote(E4)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(E4)
  }

  it should "free the non-boundary channel when one channel holds both the highest and lowest notes" in {
    // Given
    // ch1 ends up holding C4 (lowest) and C6 (highest), both pitch class C; ch2 holds the middle E5.
    val alloc = allocator2 // PCG=1, EG=1
    val rLow = alloc.allocateNote(C4, preferredChannel = Some(1)) // ch1, pitch class C, Pitch Class Group
    val rMid = alloc.allocateNote(E4 + 12, preferredChannel = Some(2)) // ch2, E5 (middle), Expression Group
    val rHigh = alloc.allocateNote(C6, preferredChannel = Some(1)) // shares ch1 -> ch1 = {C4, C6}
    rLow.channel shouldBe 1
    rMid.channel shouldBe 2
    alloc.channelGroupOf(rLow.channel) shouldBe Some(ChannelGroup.PitchClass)
    alloc.channelGroupOf(rMid.channel) shouldBe Some(ChannelGroup.Expression)
    rHigh.channel shouldBe rLow.channel
    alloc.activeMidiNotes(rLow.channel) should contain theSameElementsAs Set(C4, C6)
    // When
    val result = alloc.allocateNote(A4) // new pitch class -> free a channel
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(E4 + 12))
  }

  it should "free the channel without a high expression pitch bend among freeing candidates" in {
    // Given
    val alloc = allocator4 // PCG=2, EG=2, channels 1..4
    alloc.allocateNote(C4, inputChannel = 1) // lowest (boundary)
    alloc.allocateNote(E4, inputChannel = 2) // candidate, will get a high bend
    alloc.allocateNote(G4, inputChannel = 3) // candidate, no bend
    alloc.allocateNote(B4, inputChannel = 4) // highest (boundary)
    alloc.updateExpressionPitchBend(2, highPitchBendCents) // E4's channel: high bend
    // When
    val result = alloc.allocateNote(A4, inputChannel = 5) // new pitch class -> free a channel
    // Then
    // Criterion (a): avoid freeing the high-bend channel (E4); free the no-bend channel (G4).
    assertDroppedNotes(result.droppedNotes, Seq(G4))
  }

  it should "free the only occupied channel when the zone has a single member channel" in {
    // Given
    val alloc = allocator1 // single member channel
    alloc.allocateNote(C4)
    // When
    val result = alloc.allocateNote(E4) // new pitch class -> must free the sole channel
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(E4)
  }

  behavior of "MpeChannelAllocator - Free a channel - High Expression Pitch Bend"

  it should "drop other notes when a note on a shared channel develops a high expression pitch bend" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    alloc.allocateNote(C4, inputChannel = 1)
    alloc.allocateNote(C5, inputChannel = 2)
    alloc.allocateNote(C3, inputChannel = 3)
    // All channels have C. Add another C to share.
    val r4 = alloc.allocateNote(C6, inputChannel = 4)
    val sharedChannel = r4.channel
    // When
    val result = alloc.updateExpressionPitchBend(4, highPitchBendCents)
    // Then
    result.droppedNotes should not be empty
    alloc.activeMidiNotes(sharedChannel) should contain theSameElementsAs Set(C6)
  }

  it should "not drop notes when the expression pitch bend is below the threshold" in {
    // Given
    val alloc = allocator3
    alloc.allocateNote(C4, inputChannel = 1)
    alloc.allocateNote(C5, inputChannel = 2)
    alloc.allocateNote(C3, inputChannel = 3)
    alloc.allocateNote(C6, inputChannel = 4)
    // When
    val result = alloc.updateExpressionPitchBend(4, lowPitchBendCents)
    // Then
    result.droppedNotes shouldBe empty
  }

  it should "drop existing notes when new note with high expression pitch bend is assigned to occupied channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocateNote(C4)
    alloc.allocateNote(C5)
    // When
    // Both channels occupied with C. Third C must share.
    val result = alloc.allocateNote(C3, highPitchBendCents)
    // Then
    assertDroppedNotes(result.droppedNotes, Seq(C4))
    result.channel shouldBe r1.channel
    alloc.activeMidiNotes(result.channel) should contain theSameElementsAs Set(C3)
  }

  it should "not drop notes when new note with low expression pitch bend is assigned to occupied channel" in {
    // Given
    val alloc = allocator2
    alloc.allocateNote(C4)
    alloc.allocateNote(C5)
    // When
    val result = alloc.allocateNote(C3, lowPitchBendCents)
    // Then
    result.droppedNotes shouldBe empty
  }

  it should "free channel when new note is assigned to channel with existing high-bend note" in {
    // Given
    val alloc = allocator2
    val r1 = alloc.allocateNote(C4, highPitchBendCents)
    alloc.allocateNote(D4)
    // When
    // Third C must share. r1 has high bend.
    val result = alloc.allocateNote(C3)
    // Then
    result.channel shouldEqual r1.channel
    assertDroppedNotes(result.droppedNotes, Seq(C4))
  }

  it should "not free channel when new note is assigned to channel with existing low-bend note" in {
    // Given
    val alloc = allocator2
    alloc.allocateNote(C4, lowPitchBendCents)
    alloc.allocateNote(C5)
    // When
    val result = alloc.allocateNote(C3)
    // Then
    result.droppedNotes shouldBe empty
  }

  it should "ensure a note with high expression pitch bend is always sole note on its channel" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    alloc.allocateNote(C4, inputChannel = 1)
    alloc.allocateNote(C5, inputChannel = 2)
    alloc.allocateNote(C3, inputChannel = 3)
    val r4 = alloc.allocateNote(C6, inputChannel = 4)
    val sharedChannel = r4.channel
    // When
    alloc.updateExpressionPitchBend(4, highPitchBendCents)
    // Then
    alloc.activeMidiNotes(sharedChannel) should contain theSameElementsAs Set(C6)
  }

  behavior of "MpeChannelAllocator - Channel release"

  it should "make channel available for reuse when all notes have ended" in {
    // Given
    val alloc = allocator2
    val r1 = alloc.allocateNote(C4)
    alloc.allocateNote(E4)
    // When
    alloc.releaseNote(C4)
    // Then
    alloc.isChannelOccupied(r1.channel) shouldBe false
    // New note can reuse the channel
    val r2 = alloc.allocateNote(D4)
    r2.channel shouldBe r1.channel
    r2.droppedNotes shouldBe empty
  }

  it should "keep channel occupied until all notes receive Note Off" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    alloc.allocateNote(C4)
    alloc.allocateNote(C5) // goes to EG
    val r2 = alloc.allocateNote(C3) // must share
    val sharedChannel = r2.channel
    alloc.activeNotes(sharedChannel).size should be > 1
    // When
    alloc.releaseNote(C3)
    // Then
    alloc.isChannelOccupied(sharedChannel) shouldBe true
  }

  behavior of "MpeChannelAllocator - MPE Input"

  it should "preserve input channel assignment when it doesn't violate constraints" in {
    // Given
    val alloc = allocator15
    // When
    val result = alloc.allocateNote(C4, preferredChannel = Some(5))
    // Then
    result.channel shouldBe 5
  }

  it should "override input channel when it would violate pitch-class invariant" in {
    // Given
    val alloc = allocator15
    alloc.allocateNote(D4, preferredChannel = Some(5)) // D on channel 5
    // When
    // Try to put C on channel 5 - violates pitch-class invariant
    val result = alloc.allocateNote(C4, preferredChannel = Some(5))
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
    val r1 = alloc.allocateNote(C4)
    val ch = r1.channel
    // Then
    alloc.channelGroupOf(ch) shouldBe Some(ChannelGroup.PitchClass)

    // When
    alloc.releaseNote(C4)
    // Then
    alloc.channelGroupOf(ch) shouldBe None
  }

  behavior of "MpeChannelAllocator - Reference counting"

  it should "bypass allocation and report a duplicate for a Note On of an already active identity" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val r1 = alloc.allocate(identity)
    // When
    val r2 = alloc.allocate(identity)
    // Then
    r2.channel shouldBe r1.channel
    r2.isDuplicate shouldBe true
    r2.droppedNotes shouldBe empty
    r2.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.activeNotes(r1.channel) should contain theSameElementsAs Set(identity)
    alloc.referenceCountOf(identity) shouldBe 2
    alloc.activeChannelCount shouldBe 1
  }

  it should "deallocate a note only when its reference count reaches zero" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity).channel
    alloc.allocate(identity)
    // When
    val first = alloc.release(identity)
    // Then
    first.value.channel shouldBe channel
    alloc.referenceCountOf(identity) shouldBe 1
    alloc.isChannelOccupied(channel) shouldBe true
    alloc.channelOf(identity) shouldBe Some(channel)
    // When
    val second = alloc.release(identity)
    // Then
    second.value.channel shouldBe channel
    alloc.referenceCountOf(identity) shouldBe 0
    alloc.isChannelOccupied(channel) shouldBe false
    alloc.channelOf(identity) shouldBe None
  }

  it should "return None when releasing an identity that holds no active count" in {
    // Given
    val alloc = allocator15
    // When / Then
    alloc.release(MpeNoteIdentity(1, C4)) shouldBe None
  }

  it should "ignore the Expression Values given with a duplicate Note On" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.allocate(identity, Some(ImmutableMpeExpression(20.0, 64, 96)))
    // Then
    result.isDuplicate shouldBe true
    result.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.expressionFor(identity) shouldBe ImmutableMpeExpression(10.0, 32, 48)
    alloc.channelExpression(channel) shouldBe ImmutableMpeExpression(10.0, 32, 48)
  }

  it should "leave the Expression Values of a duplicate Note On untouched when none are given" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.allocate(identity)
    // Then
    result.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.channelExpression(channel).pressure shouldBe 32
  }

  it should "not drop notes when a duplicate Note On carries a High Expression Pitch Bend" in {
    // Given
    // first and third end up sharing a channel; second occupies a channel of its own.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val third = MpeNoteIdentity(3, C3)
    val channel = alloc.allocate(first).channel
    alloc.allocate(second)
    alloc.allocate(third).channel shouldBe channel
    // When
    // A duplicate Note On for the shared note carries a High Expression Pitch Bend. Allocation is bypassed
    // and the Expression Values are ignored, so the channel's set of active notes is unchanged and no
    // divergence can arise: the note never acquires the high bend in the first place.
    val result = alloc.allocate(third, Some(ImmutableMpeExpression(highPitchBendCents)))
    // Then
    result.isDuplicate shouldBe true
    result.droppedNotes shouldBe empty
    result.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.activeNotes(channel) should contain theSameElementsAs Set(first, third)
    alloc.channelOf(first) shouldBe Some(channel)
  }

  it should "count two identities sharing a note number as two active notes" in {
    // Given
    // Three C notes from three input channels occupy the three channels of the zone; a fourth shares the
    // oldest, which then holds two identities of the same note number.
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocate(MpeNoteIdentity(1, C4))
    val r2 = alloc.allocate(MpeNoteIdentity(2, C4))
    val r3 = alloc.allocate(MpeNoteIdentity(3, C4))
    Set(r1.channel, r2.channel, r3.channel) should have size 3
    val r4 = alloc.allocate(MpeNoteIdentity(4, C4))
    r4.channel shouldBe r1.channel
    alloc.activeNotes(r1.channel) should contain theSameElementsAs
      Set(MpeNoteIdentity(1, C4), MpeNoteIdentity(4, C4))
    // When
    // A fifth C must share; criterion (b) prefers the channel with the fewest active identities, which
    // requires counting the two same-numbered identities on r1's channel as two.
    val r5 = alloc.allocate(MpeNoteIdentity(5, C4))
    // Then
    r5.channel shouldBe r2.channel
  }

  behavior of "MpeChannelAllocator - Expression Value aggregation"

  it should "not report an Expression Pitch Bend change caused only by floating-point rounding" in {
    // Given
    // Three notes of the same pitch class share the single Member Channel, all with the same Expression
    // Pitch Bend, so the channel's average is a sum of three terms divided by three.
    val alloc = allocator1
    val expression = Some(ImmutableMpeExpression(0.1))
    alloc.allocate(MpeNoteIdentity(1, C4), expression)
    alloc.allocate(MpeNoteIdentity(2, C4), expression)
    val third = MpeNoteIdentity(3, C4)
    alloc.allocate(third, expression)
    // When
    // Releasing one leaves two terms averaging to the same value mathematically, but to a `Double` that
    // differs in its last bits.
    val result = alloc.release(third).value
    // Then
    result.update.pitchBendCents shouldBe None
  }

  it should "average the Expression Values of the notes active on a channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = alloc.allocate(MpeNoteIdentity(1, C4), Some(ImmutableMpeExpression(10.0, 32, 48)))
    alloc.allocate(MpeNoteIdentity(2, C5))
    // When
    // Both groups are full and the pitch class is already present, so the third C shares the oldest channel.
    val shared = alloc.allocate(MpeNoteIdentity(3, C3), Some(ImmutableMpeExpression(-20.0, 96, 96)))
    // Then
    shared.channel shouldBe first.channel
    val expression = alloc.channelExpression(shared.channel)
    expression.pitchBendCents shouldBe -5.0
    expression.pressure shouldBe 64
    expression.slide shouldBe 72
    shared.update shouldBe MpeExpressionUpdate(Some(-5.0), Some(64), Some(72))
  }

  it should "round a fractional average of the integer dimensions half up" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = alloc.allocate(MpeNoteIdentity(1, C4), Some(ImmutableMpeExpression(10.0, 32, 48)))
    alloc.allocate(MpeNoteIdentity(2, C5))
    // When
    // Both groups are full and the pitch class is already present, so the third C shares the oldest channel.
    // Both integer dimensions average to exactly .5, which truncation would round down and half-even would
    // round to the even neighbour.
    val shared = alloc.allocate(MpeNoteIdentity(3, C3), Some(ImmutableMpeExpression(-20.0, 97, 97)))
    // Then
    shared.channel shouldBe first.channel
    val expression = alloc.channelExpression(shared.channel)
    expression.pressure shouldBe 65 // (32 + 97) / 2 = 64.5
    expression.slide shouldBe 73 // (48 + 97) / 2 = 72.5
  }

  it should "return an Expression Values snapshot that does not track later mutations" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    val channelBefore = alloc.channelExpression(channel)
    val noteBefore = alloc.expressionFor(identity)
    // When
    alloc.updateExpressionPitchBend(1, 30.0)
    // Then
    channelBefore.pitchBendCents shouldBe 10.0
    noteBefore.pitchBendCents shouldBe 10.0
    alloc.channelExpression(channel).pitchBendCents shouldBe 30.0
    alloc.expressionFor(identity).pitchBendCents shouldBe 30.0
  }

  it should "return each note's own Expression Values, distinct from the channel's aggregate" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val firstIdentity = MpeNoteIdentity(1, C4)
    val thirdIdentity = MpeNoteIdentity(3, C3)
    val first = alloc.allocate(firstIdentity, Some(ImmutableMpeExpression(10.0, 32, 48)))
    alloc.allocate(MpeNoteIdentity(2, C5))
    // When
    // Both groups are full and the pitch class is already present, so the third C shares the oldest channel.
    val shared = alloc.allocate(thirdIdentity, Some(ImmutableMpeExpression(-20.0, 96, 96)))
    // Then
    shared.channel shouldBe first.channel
    alloc.expressionFor(firstIdentity).pitchBendCents shouldBe 10.0
    alloc.expressionFor(firstIdentity).pressure shouldBe 32
    alloc.expressionFor(firstIdentity).slide shouldBe 48
    alloc.expressionFor(thirdIdentity).pitchBendCents shouldBe -20.0
    alloc.expressionFor(thirdIdentity).pressure shouldBe 96
    alloc.expressionFor(thirdIdentity).slide shouldBe 96
    // The channel's aggregate is the average of the two, not either note's own value.
    alloc.channelExpression(shared.channel).pitchBendCents shouldBe -5.0
  }

  it should "retain the last Expression Values when the channel becomes unoccupied" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.release(identity)
    // Then
    result.value.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.isChannelOccupied(channel) shouldBe false
    val retained = alloc.channelExpression(channel)
    retained.pitchBendCents shouldBe 10.0
    retained.pressure shouldBe 32
    retained.slide shouldBe 48
  }

  it should "zero the retained Channel Pressure when the last note is released with resetPressureOnEmpty" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.release(identity, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe true
    result.update.pressure shouldBe Some(0)
    alloc.channelExpression(channel).pressure shouldBe 0
    // The other two dimensions are retained.
    alloc.channelExpression(channel).pitchBendCents shouldBe 10.0
    alloc.channelExpression(channel).slide shouldBe 48
  }

  it should "not report a pressure reset when other notes remain on the channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val third = MpeNoteIdentity(3, C3)
    val channel = alloc.allocate(first, Some(ImmutableMpeExpression(pressure = 80))).channel
    alloc.allocate(second)
    alloc.allocate(third, Some(ImmutableMpeExpression(pressure = 20))).channel shouldBe channel
    // When
    val result = alloc.release(first, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe false
    result.update.pressure shouldBe Some(20)
    alloc.channelExpression(channel).pressure shouldBe 20
  }

  it should "not report a pressure reset when the retained Channel Pressure is already zero" in {
    // Given
    val alloc = allocator15
    val identity = MpeNoteIdentity(1, C4)
    alloc.allocate(identity)
    // When
    val result = alloc.release(identity, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe false
    result.update shouldBe MpeExpressionUpdate.Unchanged
  }

  behavior of "MpeChannelAllocator - Expression Value updates"

  it should "fan an Expression Pitch Bend update out to every channel holding a note of the input channel" in {
    // Given
    val alloc = allocator15
    val cChannel = alloc.allocate(MpeNoteIdentity(1, C4)).channel
    val eChannel = alloc.allocate(MpeNoteIdentity(1, E4)).channel
    val otherChannel = alloc.allocate(MpeNoteIdentity(2, G4)).channel
    // When
    val result = alloc.updateExpressionPitchBend(1, 30.0)
    // Then
    result.droppedNotes shouldBe empty
    result.channelUpdates should contain theSameElementsAs Seq(
      MpeChannelExpressionUpdate(cChannel, MpeExpressionUpdate(pitchBendCents = Some(30.0))),
      MpeChannelExpressionUpdate(eChannel, MpeExpressionUpdate(pitchBendCents = Some(30.0)))
    )
    alloc.channelExpression(otherChannel).pitchBendCents shouldBe 0.0
  }

  it should "report no update for a channel whose average is unchanged" in {
    // Given
    val alloc = allocator15
    alloc.allocate(MpeNoteIdentity(1, C4), Some(ImmutableMpeExpression(30.0)))
    // When
    val result = alloc.updateExpressionPitchBend(1, 30.0)
    // Then
    result.channelUpdates shouldBe empty
  }

  it should "ignore a Polyphonic Key Pressure update addressed to an inactive identity" in {
    // Given
    val alloc = allocator15
    alloc.allocate(MpeNoteIdentity(1, C4))
    // When / Then
    alloc.updatePressure(MpeNoteIdentity(1, D4), 80) shouldBe MpeExpressionUpdateResult()
  }

  it should "update a single identity's Channel Pressure contribution" in {
    // Given
    // C4 and C5 both arrive on input channel 1 but land on different channels, and C3, from another input
    // channel, shares C4's.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val sibling = MpeNoteIdentity(1, C5)
    val channel = alloc.allocate(first).channel
    val siblingChannel = alloc.allocate(sibling).channel
    siblingChannel should not equal channel
    alloc.allocate(MpeNoteIdentity(3, C3)).channel shouldBe channel
    // When
    val result = alloc.updatePressure(first, 80)
    // Then
    // Only C4's own contribution changes: its channel averages 80 with C3's 0, and — unlike an input-channel
    // update — nothing fans out to C5, which shares C4's input channel.
    result.channelUpdates shouldEqual Seq(
      MpeChannelExpressionUpdate(channel, MpeExpressionUpdate(pressure = Some(40))))
    alloc.channelExpression(siblingChannel).pressure shouldBe MpeExpression.DefaultPressure
  }

  it should "keep the most recently sounded note when several notes on a channel acquire a high bend at once" in {
    // Given
    // Two identities from the same input channel end up sharing a channel.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(1, C5)
    val channel = alloc.allocate(first).channel
    alloc.allocate(MpeNoteIdentity(2, D4))
    alloc.allocate(second).channel shouldBe channel
    // When
    // One Pitch Bend message gives both of them a High Expression Pitch Bend.
    val result = alloc.updateExpressionPitchBend(1, 100.0)
    // Then
    result.droppedNotes should have size 1
    result.droppedNotes.head.channel shouldBe channel
    result.droppedNotes.head.notes.map(_.noteIdentity) shouldEqual Seq(first)
    alloc.activeNotes(channel) should contain theSameElementsAs Set(second)
    alloc.channelOf(first) shouldBe None
    result.channelUpdates shouldEqual Seq(
      MpeChannelExpressionUpdate(channel, MpeExpressionUpdate(pitchBendCents = Some(100.0))))
  }

  it should "drop the co-resident note when an Expression Pitch Bend diverges downwards" in {
    // Given
    // A High Expression Pitch Bend exceeds the threshold in either direction, so a negative bend of the same
    // magnitude must drop just as a positive one does.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val channel = alloc.allocate(first).channel
    alloc.allocate(MpeNoteIdentity(3, D4))
    alloc.allocate(second).channel shouldBe channel
    // When
    val result = alloc.updateExpressionPitchBend(2, -100.0)
    // Then
    result.droppedNotes should have size 1
    result.droppedNotes.head.notes.map(_.noteIdentity) shouldEqual Seq(first)
    alloc.activeNotes(channel) should contain theSameElementsAs Set(second)
  }

  it should "not drop a co-resident note for a bend exactly at the High Expression Pitch Bend threshold" in {
    // Given
    // The threshold is exclusive: a bend must exceed it, so a bend exactly at it leaves the channel shared.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val channel = alloc.allocate(first).channel
    alloc.allocate(MpeNoteIdentity(3, D4))
    alloc.allocate(second).channel shouldBe channel
    // When
    val result = alloc.updateExpressionPitchBend(2, 50.0)
    // Then
    result.droppedNotes shouldBe empty
    alloc.activeNotes(channel) should contain theSameElementsAs Set(first, second)
  }

  it should "report the reference count of each dropped note" in {
    // Given
    val alloc = allocator1 // a single member channel
    val identity = MpeNoteIdentity(1, C4)
    alloc.allocate(identity)
    alloc.allocate(identity)
    // When
    val result = alloc.allocate(MpeNoteIdentity(2, E4))
    // Then
    result.droppedNotes.value.notes shouldEqual Seq(MpeDroppedNote(identity, 2))
    alloc.channelOf(identity) shouldBe None
  }

  behavior of "MpeChannelAllocator.retaining"

  // ---- Retained channels ----

  it should "keep the notes, reference counts, Expression Values, pitch class and group of a retained channel" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val identity = MpeNoteIdentity(1, MidiNote.C4)
    val expression = ImmutableMpeExpression(pitchBendCents = 20.0, pressure = 70, slide = 100)
    val channel = alloc.allocate(identity, Some(expression)).channel
    alloc.allocate(identity)
    val group = alloc.channelGroupOf(channel)

    // When
    val shrunk = MpeZone(MpeZoneType.Lower, 4)
    val rebuilt = MpeChannelAllocator.retaining(shrunk, alloc,
      retainedChannels = Set(channel), droppedInputChannels = Set.empty)

    // Then
    rebuilt.channelOf(identity) shouldEqual Some(channel)
    rebuilt.referenceCountOf(identity) shouldEqual 2
    rebuilt.channelExpression(channel).pressure shouldEqual 70
    rebuilt.channelExpression(channel).slide shouldEqual 100
    rebuilt.channelPitchClass(channel) shouldEqual Some(MidiNote.C4.pitchClass)
    rebuilt.channelGroupOf(channel) shouldEqual group
  }

  it should "drop the notes of a channel that is not retained" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val kept = MpeNoteIdentity(1, MidiNote.C4)
    val dropped = MpeNoteIdentity(2, MidiNote.E4)
    val keptChannel = alloc.allocate(kept).channel
    val droppedChannel = alloc.allocate(dropped).channel
    keptChannel should not equal droppedChannel

    // When
    val rebuilt = MpeChannelAllocator.retaining(zone, alloc,
      retainedChannels = Set(keptChannel), droppedInputChannels = Set.empty)

    // Then
    rebuilt.channelOf(kept) shouldEqual Some(keptChannel)
    rebuilt.channelOf(dropped) shouldEqual None
    rebuilt.isChannelOccupied(droppedChannel) shouldBe false
  }

  it should "drop a note whose input channel left MPE control even when its output channel is retained" in {
    // Given
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone)
    val identity = MpeNoteIdentity(6, MidiNote.C4)
    val channel = alloc.allocate(identity, preferredChannel = Some(1)).channel

    // When
    val rebuilt = MpeChannelAllocator.retaining(zone, alloc,
      retainedChannels = Set(channel), droppedInputChannels = Set(6))

    // Then
    rebuilt.channelOf(identity) shouldEqual None
    rebuilt.isChannelOccupied(channel) shouldBe false
  }

  it should "start every channel of the new Zone that is not retained empty" in {
    // Given
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 4))
    alloc.allocate(MpeNoteIdentity(1, MidiNote.C4))

    // When
    val grown = MpeZone(MpeZoneType.Lower, 7)
    val rebuilt = MpeChannelAllocator.retaining(grown, alloc,
      retainedChannels = Set.empty, droppedInputChannels = Set.empty)

    // Then
    rebuilt.activeChannelCount shouldEqual 0
    rebuilt.activeAllocations shouldBe empty
  }

  // ---- At-capacity and over-subscribed groups ----

  it should "keep working when a retained channel's group is filled to exact capacity in the smaller Zone" in {
    // Given
    // A 10-Member Zone has an Expression Group of 3; a 3-Member Zone has one of 2. Three notes of the same pitch
    // class on different input channels cannot share the Pitch Class Group channel, so beyond the first they
    // occupy Expression Group channels. Retaining channel 1 (Pitch Class Group) and channels 2-3 (Expression
    // Group) fills both of the smaller Zone's groups to exactly their capacity — not beyond it.
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 10))
    val identities = Seq(
      MpeNoteIdentity(1, MidiNote.C4), MpeNoteIdentity(2, MidiNote.C4), MpeNoteIdentity(3, MidiNote.C4))
    val channels = identities.map(alloc.allocate(_).channel).toSet

    // When
    val small = MpeZone(MpeZoneType.Lower, 3)
    val rebuilt = MpeChannelAllocator.retaining(small, alloc,
      retainedChannels = channels.intersect(small.memberChannels.toSet),
      droppedInputChannels = Set.empty)

    // Then
    // Nothing throws, and a fresh note still lands on a Member Channel of the new Zone.
    val result = rebuilt.allocate(MpeNoteIdentity(1, MidiNote.G4))
    small.memberChannels.toSet should contain(result.channel)
  }

  it should "keep working when a retained channel's Pitch Class Group is over-subscribed in the smaller Zone" in {
    // Given
    // A 10-Member Zone has a Pitch Class Group of 7, room enough for three distinct pitch classes, so all three
    // land there. A 3-Member Zone has a Pitch Class Group of only 1: retaining all three channels genuinely
    // over-subscribes it (3 occupied Pitch Class Group channels against a capacity of 1), and — because the
    // channel count is conserved — leaves the Expression Group nominally under capacity (0 against 2) even
    // though the Zone is, in fact, fully occupied.
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 10))
    val identities = Seq(
      MpeNoteIdentity(1, MidiNote.C4), MpeNoteIdentity(2, MidiNote.D4), MpeNoteIdentity(3, MidiNote.E4))
    val channels = identities.map(alloc.allocate(_).channel).toSet

    // When
    val small = MpeZone(MpeZoneType.Lower, 3)
    val rebuilt = MpeChannelAllocator.retaining(small, alloc,
      retainedChannels = channels.intersect(small.memberChannels.toSet),
      droppedInputChannels = Set.empty)

    // Then
    // Nothing throws, and a fresh note of yet another pitch class still lands on a Member Channel of the new
    // Zone, even though the Expression Group's nominal room check finds no actual unoccupied channel to grant.
    val result = rebuilt.allocate(MpeNoteIdentity(4, MidiNote.G4))
    small.memberChannels.toSet should contain(result.channel)
  }

  it should "keep working when a retained channel's Expression Group is over-subscribed in the smaller Zone" in {
    // Given
    // A 10-Member Zone has an Expression Group of 3. One note claims the Pitch Class Group (channel 10, via
    // preferredChannel); three more of the same pitch class each claim an Expression Group channel (1, 2, 3).
    // Retaining only channels 1-3 — the Expression Group ones — into a 3-Member Zone (Expression Group of 2)
    // genuinely over-subscribes it (3 occupied against a capacity of 2), while leaving the Pitch Class Group
    // nominally under capacity (0 against 1) even though the Zone is, in fact, fully occupied.
    val alloc = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 10))
    alloc.allocate(MpeNoteIdentity(1, MidiNote.C4), preferredChannel = Some(10))
    val egChannels = Seq(2, 3, 4).map { inputChannel =>
      alloc.allocate(MpeNoteIdentity(inputChannel, MidiNote.C4), preferredChannel = Some(inputChannel - 1)).channel
    }.toSet
    egChannels shouldEqual Set(1, 2, 3)

    // When
    val small = MpeZone(MpeZoneType.Lower, 3)
    val rebuilt = MpeChannelAllocator.retaining(small, alloc,
      retainedChannels = egChannels, droppedInputChannels = Set.empty)

    // Then
    // Nothing throws, and a fresh note of yet another pitch class still lands on a Member Channel of the new
    // Zone, even though the Pitch Class Group's nominal room check finds no actual unoccupied channel to grant.
    val result = rebuilt.allocate(MpeNoteIdentity(5, MidiNote.D4))
    small.memberChannels.toSet should contain(result.channel)
  }
}

# Channel Choice Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `MpeChannelAllocator` conform to the revised MPE Tuner paper (PR #235): one unified tie-breaking function for both note placement and channel freeing, the refined "free the bass" boundary rule, and the audit fixes (criterion order, no-short-circuit, onset semantics, and the latent Step-4 freeing bug).

**Architecture:** `bestCandidate` becomes the single source of truth for tie-break criteria (a)–(e). `freeChannel` keeps only boundary-channel exclusion and delegates the final pick to `bestCandidate`. `ChannelState` clears its onset timestamp when it becomes unoccupied (the paper says an unoccupied channel has no onset). High-bend dropping in `doAllocate` is extracted into a named method.

**Tech Stack:** Scala 3, sbt (via `sbtn`/BSP), ScalaTest (AnyFlatSpec + Matchers), Metals MCP for compilation.

**Spec:** [`2026-06-17-channel-choice-design.md`](2026-06-17-channel-choice-design.md)

---

## Conventions for every task

- **Compile** a module with Metals MCP: `mcp__metals__compile-module` with `module = "tuner"`. Under strict TDD the test must fail for the *right reason* (assertion), not a compile error — compile first, fix compile errors, then run.
- **Run the allocator suite:**
  ```bash
  sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
  ```
- **Run a single case by name substring** (ScalaTest `-z`):
  ```bash
  sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -z \"only the new note\" -oNCXEHLOPQRMWS"
  ```
- **Run the MpeTuner suite:**
  ```bash
  sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
  ```
- **Files (all tasks):**
  - Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala`
  - Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`
  - Modify (Task 6): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
- **Commits** use the repo convention `[#154] <summary>` and end with the `Co-Authored-By` trailer. Only commit if the user has authorized committing for this branch.

> **Clock model (needed to reason about the tests):** `allocate` and `release` each call `nextTime()` once, so every allocation/release is a unique, strictly increasing logical tick. `addNote` sets `lastOnsetTime` to that tick; `removeNote` sets `lastNoteOffTime`. Because every `addNote` uses a distinct tick, **no two occupied channels ever share a `lastOnsetTime`** — so criterion (c) always discriminates among occupied candidates.

---

## Task 1: Unify tie-break criteria (a)–(e) in `bestCandidate` + clear onset when unoccupied

**Files:**
- Modify: `MpeChannelAllocator.scala` — `bestCandidate` (~405-412) and `ChannelState.removeNote` (~189-197)
- Test: `MpeChannelAllocatorTest.scala`

Fixes three audit findings at once: (1) criterion order — (c) onset must precede (d) Note Off; (2) the `preferredChannel` early-return must become criterion (e), the last tiebreak; (3) an unoccupied channel must have no onset, so `removeNote` clears it.

- [ ] **Step 1: Write the failing tests**

Add to the "MpeChannelAllocator - Channel Sharing" behavior section (after the existing tie-break tests, ~line 280):

```scala
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
    // Both candidates are unoccupied and previously used. Their stale onset order (ch1<ch2)
    // disagrees with their Note Off order (ch2<ch1). The paper treats unoccupied channels as
    // having no onset, so criterion (d) governs and the older-Note-Off ch2 must win.
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
```

- [ ] **Step 2: Compile and run — verify the new tests fail for the right reason**

Compile (`mcp__metals__compile-module`, `module = "tuner"`), then:
```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: the three new tests FAIL with assertion errors (current code: short-circuits the preferred channel, and orders Note Off before onset). All other tests still pass.

- [ ] **Step 3: Clear onset when a channel becomes unoccupied**

In `MpeChannelAllocator.scala`, `ChannelState.removeNote`, set `_lastOnsetTime = 0L` when the channel empties:

```scala
  def removeNote(midiNote: MidiNote, time: Long): Unit = {
    if (_notes.remove(midiNote).isDefined) {
      _lastNoteOffTime = time
      if (_notes.isEmpty) {
        _pitchClass = None
        _group = None
        _lastOnsetTime = 0L
      }
    }
  }
```

Update the `lastOnsetTime` ScalaDoc to read: `Zero when the channel is unoccupied (never received a note, or all notes have been released).`

- [ ] **Step 4: Rewrite `bestCandidate` to apply (a)–(e) in order**

```scala
  private def bestCandidate(candidates: Seq[ChannelState], preferredChannel: Option[Int]): ChannelState =
    candidates.minBy { s =>
      (hasHighExpressivePitchBend(s),                      // (a) no high bend (false < true)
        s.notes.size,                                      // (b) fewest active notes
        s.lastOnsetTime,                                   // (c) oldest onset
        s.lastNoteOffTime,                                 // (d) oldest last Note Off
        if (preferredChannel.contains(s.channel)) 0 else 1, // (e) prefer the input channel
        s.channel)                                         // (e) then the lowest channel number
    }
```

- [ ] **Step 5: Run the allocator suite — verify green**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: all tests PASS (the three new ones plus every pre-existing case).

- [ ] **Step 6: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala
git commit -m "$(cat <<'EOF'
[#154] Unify MPE channel tie-break criteria (a)-(e)

Order criteria as onset before last Note Off, make the preferred input
channel the last tiebreak instead of an early return, and clear a channel's
onset when it becomes unoccupied so it carries no onset, per the paper.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Rewrite `freeChannel` — boundary exclusion, "free the bass", delegate to `bestCandidate`, actually free

**Files:**
- Modify: `MpeChannelAllocator.scala` — `freeChannel` (~414-436), `allocate` Step 4 call site (~273-277), add `occupiedChannelStates` and `lowestAndHighestNotes` helpers
- Test: `MpeChannelAllocatorTest.scala`

Fixes the latent Step-4 bug (freed notes never removed), implements the paper's "free the bass" edge case, computes highest/lowest in a single pass, and reuses `bestCandidate` for the final selection.

- [ ] **Step 1: Write the failing tests**

Add to the "MpeChannelAllocator - Note Dropping (Channel Exhaustion)" behavior section (after the existing freeing tests, ~line 387). First add a 4-member fixture next to the others (~line 33):

```scala
  // Lower Zone with 4 members: PCG=2, EG=2, channels 1..4
  private def allocator4: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 4))
```

Then the tests:

```scala
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
```

- [ ] **Step 2: Compile and run — verify the new tests fail for the right reason**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: the four new tests FAIL on assertions (current `freeChannel` leaves dropped notes on the channel, picks oldest onset for the empty-candidate fallback, and ignores high bend). Other tests still pass.

- [ ] **Step 3: Add the helpers and rewrite `freeChannel`**

Add helpers near the other private accessors (after `unoccupiedChannels`, ~line 377):

```scala
  private def occupiedChannelStates: Seq[ChannelState] = channelStates.values.filter(_.isOccupied).toSeq

  private def lowestAndHighestNotes(states: Seq[ChannelState]): (MidiNote, MidiNote) = {
    val notes = states.iterator.flatMap(_.notes.iterator)
    var lowest = notes.next()
    var highest = lowest
    for (note <- notes) {
      if (note.number < lowest.number) lowest = note
      if (note.number > highest.number) highest = note
    }
    (lowest, highest)
  }
```

Replace `freeChannel` entirely:

```scala
  /**
   * Frees a channel so the incoming note can be placed on it, dropping all of the freed channel's
   * active notes. Boundary channels — those holding the highest- or lowest-pitched active note — are
   * preserved when possible. The final selection among the remaining candidates reuses the tie-break
   * criteria of [[bestCandidate]] (criterion (e) degenerates to the lowest channel number, since the
   * candidates are occupied).
   *
   * @param time The logical timestamp at which the freed notes are dropped.
   * @return The notes dropped from the freed channel.
   */
  private def freeChannel(time: Long): DroppedNotes = {
    val occupied = occupiedChannelStates
    assert(occupied.nonEmpty)

    val target =
      if (occupied.sizeIs == 1) {
        // Only one candidate: free it regardless of register.
        occupied.head
      } else {
        val (lowest, highest) = lowestAndHighestNotes(occupied)
        val nonBoundary = occupied.filterNot { s =>
          s.notes.exists(n => n.number == lowest.number || n.number == highest.number)
        }
        if (nonBoundary.nonEmpty) {
          bestCandidate(nonBoundary, None)
        } else {
          // Every occupied channel is a boundary channel (extremes on different channels): free the
          // channel holding the lower (bass) note, retaining the upper melodic note.
          bestCandidate(occupied.filter(_.notes.exists(_.number == lowest.number)), None)
        }
      }

    val dropped = DroppedNotes(target.channel, target.notes.toSeq, target.group.get)
    target.notes.foreach(n => target.removeNote(n, time))
    dropped
  }
```

- [ ] **Step 4: Update the Step 4 call site in `allocate`**

Replace the Step 4 block (~273-277):

```scala
    // Step 4: No channel with the same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(time)
    doAllocate(channelStates(dropped.channel), midiNote, expressivePitchBendCents, time, dropped.group)
      .copy(droppedNotes = Some(dropped))
```

- [ ] **Step 5: Run the allocator suite — verify green**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala
git commit -m "$(cat <<'EOF'
[#154] Rewrite freeChannel to reuse bestCandidate and free the bass

Boundary-channel exclusion now computes the extremes in a single pass and
delegates the final pick to bestCandidate. When every occupied channel is a
boundary channel, free the bass channel per the paper. The freed channel's
notes are now actually removed, fixing a note lingering mistuned on it.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Extract high-bend dropping out of `doAllocate`

**Files:**
- Modify: `MpeChannelAllocator.scala` — `doAllocate` (~379-403)
- Test: existing cases cover this (no new test) — it is a behavior-preserving refactor.

- [ ] **Step 1: Replace `doAllocate` and add the extracted method**

```scala
  private def doAllocate(state: ChannelState,
                         midiNote: MidiNote,
                         expressivePitchBendCents: Double,
                         time: Long,
                         targetGroup: ChannelGroup): AllocationResult = {
    val existingNotes = state.notes
    state.addNote(midiNote, MutableMpeExpression(expressivePitchBendCents), time, targetGroup)
    val dropped = dropExistingNotesForHighBend(state, existingNotes, expressivePitchBendCents, time)
    AllocationResult(state.channel, dropped)
  }

  /**
   * Drops the existing notes on a channel when a high expressive pitch bend means they can no longer
   * coexist with the newly added note (paper Sections 5.2.2 and 5.2.3): either the new note has a high
   * bend, or the channel already held a note with a high bend.
   *
   * @param state             The channel the new note was just added to.
   * @param existingNotes     The notes present on the channel before the new note was added.
   * @param newPitchBendCents The new note's expressive pitch bend in cents.
   * @param time              The logical timestamp of the drop.
   * @return The dropped notes, or `None` when nothing is dropped.
   */
  private def dropExistingNotesForHighBend(state: ChannelState,
                                           existingNotes: Set[MidiNote],
                                           newPitchBendCents: Double,
                                           time: Long): Option[DroppedNotes] = {
    if (existingNotes.isEmpty) {
      None
    } else {
      val existingHighBend =
        existingNotes.exists(n => isHighExpressivePitchBend(state.expressionFor(n).pitchBendCents))
      val newHighBend = isHighExpressivePitchBend(newPitchBendCents)
      if (existingHighBend || newHighBend) {
        val toDrop = DroppedNotes(state.channel, existingNotes.toSeq, state.group.get)
        existingNotes.foreach(n => state.removeNote(n, time))
        Some(toDrop)
      } else {
        None
      }
    }
  }
```

- [ ] **Step 2: Compile and run — verify nothing regressed**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: all tests PASS (the high-bend cases at "drop existing notes when new note with high expressive pitch bend…" and "free channel when new note is assigned to channel with existing high-bend note" exercise the extracted method).

- [ ] **Step 3: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala
git commit -m "$(cat <<'EOF'
[#154] Extract high-bend note dropping from doAllocate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Verify mermaid-branch coverage and review test names

**Files:**
- Test: `MpeChannelAllocatorTest.scala` (review; add only on a real gap)

The paper's Section 4.5 mermaid has these branches. Confirm each maps to a test:

| Branch | Meaning | Covered by |
|---|---|---|
| Q1a No → Q2 | PCG has no unoccupied channel | "allocate note with new pitch class to Expression Group when Pitch Class Group is full" |
| Q1a Yes / Q1b Yes → Q2 | PCG already holds P | "allocate second note with same pitch class to Expression Group" |
| Q1a Yes / Q1b No → A1 (Step 1) | assign to unoccupied PCG channel | "allocate first note…", "allocate notes with distinct pitch classes…" |
| Q2 Yes → A2 (Step 2) | assign to unoccupied EG channel | "allocate second note with same pitch class to Expression Group", "…to Expression Group when Pitch Class Group is full" |
| Q2 No → Q3 Yes → A3 (Step 3) | share with same pitch class | "share channel with same pitch class when both groups are full", "share when Expression Group is full but PCG has same pitch class", "share in Expression Group when PCG doesn't have the pitch class" |
| Q2 No → Q3 No → A4 (Step 4) | free a channel | "free a channel when all channels occupied…", and the Task 2 freeing tests |

- [ ] **Step 1: Confirm the mapping holds in the current file.** Each row above must point to a present, passing test. If any row has no test, add one near the matching behavior section following the row's description (Given/When/Then, BDD style). Based on the current file, all rows are covered — expect no additions.

- [ ] **Step 2: Review names that no longer isolate their criterion.** The case "prefer channel with oldest last Note Off when note counts are equal" now resolves on onset (criterion c) before Note Off, because its candidates are occupied with distinct onsets. Rename it to "prefer the oldest onset among occupied candidates when note counts are equal" (or restructure it so its candidates are unoccupied and it genuinely isolates the Note Off criterion). Keep the assertion and Given/When/Then intact.

- [ ] **Step 3: Run the allocator suite — verify green**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: all tests PASS.

- [ ] **Step 4: Commit (only if Step 1 or Step 2 changed the file)**

```bash
git add tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala
git commit -m "$(cat <<'EOF'
[#154] Align allocator tests with mermaid branches and criterion order

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ScalaDoc pass on changed members

**Files:**
- Modify: `MpeChannelAllocator.scala`

- [ ] **Step 1: Confirm ScalaDoc on every changed/new member.** Ensure `bestCandidate`, `freeChannel`, `dropExistingNotesForHighBend`, `lowestAndHighestNotes`, and `occupiedChannelStates` each carry a one-line-or-more ScalaDoc describing behavior and parameters, and that `ChannelState.lastOnsetTime` reflects the cleared-on-unoccupied semantics from Task 1. (`bestCandidate` and `freeChannel` ScalaDoc were written in Tasks 1–2; verify they read correctly and reference criteria (a)–(e).)

- [ ] **Step 2: Compile**

`mcp__metals__compile-module`, `module = "tuner"`. Expected: success, no warnings about the edited file.

- [ ] **Step 3: Commit (if anything changed)**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala
git commit -m "$(cat <<'EOF'
[#154] Document MpeChannelAllocator tie-break and freeing methods

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Enable the two "2 candidate channels" tests in `MpeTunerTest`

**Files:**
- Modify: `MpeTunerTest.scala` — lines ~1604 (non-MPE, `tuner2`) and ~1660 (MPE, `tuner2MpeInput`)

These two tests assert the "free the bass" behavior at the tuner level and go green once Task 2 lands. Leave the high-bend freeChannel truth-table tests (≈1687–1754), the runtime-divergence test (≈1758), and the common-input-channel test (≈1802) ignored — they require seeding the incoming note's expressive bend into the allocator from `MpeTuner`, which is TODO #154 future work and out of scope.

- [ ] **Step 1: Un-ignore the two tests**

Change the test at ~1604 from:
```scala
  ignore should "preserve the highest and drop the lowest note during channel exhaustion dropping when there are only" +
```
to:
```scala
  it should "preserve the highest and drop the lowest note during channel exhaustion dropping when there are only" +
```
Do the same for the test at ~1660 (the `tuner2MpeInput` one with the identical name).

- [ ] **Step 2: Run the MpeTuner suite — verify the two now pass**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: both enabled tests PASS; the rest of the suite is unchanged. (To confirm they were genuinely red before Task 2, optionally `git stash` the `MpeChannelAllocator.scala` change and re-run — they fail by dropping the wrong note — then restore.)

- [ ] **Step 3: Commit**

```bash
git add tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Enable the two-candidate-channel freeing tests in MpeTunerTest

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full `tuner` module tests**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: all PASS.

- [ ] **Step 2: Full project suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: all PASS.

- [ ] **Step 3: Coverage**

Invoke the `scoverage-inspector` skill and follow its policy for the `tuner` module: confirm `MpeChannelAllocator` keeps the module above its statement floor and that the changed code meets the 80% target. If a real gap exists (e.g. an unhit `freeChannel` branch), add a focused allocator test for it and re-run.

- [ ] **Step 4: Documentation sanity check**

Confirm the implemented `bestCandidate` ordering and `freeChannel` edge cases match paper Sections 4.5 and 5.1 wording. No architecture/paper edits are expected (PR #235 already updated the paper).

---

## Self-Review (performed while writing this plan)

- **Spec coverage:** unified tie-break (Task 1), `freeChannel` reuse + free-the-bass + single-pass (Task 2), `doAllocate` extraction (Task 3), latent Step-4 freeing bug (Task 2), mermaid-branch coverage (Task 4), enable the two ignored tuner tests / keep the rest ignored (Task 6), verification + coverage (Task 7). All spec sections map to a task.
- **Type/name consistency:** `freeChannel(time: Long)`, `occupiedChannelStates`, `lowestAndHighestNotes`, `dropExistingNotesForHighBend`, `bestCandidate(candidates, preferredChannel)` are used identically wherever referenced.
- **No placeholders:** every code and command step is concrete; the only conditional additions (Task 4 Step 1, Task 7 Step 3) are gated on a genuinely observed gap, with the action specified.

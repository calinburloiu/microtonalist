# Channel Choice Refactoring — Design

**Issue:** #154 (MPE Tuner polyphonic expression)
**Date:** 2026-06-17
**Scope:** `MpeChannelAllocator` and its unit tests only (allocator-only — see Scope below).

## Background

Commit `2f4e1de` (PR #235) revised the MPE Tuner paper
([`docs/architecture/tuner/mpe-tuner-paper.md`](../../docs/architecture/tuner/mpe-tuner-paper.md)) by:

- unifying the tie-breaking rules so that the *same* criteria (a)–(e) govern both placing a new note (Steps 1–3) and
  choosing a channel to free (Step 4 / Section 5.1);
- refining the special rules for picking a channel to free (boundary-channel exclusion and its edge cases);
- improving the description of the allocation steps (mermaid diagram in Section 4.5).

This work updates `MpeChannelAllocator` to conform to that revision and performs an audit, addressing the issues raised
in the task prompt plus correctness bugs found along the way.

The tie-breaking criteria from the paper (Section 4.5), applied in order until a single channel remains:

- **(a)** prefer channels without a high expressive pitch bend;
- **(b)** among those, prefer the channel with the lowest count of active notes;
- **(c)** among equal counts, prefer the oldest channel — earliest last note **onset**;
- **(d)** if still tied, prefer the oldest last **Note Off** (idle longest);
- **(e)** deterministic default keyed to input mode: in non-MPE mode the lowest channel number; in MPE mode the new
  note's input channel when it is itself unoccupied and therefore a candidate, otherwise the lowest channel number.

## Scope

Allocator-only. `MpeTuner` is **not** modified.

Consequence for the ignored `MpeTunerTest` cases: `MpeTuner.processMemberNoteOn` calls
`alloc.allocate(midiNote, preferredChannel = …)` **without** passing `expressivePitchBendCents`, so the allocator never
learns an incoming note's expressive bend. The high-bend "free a channel" truth-table tests (`MpeTunerTest` ~1687–1754)
therefore cannot go green without separately seeding expression into the allocator at Note On — that is TODO #154
future work and stays out of this change. Criterion (a) for *freeing* is instead exercised directly at the
`MpeChannelAllocatorTest` level (call `updateExpressivePitchBend` to set a high bend, then trigger Step 4).

## Audit findings

1. **Tie-break criterion order is wrong.** `bestCandidate`'s `minBy` tuple sorts by `lastNoteOffTime` *before*
   `lastOnsetTime`; the paper's order is (c) onset then (d) Note Off. They are swapped.
2. **`preferredChannel` short-circuits the tie-break.** It is returned at the top of `bestCandidate`, before criteria
   (a)–(d). The paper makes the input-channel preference criterion **(e)** — the *last* tiebreak. A candidate with an
   older last Note Off must win over the input channel at Steps 1–2.
3. **`freeChannel` duplicates the tie-break** (`minBy(_.lastOnsetTime)`) instead of reusing `bestCandidate`, computes
   highest/lowest in three passes, and its empty-candidate fallback picks "oldest onset" rather than the paper's
   "free the bass" rule.
4. **Latent correctness bug at Step 4.** `freeChannel` returns the target's notes as "dropped" but never removes them
   from the `ChannelState`. `doAllocate` only removes existing notes on a high bend, so when the freed channel's notes
   have no high bend they **linger** alongside the new note. Because the channel's `pitchClass` was fixed by its first
   note, the new (different-pitch-class) note is then **mistuned**. Masked today by `contain` (not
   `theSameElementsAs`) assertions in the allocator tests.
5. The current `allocate` Steps 1–4 already match the structure of the new mermaid diagram; the step *conditions* are
   correct and need no change.
6. (Resolved by the user) an uncommitted typo had broken the `else` branch of `freeChannel`.

## Changes

### 1. `bestCandidate` — single source of truth for criteria (a)–(e)

```scala
private def bestCandidate(candidates: Seq[ChannelState], preferredChannel: Option[Int]): ChannelState =
  candidates.minBy { s =>
    (hasHighExpressivePitchBend(s),                      // (a) no high bend (false < true)
     s.notes.size,                                       // (b) fewest active notes
     s.lastOnsetTime,                                    // (c) oldest onset
     s.lastNoteOffTime,                                  // (d) oldest last Note Off
     if (preferredChannel.contains(s.channel)) 0 else 1, // (e) prefer input channel
     s.channel)                                          // (e) then lowest channel number
  }
```

- Fixes finding #1: `(c)` onset now precedes `(d)` Note Off.
- Fixes finding #2: the preferred channel becomes the last tiebreak instead of an early return.
- Existing preferred-channel tests still pass: on a fresh zone all of (a)–(d) tie, so (e) selects the preferred
  channel; when the preferred channel is occupied it is not a candidate, so the tuple naturally ignores it.

### 2. `freeChannel` — boundary exclusion, then delegate to `bestCandidate`

`freeChannel` retains only the boundary logic and delegates the final pick to `bestCandidate(candidates, None)`
(criterion (e) degenerates to lowest channel number, exactly as the paper states for Step 4):

```
occupied = occupiedChannelStates                       // factored accessor
assert occupied.nonEmpty
if occupied.size == 1            -> free it             // "only one candidate": no exclusion
(lowest, highest) = lowestAndHighestNotes(occupied)    // single pass (finding #3)
candidates = occupied without channels holding the lowest or highest note
if candidates.nonEmpty           -> bestCandidate(candidates, None)
else  /* all occupied are boundary channels, extremes on different channels */
                                 -> bestCandidate(channels holding the lowest note, None)  // free the bass
```

Paper edge cases covered:

- **Only one candidate** — the sole channel is freed regardless of register (the `size == 1` guard, and naturally the
  general case when only one channel survives exclusion).
- **One channel holds both extremes** — exclusion removes only that channel, leaving the other as the sole remaining
  candidate (handled by the `candidates.nonEmpty` branch).
- **Two boundary channels (extremes on different channels)** — excluding both would leave nothing, so the exclusion is
  not applied and the Tuner frees the **bass** (lower-note) channel, retaining the upper melodic note (the `else`
  branch).

`freeChannel` also **removes** the selected channel's notes before returning (fixes finding #4), so Step 4 in
`allocate` places the new note on a now-empty channel. The unused `incomingNote` parameter is dropped.

### 3. `lowestAndHighestNotes` — single pass (prompt issue)

New private helper that walks all active notes once with two `var`s, comparing by `MidiNote.number`:

```scala
private def lowestAndHighestNotes(states: Seq[ChannelState]): (MidiNote, MidiNote) = {
  val notes = states.iterator.flatMap(_.notes)
  var lowest = notes.next()   // caller guarantees at least one note
  var highest = lowest
  for (note <- notes) {
    if (note.number < lowest.number) lowest = note
    if (note.number > highest.number) highest = note
  }
  (lowest, highest)
}
```

### 4. `doAllocate` — extract high-bend drop (prompt issue)

Factor the "drop existing notes because of a high expressive pitch bend" block into a focused method:

```scala
private def dropExistingNotesForHighBend(state: ChannelState,
                                         existingNotes: Set[MidiNote],
                                         newPitchBendCents: Double,
                                         time: Long): Option[DroppedNotes]
```

It implements paper Sections 5.2.2 (new high-bend note on an occupied channel drops the existing notes) and 5.2.3 (new
note on a channel that already holds a high-bend note frees the channel). With finding #4 fixed, at Step 4 the channel
arrives empty, so this method sees no existing notes and does nothing there — its concern is purely the
share/high-bend path of Steps 1–3.

## Tests

TDD throughout: red (failing test for the right reason, not a compile error) → green → refactor.

### `MpeChannelAllocatorTest`

Cover every mermaid branch and each tie-break criterion *in the corrected order*:

- Mermaid branches: Q1a No→Q2, Q1a Yes/Q1b Yes→Q2, Q1a Yes/Q1b No→Step 1, Q2 Yes→Step 2, Q2 No/Q3 Yes→Step 3,
  Q2 No/Q3 No→Step 4. (Most exist; add the missing ones.)
- Criterion order: (c) oldest onset before (d) oldest Note Off; (d) oldest Note Off beats the preferred input channel
  (locks in finding #2).
- Freeing: "free the bass" when the two candidates are both boundary channels; single channel holding both extremes
  frees the other; single-occupied-channel frees regardless of register; criterion (a) — free the channel without a
  high expressive pitch bend (set via `updateExpressivePitchBend`).
- Finding #4: after a Step-4 free, the freed channel holds **only** the new note (strengthen existing `contain`
  assertions to `theSameElementsAs`, and assert the new note's channel `pitchClass`).

Review existing test names that no longer isolate the criterion they claim under the corrected order (e.g. the "oldest
last Note Off" case that now resolves earlier on onset) and rename/restructure them.

### `MpeTunerTest`

Enable (un-`ignore`) the two "preserve the highest and drop the lowest note … when there are only 2 candidate
channels" tests (lines ~1604 non-MPE and ~1660 MPE); verify each goes red→green. Leave the high-bend freeChannel
truth-table and runtime-divergence tests ignored (TODO #154).

## Verification

- `tuner` module tests green.
- Full suite green.
- Coverage via the `scoverage-inspector` skill: modified files meet the module floor and the 80% target for changed
  code.

## Out of scope

- Any `MpeTuner` change (expression seeding at Note On, `updateExpressivePitchBend` "last note bent" assumption) —
  TODO #154 future work.
- Architecture/paper docs (already updated by PR #235).

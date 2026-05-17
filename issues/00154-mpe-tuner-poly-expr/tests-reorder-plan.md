# MpeTunerTest within-section cleanup plan

## Context

The previous reorganization (PR #200) split `MpeTunerTest.scala` into 15
`behavior of` sections grouped by operation (reset / tune / process / MCM /
PBS) and input mode (MPE / Non-MPE). Tests inside each section, however, are
still in their old order — many were dropped in from the dissolved sections
in the relative order they appeared in the source file, not in an order that
makes the section easy to scan. A few near-duplicates also survived the move.

This pass cleans up each section in-place. Goals:

1. Reorder tests so similar tests sit together, under named subgroups
   (`####`-style comments) that capture the local theme.
2. Remove tests that add no coverage beyond a stronger sibling already in
   the same section. Apply "when in doubt, KEEP".
3. Update `issues/00154-mpe-tuner-poly-expr/tests-reorg.csv` so the
   Non-MPE/MPE pair table matches the new in-section order (and drops
   removed tests).

One section (`reset() - MPE Input`) has a single test and is left as-is.
The reorganization is otherwise purely test-side: no production changes, no
fixture changes.

Three side findings the subagents surfaced are folded into this plan:

- **TODO #154 above the CP-seeding test** asks "Do we have a similar test
  for PB?". Verified: there is no test asserting that an MPE input
  member-channel Pitch Bend value sent before a Note On gets seeded into
  the allocated Member Channel's pitch bend at the time of Note On.
  Confirmed coverage gap. The TODO is rewritten in step 4 below to
  describe the gap concretely instead of asking the open question.
- **"as Zone-level Pitch Bend" typo** in the two Non-MPE Expression test
  names asserting CP / Slide is fixed as part of the renames (see
  "Renames to apply" → `process() - Expression - Non-MPE Input`).

## New within-section test order

Each section below lists the post-cleanup order of test cases. Subgroup
headings are intent-only — they become inline `// ----`-style comment
markers (or just blank-line separators) in the file, not new
`behavior of` blocks.

### `MpeTuner - reset() - Non-MPE Input`

#### State teardown
1. clear internal state after reset

#### Note Off emission
2. emit Note Off for every active Member Channel note before resetting state
3. not emit Note Off messages on reset when no notes are active

### `MpeTuner - reset() - MPE Input`

1. emit Note Off for active Master Channel notes before resetting state
   *(single test — no reorg)*

### `MpeTuner - tune() - Non-MPE Input`

#### Output messages when idle
1. store tuning but output no messages when no active notes

#### Retune of active notes
2. retune notes of different pitch classes with correct offsets on each occupied member channel *(merged from "output updated Pitch Bend on each occupied member channel" + "correctly retune notes of different pitch classes on different channels")*

#### Released-channel behavior
3. not update released channel's pitch bend on tuning changes

#### Paper worked examples
4. reproduce paper section "Tuning change during performance"

### `MpeTuner - tune() - MPE Input`

#### Member-channel retune
1. output updated Pitch Bend on each occupied member channel

#### Expressive PB interaction
2. recompute pitch bend = new tuning offset + current expressive pitch bend on each occupied channel

#### Master-channel notes immunity
3. not retune Master Channel notes on tune() call

#### Released-channel behavior
4. not update released channel's pitch bend on tuning changes

### `MpeTuner - process() - Basic - Non-MPE Input`

#### Note On output stream
1. output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On
2. preserve Note On velocity

#### Note Off behavior
3. output Note Off on the correct member channel
4. preserve Note Off velocity
5. treat Note On with velocity 0 as Note Off

#### Member-channel control-dimension initialization
6. initialize control dimensions before Note On even when input omits them
7. initialize member channel CC #74 to default 64 even after sending CC #74
8. initialize member channel Channel Pressure to default 0 even after sending CP

#### Channel allocation across pitch classes
9. allocate multiple notes with distinct pitch classes to separate member channels
10. correctly allocate notes from any input channel
11. allocate second note with same pitch class to Expression Group with independent pitch bends

#### Channel reuse after Note Off
12. make channel available for reuse after Note Off

#### Pitch bend computation
13. compute output pitch bend = tuning offset for single note on channel
14. clamp pitch bend to valid range when tuning offset exceeds PBS

### `MpeTuner - process() - Basic - MPE Input`

> **Removal**: drop `process MPE input notes with tuning offsets applied` —
> it asserts `extractPitchBends(noteOn(1, E4)).head.cents shouldEqual -14.0`,
> a strict subset of `compute output pitch bend = tuning offset for single
> note on channel`, which checks the same offset for four pitch classes
> (C/E/D/G). This is the example consolidation the user gave us upfront.

#### Pitch bend computation
1. compute output pitch bend = tuning offset for single note on channel
2. clamp pitch bend to valid range when tuning offset exceeds PBS

#### Channel allocation & splitting
3. split notes with different pitch classes from the same MPE input channel onto different output channels
4. leave Pitch Class Group channel unaffected when bending Expression Group channel of same pitch class

#### Master-Channel note forwarding (Lower / Upper zone)
5. forward Note On/Off on Lower Master Channel without emitting member-channel setup messages *(merged from "forward Note On received on Lower Master Channel …" + "forward Note Off received on Lower Master Channel …" + "not emit Pitch Bend, CC #74, or Channel Pressure setup for Master Channel Note On")*
6. forward Note On/Off received on Upper Master Channel on the same Master Channel
7. allow multiple active notes on the Master Channel concurrently

#### Master/Member separation
8. not consume Member Channel slots for Master Channel notes
9. route member channel notes to their own zone in dual-zone

#### Member-channel control-dimension seeding
10. seed Member Channel CC #74 from the per-input-channel value at Note On
11. seed Member Channel Channel Pressure from the per-input-channel value at Note On

#### Channel reuse after Note Off
12. make channel available for reuse after Note Off

#### Note Off velocity
13. preserve Note Off velocity

#### Paper worked examples
14. reproduce paper section "Basic allocation in quarter-comma meantone"

### `MpeTuner - process() - Expression - Non-MPE Input`

> Current source order already matches this proposal — only subgroup
> separators need to be added. The two CP / Slide names get the
> long-pending typo fix at the same time (see "Renames to apply").

#### Zone-level redirection from Pitch Bend
1. redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend
2. not bleed master channel pitch bend into member channel tuning on retune

#### Zone-level redirection from Channel Pressure / Slide
3. redirect input Channel Pressure to Master Channel as Zone-level Pitch Bend *(renamed → Channel Pressure)*
4. redirect input Slide CC #74 to Master Channel as Zone-level Pitch Bend *(renamed → Slide CC #74)*

#### PolyPressure → Channel Pressure conversion
5. convert Polyphonic Key Pressure to Channel Pressure on member channel
6. ignore Polyphonic Key Pressure for non-active notes

### `MpeTuner - process() - Expression - MPE Input`

#### Per-note PB (combined with tuning offset)
1. treat per-note pitch bend as expressive pitch bend combined with tuning offset

#### Fan-out across split notes (PB / CC #74 / CP)
2. fan out expressive Pitch Bend to all output channels for split notes from same MPE input channel
3. fan out CC #74 to all output channels for split notes from same MPE input channel
4. fan out Channel Pressure to all output channels for split notes from same MPE input channel

#### Forward to allocated Member Channel when an active note exists
5. forward CC #74 to the allocated Member Channel when an active note exists on MPE input channel
6. forward Channel Pressure to allocated Member Channel when active note exists on MPE input channel

#### Gating: no active note on input channel
7. not forward Pitch Bend on an MPE input member channel with no active note
8. not forward CC #74 on an MPE input channel with no active note
9. not forward Channel Pressure on an MPE input channel with no active note

#### Expression after Note Off
10. not forward expressive controls from an input channel after its notes have been released

#### Master-channel PB forwarding
11. forward Master Channel pitch bend without modification

#### Master/Member-channel PolyPressure handling
12. forward Polyphonic Key Pressure as-is for Master Channel notes
13. drop Polyphonic Key Pressure received on a Member Channel

#### Averaging across active notes on member channel *(ignored)*
14. average the expression pitch bend value of all active notes on a member channel *(ignored)*
15. average the channel pressure value of all active notes on a member channel *(ignored)*
16. average the MPE slide (CC #74) value of all active notes on a member channel *(ignored)*

#### Distributing across input channel *(ignored)*
17. distribute the pitch bend values of the input channel *(ignored)*
18. distribute the channel pressure values of the input channel *(ignored)*
19. distribute the slide values of the input channel *(ignored)*

### `MpeTuner - process() - Note Dropping - Non-MPE Input`

#### Single-channel edge case
1. free a single channel during exhaustion dropping when there is a single member channel

#### Channel exhaustion dropping (with Note Off output)
2. trigger note dropping with Note Off output for dropped notes on channel exhaustion

#### Drop policy: preserve highest / lowest
3. preserve the lowest note during channel exhaustion dropping
4. preserve the highest note during channel exhaustion dropping
5. preserve the highest and drop the lowest note during channel exhaustion dropping when there are only 2 candidate channels *(ignored)*

#### Paper worked example
6. reproduce paper section "Note dropping under channel exhaustion"

### `MpeTuner - process() - Note Dropping - MPE Input`

#### Channel exhaustion dropping (mirrors Non-MPE)
1. trigger note dropping with Note Off output for dropped notes on channel exhaustion
2. preserve the lowest note during channel exhaustion dropping
3. preserve the highest note during channel exhaustion dropping
4. preserve the highest and drop the lowest note during channel exhaustion dropping when there are only 2 candidate channels *(ignored)*

#### Single-channel edge case
5. free a single channel during exhaustion dropping when there is a single member channel

#### High-expressive-PB dropping — incoming-note triggered *(future-work truth table)*
6. drop a channel with high expressive PB to make room for an incoming note with low expression PB *(ignored)*
7. drop a channel with high expressive PB to make room for an incoming note with high expression PB *(ignored)*
8. drop a channel with low expressive PB to make room for an incoming note with high expression PB *(ignored)*
9. prefer to drop a channel with low expressive PB to make room for an incoming note with high expression PB *(ignored)*

#### High-expressive-PB dropping — runtime developed
10. drop other notes on a shared channel when one note develops a high expressive pitch bend *(ignored)*
11. not drop other notes on a shared channel when one note develops a low expressive pitch bend

#### Shared-channel dropping with common input channel
12. drop all notes on a shared channel with a common input channel when a high expressive pitch bend is received on it *(ignored)*

### `MpeTuner - process() - Zone-level Messages - Non-MPE Input`

#### Zone-level CCs forwarded to Master Channel
1. forward zone-level CCs on Master Channel
2. forward Sustain Pedal (CC #64) on Master Channel

#### Other zone-level messages forwarded to Master Channel
3. forward Program Change on Master Channel

### `MpeTuner - process() - Zone-level Messages - MPE Input`

#### Forwarding to zone Master Channel (single-zone)
1. forward Sustain Pedal (CC #64) received on member channel to zone Master Channel
2. forward zone-level CCs received on member channel to zone Master Channel
3. forward Program Change received on member channel to zone Master Channel

#### Routing to upper zone Master Channel (dual-zone)
4. route zone-level CC to upper zone Master Channel when received on upper member channel
5. route Program Change to upper zone Master Channel when received on upper member channel

### `MpeTuner - MCM Processing - Non-MPE Input`

#### MCM emission on reset
1. output MPE Configuration Message (MCM) for the configured zone

#### MCM-driven zone reconfiguration
2. reconfigure lower zone on MCM received on channel 0
3. reconfigure upper zone on MCM received on channel 15
4. disable zone when MCM with memberCount=0 is received
5. shrink other zone when MCM causes overlap

#### Effects on active notes / other state
6. stop all active notes when MCM is received
7. reset PBS to defaults when MCM is received
8. not output PBS messages after MCM

#### RPN sequence validation gating
9. not trigger MCM on incomplete RPN sequence
10. not trigger MCM for non-MCM RPN (e.g. PBS RPN)

#### Channel-of-receipt gating
11. ignore MCM on non-master channel

#### Mode switching
12. switch input mode to MPE automatically when an MCM is received

#### Revert on reset
13. revert to initialZones on reset() after MCM

### `MpeTuner - PBS Processing - Non-MPE Input`

#### RPN 0 emission on reset
1. output RPN 0 on master channel with configured master pitch bend sensitivity
2. output RPN 0 (Pitch Bend Sensitivity) on all member channels

#### Master-channel PBS update
3. update master PBS on master channel

#### Member-channel PBS update & forwarding
4. update member PBS and forward only on the received channel
5. forward PBS on each channel when received on all member channels

#### LSB (cents) handling
6. handle PBS LSB (cents) update

#### Pitch-bend recomputation after PBS change
7. emit a single recomputed pitch bend on each occupied channel after member PBS change, preserving intonation of an active note without expressive pitch bend *(merged from "recompute pitch bends on occupied channels after member PBS change" + "preserve intonation of active note without expressive pitch bend after PBS change")*

#### Zone isolation of PBS
8. not affect other zone's PBS

#### Revert on reset
9. revert PBS to initial values on reset()

### `MpeTuner - PBS Processing - MPE Input`

#### Master-channel PBS update
1. update master PBS on master channel

#### Member-channel PBS update & forwarding
2. update member PBS and forward only on the received channel

#### Pitch-bend recomputation after PBS change
3. recompute pitch bends on occupied channels after member PBS change
4. preserve intonation of active note with expressive pitch bend after PBS change

#### Revert on reset
5. revert PBS to initial values on reset()

## CSV update (`issues/00154-mpe-tuner-poly-expr/tests-reorg.csv`)

Re-emit the table so each category's row order matches the new in-section
order above, and drop the removed test. Concretely:

- In `== process() - Basic ==`, delete the row whose right cell is
  `process MPE input notes with tuning offsets applied` (currently leaves
  an empty Non-MPE cell). After deletion the Basic block reorders so
  pitch-bend computation rows come first, then channel allocation/split,
  then Master-Channel forwarding etc., matching the new file order.
- Reorder every other category's rows to match the new in-section order.
  Pair Non-MPE/MPE rows when both modes have a test with the same intent
  (same as the existing pairing rule). Insert empty cells for orphans on
  the opposite side.
- No new rows are added; coverage gaps stay visible.

A new CSV file in full is produced as part of the implementation step.

## Consolidations

In addition to the example removal in `process() - Basic - MPE Input`,
fold these four near-duplicate clusters into single tests. Each merged
test must retain *every* assertion currently spread across the inputs —
this is a textual merge, not coverage reduction.

### `tune() - Non-MPE Input`: merge tests #2 and #3
- *In*:
  - `output updated Pitch Bend on each occupied member channel` —
    asserts that after a retune from quarter-comma meantone to
    Pythagorean, each occupied member channel emits a pitch bend (set
    membership over channels).
  - `correctly retune notes of different pitch classes on different
    channels` — same scenario; asserts the cents value per pitch class
    on each emitted pitch bend.
- *Out (one merged test)*:
  - `retune notes of different pitch classes with correct offsets on
    each occupied member channel` — asserts both: every occupied
    member channel receives a pitch bend AND the cents value matches
    the new tuning offset for that pitch class.

### `process() - Basic - MPE Input`: merge tests #5, #6, and #9
- *In*:
  - `forward Note On received on Lower Master Channel on the same
    Master Channel`.
  - `forward Note Off received on Lower Master Channel on the same
    Master Channel`.
  - `not emit Pitch Bend, CC #74, or Channel Pressure setup for Master
    Channel Note On` (currently using a separate fixture, but the
    Note On stimulus is the same).
- *Out (one merged test)*:
  - `forward Note On/Off on Lower Master Channel without emitting
    member-channel setup messages` — asserts (a) the Note On is
    forwarded on the Master Channel with the original velocity, (b) no
    PB / CC #74 / Channel Pressure setup messages are emitted on any
    member channel as a side-effect of that Note On, and (c) the
    subsequent Note Off is forwarded on the Master Channel.
  - The existing Upper-zone test (`forward Note On/Off received on
    Upper Master Channel on the same Master Channel`) stays untouched —
    no symmetric "no-setup" assertion is added in this pass; flag for a
    future symmetry follow-up.

### `PBS Processing - Non-MPE Input`: merge tests #7 and #8
- *In*:
  - `recompute pitch bends on occupied channels after member PBS change`
    — asserts a single PB is re-emitted on the occupied channel with
    the correct count/channel-targeting under the new sensitivity.
  - `preserve intonation of active note without expressive pitch bend
    after PBS change` — same setup; asserts the cents value of the
    re-emitted PB still resolves to the original tuning offset under
    the new sensitivity.
- *Out (one merged test)*:
  - `emit a single recomputed pitch bend on each occupied channel
    after member PBS change, preserving intonation of an active note
    without expressive pitch bend` — asserts both invariants.

After these merges plus the one example removal, the test count moves
from **88 live + 14 ignored = 102** to **83 live + 14 ignored = 97**
(−1 removal; tune-NonMPE merge −1; Basic-MPE merge −2; PBS-NonMPE
merge −1).

## Renames to apply

Only three renames — typo fixes and one misleading name. Every other test
name reads cleanly after `it should "…"` and stays as-is. Test orderings
in the section above use the original names — the rename is applied at
the same time the block is moved.

### `process() - Expression - Non-MPE Input` *(typo fixes)*
- `redirect input Channel Pressure to Master Channel as Zone-level Pitch Bend`
  → `redirect input Channel Pressure to Master Channel as Zone-level Channel Pressure`
- `redirect input Slide CC #74 to Master Channel as Zone-level Pitch Bend`
  → `redirect input Slide CC #74 to Master Channel as Zone-level Slide CC #74`

### `process() - Basic - MPE Input` *(misleading name)*
- `compute output pitch bend = tuning offset for single note on channel`
  → `compute output Pitch Bend equal to tuning offset for each pitch class`
  *("single note" is wrong — the test asserts the offset for four pitch
  classes C/E/D/G.)*

## Implementation steps

1. **Edit `tuner/src/test/scala/.../MpeTunerTest.scala`**: for each section
   above, move test blocks (whole `it should "…" in { … }` /
   `ignore should "…"` blocks, verbatim) into the listed order. Insert a
   single blank line and a `// ---- <subgroup name> ----` comment above
   each subgroup.
2. **Apply the renames in the "Renames to apply" section** to the moved
   blocks (edit only the string between `should "` and `" in`; no logic
   changes).
3. **Apply the merges in the "Consolidations" section.** Each merge
   produces one `it should "<merged name>" in new Fixture(...) { … }`
   block whose body unions all assertions from the inputs (preserve
   `// Given / When / Then` comments by interleaving them; reuse the
   single fixture that satisfies the whole merged scenario). Delete the
   now-empty input blocks.
4. **Delete `process MPE input notes with tuning offsets applied`** from
   `process() - Basic - MPE Input`.
5. **Update the existing TODO #154 comment** above the
   `seed Member Channel Channel Pressure …` test (currently
   `// TODO #154 Do we have a similar test for PB?`) to describe the
   confirmed coverage gap, e.g.:
   `// TODO #154 Add a "seed Member Channel Pitch Bend from the per-input-channel value at Note On" test — gap confirmed.`
6. **Regenerate `issues/00154-mpe-tuner-poly-expr/tests-reorg.csv`** so
   the row order in each category matches the new in-section order, the
   removed test row is gone, the merged-test rows replace their inputs
   (collapsed onto one row per merge), and renamed tests use their
   post-rename text. Re-pair Non-MPE/MPE rows where renames preserve a
   near-match (e.g. the four parallel renames between Note Dropping
   Non-MPE / MPE).
7. **Verification**:
   - `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest"`
     — Before: 88 live + 14 ignored = 102. After: 83 live + 14 ignored
     = 97. (One removal + three merges with net deltas −1, −1, −2, −1.)
   - `grep -nE '^\s*(behavior of|it should|ignore should)'` on the file —
     same 15 `behavior of` headings, and within each the test names in
     the new order with the renames and merges applied.
   - Open the CSV and confirm the removed row is gone, the three merged
     rows replace their inputs, ordering matches, and renamed cells use
     the new text.

## Critical files

- `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
- `issues/00154-mpe-tuner-poly-expr/tests-reorg.csv`
- `issues/00154-mpe-tuner-poly-expr/tests-reorg-plan.md` (no edits; only
  read for context)

## Side findings status

- *Bug in `not drop other notes on a shared channel when one note develops
  a low expressive pitch bend`* — already fixed by the user out-of-band
  before this plan executes. No action here.
- *PB-seeding coverage gap on MPE input member channels* — confirmed real
  by grep; the existing TODO #154 comment is rewritten in step 5 to
  capture the gap concretely (the test itself stays out of scope).
- *"as Zone-level Pitch Bend" typo on the CP and Slide redirection
  tests* — folded into the renames (see "Renames to apply" → Expression
  Non-MPE).

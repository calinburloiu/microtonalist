# MpeTunerTest reorganization plan

## Context

`tuner/src/test/scala/.../MpeTunerTest.scala` has grown to 2127 lines across 21
`behavior of` sections that mix several axes: the operation under test
(`reset()`, `tune()`, `process()`), the kind of MIDI behavior (basic note
allocation, expression, dropping, zone-level messages, MCM, PBS), and the input
mode (MPE vs Non-MPE). The current layout has duplicated category names, some
sections (e.g. "MPE Input Master Channel Notes", "Note Off Behavior", "Worked
Examples") that span multiple concerns, and 9 `ignore`d future-work tests
floating in three early sections that don't fit the rest of the file.

This reorganization splits the suite into 8 well-defined categories, each
sub-divided by input mode (MPE / Non-MPE), so:

1. The structure matches the production semantics under test.
2. The side-by-side CSV (`tests-reorg.csv`) makes it obvious which MPE/Non-MPE
   pairs exist and which are missing — coverage gaps to drive future tests.
3. Future contributors know where a new test belongs.

User decisions captured up front:

- Worked Examples → distributed into the 8 categories.
- Drop redundant ` in MPE input mode` / ` in non-MPE mode` suffixes from test
  names — the new `behavior of` heading carries the mode.
- `ignore`d tests are listed as if they were live tests (their ignored state is
  temporary).

## New `behavior of` headings

Each category gets two headings, one per input mode (omitted if no tests exist
on that side — currently only "MCM Processing — MPE Input" is empty):

```
MpeTuner - reset() - Non-MPE Input
MpeTuner - reset() - MPE Input
MpeTuner - tune() - Non-MPE Input
MpeTuner - tune() - MPE Input
MpeTuner - process() - Basic - Non-MPE Input
MpeTuner - process() - Basic - MPE Input
MpeTuner - process() - Expression - Non-MPE Input
MpeTuner - process() - Expression - MPE Input
MpeTuner - process() - Note Dropping - Non-MPE Input
MpeTuner - process() - Note Dropping - MPE Input
MpeTuner - process() - Zone-level Messages - Non-MPE Input
MpeTuner - process() - Zone-level Messages - MPE Input
MpeTuner - MCM Processing - Non-MPE Input
MpeTuner - PBS Processing - Non-MPE Input
MpeTuner - PBS Processing - MPE Input
```

## Test-by-test mapping

Source line numbers refer to the pre-reorganization file. "→" indicates the
new `behavior of` heading. Renames shown in *italics*; if no rename is needed,
the title is in plain text.

### reset()

Tie-breaker rule: when a test overlaps two categories and one is `MCM Processing`
or `PBS Processing`, the MCM/PBS category wins. The three reset-output tests
that emit MCM or PBS messages are routed accordingly (see MCM/PBS Processing
sections below).

Non-MPE Input:
- L509 `clear internal state after reset`
- L522 `emit Note Off for every active Member Channel note before resetting state`
- L550 `not emit Note Off messages on reset when no notes are active`

MPE Input:
- L537 `emit Note Off for active Master Channel notes before resetting state`

### tune()

Non-MPE Input:
- L559 `store tuning but output no messages when no active notes`
- L567 `output updated Pitch Bend on each occupied member channel`
- L581 `correctly retune notes of different pitch classes on different channels`
- L1559 `not update released channel's pitch bend on tuning changes` (moved from "Note Off Behavior")
- L1700 `reproduce paper section "Tuning change during performance"` (moved from "Worked Examples")

MPE Input:
- L595 *`output updated Pitch Bend on each occupied member channel`* (suffix dropped)
- L612 `recompute pitch bend = new tuning offset + current expressive pitch bend on each occupied channel`
- L1431 `not retune Master Channel notes on tune() call` (moved from "MPE Input Master Channel Notes")
- L1601 *`not update released channel's pitch bend on tuning changes`* (suffix dropped; moved from "Note Off Behavior")

### process() - Basic

Non-MPE Input:
- L641 `output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On`
- L658 `output Note Off on the correct member channel`
- L669 `allocate multiple notes with distinct pitch classes to separate member channels`
- L679 `preserve Note On velocity`
- L686 `preserve Note Off velocity`
- L706 `treat Note On with velocity 0 as Note Off`
- L716 `correctly allocate notes from any input channel`
- L821 *`initialize member channel CC #74 to default 64 even after sending CC #74`* (suffix dropped; moved from "Non-MPE to MPE Conversion")
- L835 *`initialize member channel Channel Pressure to default 0 even after sending CP`* (suffix dropped; moved from "Non-MPE to MPE Conversion")
- L846 `initialize control dimensions before Note On even when input omits them` (moved from "Non-MPE to MPE Conversion")
- L955 `compute output pitch bend = tuning offset for single note on channel` (moved from "Pitch Bend Computation")
- L974 `clamp pitch bend to valid range when tuning offset exceeds PBS` (moved from "Pitch Bend Computation")
- L1068 `allocate second note with same pitch class to Expression Group with independent pitch bends` (moved from "Dual-Group Allocation")
- L1583 `make channel available for reuse after Note Off` (moved from "Note Off Behavior")

MPE Input:
- L696 *`preserve Note Off velocity`* (suffix dropped; moved from "Non-MPE Basic")
- L1000 *`compute output pitch bend = tuning offset for single note on channel`* (suffix dropped; moved from "Pitch Bend Computation")
- L1019 *`clamp pitch bend to valid range when tuning offset exceeds PBS`* (suffix dropped; moved from "Pitch Bend Computation")
- L1044 `leave Pitch Class Group channel unaffected when bending Expression Group channel of same pitch class` (moved from "Dual-Group Allocation")
- L1247 `process MPE input notes with tuning offsets applied` (moved from "MPE Input")
- L1279 `split notes with different pitch classes from the same MPE input channel onto different output channels` (moved from "MPE Input")
- L1356 `forward Note On received on Lower Master Channel on the same Master Channel` (from "MPE Input Master Channel Notes")
- L1366 `not emit Pitch Bend, CC #74, or Channel Pressure setup for Master Channel Note On`
- L1376 `forward Note Off received on Lower Master Channel on the same Master Channel`
- L1386 `forward Note On/Off received on Upper Master Channel on the same Master Channel`
- L1401 *`route member channel notes to their own zone in dual-zone`* (suffix dropped)
- L1417 `not consume Member Channel slots for Master Channel notes`
- L1441 `allow multiple active notes on the Master Channel concurrently`
- L1532 *`seed Member Channel CC #74 from the per-input-channel value at Note On`* (suffix dropped)
- L1546 *`seed Member Channel Channel Pressure from the per-input-channel value at Note On`* (suffix dropped)
- L1625 *`make channel available for reuse after Note Off`* (suffix dropped; moved from "Note Off Behavior")
- L1666 `reproduce paper section "Basic allocation in quarter-comma meantone"` (moved from "Worked Examples")

### process() - Expression

Non-MPE Input:
- L727 `redirect input Pitch Bend to Master Channel as Zone-level Pitch Bend` (moved from "Non-MPE Pitch Bend")
- L742 `not bleed master channel pitch bend into member channel tuning on retune` (moved from "Non-MPE Pitch Bend")
- L765 `redirect input Channel Pressure to Master Channel as Zone-level Pitch Bend` (moved from "Non-MPE Channel Pressure") — *current name says "as Zone-level Pitch Bend" but it asserts Channel Pressure; the suspected typo is preserved as-is, flagged for separate fix.*
- L782 `redirect input Slide CC #74 to Master Channel as Zone-level Pitch Bend` (moved from "Non-MPE Slide CC #74") — *same typo preserved as-is.*
- L799 `convert Polyphonic Key Pressure to Channel Pressure on member channel` (moved from "Non-MPE to MPE Conversion")
- L810 `ignore Polyphonic Key Pressure for non-active notes` (moved from "Non-MPE to MPE Conversion")

MPE Input:
- L1255 `treat per-note pitch bend as expressive pitch bend combined with tuning offset` (from "MPE Input")
- L1272 `forward Master Channel pitch bend without modification`
- L1298 `fan out expressive Pitch Bend to all output channels for split notes from same MPE input channel`
- L1320 `fan out CC #74 to all output channels for split notes from same MPE input channel`
- L1337 `fan out Channel Pressure to all output channels for split notes from same MPE input channel`
- L1461 `forward Polyphonic Key Pressure as-is for Master Channel notes` (from "MPE Input Master Channel Notes")
- L1472 *`drop Polyphonic Key Pressure received on a Member Channel`* (suffix dropped)
- L1483 `not forward CC #74 on an MPE input channel with no active note`
- L1492 `not forward Channel Pressure on an MPE input channel with no active note`
- L1501 `not forward Pitch Bend on an MPE input member channel with no active note`
- L1510 `forward CC #74 to the allocated Member Channel when an active note exists on MPE input channel`
- L1521 `forward Channel Pressure to allocated Member Channel when active note exists on MPE input channel`
- L1643 `not forward expressive controls from an input channel after its notes have been released` (from "Note Off Behavior")
- L322 `average the expression pitch bend value of all active notes on a member channel` *(ignored; from "Averaging expression parameters")*
- L346 `average the channel pressure value of all active notes on a member channel` *(ignored)*
- L371 `average the MPE slide (CC #74) value of all active notes on a member channel` *(ignored)*
- L415 `distribute the pitch bend values of the input channel` *(ignored; from "distributing expression parameters")*
- L431 `distribute the channel pressure values of the input channel` *(ignored)*
- L448 `distribute the slide values of the input channel` *(ignored)*

### process() - Note Dropping

Non-MPE Input:
- L1093 `trigger note dropping with Note Off output for dropped notes on channel exhaustion`
- L1108 `preserve the lowest note during channel exhaustion dropping`
- L1120 `preserve the highest note during channel exhaustion dropping`
- L1133 `preserve the highest and drop the lowest note during channel exhaustion dropping when there are only 2 candidate channels` *(ignored)*
- L1145 `free a single channel during exhaustion dropping when there is a single member channel`
- L1726 `reproduce paper section "Note dropping under channel exhaustion"` (moved from "Worked Examples")

MPE Input:
- L1156 *`trigger note dropping with Note Off output for dropped notes on channel exhaustion`* (suffix dropped)
- L1171 *`preserve the lowest note during channel exhaustion dropping`* (suffix dropped)
- L1184 *`preserve the highest note during channel exhaustion dropping`* (suffix dropped)
- L1198 *`preserve the highest and drop the lowest note during channel exhaustion dropping when there are only 2 candidate channels`* (suffix dropped) *(ignored)*
- L1210 *`free a single channel during exhaustion dropping when there is a single member channel`* (suffix dropped)
- L209 `drop a channel with high expressive PB to make room for an incoming note with low expression PB` *(ignored; moved from "Dropping on high expressive pitch bend")*
- L226 `drop a channel with high expressive PB to make room for an incoming note with high expression PB` *(ignored)*
- L243 `drop a channel with low expressive PB to make room for an incoming note with high expression PB` *(ignored)*
- L260 `prefer to drop a channel with low expressive PB to make room for an incoming note with high expression PB` *(ignored)*
- L278 `drop other notes on a shared channel when one note develops a high expressive pitch bend` *(ignored)*
- L300 `not drop other notes on a shared channel when one note develops a low expressive pitch bend`
- L1222 `drop all notes on a shared channel with a common input channel when a high expressive pitch bend is received on it` *(ignored)*

### process() - Zone-level Messages

Non-MPE Input:
- L862 `forward Sustain Pedal (CC #64) on Master Channel`
- L870 `forward Program Change on Master Channel`
- L878 `forward zone-level CCs on Master Channel`

MPE Input:
- L898 `forward Sustain Pedal (CC #64) received on member channel to zone Master Channel`
- L907 `forward Program Change received on member channel to zone Master Channel`
- L916 `forward zone-level CCs received on member channel to zone Master Channel`
- L935 `route zone-level CC to upper zone Master Channel when received on upper member channel`
- L944 `route Program Change to upper zone Master Channel when received on upper member channel`

### MCM Processing

Non-MPE Input (all current MCM tests start in Non-MPE input mode; MCM itself
switches the tuner into MPE during the test):
- L469 `output MPE Configuration Message (MCM) for the configured zone` (moved from `reset()` per tie-breaker rule)
- L1754 `reconfigure lower zone on MCM received on channel 0`
- L1769 `reconfigure upper zone on MCM received on channel 15`
- L1784 `stop all active notes when MCM is received`
- L1795 `shrink other zone when MCM causes overlap`
- L1811 `disable zone when MCM with memberCount=0 is received`
- L1822 `not trigger MCM on incomplete RPN sequence`
- L1836 `not trigger MCM for non-MCM RPN (e.g. PBS RPN)`
- L1847 `ignore MCM on non-master channel`
- L1854 `revert to initialZones on reset() after MCM`
- L1875 `not output PBS messages after MCM`
- L1886 `switch input mode to MPE automatically when an MCM is received`
- L1895 `reset PBS to defaults when MCM is received`

MPE Input: *no current tests*.

### PBS Processing

Non-MPE Input:
- L482 `output RPN 0 (Pitch Bend Sensitivity) on all member channels` (moved from `reset()` per tie-breaker rule)
- L497 `output RPN 0 on master channel with configured master pitch bend sensitivity` (moved from `reset()` per tie-breaker rule)
- L1922 `update master PBS on master channel`
- L1936 `update member PBS and forward only on the received channel`
- L1954 `forward PBS on each channel when received on all member channels`
- L1968 `recompute pitch bends on occupied channels after member PBS change`
- L1983 `not affect other zone's PBS`
- L1994 `handle PBS LSB (cents) update`
- L2037 `preserve intonation of active note without expressive pitch bend after PBS change`
- L2050 `revert PBS to initial values on reset()`

MPE Input:
- L2011 `preserve intonation of active note with expressive pitch bend after PBS change` (uses inline MPE-mode tuner)
- L2066 *`update master PBS on master channel`* (suffix dropped)
- L2079 *`update member PBS and forward only on the received channel`* (suffix dropped)
- L2098 *`recompute pitch bends on occupied channels after member PBS change`* (suffix dropped)
- L2112 *`revert PBS to initial values on reset()`* (suffix dropped)

## Sections being dissolved

These existing `behavior of` headings disappear; every test is moved to one of
the new categories above:

- `MpeTuner - Dropping on high expressive pitch bend` → process() - Note Dropping - MPE Input
- `MpeTuner - Averaging expression parameters` → process() - Expression - MPE Input
- `MpeTuner - distributing expression parameters` → process() - Expression - MPE Input
- `MpeTuner - process() Non-MPE Basic` → process() - Basic - Non-MPE Input (+ one to Basic - MPE Input)
- `MpeTuner - process() Non-MPE Pitch Bend` → process() - Expression - Non-MPE Input
- `MpeTuner - process() Non-MPE Channel Pressure` → process() - Expression - Non-MPE Input
- `MpeTuner - process() Non-MPE Slide CC #74` → process() - Expression - Non-MPE Input
- `MpeTuner - process() Non-MPE to MPE Conversion` → process() - Basic (init/seed tests) & process() - Expression (Poly Pressure tests)
- `MpeTuner - process() Zone-Level Messages` → process() - Zone-level Messages - Non-MPE Input
- `MpeTuner - process() Zone-Level Messages MPE Input` → process() - Zone-level Messages - MPE Input
- `MpeTuner - process() Pitch Bend Computation` → process() - Basic (both modes)
- `MpeTuner - process() Dual-Group Allocation` → process() - Basic (split across modes)
- `MpeTuner - process() Note Dropping` → process() - Note Dropping (split across modes)
- `MpeTuner - process() MPE Input` → process() - Basic - MPE Input + process() - Expression - MPE Input
- `MpeTuner - process() MPE Input Master Channel Notes` → process() - Basic - MPE Input + process() - Expression - MPE Input + tune() - MPE Input
- `MpeTuner - process() Note Off Behavior` → tune() (when assertion is on tune output) / process() - Basic (channel-reuse) / process() - Expression (forward-after-release)
- `MpeTuner - Worked Examples` → distributed (Basic - MPE Input, tune() - Non-MPE, Note Dropping - Non-MPE)
- `MpeTuner - MCM Processing` → MCM Processing - Non-MPE Input (kept)
- `MpeTuner - PBS Processing` → PBS Processing (split across modes)

## CSV layout

The side-by-side table lives in [`tests-reorg.csv`](tests-reorg.csv) with two
columns (`Non-MPE Input`, `MPE Input`). Each category starts with a separator
row whose left cell is `== <category> ==` and right cell is empty. Test names
that have a near-match across modes share a row; orphans get an empty cell on
the opposite side. Renamed test cases use the post-rename text. Ignored tests
appear with no extra marker.

## Coverage gaps surfaced by the table

The CSV makes the asymmetry visible at a glance:

- **MPE Input is much richer for `process() - Expression`** (per-note PB, fan-out
  of expression to all split-mapped output channels, gating expression by
  active-note presence) — Non-MPE has only zone-level redirection tests. Likely
  fine: this asymmetry is inherent to MPE.
- **Note Dropping**: every "high expression PB → drop" case (6 ignored tests) is
  MPE-only. Non-MPE has only channel-exhaustion dropping (5 tests, all live).
- **`reset()` (after re-routing per the MCM/PBS tie-breaker rule) is essentially
  Non-MPE only** — only one MPE-specific test (master-channel notes). Possible
  additions: "clear internal state" and "no-op when empty" in MPE mode.
- **Zone-level Messages** is symmetric for the three forwarding tests; only MPE
  has upper-zone routing variants.
- **MCM Processing - MPE Input** — gap closed in PR #202: 12 of 13 MCM tests
  moved to the MPE Input section; only the auto-mode-switch test remains under
  Non-MPE Input.
- Several MPE-only tests in `process() - Basic` could plausibly have Non-MPE
  counterparts (preserve Note On velocity in both modes, treat Note On with
  velocity 0 as Note Off in both modes, etc.) — listed as gaps on the MPE side
  with empty Non-MPE cells where parity would be cheap to add.

These gaps are intentionally surfaced rather than fixed in this change.

## Reorganization steps for MpeTunerTest.scala

1. Keep the file header (imports, class declaration line 31) and the entire
   private fixture/helper block (lines 33–202) verbatim.
2. Delete the existing `behavior of` headings and re-emit them in the order
   listed in "New behavior of headings".
3. For each new heading, append the test blocks in the order given in
   "Test-by-test mapping". Move the entire block (`it should "…" in
   new Fixture(…) { … }` or `ignore should "…"`); preserve all code,
   comments, and `ignore` modifiers verbatim.
4. Apply the renames noted with *italics* by editing just the string between
   `should "` and `" in` (no logic changes).
5. Delete the two `// TODO #154 Move new tests for their own category` /
   `////////////////////////////////` / `///////////////////////` marker
   comments at lines 204–206 and 465–466, since their reason for existing is
   resolved.
6. Re-run the suite to confirm nothing broke during the cut/paste:
   `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest"`.

The tests themselves are pure-Scala unit tests; no production code changes,
no fixture changes, no helper changes. Diff should be 100% reordering + a
handful of string edits on test names.

## Verification

After applying the reorganization:

1. `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest"`
   — every test must still pass / stay ignored. Counts should match
   (currently 88 live + 14 ignored = 102 tests; the moves don't add or remove
   any).
2. Spot-check the new file structure: `grep -nE '^\s*(behavior of|it should|ignore should)'`
   on the reorganized file should yield the headings in the order listed in
   "New behavior of headings" and the right count under each.
3. Open [`tests-reorg.csv`](tests-reorg.csv) in a spreadsheet viewer and
   confirm the gap pattern matches "Coverage gaps surfaced by the table".

## Critical files

- `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
  — only file modified.
- [`tests-reorg-plan.md`](tests-reorg-plan.md) — this file.
- [`tests-reorg.csv`](tests-reorg.csv) — side-by-side category table.

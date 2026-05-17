# Plan: PBS-to-Master-Channel forwarding in non-MPE input mode + MCM test mode split

**Issue dir:** `issues/00154-mpe-tuner-poly-expr/`
**Tracking issue:** #202 (label `bugfix`).
**Branch prefix:** `bugfix/` (current branch `bugfix/pbs-non-mpe-input` already matches).
**Commit strategy:** Two separate commits, two TDD red→green→refactor cycles.

---

## Context

`MpeTuner` is the MIDI Polyphonic Expression tuner specified in
`docs/tuner/mpe-tuner-paper.md`. In **non-MPE input mode** the tuner converts
conventional MIDI into MPE output by routing each input note to a Member
Channel and forwarding zone-wide controls (Pitch Bend, CC #74, Channel
Pressure) to the **Master Channel** of the chosen output Zone (see
`docs/tuner/mpe-tuner-paper.md` §3.3.2).

### Bug

Pitch Bend Sensitivity (PBS) — sent as the RPN sequence `CC#101=0, CC#100=0,
CC#6=semitones [, CC#38=cents]` — should follow the same routing as Pitch Bend
in non-MPE input mode, i.e. be forwarded to the Master Channel of the routing
Zone and update that Zone's **master** PBS. Currently:

- `MpeTuner.processCc` forwards the RPN-selector CCs (`CC#100`/`CC#101`) **as-is on the input
  channel**.
- `MpeTuner.processPbs` calls `findZoneForChannel(inputChannel)`
  which, in non-MPE mode, only matches when the input channel happens to
  coincide with the routing Zone's master or member ranges. When it doesn't
  match (e.g. PBS arrives on channel 0 of input but the routing zone is the
  Upper Zone, or on channel 5 of a Lower-zone-only setup), the message hits the
  `case None` branch and is forwarded **as-is on the original channel** — never
  reaching the Master Channel.
- Even when `findZoneForChannel` does match, the assumption that a non-MPE input
  carries member-channel semantics is wrong: in non-MPE mode there is no
  "incoming member channel". A pre-existing TODO at MpeTunerTest.scala already
  flagged this exact issue.

Result: PBS adjustments from a non-MPE controller never affect the output Pitch
Bend interpretation, breaking microtonal accuracy whenever a non-default PBS is
in play.

### Test-organization cleanup (separate concern)

The `behavior of "MpeTuner - MCM Processing - Non-MPE Input"` section
mixes 13 cases that mostly test the **outgoing side-effects** of MCM reception
(reconfiguration, note-stop, PBS reset, RPN gating) — these all happen the same
way regardless of input mode. Only one case — *"switch input mode to MPE
automatically when an MCM is received"* — is genuinely non-MPE-specific. The
repo's established convention (`tests-reorg-plan.md`) is to split categories
into `Non-MPE Input` and `MPE Input` headings whenever both modes exercise
distinct behaviour. This section is conformed: move 12 of 13 tests to a new
`MPE Input` section using the MPE-mode fixtures, leaving only the auto-switch
test under `Non-MPE Input`.

### Outcome

- Non-MPE PBS forwarding behaves consistently with non-MPE PB forwarding: both
  redirect to the Zone's master channel and update master-side state.
- `MpeTunerTest` test categorisation matches the rest of the file: MCM
  Processing has both a `Non-MPE Input` and an `MPE Input` `behavior of`
  section, with each test placed in the section whose mode it actually
  exercises.

---

## Commit 1 — fix: PBS forwarded to Master Channel in non-MPE input mode

### Files
- **Source:** `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- **Tests:** `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
  (section: `behavior of "MpeTuner - PBS Processing - Non-MPE Input"`)

### TDD cycle

1. **Red — rewrite/append failing tests** for the new contract under
   `MpeTuner - PBS Processing - Non-MPE Input`. Target behaviour:

   For any non-MPE input mode tuner, regardless of which channel `X` the PBS
   RPN sequence arrives on, the output sequence is the RPN setup + Data Entry
   on the **Master Channel** of the routing Zone (Lower if enabled, else
   Upper), and the routing Zone's **master** PBS is updated.

   - `"update master PBS and forward to lower zone master channel when PBS arrives on any input channel"`
     — parameterised across input channels {0, 5, 10, 15}.
   - `"update master PBS and forward to upper zone master channel when only upper zone is enabled"`
   - `"route PBS to lower zone master in dual-zone setup regardless of input channel"`
   - `"handle PBS LSB (cents) update by forwarding to master channel"`
   - `"not update member PBS when PBS is received in non-MPE mode"`
   - `"not affect the other zone's master PBS in dual-zone setup"`
   - **Removes** `"update member PBS and forward only on the received channel"` (the TODO test).
   - **Moves to `PBS Processing - MPE Input`**:
     - `"forward PBS on each channel when received on all member channels"` → adapted to `tuner7MpeInput`
     - `"emit a single recomputed pitch bend on each occupied channel after member PBS change, …"` → adapted to `tuner7MpeInput, Some(quarterCommaMeantone)` and `noteOn(2, E4)`
   - **Keep** with light edits (remove re-sent selectors expectation):
     - `"update master PBS on master channel"`
     - `"handle PBS LSB (cents) update"`
     - `"not affect other zone's PBS"` — repurposed to assert output goes to ch 0 and upper zone channels get nothing
   - **Keep unchanged:**
     - `"revert PBS to initial values on reset()"`

2. **Green — implement the fix** in `MpeTuner.scala`:

   - **Route RPN selectors to master in non-MPE mode.** In `processCc`,
     change the `case ScMidiCc.RpnLsb | ScMidiCc.RpnMsb` branch so that when
     `inputMode == MpeInputMode.NonMpe` the message is forwarded via
     `forwardOnZoneMasterChannel(buffer, msg)`; in MPE mode it stays
     `buffer += msg.asJava`.
   - **Add an explicit non-MPE branch to `processPbs`.** When `inputMode ==
     MpeInputMode.NonMpe`, resolve the routing zone (Lower if enabled, else
     Upper) and treat the input as an update to that zone's **master** PBS.
   - **Remove RPN-selector re-emission inside `applyPbsUpdate`.** With selectors
     now forwarded by `processCc`, the re-send is pure duplication. Drop the two
     `buffer +=` lines for CC#100 and CC#101; leave only the Data Entry emit.

3. **Refactor** — update ScalaDoc comments. No new abstractions.

### Commit message
```
[#202] Forward PBS to Master Channel in non-MPE input mode

Per docs/tuner/mpe-tuner-paper.md §3.3.2, non-MPE input mode forwards
zone-level controls to the routing Zone's Master Channel. Pitch Bend
Sensitivity was inconsistent: RPN selectors and Data Entry were forwarded
on the input channel, and the master/member PBS distinction was driven by
findZoneForChannel which is meaningless for non-MPE input.
```

---

## Commit 2 — test: split `MCM Processing` into Non-MPE / MPE Input sections

### File
- `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

### Plan

1. Keep `behavior of "MpeTuner - MCM Processing - Non-MPE Input"` but retain
   **only** one case under it:
   - `"switch input mode to MPE automatically when an MCM is received"`
2. Insert new `behavior of "MpeTuner - MCM Processing - MPE Input"` heading
   immediately after the Non-MPE section (before `PBS Processing - Non-MPE Input`).
3. Move the remaining 12 cases to the new `MPE Input` section, swapping fixtures:

   | Test | Original fixture | New fixture |
   |---|---|---|
   | "output MPE Configuration Message (MCM) for the configured zone" | `defaultTuner` | `mpeTunerMpeInput` |
   | "reconfigure lower zone on MCM received on channel 0" | `dualZoneTuner` | `dualZoneTunerMpeInput` |
   | "reconfigure upper zone on MCM received on channel 15" | `dualZoneTuner` | `dualZoneTunerMpeInput` |
   | "disable zone when MCM with memberCount=0 is received" | `dualZoneTuner` | `dualZoneTunerMpeInput` |
   | "shrink other zone when MCM causes overlap" | `dualZoneTuner` | `dualZoneTunerMpeInput` |
   | "stop all active notes when MCM is received" | `defaultTuner` | `mpeTunerMpeInput`; `noteOn(nonMpeInputChannel,…)` → `noteOn(2,…)` |
   | "reset PBS to defaults when MCM is received" | `tuner7` | `tuner7MpeInput`; restore original two-PBS-send setup |
   | "not output PBS messages after MCM" | `defaultTuner` | `mpeTunerMpeInput` |
   | "not trigger MCM on incomplete RPN sequence" | `defaultTuner` | `mpeTunerMpeInput` |
   | "not trigger MCM for non-MCM RPN (e.g. PBS RPN)" | `defaultTuner` | `mpeTunerMpeInput` |
   | "ignore MCM on non-master channel" | `defaultTuner` | `mpeTunerMpeInput` |
   | "revert to initialZones on reset() after MCM" | `dualZoneTuner` | `dualZoneTunerMpeInput` |

4. Update `issues/00154-mpe-tuner-poly-expr/tests-reorg.csv` to mirror the
   new test layout: move 12 MCM rows from the Non-MPE column to the MPE column
   and add the new PBS rows for the bug-fix tests.
5. Update `issues/00154-mpe-tuner-poly-expr/tests-reorg-plan.md` to mark the
   MCM Processing coverage gap as closed.

### Commit message
```
[#202] Reorganise MCM Processing tests by input mode

Mirror the Non-MPE / MPE Input split already used by reset(), tune(),
process() and PBS Processing. All MCM tests except the auto-mode-switch
case exercise side-effects that are mode-independent; move them under
MpeTuner - MCM Processing - MPE Input using MPE-input fixtures.
```

---

## Follow-up (same branch, not part of the two main commits)

Add a TODO to `MpeTuner` class body:

```scala
// TODO #154 Forbid or warn when MpeTuner is configured with non-MPE input mode while both zones are
//  enabled — Upper Zone is unreachable in non-MPE routing. Also remove the now-unused `dualZoneTuner`
//  test fixture once no Non-MPE tests reference it.
```

---

## Workflow order

1. **Open issue #202** via GitHub MCP.
2. **Commit 1 — fix** (TDD).
3. **Commit 2 — test reorg**.
4. **Commit 3 — TODO follow-up**.
5. **Open draft PR #203** — label `bugfix`, milestone MPE, body `Resolves #202`.

## Critical files referenced

- `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` — `processCc`, `processPbs`, `applyPbsUpdate`, `forwardOnZoneMasterChannel`, `resolveZoneMasterChannel`
- `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` — MCM and PBS Processing sections
- `docs/tuner/mpe-tuner-paper.md` §3.3.2 — authoritative routing rule
- `issues/00154-mpe-tuner-poly-expr/tests-reorg-plan.md` and `tests-reorg.csv`

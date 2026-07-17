# MPE Tuner Paper Review Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply all 38 review findings (M1–M3, S1–S6, P1–P13, N1–N16) to `docs/architecture/tuner/mpe-tuner-paper.md`,
release-critical findings first.

**Architecture:** Pure documentation edits to a single Markdown file, applied as exact-string replacements. Tasks are
grouped by finding category and ordered so that (a) all release-critical fixes come first and (b) every `Find:` anchor
is valid at the moment its task runs (some later edits anchor on text produced by earlier tasks). One commit per task.

**Tech Stack:** Markdown with embedded mermaid; the Edit tool (exact `old_string`/`new_string` matching); git.

**Provenance:** Derived from the paper review of 2026-07-16 (two parallel reviews: internal consistency, and fact-check
against `docs/architecture/tuner/mpe-spec.md` as ground truth for RP-053), recorded in the companion
`paper-review-findings.md` (retained alongside this plan). The findings were produced against commit `d846ca7`; the paper is
unchanged since, and every `Find:` block below was copied verbatim from the current file. The one spec defect found
(Reset All Controllers listed as CC #127 instead of CC #121) was already fixed in commit `62a704d` — **do not edit
`mpe-spec.md`**.

## Release triage

An MPE Tuner release is urgent and the paper must be *decent, not perfect*. The dividing line: **a finding is
release-critical if an implementer relying on the paper would build wrong or undefined behavior** — normative
contradictions, unsatisfiable algorithm statements, and unspecified message handling. Completeness of the background,
precision polish, and style are follow-up.

**Fix for the release (Tasks 1–3 and 5–8):**

- **M1** — Section 8.3 contradicts the spec and the paper's own averaging model; taken literally, shared channels stop
  receiving tuning and averaging updates after any Note Off.
- **M2** — allocation Steps 1–2 and the flowchart are unsatisfiable under Section 4.2's dynamic group model; this is
  the core algorithm of the paper.
- **M3** — worked example 9.1 is not derivable from the tie-break rules (wrong channel, input mode unstated); worked
  examples are what implementers test against.
- **S3** — non-MPE conversion never says where Damper Pedal, Modulation, etc. go; the output routing is undefined.
- **S4** — a velocity-0 Note On not treated as Note Off corrupts allocation state; silent correctness bug in any
  implementation that follows the paper.
- **S5** — Pitch Bend Sensitivity handling is unspecified per input mode and the generic bullet invites an `n²`
  message flood; getting sensitivity right (zone-wide interpretation, per-channel forwarding in MPE mode, Master-Channel
  application in Non-MPE mode with Member-Channel sensitivity set only via non-MIDI config) is what keeps output
  correctly tuned. (Re-emission of non-default sensitivities after an MCM was intentionally dropped — see the Task 7
  design decision.)
- **S6** — Note Off emission on freeing is asserted only in an example; without it receivers are left with stuck notes.

**Follow-up after the release (Tasks 4 and 9–11):** S2 (the original finding was largely inaccurate — MPE-mode PKP is
already covered by Section 3.5; only a minor residual survives, see Task 4), S1 (background completeness — the facts all
appear later in the paper), P1–P13 (precision polish), N1–N16 (language and style).

Each task heading below carries its **(release)** or **(follow-up)** flag. For a release-only run, execute Tasks 1–3
and 5–8 (Task 4 is follow-up), then Task 12 (one Task 12 check has a known, documented deviation until Task 11 runs).

## Global Constraints

- The only file modified is `docs/architecture/tuner/mpe-tuner-paper.md`. Never edit `mpe-spec.md` (it is the ground
  truth being quoted).
- Execute tasks **in order**. Task 6 (S4) and edit P11 in Task 10 anchor on text produced by Task 1 (M1); edit P4 in
  Task 10 anchors on text produced by Task 2 (M2); Task 9 (S1) must run before Task 10 (its Step 2 anchors on the
  sentence that P9 rewrites).
- Apply each edit with the Edit tool using the `Find:` block as `old_string` and the `Replace with:` block as
  `new_string`, **verbatim, including line breaks and leading spaces**. Never locate text by line number — line numbers
  shift as edits are applied.
- Every quotation from the MPE Specification must match `docs/architecture/tuner/mpe-spec.md` word-for-word; where a
  quote is adjusted, use bracketed alterations (e.g. `[i]f`). All quotes in the `Replace with:` blocks below have
  already been verified against the spec — copy them exactly.
- Match the paper's local formatting: Sections 3–6 wrap prose at ~120 columns; Sections 1, 2, 8, 9 use one physical
  line per paragraph/list item — the exception is Section 8's Pitch Bend Sensitivity item, whose MPE / Non-MPE
  sub-bullets wrap at ~120 columns (Task 7). The `Replace with:` blocks already respect this — do not re-wrap them.
- Citation form is `[1, §X.Y]`; em dashes are spaced (` — `).
- No existing section needs renumbering, because every numbered addition is **appended at the end** of its sibling
  sequence and simply takes the next free number: Sections 2.6 and 2.7 (Task 9) go after the existing 2.5, and item 4 in
  Section 3.4 (Task 5) goes after the existing items 1–3. All other additions are unnumbered paragraphs, entries within
  an already-lettered/bulleted list, or flowchart nodes. If you find an addition would land in the *middle* of a
  numbered sequence, stop — that would require renumbering and the plan expects it not to.
- After finishing a task: tick its checkboxes in this file, then commit the paper **and this plan file** together with
  the message given in the task's commit step.

## Finding → Task map

| Findings | Task | Triage |
|---|---|---|
| M1 | Task 1 | release |
| M2 | Task 2 | release |
| M3 | Task 3 | release |
| S2 (revised) | Task 4 | follow-up |
| S3 | Task 5 | release |
| S4 | Task 6 | release |
| S5 | Task 7 | release |
| S6 | Task 8 | release |
| S1 | Task 9 | follow-up |
| P1–P13 | Task 10 | follow-up |
| N1–N16 | Task 11 | follow-up |
| (verification) | Task 12 | both |

---

### Task 1: M1 — Fix Note Off cessation scope in Section 8.3 (release)

Section 8.3 currently scopes Pitch Bend cessation to the released note's *channel*, contradicting the spec's per-note
statement and the paper's own averaging model (Sections 6.1 and 7): a shared channel with remaining active notes must
keep receiving tuning-change and averaging updates.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Rewrite the 8.3 paragraph**

Find:

````
Upon Note Off, the MPE Tuner ceases controlling the Pitch Bend for the released note's channel (consistent with the specification's statement that "control of a note ceases once Note Off has occurred" [1, §3.3.3]). The channel becomes available for reuse once all its notes have received Note Off messages.
````

Replace with:

````
Upon Note Off, per-note control of the released note ceases: the note is removed from its channel's Expression Value averages (Section 6.1), consistent with the specification's statement that "control of a note ceases once Note Off has occurred" [1, §3.3.3]. While other notes remain active on the channel, the Tuner continues to update the channel's Pitch Bend — for tuning changes (Section 7) as well as for Expression Value changes (Section 6). The channel becomes available for reuse once all its notes have received Note Off messages.
````

- [x] **Step 2: Verify**

Run: `grep -c "per-note control of the released note ceases" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review M1: per-note Note Off cessation in Section 8.3"
```

---

### Task 2: M2 — Rephrase allocation in capacity terms (release)

Section 4.2 defines groups dynamically (unoccupied channels belong to *no* group), but Steps 1–2 of the algorithm, the
flowchart, and the worked examples ask whether a group "contains an unoccupied channel" — unsatisfiable under that
model. Rephrase in *capacity* terms, first defining "capacity" in Section 4.3.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Define "capacity" in Section 4.3**

Find:

````
The number of channels allocated to each group depends on the total number of Member Channels `n` configured for the Zone:
````

Replace with:

````
Each group has a **capacity** — the maximum number of occupied channels that may be assigned to it at any moment — determined by the total number of Member Channels `n` configured for the Zone:
````

- [x] **Step 2: Use capacity wording in the 4.3 rationale**

Find:

````
Notably, for a single Zone with 15 Member Channels, the Pitch Class Group has 12 channels — exactly the number required to represent all 12 pitch classes of a standard keyboard simultaneously.
````

Replace with:

````
Notably, for a single Zone with 15 Member Channels, the Pitch Class Group has a capacity of 12 channels — exactly the number required to represent all twelve pitch classes of a standard keyboard simultaneously.
````

(The `12` → `twelve` change is N15's numeral convention, folded in here to avoid touching this sentence twice.)

- [x] **Step 3: Rewrite allocation Step 1**

Find:

````
1. **Allocate in Pitch Class Group**: If the Pitch Class Group contains an unoccupied channel *and* no occupied channel
   in the Pitch Class Group has an active note with the new note's pitch class, assign the new note to an unoccupied
   channel in the Pitch Class Group.
````

Replace with:

````
1. **Allocate in Pitch Class Group**: If the Pitch Class Group has spare capacity — fewer than `a` occupied channels
   are assigned to it — *and* no channel assigned to it has an active note with the new note's pitch class, assign the
   new note to an unoccupied Member Channel, which thereby joins the Pitch Class Group.
````

- [x] **Step 4: Rewrite allocation Step 2**

Find:

````
2. **Allocate in Expression Group**: If the Pitch Class Group already holds a note with the new note's pitch class *or*
   all Pitch Class Group channels are occupied, attempt to assign the new note to an unoccupied channel in the
   Expression Group.
````

Replace with:

````
2. **Allocate in Expression Group**: If the Pitch Class Group already holds a note with the new note's pitch class *or*
   is at full capacity, and the Expression Group has spare capacity — fewer than `b` occupied channels are assigned to
   it — assign the new note to an unoccupied Member Channel, which thereby joins the Expression Group.
````

- [x] **Step 5: Rewrite allocation Step 3**

Find:

````
3. **Share channel**: If no unoccupied channel is available in the Expression Group — and the Pitch Class Group either
   has an active note with the new note's pitch class or has all channels occupied — assign the new note to any channel
   (from either group) that already holds active notes with the same pitch class.
````

Replace with:

````
3. **Share channel**: If the Expression Group is at full capacity — and the Pitch Class Group either has an active note
   with the new note's pitch class or is itself at full capacity — assign the new note to any channel (from either
   group) that already holds active notes with the same pitch class.
````

- [x] **Step 6: Update the flowchart question nodes**

Find:

````
    Q1a{"Does the Pitch Class Group<br/>have an unoccupied channel?"}
````

Replace with:

````
    Q1a{"Does the Pitch Class Group<br/>have spare capacity?"}
````

Then find:

````
    Q2{"Does the Expression Group<br/>have an unoccupied channel?"}
````

Replace with:

````
    Q2{"Does the Expression Group<br/>have spare capacity?"}
````

- [x] **Step 7: Update the flowchart assignment nodes**

Find:

````
    Q1b -- No --> A1["Step 1 — Assign to an unoccupied<br/>Pitch Class Group channel"]
````

Replace with:

````
    Q1b -- No --> A1["Step 1 — Assign to an unoccupied channel,<br/>which joins the Pitch Class Group"]
````

Then find:

````
    Q2 -- Yes --> A2["Step 2 — Assign to an unoccupied<br/>Expression Group channel"]
````

Replace with:

````
    Q2 -- Yes --> A2["Step 2 — Assign to an unoccupied channel,<br/>which joins the Expression Group"]
````

- [x] **Step 8: Fix static phrasing in example 9.1's intro**

Find:

````
Consider a Lower Zone with 7 Member Channels (Channels 2–8), configured with a quarter-comma meantone Tuning. The Pitch Class Group has 5 channels and the Expression Group has 2 channels (per the formula for `n = 7`).
````

Replace with:

````
Consider a Lower Zone with 7 Member Channels (Channels 2–8), configured with a quarter-comma meantone Tuning. The Pitch Class Group has a capacity of 5 channels and the Expression Group a capacity of 2 (per the formula for `n = 7`).
````

- [x] **Step 9: Fix static phrasing in example 9.1's steps 1–3**

Find:

````
1. **Note C4 arrives**: Pitch Class Group has unoccupied channels and no channel holds pitch class C. Assign to Channel 2, Pitch Class Group. Output Pitch Bend encodes the meantone offset for C.

2. **Note E4 arrives**: Pitch Class Group has unoccupied channels and no channel holds pitch class E. Assign to Channel 3, Pitch Class Group. Output Pitch Bend encodes the meantone offset for E.

3. **Note G4 arrives**: Assign to Channel 4, Pitch Class Group. Output Pitch Bend encodes the meantone offset for G.
````

Replace with:

````
1. **Note C4 arrives**: The Pitch Class Group has spare capacity and none of its channels holds pitch class C. Assign to Channel 2, which joins the Pitch Class Group. Output Pitch Bend encodes the meantone offset for C.

2. **Note E4 arrives**: The Pitch Class Group has spare capacity and none of its channels holds pitch class E. Assign to Channel 3, which joins the Pitch Class Group. Output Pitch Bend encodes the meantone offset for E.

3. **Note G4 arrives**: Assign to Channel 4, which joins the Pitch Class Group. Output Pitch Bend encodes the meantone offset for G.
````

- [x] **Step 10: Verify**

Run: `grep -c "unoccupied channel?" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0`
Run: `grep -c "spare capacity" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `6`

- [x] **Step 11: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review M2: allocation algorithm phrased in capacity terms"
```

---

### Task 3: M3 — Correct worked-example channel selection and derive it from the criteria (release)

Example 9.1 step 4 assigns the second C4 to Channel 6, but with Channels 5–8 unoccupied the tie-breaking criteria yield
Channel **5**: (a)–(c) degenerate for unoccupied channels, (d) has no Note Off history to use, and (e) picks the lowest
channel number in Non-MPE Input Mode. The example never states its input mode, on which criterion (e) depends. Fix the
channel number everywhere it propagates, state the input mode for all of Section 9, and — per explicit user request —
make the example *show the derivation*, citing the criteria as done above.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: State the examples' input mode at the top of Section 9**

Find:

````
## 9. Worked Examples

### 9.1 Basic Allocation in Quarter-Comma Meantone
````

Replace with:

````
## 9. Worked Examples

The examples in this section assume Non-MPE Input Mode, so tie-breaking criterion (e) of Section 4.5 resolves to the lowest-numbered candidate channel.

### 9.1 Basic Allocation in Quarter-Comma Meantone
````

- [x] **Step 2: Rewrite example 9.1 step 4 with the channel-selection derivation**

Find:

````
4. **Second C4 arrives** (e.g., re-articulated while the first is sustained): Pitch class C is already in the Pitch Class Group (Channel 2). Assign to Channel 6, Expression Group. Both channels output the meantone offset for C; their Expression Pitch Bends are independent.
````

Replace with:

````
4. **Second C4 arrives** (e.g., re-articulated while the first is sustained): Pitch class C is already in the Pitch Class Group (Channel 2), so Step 2 of the allocation algorithm applies. Channels 5–8 are the unoccupied candidates. Criteria (a)–(c) of the tie-breaking rules degenerate for unoccupied channels, and criterion (d) does not discriminate because no candidate has yet received a Note Off; criterion (e) — the lowest channel number, in Non-MPE Input Mode — therefore selects Channel 5, which joins the Expression Group. Both channels output the meantone offset for C; their Expression Pitch Bends are independent.
````

- [x] **Step 3: Propagate the channel number to example 9.1 step 5**

Find:

````
5. **Performer bends the second C4 upward**: Only Channel 6's Pitch Bend is affected. Channel 2's Pitch Bend remains at the pure meantone offset for C, preserving the first note's intonation.
````

Replace with:

````
5. **Performer bends the second C4 upward**: Only Channel 5's Pitch Bend is affected. Channel 2's Pitch Bend remains at the pure meantone offset for C, preserving the first note's intonation.
````

- [x] **Step 4: Propagate the channel number to example 9.2 step 5**

Find:

````
5. Recomputes and sends Pitch Bend on Channel 6 (pitch class C, new Pythagorean Tuning Pitch Bend plus the current Expression Pitch Bend of the bent note).
````

Replace with:

````
5. Recomputes and sends Pitch Bend on Channel 5 (pitch class C, new Pythagorean Tuning Pitch Bend plus the current Expression Pitch Bend of the bent note).
````

- [x] **Step 5: Verify**

Run: `grep -c "Channel 6" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0`

- [x] **Step 6: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review M3: derive worked-example channel choice from tie-break criteria"
```

---

### Task 4: S2 (revised) — Note that PKP on an input Member Channel is discarded (follow-up)

The original S2 finding was largely inaccurate. PKP handling in MPE Input Mode is **already covered for the Master
Channel** by Section 3.5, which forwards Master Channel messages "without modification" and whose rationale (point 3)
explicitly preserves the ability to use Polyphonic Key Pressure on Master Channel notes. The only genuinely uncovered
case is PKP arriving on an *input Member Channel*, which the MPE Specification forbids [1, §2.5]; the paper never states
the Tuner discards it. That rule concerns Member Channels, so it belongs in Section 6.2 (which describes what an input
Member Channel may carry) — **not** in Section 3.5 (Master Channel Note Forwarding), where the earlier draft of this
plan wrongly placed it. It is minor — a conforming sender never emits PKP on a Member Channel — hence follow-up, not
release.

(If you prefer to drop S2 entirely, that is defensible: this is illegal input the paper is under no obligation to
address, and the paper does not exhaustively specify other illegal-input handling. Skipping this task leaves no
correctness gap.)

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Append a sentence to the first paragraph of Section 6.2**

Find:

````
Since Pitch Bend, Channel Pressure, and CC #74 are channel messages, all notes
active on the same input channel necessarily share the same Expression Values.
````

Replace with:

````
Since Pitch Bend, Channel Pressure, and CC #74 are channel messages, all notes
active on the same input channel necessarily share the same Expression Values. Polyphonic Key Pressure is not among the
control dimensions an input Member Channel may carry — the MPE Specification forbids it there [1, §2.5] — so any
Polyphonic Key Pressure received on an input Member Channel is discarded and never re-emitted. (Polyphonic Key Pressure
on a Master Channel is a different case: it is forwarded unmodified as part of Master Channel forwarding, Section 3.5.)
````

- [x] **Step 2: Verify**

Run: `grep -c "not among the" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S2 (revised): discard PKP on input Member Channel"
```

---

### Task 5: S3 — Cover the remaining channel-wide messages in Non-MPE conversion (release)

Section 3.4 redirects only Pitch Bend, CC #74, and Channel Pressure; other channel-wide messages (Damper Pedal,
Modulation, etc.) are addressed only by Section 8.2's Master-Channel *forwarding* — but non-MPE input has no Master
Channel to forward from. Add item 4 to Section 3.4. Item 4 also states explicitly that Pitch Bend Sensitivity
(RPN 00 00) received on the non-MPE input is forwarded to the Master Channel, consistent with S5 (Task 7).

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Add item 4 to Section 3.4**

Find:

````
    - **CC #74**: not emitted on Member Channels. In Non-MPE Input Mode there is no source of per-note CC #74 — the
      dimension is controllable only globally, via the Master Channel (item 2) — so the Tuner never sends CC #74 on a
      Member Channel (Sections 6.3 and 8.1).

### 3.5 Master Channel Note Forwarding
````

Replace with:

````
    - **CC #74**: not emitted on Member Channels. In Non-MPE Input Mode there is no source of per-note CC #74 — the
      dimension is controllable only globally, via the Master Channel (item 2) — so the Tuner never sends CC #74 on a
      Member Channel (Sections 6.3 and 8.1).

4. **Redirection of Remaining Channel Messages**: All other Channel Voice and Channel Mode messages received on a
   non-MPE input channel — for example Damper Pedal (CC #64), Modulation (CC #1), Volume (CC #7), Program Change and
   Bank Select, and Reset All Controllers (CC #121) — are redirected to the Master Channel of the selected output Zone,
   where they act as Zone-level messages (Section 8.2). Non-MPE input has no Master Channel of its own from which
   Section 8.2's forwarding could operate; this redirection gives those messages their conformant Zone-level home on
   the output. A Pitch Bend Sensitivity message (RPN 00 00) received on the non-MPE input is likewise forwarded to the
   Master Channel, where it configures the sensitivity of the Master Channel Pitch Bend to which the input's Pitch Bend
   is redirected (Section 8).

### 3.5 Master Channel Note Forwarding
````

- [x] **Step 2: Verify**

Run: `grep -c "Redirection of Remaining Channel Messages" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`
Run: `grep -c "is likewise forwarded to the Master Channel" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1` — **known deviation**: actual is `0`. The Replace block (copied verbatim per the plan's own instructions) wraps at
~120 columns per this section's formatting convention, splitting the phrase across "...forwarded to the" / "Master
Channel..." on two physical lines, so a single-line `grep -c` can't match it. Confirmed present and correct via a
whitespace-collapsing check: `python3 -c "import re; print(re.sub(r'\s+',' ',open('docs/architecture/tuner/mpe-tuner-paper.md').read()).count('is likewise forwarded to the Master Channel'))"` → `1`.

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S3: redirect remaining channel messages in non-MPE conversion"
```

---

### Task 6: S4 — Address the velocity-0 Note On shorthand (release)

Occupancy tracking, averaging, and channel freeing all depend on recognizing Note Offs; a velocity-0 Note On must be
treated as Note Off or allocation state corrupts. Add a paragraph to Section 8.3. **Depends on Task 1** (anchors on
M1's rewritten paragraph, whose final sentence is unchanged from the original).

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Append the paragraph after 8.3's first paragraph**

Find:

````
The channel becomes available for reuse once all its notes have received Note Off messages.
````

Replace with:

````
The channel becomes available for reuse once all its notes have received Note Off messages.

A Note On with velocity 0 in the input is treated as a Note Off, following the MIDI 1.0 shorthand and the specification's recommendation "that this message be interpreted as Note Off velocity 64" [1, §3.3.2]. Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and channel reuse all depend on detecting note releases.
````

- [x] **Step 2: Verify**

Run: `grep -c "Note On with velocity 0" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S4: treat velocity-0 Note On as Note Off"
```

---

### Task 7: S5 — Complete the Pitch Bend Sensitivity semantics, per input mode (release)

Section 8's Pitch Bend Sensitivity bullet is generic and mode-agnostic. Two points need stating, **split by input
mode** — because the naive "emit to every Member Channel on output" reading floods when the Tuner is an MPE-in/MPE-out
processor:

- **Zone-wide interpretation (MPE mode):** a sensitivity received on any input Member Channel applies to all Member
  Channels when the Tuner interprets incoming Pitch Bend (spec §2.4).
- **Output emission without a flood:** "send the message to every Member Channel individually" is a *sender*
  recommendation (spec §2.4). A conforming MPE input already sends the message to all `n` Member Channels, so if the
  Tuner re-fanned each of those `n` messages across all `n` output channels it would emit `n²` messages. In MPE Input
  Mode the Tuner therefore forwards each received message on its *corresponding* output Member Channel (per-channel
  pass-through), which already covers every channel. In Non-MPE Input Mode the input has no Member Channels, and a
  Pitch Bend Sensitivity message received there applies to the **output Master Channel** — matching the redirection of
  the input's Pitch Bend to that channel (Section 3.4); the output Member Channels keep the MCM default ±48, since a
  Member Channel's Pitch Bend then carries only the Tuning Pitch Bend, and in this mode their sensitivity can be changed
  only through the non-MIDI configuration interface. (This also closes a separate gap: the paper never stated that
  Non-MPE-mode Pitch Bend Sensitivity applies to the output Master Channel.)
- **Re-emission after an MCM (intentionally omitted — design decision, 2026-07-16):** in principle an MCM resets
  sensitivities to ±2/±48, which would call for re-emitting any non-default sensitivity after every MCM the Tuner
  outputs. The Tuner deliberately does **not** do this — non-default sensitivities are not re-emitted after an MCM — so
  this point is left out of the bullet rewrite below and its Step 2 verify is expected to be `0`.

This rewrite also switches the bullet to the `RPN 00 00` notation (N12's convention; N12's remaining occurrences are
handled in Task 11).

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Rewrite the Pitch Bend Sensitivity bullet**

Find:

````
- **Pitch Bend Sensitivity**: the MPE Tuner relies on the default Pitch Bend Sensitivity values that the MCM establishes — ±48 semitones for Member Channels and ±2 semitones for the Master Channel [1, §2.4]. It also listens for Pitch Bend Sensitivity messages (RPN 0) on its input, conforms to them when interpreting incoming Pitch Bend, and outputs them so that the receiving instrument applies the same sensitivity.
````

Replace with:

````
- **Pitch Bend Sensitivity**: the MPE Tuner relies on the default Pitch Bend Sensitivity values that the MCM
  establishes — ±48 semitones for Member Channels and ±2 semitones for the Master Channel [1, §2.4]. It also listens
  for Pitch Bend Sensitivity messages (RPN 00 00) on its input and conforms to them when interpreting incoming Pitch
  Bend. How a received message propagates to the output depends on the input mode:
    - **MPE Input Mode**: a sensitivity received on any input Member Channel applies Zone-wide, since "[a] receiver must
      apply the last Pitch Bend Sensitivity message received on any Member Channel to all Member Channels in the Zone"
      [1, §2.4]. On output, the Tuner mirrors the input, forwarding each received message on its corresponding output
      Member Channel. It does *not* replicate every received message across all Member Channels. A conforming MPE sender
      already addresses the message to each of the `n` Member Channels [1, §2.4], so re-fanning those `n` messages to
      all `n` channels would produce an `n²` flood. Because the Tuner both receives and sends MPE, per-channel
      forwarding already configures every output Member Channel.
    - **Non-MPE Input Mode**: the input carries no Member Channels. Because a Member Channel's Pitch Bend then carries
      only the Tuning Pitch Bend, a Pitch Bend Sensitivity message received on the input applies to the output Master
      Channel, consistent with the redirection of the input's Pitch Bend to that channel (Section 3.4). The output
      Member Channels retain the MCM's default of ±48 semitones, which can be changed only through the non-MIDI
      configuration interface.
````

- [x] **Step 2: Verify**

Run: `grep -c "re-emits any non-default sensitivity after every MCM it outputs" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0` (re-emission after an MCM is intentionally not documented — see the design decision noted above)
Run: `grep -c "which can be changed only through the non-MIDI" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1` (the full "… non-MIDI configuration interface" phrase wraps across two physical lines, so match only up to the wrap point)

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S5: Pitch Bend Sensitivity semantics per input mode"
```

---

### Task 8: S6 — Make Note Off emission on freeing normative (release)

Example 9.3 says "(Note Off sent for E)" but the normative text never states that freeing a channel emits Note Off.
State it in Section 5.1.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Extend the freeing definition in Section 5.1**

Find:

````
classes present among the active notes, some notes must be dropped to free a channel for the incoming note. The term
**freeing a channel** refers to dropping all notes on that channel to make it unoccupied.
````

Replace with:

````
classes present among the active notes, some notes must be dropped to free a channel for the incoming note. The term
**freeing a channel** refers to dropping all notes on that channel to make it unoccupied. When a channel is freed, the
Tuner emits an explicit Note Off message for each of its dropped notes, before the incoming note's Note On. (Note Off
emission upon Zone reconfiguration is a distinct case, governed by Section 3.3.)
````

- [x] **Step 2: Verify**

Run: `grep -c "emits an explicit Note Off message for each of its dropped notes" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`

- [x] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S6: freeing a channel emits Note Off messages"
```

---

### Task 9: S1 — Add missing spec facts to the Background (follow-up)

Four spec facts that the paper's own design relies on are never introduced in the Background: the Polyphonic Key
Pressure rules, the zone-level vs. note-level message classification, the Master Channel Note On/Off permission, and
the receiver state-tracking obligation. Add the first two as new Sections 2.6 (Zone-Level Messages) and 2.7 (Pressure),
and the latter two as one sentence each in 2.4 and 2.5.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Add the Master Channel Note On/Off permission at the end of Section 2.4**

Find:

````
> "A controller may choose the Channel in which the change of pitch for the new note requires the smallest adjustment of pitch for other playing notes. Alternatively, at least one commercial implementation provides gentle degradation of pitch control when all Channels are occupied by switching to a mode where notes step discretely from one pitch to the next, permitting Pitch Bend to respond only to small vibrato gestures." [1, §3.2]
````

Replace with:

````
> "A controller may choose the Channel in which the change of pitch for the new note requires the smallest adjustment of pitch for other playing notes. Alternatively, at least one commercial implementation provides gentle degradation of pitch control when all Channels are occupied by switching to a mode where notes step discretely from one pitch to the next, permitting Pitch Bend to respond only to small vibrato gestures." [1, §3.2]

Allocation concerns Member Channels, but notes are not confined to them: "For the sake of MIDI 1.0 compatibility, Note On/Off messages are permitted on the Master Channel, and a synthesizer must respond to these" [1, §3.2]. Such notes forgo the per-note control dimensions, since the Master Channel's control messages affect the whole Zone.
````

- [x] **Step 2: Add the receiver state-tracking obligation at the end of Section 2.5**

Find:

````
This practice prevents "swooping" noises caused by a Channel retaining a previous note's Pitch Bend value when a new note begins.
````

Replace with:

````
This practice prevents "swooping" noises caused by a Channel retaining a previous note's Pitch Bend value when a new note begins.

The initial state that these setup messages establish is complemented by a receiver-side obligation: control values "must be tracked and stored on all Member Channels, even when no note is playing, to provide an initial state for a new note" [1, §3.3].
````

- [x] **Step 3: Add Sections 2.6 (Zone-Level Messages) and 2.7 (Pressure)**

Find:

````
---

## 3. MPE Tuner Architecture
````

Replace with:

````
### 2.6 Zone-Level Messages

MPE distinguishes note-level messages, which shape an individual note through its Member Channel, from Zone-level messages, which affect all notes in a Zone. Zone-level messages such as the Damper Pedal "should be sent only on a Zone's Master Channel (not on Member Channels). If an MPE synthesizer receives one of those messages on a Member Channel, it must ignore it" [1, §2.3]. Table 1 of the specification classifies every MIDI message along these lines.

### 2.7 Pressure

Pressure is subject to dedicated rules: "Polyphonic Key Pressure must not be sent on Member Channels" [1, §2.5] — aftertouch is conveyed there by Channel Pressure instead — while on the Master Channel, "Polyphonic Key Pressure may be sent for notes on the Master Channel at the discretion of the implementer, to preserve compatibility with non-MPE-aware devices" [1, §2.5].

---

## 3. MPE Tuner Architecture
````

- [x] **Step 4: Verify**

Run: `grep -c "### 2.6 Zone-Level Messages" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`
Run: `grep -c "### 2.7 Pressure" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`
Run: `grep -c "tracked and stored on all Member Channels" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `2` (Section 2.5 and the pre-existing quote in Section 6.2)

- [x] **Step 5: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review S1: add missing spec facts to Background"
```

---

### Task 10: P1–P13 — Minor precision and consistency fixes (follow-up)

Thirteen independent precision fixes. Apply each edit in the order given (P4 anchors on Task 2's output; P11 anchors on
Task 6's output).

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1 — P1: Scope the "never occurs" claim to channel exhaustion** (Section 5.2's High-Bend drops are
  independent of channel count)

Find:

````
maximum of 15 Member Channels, note dropping never occurs:
````

Replace with:

````
maximum of 15 Member Channels, note dropping due to channel exhaustion never occurs:
````

- [x] **Step 2 — P2: Fix the input-pitch-alteration claim for Non-MPE mode** (input Pitch Bend goes to the Master
  Channel there, not to Expression Pitch Bend)

Find:

````
Any pitch alteration present in the input signal is never interpreted as an alternative tuning; it contributes exclusively to the Expression Pitch Bend, while the Tuning Pitch Bend is derived solely from the active Tuning.
````

Replace with:

````
Any pitch alteration present in the input signal is never interpreted as an alternative tuning; it contributes exclusively to the expression domain — as Expression Pitch Bend in MPE Input Mode, or as Master Channel Pitch Bend in Non-MPE Input Mode (Section 3.4) — while the Tuning Pitch Bend is derived solely from the active Tuning.
````

- [x] **Step 3 — P3: Fix example 9.3's contradiction of the Expression Group's overflow role**

Find:

````
1. The Pitch Class Group (1 channel) is occupied by pitch class C. Pitch class A is not represented — it needs a Pitch Class Group channel.
````

Replace with:

````
1. The Pitch Class Group (1 channel) is occupied by pitch class C. Pitch class A is not represented — it cannot share a channel and needs a channel of its own.
````

- [x] **Step 4 — P4: Condition allocation Step 3 on the High-Bend rules** (depends on Task 2's Step 5 rewrite)

Find:

````
3. **Share channel**: If the Expression Group is at full capacity — and the Pitch Class Group either has an active note
   with the new note's pitch class or is itself at full capacity — assign the new note to any channel (from either
   group) that already holds active notes with the same pitch class.
````

Replace with:

````
3. **Share channel**: If the Expression Group is at full capacity — and the Pitch Class Group either has an active note
   with the new note's pitch class or is itself at full capacity — assign the new note to any channel (from either
   group) that already holds active notes with the same pitch class. This assignment is subject to the High Expression
   Pitch Bend rules of Section 5.2, which may require freeing the channel instead (Sections 5.2.2 and 5.2.3).
````

Then update the corresponding flowchart node. Find:

````
    Q3 -- Yes --> A3["Step 3 — Assign to that channel,<br/>shared with the same pitch class"]
````

Replace with:

````
    Q3 -- Yes --> A3["Step 3 — Assign to that channel, shared with the<br/>same pitch class (subject to Section 5.2)"]
````

- [x] **Step 5 — P5: Fold the sole-note invariant paragraph into Section 5.3** (it sits inside 5.2.2,
  forward-references 5.2.3, omits 5.2.2's own contribution, and duplicates invariant 2 of 5.3)

Delete the paragraph from 5.2.2. Find:

````
It follows that **when an active note on a channel has a High Expression Pitch Bend, that note is necessarily the sole
active note on the channel**. No other active notes can coexist with it: existing notes are dropped when one develops a
High Expression Pitch Bend (Section 5.2.1), and new notes arriving on a channel with a high-bend note cause the channel
to be freed (Section 5.2.3).

#### 5.2.3 New Note Assigned to a Channel with a High-Bend Note
````

Replace with:

````
#### 5.2.3 New Note Assigned to a Channel with a High-Bend Note
````

Then cite all three subsections in 5.3's invariant 2. Find:

````
2. An active note with a High Expression Pitch Bend (absolute deviation > `t`) is always the sole active note on its
   channel. No other notes may coexist with it: pre-existing notes are dropped, and the channel is freed before any new
   note is assigned to it (Sections 5.2.1 and 5.2.3).
````

Replace with:

````
2. An active note with a High Expression Pitch Bend (absolute deviation > `t`) is always the sole active note on its
   channel. No other notes may coexist with it: co-resident notes are dropped when a shared note develops a high bend
   (Section 5.2.1) or when a new note arrives already carrying one (Section 5.2.2), and a channel holding a high-bend
   note is freed before any new note is assigned to it (Section 5.2.3).
````

- [x] **Step 6 — P6: Soften the Master-Channel PKP capability** (the spec makes it discretionary for senders and only
  "may be recognized" by receivers)

Find:

````
any of these messages applied to the Master Channel will affect every sounding note in the Zone. In exchange, the sender
gains access to a form of per-note pressure that is *only* available on the Master Channel: Polyphonic Key Pressure. The
MPE Specification [1, §2.5] forbids Polyphonic Key Pressure on Member Channels but explicitly permits it on Master
Channel notes, so a performer playing a Master Channel note retains per-note pressure through Polyphonic Key Pressure in
place of the Channel Pressure dimension that Member Channel notes use.
````

Replace with:

````
any of these messages applied to the Master Channel will affect every sounding note in the Zone. In exchange, the sender
may gain a form of per-note pressure that is available only on the Master Channel: Polyphonic Key Pressure. The MPE
Specification [1, §2.5] forbids Polyphonic Key Pressure on Member Channels but permits it on Master Channel notes at the
implementer's discretion, for compatibility with non-MPE-aware devices; a performer playing a Master Channel note
therefore retains per-note pressure where the sending and receiving implementations recognize Polyphonic Key Pressure on
the Master Channel, in place of the Channel Pressure dimension that Member Channel notes use.
````

- [x] **Step 7 — P7: Acknowledge the normative placement of the one-channel-per-note statement and ground the
  departure in the spec's own sharing allowance**

Find:

````
The MPE Specification recommends:

> "An MPE controller assigns every new note its own MIDI Channel, until there are no unoccupied Channels available." [1, §2.2.1]

The MPE Tuner does **not** follow this recommendation unconditionally.
````

Replace with:

````
The MPE Specification states, within its normative description of MPE operation:

> "An MPE controller assigns every new note its own MIDI Channel, until there are no unoccupied Channels available." [1, §2.2.1]

The MPE Tuner does **not** follow this rule unconditionally; the specification itself acknowledges that "[i]f the number of active notes exceeds the number of available Channels, two or more notes will have to share a Channel" [1, §1.2], and the MPE Tuner invokes that allowance deliberately rather than only under exhaustion.
````

- [x] **Step 8 — P8: Disambiguate "among all active notes" in the boundary-channel exclusion** (Master-Channel notes
  are exempt from allocation)

Find:

````
1. **Exclude boundary channels**: Channels holding the highest-pitched and lowest-pitched notes among all active notes
   are excluded from consideration.
````

Replace with:

````
1. **Exclude boundary channels**: Channels holding the highest-pitched and lowest-pitched notes among the active notes
   on the Zone's Member Channels are excluded from consideration.
````

- [x] **Step 9 — P9: Generalize the "swooping" cause** (any stale value corrected after note start, not only a previous
  note's Pitch Bend)

Find:

````
This practice prevents "swooping" noises caused by a Channel retaining a previous note's Pitch Bend value when a new note begins.
````

Replace with:

````
This practice prevents "swooping" noises, which arise when a stale control value retained on a Channel — most commonly a previous note's Pitch Bend — is corrected only after a new note has begun sounding.
````

- [x] **Step 10 — P10: Acknowledge the merged control stream for multi-channel non-MPE input**

Find:

````
   they serve as Zone-level controls affecting all notes equally. Consequently, none of these three dimensions carries
   a per-note value onto a Member Channel.
````

Replace with:

````
   they serve as Zone-level controls affecting all notes equally. Consequently, none of these three dimensions carries
   a per-note value onto a Member Channel. When the input spans multiple channels, the redirected controls of all input
   channels merge onto the single Master Channel, the most recent message taking effect: the Tuner treats non-MPE input
   as one merged control stream rather than preserving per-input-channel independence.
````

- [x] **Step 11 — P11: Document the Channel Pressure behavior at Note Off** (depends on Task 6; the paragraph goes
  after S4's velocity-0 paragraph in Section 8.3)

Find:

````
A Note On with velocity 0 in the input is treated as a Note Off, following the MIDI 1.0 shorthand and the specification's recommendation "that this message be interpreted as Note Off velocity 64" [1, §3.3.2]. Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and channel reuse all depend on detecting note releases.
````

Replace with:

````
A Note On with velocity 0 in the input is treated as a Note Off, following the MIDI 1.0 shorthand and the specification's recommendation "that this message be interpreted as Note Off velocity 64" [1, §3.3.2]. Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and channel reuse all depend on detecting note releases.

At Note Off, whether the Tuner emits a Channel Pressure reset depends on the input mode. The specification requires that "Channel Pressure must be set to zero immediately before a Note On or a Note Off wherever it is appropriate to the design of a controller" [1, §3.3.4]. In MPE Input Mode the output Channel Pressure passes through from the input sender, so the Tuner emits no reset of its own — it inherits the sender's behavior: a conforming sender's pre-release reset propagates to the output through the update mechanism of Section 6.2, and if the sender emits none, neither does the Tuner. In Non-MPE Input Mode the per-note Channel Pressure on an output Member Channel is the Tuner's own, synthesized from the input's Polyphonic Key Pressure (Section 3.4); here the Tuner is the controller to which §3.3.4 applies, so it performs the reset itself, returning the channel's Channel Pressure to 0 as Section 6.3 requires. Deferring to the sender in MPE Input Mode and resetting in Non-MPE Input Mode both fall within the specification's "wherever it is appropriate" qualifier and are documented design choices.
````

- [x] **Step 12 — P12: Fix criterion (d)'s gloss** ("idle the longest" is wrong for occupied candidates at Steps 3–4)

Find:

````
- **(d)** If the oldest channel is still ambiguous, prefer the channel with the oldest last Note Off — the channel that
  has been idle the longest.
````

Replace with:

````
- **(d)** If the oldest channel is still ambiguous, prefer the channel whose most recent Note Off is the oldest.
  Channels with no Note Off history cannot be discriminated by this criterion; when it fails to single out a channel,
  selection falls to criterion (e).
````

- [x] **Step 13 — P13: Qualify Overview item 3 for Non-MPE mode** (no Expression component there)

Find:

````
3. Computes the appropriate Pitch Bend value for each Member Channel as the sum of the Tuning Pitch Bend for the channel's pitch class and the Expression Pitch Bend derived from the input (Section 1.3).
````

Replace with:

````
3. Computes the appropriate Pitch Bend value for each Member Channel as the sum of the Tuning Pitch Bend for the channel's pitch class and the Expression Pitch Bend derived from the input (Section 1.3); in Non-MPE Input Mode the Expression component is absent (Section 3.4).
````

- [x] **Step 14: Verify**

Run: `grep -c "cannot share a channel and needs a channel of its own" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1`
Run: `grep -c "It follows that" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0` (the only occurrence was the 5.2.2 paragraph deleted in Step 5)

- [x] **Step 15: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review P1-P13: precision and consistency fixes"
```

---

### Task 11: N1–N16 — Language and style fixes (follow-up)

Sixteen style fixes. All are independent of one another; the Section 2.1 MCM edit intentionally combines N12 and N15.

**File:** `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1 — N1: Fix the neither/nor construction in Section 4.5**

Find:

````
the lowest channel number. For the same reason, preserving the input channel can neither relax the pitch-class
invariant or the group constraints — a channel is admitted as a candidate at Steps 1 and 2 only once both are
satisfied — nor override the perceptual criteria that govern Steps 3 and 4.
````

Replace with:

````
the lowest channel number. For the same reason, preserving the input channel can relax neither the pitch-class
invariant nor the group constraints — a channel is admitted as a candidate at Steps 1 and 2 only once both are
satisfied — nor can it override the perceptual criteria that govern Steps 3 and 4.
````

- [x] **Step 2 — N2: Add the missing comma and strengthen the modal in Section 4.1**

Find:

````
two different tuning offsets simultaneously which may compromise the intonation of at least one note.
````

Replace with:

````
two different tuning offsets simultaneously, which would compromise the intonation of at least one note.
````

- [x] **Step 3 — N3: Remove the sole first-person sentence in Section 5** (also fixes "the last resort measure" vs.
  Section 4.5's "a last-resort measure")

Find:

````
conditions under which notes are dropped and the criteria for selecting which notes to drop. We maintain the principle
that dropping is the last resort measure, used only when the fundamental invariants of intonation would otherwise be
violated.
````

Replace with:

````
conditions under which notes are dropped and the criteria for selecting which notes to drop. Dropping is treated as a
last-resort measure, used only when the fundamental invariants of intonation would otherwise be violated.
````

- [x] **Step 4 — N4: Replace the colloquial "its turn to be reused"**

Find:

````
- **(d)** The oldest last Note Off identifies the channel that has gone longest without a release, so it is its turn to
  be reused.
````

Replace with:

````
- **(d)** The oldest last Note Off identifies the channel that has gone longest without a release, making it the
  natural candidate for reuse.
````

- [x] **Step 5 — N5: "equally listens" → "likewise listens" in Section 8**

Find:

````
It equally listens for MCMs on its input
````

Replace with:

````
It likewise listens for MCMs on its input
````

- [x] **Step 6 — N6: Fix the faulty predication in Section 3.5**

Find:

````
A note placed on the Master Channel by an MPE sender is a deliberate choice: the sender opts out of the
````

Replace with:

````
Placing a note on the Master Channel is a deliberate choice by the MPE sender: the sender opts out of the
````

- [x] **Step 7 — N7: Replace undefined "aftertouch" and tone down "dubious" in Section 3.4**

Find:

````
      ordering is in any case uncommon and of dubious musical meaning, since aftertouch models pressure on a key that is
      already held.
````

Replace with:

````
      ordering is in any case uncommon and of questionable musical meaning, since Polyphonic Key Pressure models
      pressure on a key that is already held.
````

- [x] **Step 8 — N8: "may not share" → "must not share" in Section 4.1** (removes the permission/prohibition ambiguity)

Find:

````
they may not share a Member Channel
````

Replace with:

````
they must not share a Member Channel
````

- [x] **Step 9 — N9: "twelve keys" → "twelve keys per octave" in Section 1.1**

Find:

````
the twelve keys of a standard MIDI keyboard are insufficient
````

Replace with:

````
the twelve keys per octave of a standard MIDI keyboard are insufficient
````

- [x] **Step 10 — N10: Fix the EDO gloss in Section 1.1**

Find:

````
corresponding to the twelve-tone equal temperament (12-EDO) tuning system
````

Replace with:

````
corresponding to the twelve-tone equal temperament tuning system (12 equal divisions of the octave, 12-EDO)
````

- [x] **Step 11 — N11 + N16: Full CC #74 gloss and Section 2.1 pointer at first mention** (Section 1.1), then drop the
  duplicate gloss (Section 1.3)

Find:

````
MPE achieves per-note control by assigning each sounding note to its own MIDI Channel within a defined Zone, thereby enabling independent Pitch Bend, Channel Pressure, and CC #74 (timbre) control for each note.
````

Replace with:

````
MPE achieves per-note control by assigning each sounding note to its own MIDI Channel within a defined Zone (Section 2.1), thereby enabling independent Pitch Bend, Channel Pressure, and CC #74 (timbre or slide) control for each note.
````

Then find:

````
Pitch Bend, Channel Pressure, and CC #74 (timbre or slide) [1, §2.4–2.6]
````

Replace with:

````
Pitch Bend, Channel Pressure, and CC #74 [1, §2.4–2.6]
````

- [x] **Step 12 — N12 + N15 (MCM): Standardize RPN notation and stop re-expanding MCM** (the first edit covers both
  findings; Section 8's `RPN 0` was already converted by Task 7)

Find:

````
A Zone is configured by sending an MPE Configuration Message (MCM) — Registered Parameter Number 00 06 — on the Master Channel of the desired Zone.
````

Replace with:

````
A Zone is configured by sending an MCM — Registered Parameter Number (RPN) 00 06 — on the Master Channel of the desired Zone.
````

Then find:

````
These values may be changed via RPN 0.
````

Replace with:

````
These values may be changed via RPN 00 00.
````

Then find:

````
Receipt of an MPE Configuration Message (MCM) shall cause the Tuner to switch to MPE Input Mode automatically
````

Replace with:

````
Receipt of an MCM shall cause the Tuner to switch to MPE Input Mode automatically
````

Then find:

````
or in-band, through an MPE Configuration Message (MCM) received on a Master Channel
````

Replace with:

````
or in-band, through an MCM received on a Master Channel
````

- [x] **Step 13 — N13: Have Section 6.1 reference Section 4.6's formula instead of restating it**

Find:

````
notes (Section 4.6). The Pitch Bend emitted on the channel combines this average with the tuning domain:

```
Output Pitch Bend = Tuning Pitch Bend(pitch class) + average(Expression Pitch Bends of all active notes)
```

The Channel Pressure and CC #74 dimensions are emitted as plain averages, having no tuning component.
````

Replace with:

````
notes (Section 4.6). The Pitch Bend emitted on the channel combines this average with the tuning domain according to
the formula of Section 4.6. The Channel Pressure and CC #74 dimensions are emitted as plain averages, having no tuning
component.
````

(The formula in Section 1.3 — `Pitch Bend = Tuning Pitch Bend + Expression Pitch Bend` — is the definitional
two-component identity, not the averaging formula; it stays.)

- [x] **Step 14 — N14: Restore the silently altered quotes** (three edits: the Section 2.4 quote, its shorter
  repetition in Section 4.7.4, and the Section 4.7.5 quote)

Find:

````
> "In particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch. Alternatively, a guitar-type controller might permit the same pitch to be played simultaneously on different strings." [1, §3.2]
````

Replace with:

````
> "However, in particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch. Alternatively, a guitar-type controller might permit the same pitch to be played simultaneously on different strings." [1, §3.2]
````

Then find:

````
> "In particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch." [1, §3.2]
````

Replace with:

````
> "However, in particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch." [1, §3.2]
````

Then find:

````
> "If an MPE synthesizer receives Pitch Bend on both a Master and a Member Channel, it must combine the data meaningfully." [1, §2.3.2]
````

Replace with:

````
> "If an MPE synthesizer receives Pitch Bend (for example) on both a Master and a Member Channel, it must combine the data meaningfully." [1, §2.3.2]
````

- [x] **Step 15 — N15 (remaining items): heading, stray capital, list blank line, numerals,
  self-reference** (five edits; the MCM and `12 pitch classes` items were handled in Step 12 and Task 2)

Heading "Note-On" → "Note On". Find:

````
### 2.5 Note-On Setup and Message Ordering
````

Replace with:

````
### 2.5 Note On Setup and Message Ordering
````

Stray capitalized "Channels" outside a quote (Section 4.2). Find:

````
Within this group, no two occupied Channels may have active notes of the same pitch class.
````

Replace with:

````
Within this group, no two occupied channels may have active notes of the same pitch class.
````

Missing blank line before the second `- **(a)**` bullet list (some renderers won't recognize the list). Find:

````
principle of minimal perceptual disruption.
- **(a)** A High Expression Pitch Bend is a dynamic gesture that draws the listener's attention, so dropping such a note
````

Replace with:

````
principle of minimal perceptual disruption.

- **(a)** A High Expression Pitch Bend is a dynamic gesture that draws the listener's attention, so dropping such a note
````

Numeral convention — "twelve" for pitch classes in prose (Section 9.2; Section 4.3's instance was fixed in Task 2;
channel counts and `n` values keep numerals). Find:

````
1. Updates the tuning offsets for all 12 pitch classes.
````

Replace with:

````
1. Updates the tuning offsets for all twelve pitch classes.
````

Self-reference — standardize on "this paper" (two edits). Find:

````
The specification herein is intended to serve as a reference for software implementations.
````

Replace with:

````
This paper is intended to serve as a reference for software implementations.
````

Then find:

````
A **Tuning**, in the context of this specification, is a set of twelve pitch offsets
````

Replace with:

````
A **Tuning**, in the context of this paper, is a set of twelve pitch offsets
````

- [x] **Step 16: Verify**

Run: `grep -cE "MPE Configuration Message \(MCM\)" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `1` (the first mention in Section 1.4 only)
Run: `grep -c "RPN 0\." docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0`
Run: `grep -c "dubious\|aftertouch\|equally listens\|its turn" docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `0` — **addendum**: Task 9 (run after N7 was written) introduced a second "aftertouch" occurrence in the new Section 2.7.
Fixed inline during Task 11 (reworded to "per-note pressure is conveyed there by Channel Pressure instead"), not itemized
above since Section 2.7 didn't exist when this step was drafted.

- [x] **Step 17: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md issues/00154-mpe-tuner-poly-expr/paper-review-plan.md
git commit -m "Apply paper review N1-N16: language and style fixes"
```

---

### Task 12: Final verification (run after the release tasks 1–3 and 5–8, or after Task 11 for the full pass)

> **Status (re-opened 2026-07-17):** the per-step notes below record the *release-pass* run (commit `7184ba6`). All
> boxes are re-opened for the full pass — re-run every step after Tasks 10–11 and the Task 7 (S5) wording
> reconciliation of 2026-07-17.

- [ ] **Step 1: Confirm no finding was missed** — verified for the executed tasks (1–3 and 5–9); Tasks 10–11 out of
  scope for this pass.

Walk the Finding → Task map at the top of this plan and confirm every checkbox in the executed tasks is ticked (Tasks
1–3 and 5–8 for a release-only pass; Tasks 1–11 for the full pass).

- [ ] **Step 2: Check quote fidelity against the spec** — 1 known failure (the Section 4.7.5 "(for example)" omission,
  restored only by N14 in Task 11), exactly as documented below.

Run this script; it extracts every quotation that is followed by a `[1, …]` citation and checks it appears verbatim in
the spec (after collapsing line-wrap whitespace). Bracketed alterations like `[i]f` are normalized before matching.

```bash
python3 - <<'EOF'
import re
paper = open('docs/architecture/tuner/mpe-tuner-paper.md').read()
spec = re.sub(r'\s+', ' ', open('docs/architecture/tuner/mpe-spec.md').read())
failures = 0
for m in re.finditer(r'"([^"]{25,})"\s*\[1', paper):
    q = re.sub(r'\s+', ' ', m.group(1))
    q = re.sub(r'\[(\w)\]', r'\1', q)  # unwrap bracketed alterations
    if q not in spec and q.lower() not in spec.lower():
        failures += 1
        print('NOT FOUND IN SPEC:', q[:100])
print('OK' if failures == 0 else f'{failures} quote(s) failed')
EOF
```

Expected: `OK` after the full pass. After a release-only pass (Tasks 4 and 9–11 deferred), one known failure is acceptable:
the Section 4.7.5 quote missing "(for example)", which N14 (Task 11, Step 14) restores.

- [ ] **Step 3: Check the mermaid diagrams still parse** — 13 arrows, as expected.

Both diagrams were edited only in node label text; confirm the structure survived:

Run: `grep -c '\-\->' docs/architecture/tuner/mpe-tuner-paper.md`
Expected: `13` (4 arrows in the signal-flow diagram, 9 in the allocation flowchart)

- [ ] **Step 4: Read the full paper once, end to end** — no dangling "Channel 6" or "unoccupied channel in the … Group"
  phrasing; section numbering intact; new cross-references (2.1, 3.3, 3.4, 4.5, 6.1, 6, 7, 8.2, 2.5, 3.5) all resolve to
  sections that exist and say what the reference claims.

Read `docs/architecture/tuner/mpe-tuner-paper.md` in full and check: no dangling references to "Channel 6" or to
"unoccupied channel in the … Group" phrasing; section numbering is intact (2.6, 2.7, and 3.4 item 4 are additive); every
cross-reference cited in new text (Sections 2.1, 3.3, 3.4, 4.5, 4.6, 5.1, 5.2, 6.1, 6.2, 8.1, 8.2) points at a section
that exists and says what the reference claims.

- [ ] **Step 5: Confirm a clean tree and consistent history** — `git status --short` empty; 9 "Apply paper review …"
  commits (Tasks 1–3, 5–9) atop the pre-existing history.

Run: `git status --short` — expected: empty (everything committed).
Run: `git log --oneline -12` — expected: one "Apply paper review …" commit per executed task (7 for a release-only
pass — Tasks 1–3 and 5–8; 11 for the full pass).

---

## Out of scope (recorded for follow-up)

1. **Paper ↔ code consistency pass**: verify the paper against the actual Scala implementation (e.g., the MPE channel
   allocator revised in commit `c23ad7c`) — the paper declares itself "a reference for software implementations".
2. The spec's Reset All Controllers CC number was already corrected (CC #127 → CC #121) in commit `62a704d`; nothing
   left to do.

## Verified as correct by the review (no action needed)

Zone structure (Master Channels 1/16, members ascending from 2 / descending from 15); MCM = RPN 00 06 mechanics,
zero-member deactivation, most-recent-message precedence, up-to-15-members rule, and reset-on-reconfiguration
obligations; default Pitch Bend Sensitivity ±2 Master / ±48 Member; the Note On setup ordering Pitch Bend → CC #74 →
Channel Pressure → Note On; Channel Pressure = 0 at onset; Master-Channel note forwarding legality;
PKP→Channel-Pressure conversion legality; Master Pitch Bend passthrough; state retention mirroring spec §3.3; reverting
to Non-MPE Input Mode when MPE is off (legal — manufacturer-defined); dual 7-member zones matching the spec's Example
Two; Appendix A group-size arithmetic; worked examples 9.2–9.3 (modulo M3's knock-on in 9.2 and P3).

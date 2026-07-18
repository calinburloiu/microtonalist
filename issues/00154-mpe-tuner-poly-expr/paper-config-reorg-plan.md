# MPE Tuner Paper Config Reorg — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax
> for tracking.

**Goal:** Execute the reorg specified in `paper-config-reorg-design.md` — extract Configuration into a new
dedicated Section 4 and dissolve Section 8 — on `docs/architecture/tuner/mpe-tuner-paper.md`, with every
insertion and removal spelled out verbatim below.

**Architecture:** Content edits (Tasks 2–6) happen first, in **current** section numbering, with one
sentinel: references to the new Configuration section are written `Section C` / `C.1` / `C.2` / `C.3`.
The renumber (Task 7) is a single final sweep — a one-pass map over `Section N` references and headers,
then a `C → 4` pass — verified against a before/after reference-count snapshot. This realizes the design's
"renumber last, resolve by meaning" rule: at sweep time every number still means its current section, so a
one-pass map *is* resolution by meaning.

**Tech Stack:** Markdown, `perl` one-liners for the sweep, `grep` for verification. No build, no tests.

## Global Constraints

- **Markdown only.** Do not modify Scala or any other committed source. Do not read `MpeTuner` to resolve
  behavior questions — the paper is the specification.
- **Target file:** `docs/architecture/tuner/mpe-tuner-paper.md` (called "the paper" below). The design
  spec is `issues/00154-mpe-tuner-poly-expr/paper-config-reorg-design.md`.
- **Maintain the paper's technical academic tone** and its capitalization of MPE terms (Zone, Master
  Channel, Member Channel, Pitch Bend, Tuning Pitch Bend, Expression Pitch Bend, Expression Values,
  Tuning, Tuner). Generic "input mode" stays lowercase; the modes themselves are "MPE Input Mode" /
  "Non-MPE Input Mode".
- **Numbering convention:** all text inserted by Tasks 2–6 uses **current** section numbers. References
  to the new Configuration section use the **C sentinel** (`Section C`, `Section C.1`, `Section C.2`,
  `Section C.3`). Only Task 7 renumbers.
- **Never touch `[1, §N]`** — those cite the MPE Specification, not the paper.
- Every `Remove:`/`Add:` block below is exact text. If an old string fails to match, **stop and
  re-verify against the file** — do not improvise a similar edit.
- Work on branch `doc/mpe-paper-config-reorg`. Commit after each task with the `[#154]` prefix.

---

### Task 1: Commit the pending working-tree changes

Two files are already modified in the working tree and must be committed before the reorg edits start,
as two separate commits.

**Files:**
- Commit: `docs/architecture/tuner/mpe-tuner-paper.md` (two one-line citation-notation fixes)
- Commit: `issues/00154-mpe-tuner-poly-expr/paper-config-reorg-design.md` (review fixes + decisions)
- Commit: `issues/00154-mpe-tuner-poly-expr/paper-config-reorg-plan.md` (this plan)

**Interfaces:**
- Produces: a clean working tree whose paper text is the baseline every `Remove:` block in Tasks 2–7
  matches.

- [x] **Step 1: Verify the expected diff**

Run: `git status --short && git diff --stat`
Expected: exactly the three files above modified/untracked, nothing else.

- [x] **Step 2: Commit the paper citation fixes**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Use [1, §N] citation form for in-prose MPE spec references"
```

- [x] **Step 3: Commit the design spec update and this plan**

```bash
git add issues/00154-mpe-tuner-poly-expr/paper-config-reorg-design.md \
        issues/00154-mpe-tuner-poly-expr/paper-config-reorg-plan.md
git commit -m "[#154] Resolve review findings in config reorg design spec; add implementation plan"
```

---

### Task 2: Extract Configuration into new Section C (Work item 1)

Removes Section 3.3 (Zones) from the Architecture chapter, inserts the new Configuration section between
Section 3 and Section 4, and repoints the seven surviving `Section 3.3` references (the other two die
with Section 8 in Task 6). Also repoints the `(Section 8)` reference in 3.4 item 4 to the new PBS
subsection. The C.1 text carries the design's one-way-switch decision — MCMs that deactivate all Zones
no longer revert the Tuner to Non-MPE Input Mode — the reorg's one deliberate behavior change.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

**Interfaces:**
- Produces: sections `## C. Configuration`, `### C.1 Input Mode`, `### C.2 Zones`,
  `### C.3 Pitch Bend Sensitivity`; consumed by Task 7's `C → 4` pass. Task 6's coherence sentence
  references `Section C`.

- [x] **Step 1: Remove Section 3.3 Zones**

Position: Section 3, between 3.2 and 3.4.

Remove (replace with the `### 3.4` heading alone, i.e. delete everything before it):

```markdown
### 3.3 Zones

The MPE Tuner has one Zone configuration, shared by its input and its output: the number of Member Channels allocated to the Lower Zone and, optionally, to the Upper Zone. As in the MPE Specification, one or two Zones may be defined. The role this shared configuration plays depends on the input mode:

- In **MPE Input Mode**, the Zone configuration applies to both input and output: the Tuner expects the input stream to be organized according to the configured Zones, and produces output organized by the same Zones. Notes received on the Member Channels of an input Zone are allocated to Member Channels of the same output Zone.
- In **Non-MPE Input Mode**, the input has no Zone structure, so the Zone configuration affects the output only. Furthermore, only one Zone is accessible from the output: input notes are routed exclusively to the Lower Zone if it is enabled, otherwise to the Upper Zone. When two Zones are defined, the Upper Zone is ignored. This restriction prevents ambiguity in channel allocation and zone-level message routing when the input carries no Zone information of its own.

The Zone configuration may be established and changed in two ways: through the non-MIDI configuration interface, or in-band, through an MCM received on a Master Channel. Conforming to the MPE Specification, an MCM received on any channel other than a Master Channel is invalid and is ignored [1, §2.1.1]. Upon receiving a valid MCM, the Tuner switches to MPE Input Mode if it is not already in it (Section 3.1) and reconfigures the addressed Zone to the received number of Member Channels, applying the specification's rules: an MCM with zero Member Channels deactivates the Zone, and channels claimed from the other Zone are reassigned to the Zone configured most recently [1, §2.1.1]. When MCMs deactivate all Zones, MPE operation is off [1, §2.1]; the Tuner then reverts to Non-MPE Input Mode, restoring the output Zone configuration provided through the configuration interface.

Whenever the Zone configuration changes — through either mechanism — the Tuner emits the corresponding MCM(s) on its output, so that the receiving instrument adopts the same Zone structure (Section 8). The reconfiguration also resets the Tuner's state for every channel entering or leaving MPE control, mirroring the receiver obligations of the MPE Specification [1, §2.1.4]: active notes on the affected channels are dropped, the channels' group assignments (Section 4.2) are cleared, and the retained Expression Values and remembered input-channel control values (Section 6) return to their defaults — Expression Pitch Bend 0, Channel Pressure 0, and CC #74 64. Channels of a Zone untouched by the reconfiguration keep their notes and state. For the dropped notes, an implementation may either emit no Note Off messages — relying on the downstream receiver's obligation to stop ongoing notes upon receiving the MCM [1, §2.1.4] — or emit explicit Note Off messages before the MCM, for robustness with receivers that do not fully conform; the choice is left to the implementer.

### 3.4 Non-MPE to MPE Conversion
```

Add:

```markdown
### 3.4 Non-MPE to MPE Conversion
```

- [x] **Step 2: Insert the Configuration section**

Position: between the end of Section 3 (after 3.5) and Section 4, i.e. anchored on the `---` +
`## 4. Allocation of Notes to Member Channels` boundary.

Remove:

```markdown
---

## 4. Allocation of Notes to Member Channels
```

Add:

```markdown
---

## C. Configuration

The MPE Tuner's operation is governed by three configuration parameters, established before any note flows and applying across every stage of the pipeline: the input mode (Section C.1), the Zone configuration (Section C.2), and the Pitch Bend Sensitivity (Section C.3). All three follow the same model: the parameter is established through a **non-MIDI configuration interface**, and may additionally be overridden in-band, through MIDI messages received on the input — MCMs for the input mode and the Zones, and Pitch Bend Sensitivity messages (RPN 00 00) for the Pitch Bend Sensitivity — subject to the per-parameter limitations stated in the subsections below. Where the MPE Specification defines defaults, notably the Pitch Bend Sensitivity values of Section 2.3, the Tuner adopts them in the absence of explicit configuration.

### C.1 Input Mode

Section 3.2 defines the two input modes; this subsection specifies how the operating mode is selected. The input mode may be set through the non-MIDI configuration interface, or switched in-band by the input stream itself (Section 3.1): upon receiving a valid MCM, the Tuner switches to MPE Input Mode if it is not already in it. The in-band switch is one-way: no MCM returns the Tuner to Non-MPE Input Mode, which is re-entered only through the non-MIDI configuration interface.

MCMs that deactivate all Zones are no exception: they too switch the Tuner to MPE Input Mode if necessary, and they turn MPE operation off [1, §2.1]. The Zone configuration is shared by the input and the output (Section C.2), so all Zones being deactivated leaves the output without MPE as well; the deactivation is mirrored on the output like any other Zone change (Section C.2), and the Tuner produces no note output until a Zone is re-activated, by a subsequent MCM or through the non-MIDI configuration interface.

### C.2 Zones

The MPE Tuner maintains a single Zone configuration shared by its input and its output — not separate input and output configurations: the number of Member Channels allocated to the Lower Zone and, optionally, to the Upper Zone. As in the MPE Specification, one or two Zones may be defined. The role this shared configuration plays depends on the input mode:

- In **MPE Input Mode**, the Zone configuration applies to both input and output: the Tuner expects the input stream to be organized according to the configured Zones, and produces output organized by the same Zones. Notes received on the Member Channels of an input Zone are allocated to Member Channels of the same output Zone.
- In **Non-MPE Input Mode**, the input has no Zone structure, so the Zone configuration affects the output only. Furthermore, only one Zone is accessible from the output: input notes are routed exclusively to the Lower Zone if it is enabled, otherwise to the Upper Zone. When two Zones are defined, the Upper Zone is ignored. This restriction prevents ambiguity in channel allocation and zone-level message routing when the input carries no Zone information of its own.

Beyond the non-MIDI configuration interface, the Zone configuration may be changed in-band, through an MCM received on a Master Channel. Conforming to the MPE Specification, an MCM received on any channel other than a Master Channel is invalid and is ignored [1, §2.1.1]. A valid MCM — which also switches the Tuner to MPE Input Mode if it is not already in it (Section C.1) — reconfigures the addressed Zone to the received number of Member Channels, applying the specification's rules: an MCM with zero Member Channels deactivates the Zone, and channels claimed from the other Zone are reassigned to the Zone configured most recently [1, §2.1.1]. An MCM that deactivates all Zones suspends MPE operation altogether (Section C.1).

The Tuner emits the MCM(s) describing its Zone configuration at start-up, and again whenever the configuration changes — through either mechanism — so that the receiving instrument adopts the same Zone structure. A reconfiguration also resets the Tuner's state for every channel entering or leaving MPE control, mirroring the receiver obligations of the MPE Specification [1, §2.1.4]: active notes on the affected channels are dropped, the channels' group assignments (Section 4.2) are cleared, the retained Expression Values and remembered input-channel control values (Section 6) return to their defaults — Expression Pitch Bend 0, Channel Pressure 0, and CC #74 64 — and Pitch Bend Sensitivity returns to the specification's defaults of ±48 semitones on Member Channels and ±2 semitones on the Master Channel (Section C.3). Channels of a Zone untouched by the reconfiguration keep their notes and state. For the dropped notes, an implementation may either emit no Note Off messages — relying on the downstream receiver's obligation to stop ongoing notes upon receiving the MCM [1, §2.1.4] — or emit explicit Note Off messages before the MCM, for robustness with receivers that do not fully conform; the choice is left to the implementer.

### C.3 Pitch Bend Sensitivity

The MPE Specification defines default Pitch Bend Sensitivity values — ±48 semitones for Member Channels and ±2 semitones for the Master Channel — and makes the MCM the trigger that applies them: upon receiving an MCM, a receiver must reset the Pitch Bend Sensitivity of the affected channels to these defaults (Section 2.3) [1, §2.4]. The MPE Tuner relies on these defaults both when interpreting incoming Pitch Bend and when encoding Pitch Bend on its output.

The Tuner also listens for Pitch Bend Sensitivity messages (RPN 00 00) on its input and conforms to them when interpreting incoming Pitch Bend. How a received message propagates to the output depends on the input mode:

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
  Member Channels retain the specification's default of ±48 semitones, which can be changed only through the
  non-MIDI configuration interface.

---

## 4. Allocation of Notes to Member Channels
```

Notes against the design spec: the C.2 text keeps every 3.3 fact except the revert clause, which the
design's one-way-switch decision replaces in C.1 (the reorg's one deliberate behavior change), and
clarifies the opening sentence (a single shared Zone *configuration*, not a single Zone); the remaining
mode-switching clauses move to C.1; the dual-source sentence generalizes into the C preamble; the
`(Section 8)` cross-reference is dropped and replaced by the absorbed start-up-MCM fact (inventory
fact 2); PBS joins the reconfiguration reset list; C.3 carries inventory facts 6–9 and 11 with the
causality corrected and the `n²` bullet verbatim (only "the MCM's default of ±48 semitones" becomes "the
specification's default of ±48 semitones") — the revert-path limitation paragraph is gone with the
revert itself.

- [x] **Step 3: Repoint the seven surviving `Section 3.3` references**

Seven `Edit` operations (fragments are unique after Step 1). The overview sentences in 1.4 and 3.1
item 1 stay unconditionally true under the design's one-way-switch decision, so edits 1 and 2 are plain
repoints:

1. Section 1.4 — Remove: `reconfigure its Zones accordingly (Section 3.3).`
   Add: `reconfigure its Zones accordingly (Section C).`
2. Section 3.1 item 1 — Remove: `reconfigure its Zones according to the message (Section 3.3).`
   Add: `reconfigure its Zones according to the message (Section C).`
3. Section 3.2, Non-MPE bullet (both references on the same line) —
   Remove: `is routed to a single output Zone (Section 3.3). In this mode the Zone configuration originates solely from the non-MIDI configuration interface (Section 3.3).`
   Add: `is routed to a single output Zone (Section C.2). In this mode the Zone configuration originates solely from the non-MIDI configuration interface (Section C.2).`
4. Section 3.2, MPE bullet — Remove: `which reconfigure the Tuner's Zones (Section 3.3).`
   Add: `which reconfigure the Tuner's Zones (Section C.2).`
5. Section 3.4 item 2 — Remove: `selected for non-MPE input (see Section 3.3), where`
   Add: `selected for non-MPE input (see Section C.2), where`
6. Section 5.1 — Remove: `emission upon Zone reconfiguration is a distinct case, governed by Section 3.3.)`
   Add: `emission upon Zone reconfiguration is a distinct case, governed by Section C.2.)`
7. Section 3.4 item 4 (PBS sentence; per the design's repoint table this goes to the PBS subsection) —
   Remove: `is redirected (Section 8).`
   Add: `is redirected (Section C.3).`

- [x] **Step 4: Verify**

```bash
grep -c "Section 3.3" docs/architecture/tuner/mpe-tuner-paper.md   # expect 1 (Section 8 intro; dies in Task 6)
grep -c "^### C\." docs/architecture/tuner/mpe-tuner-paper.md      # expect 3
grep -c "^## C\. Configuration" docs/architecture/tuner/mpe-tuner-paper.md  # expect 1
grep -c "(Section 8)" docs/architecture/tuner/mpe-tuner-paper.md   # expect 0
```

- [x] **Step 5: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Extract Configuration into a dedicated section (reorg WI1)"
```

---

### Task 3: Velocity-0 convention and channel-reuse rule into Allocation (Work items 2, 5-part)

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

- [x] **Step 1: Add the velocity-0 convention to the Section 4 preamble**

Position: Section 4, end of the scoping paragraph, before `### 4.1 Fundamental Invariant`. Phrased as a
global convention (it also governs Section 6's averaging), one sentence, no block quote, no
self-justification.

Remove:

```markdown
allocation procedure described below.

### 4.1 Fundamental Invariant
```

Add:

```markdown
allocation procedure described below.

Throughout this paper, a Note On with velocity 0 is treated as a Note Off [1, §3.3.2].

### 4.1 Fundamental Invariant
```

- [x] **Step 2: Add the channel-reuse rule to 4.2 Dual-Group Channel Partitioning**

Position: 4.2, end of the group-assignment paragraph — the reuse rule is the same occupancy lifecycle as
the persistence rule, stated from the other end.

Remove:

```markdown
determined at the moment a note is placed on it and persists for the lifetime of that channel's occupancy.
```

Add:

```markdown
determined at the moment a note is placed on it and persists for the lifetime of that channel's occupancy. The
channel becomes available for reuse once all its notes have received Note Off messages.
```

- [x] **Step 3: Verify**

```bash
grep -c "velocity 0" docs/architecture/tuner/mpe-tuner-paper.md          # expect 2 (new + 8.3; 8.3 dies in Task 6)
grep -c "available for reuse" docs/architecture/tuner/mpe-tuner-paper.md # expect 2 (same)
```

- [x] **Step 4: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Move velocity-0 convention and channel-reuse rule into Allocation (reorg WI2, WI5)"
```

---

### Task 4: Broaden 3.5 into "Master Channel Forwarding" (Work item 3)

Retitles 3.5, makes it own the passthrough mechanic for notes, Pitch Bend, and Zone-level controls;
splits 4.7.5 (mechanic moves out, quote and argument stay); repoints 3.4 item 4's two `Section 8.2`
references.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

- [ ] **Step 1: Retitle 3.5**

Remove: `### 3.5 Master Channel Note Forwarding`
Add: `### 3.5 Master Channel Forwarding`

- [ ] **Step 2: Absorb the Pitch Bend mechanic and Zone-level messages; widen the closing line**

Position: end of 3.5, after rationale item 3, replacing the current closing paragraph.

Remove:

```markdown
   the ability to use Polyphonic Key Pressure on those notes.

In Non-MPE Input Mode the concept of a Master Channel does not apply to the input: every incoming note is allocated to a
Member Channel regardless of the input channel number.
```

Add:

```markdown
   the ability to use Polyphonic Key Pressure on those notes.

The forwarding rule extends beyond notes. The MPE Tuner forwards Master Channel Pitch Bend as received, without
modification; Master Pitch Bend is a Zone-level expressive control, and Section 4.7.5 discusses its relationship to
tuning. Zone-level messages — Damper Pedal, Program Change, Reset All Controllers, and the other messages listed in
Table 1 of the MPE Specification (Section 2.6) — are likewise forwarded on the Master Channel without modification.
The MPE Tuner does not interpret or alter any of these messages.

In Non-MPE Input Mode there is no input Master Channel to forward from: every incoming note is allocated to a Member
Channel regardless of the input channel number, and the input's channel-global controls and Zone-level messages reach
the output Master Channel by *redirection* instead, under the conversion rules of Section 3.4, items 2 and 4.
```

- [ ] **Step 3: Split 4.7.5 — mechanic out, argument and quote stay**

Remove:

```markdown
The MPE Tuner forwards Master Channel Pitch Bend as received, without modification. Master Pitch Bend is not used by the Tuner in computing tuning offsets; it is a Zone-level expressive control, belonging entirely to the performer. The Tuner's tuning offsets are applied exclusively through the Tuning Pitch Bend component of Member Channel Pitch Bend.
```

Add:

```markdown
Master Channel Pitch Bend is forwarded without modification, as part of Master Channel forwarding (Section 3.5). It is not used by the Tuner in computing tuning offsets; it is a Zone-level expressive control, belonging entirely to the performer. The Tuner's tuning offsets are applied exclusively through the Tuning Pitch Bend component of Member Channel Pitch Bend.
```

Do **not** touch 4.7.4.

- [ ] **Step 4: Repoint 3.4 item 4's two `Section 8.2` references**

Remove:

```markdown
   where they act as Zone-level messages (Section 8.2). Non-MPE input has no Master Channel of its own from which
   Section 8.2's forwarding could operate;
```

Add:

```markdown
   where they act as Zone-level messages (Section 3.5). Non-MPE input has no Master Channel of its own from which
   Section 3.5's forwarding could operate;
```

- [ ] **Step 5: Verify**

```bash
grep -c "Master Channel Note Forwarding" docs/architecture/tuner/mpe-tuner-paper.md  # expect 0
grep -c "Section 8.2" docs/architecture/tuner/mpe-tuner-paper.md                     # expect 0 (8.2's own heading doesn't match)
```

- [ ] **Step 6: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Broaden 3.5 into Master Channel Forwarding (reorg WI3)"
```

---

### Task 5: Expression Value Processing additions (Work items 4, 5-part, 6)

Adds the Note Off removal rule to 6.1, creates 6.1.1 Message Ordering, repoints 6.1's and 6.3's `Section
8.1` references, and creates 6.4 Channel Pressure Reset at Note Off.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

- [ ] **Step 1: Add the Note Off removal rule to 6.1**

Position: 6.1, between the averaging paragraph and the retention paragraph (the removal rule is why the
retention rule has a moment to apply). The `(Section 6.1)` self-reference from the 8.3 original is
dropped; the `[1, §3.3.3]` quote is kept.

Remove:

```markdown
component.

When the last active note
```

Add:

```markdown
component.

Upon Note Off, per-note control of the released note ceases: the note is removed from its channel's Expression Value
averages, consistent with the specification's statement that "control of a note ceases once Note Off has occurred"
[1, §3.3.3].

When the last active note
```

- [ ] **Step 2: Repoint 6.1's omission-optimization reference**

Remove: `the values the channel already holds (Section 8.1).`
Add: `the values the channel already holds (Section 6.1.1).`

- [ ] **Step 3: Insert 6.1.1 Message Ordering**

Position: end of 6.1, before `### 6.2`. The trailing "In particular, in Non-MPE Input Mode CC #74 is
never emitted…" sentence of 8.1 is **not** carried over (restatement of 6.3 and 3.4 item 3).

Remove:

```markdown
the new value is sent on that channel.

### 6.2 MPE Input Mode
```

Add:

```markdown
the new value is sent on that channel.

#### 6.1.1 Message Ordering

For each new note, the MPE Tuner outputs messages in the following order on the assigned Member Channel:

1. **Pitch Bend**: encoding the sum of the Tuning Pitch Bend and the initial averaged Expression Pitch Bend.
2. **CC #74**: the timbre Expression Value.
3. **Channel Pressure**: the Channel Pressure Expression Value.
4. **Note On**: the note message itself.

This ordering follows the MPE Specification's recommendation [1, §3.3.1] and ensures that the receiving instrument has the correct pitch and articulation state before the note begins sounding.

An implementation may omit any of the three control dimension messages whose value is unchanged since its last emission on that output channel, relying on the state retention rule of Section 6.1.

### 6.2 MPE Input Mode
```

- [ ] **Step 4: Repoint 6.3's `Section 8.1` reference**

Remove: `the dimension is thus controllable only globally (Section 8.1).`
Add: `the dimension is thus controllable only globally (Section 3.4, item 3).`

- [ ] **Step 5: Insert 6.4 Channel Pressure Reset at Note Off**

Position: end of Section 6, after 6.3's last paragraph, before the `---` + `## 7.` boundary. Verbatim
move of the 8.3 paragraph (working-tree wording).

Remove:

```markdown
every output channel whose average changes receives the updated value immediately.

---

## 7. Real-Time Tuning Changes
```

Add:

```markdown
every output channel whose average changes receives the updated value immediately.

### 6.4 Channel Pressure Reset at Note Off

At Note Off, whether the Tuner emits a Channel Pressure reset depends on the input mode. The specification requires that "Channel Pressure must be set to zero immediately before a Note On or a Note Off wherever it is appropriate to the design of a controller" [1, §3.3.4].

- In **MPE Input Mode** the output Channel Pressure passes through from the input sender, so the Tuner emits no reset of its own — it inherits the sender's behavior: a conforming sender's pre-release reset propagates to the output through the update mechanism of Section 6.2, and if the sender emits none, neither does the Tuner.
- In **Non-MPE Input Mode** the per-note Channel Pressure on an output Member Channel is the Tuner's own, synthesized from the input's Polyphonic Key Pressure (Section 3.4); here the Tuner is the controller to which the MPE Specification requirement [1, §3.3.4] applies, so it performs the reset itself, returning the channel's Channel Pressure to 0 as Section 6.3 requires.

Deferring to the sender in MPE Input Mode and resetting in Non-MPE Input Mode both fall within the specification's "wherever it is appropriate" qualifier and are documented design choices.

---

## 7. Real-Time Tuning Changes
```

- [ ] **Step 6: Verify**

```bash
grep -c "Section 8.1" docs/architecture/tuner/mpe-tuner-paper.md   # expect 1 (3.4 item 3; fixed in Task 6)
grep -c "^#### 6.1.1" docs/architecture/tuner/mpe-tuner-paper.md   # expect 1
grep -c "^### 6.4" docs/architecture/tuner/mpe-tuner-paper.md      # expect 1
```

- [ ] **Step 7: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Move message ordering and Note Off facts into Expression Value Processing (reorg WI4-WI6)"
```

---

### Task 6: Dissolve Section 8; add the conformance pointer to 1.4

Every Section 8 fact now lives at its destination (Tasks 2–5); what remains is restatement (inventory
facts 1, 3–5, 10, 13-merged, 14, 17, 20). Delete the section, fix the last `8.1` reference, and add the
design's coherence mitigation to Section 1.4.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

- [ ] **Step 1: Repoint 3.4 item 3's reference**

Remove: `Member Channel (Sections 6.3 and 8.1).`
Add: `Member Channel (Section 6.3).`

- [ ] **Step 2: Delete Section 8 in its entirety**

Remove (ends by consuming Section 8's trailing `---`; the rule preceding `## 8.` remains as the
separator before `## 9.`):

```markdown
## 8. MPE Tuner Output Conformance

The output of the MPE Tuner conforms to the MPE Specification. The following features behave exactly as the MPE Specification defines them, with no Tuner-specific behavior:

- **Zone Configuration**: the MPE Tuner outputs MPE Configuration Messages to establish the Zone structure on the receiving instrument, supporting both single-Zone and dual-Zone configurations [1, §2.1], emitting them at start-up and on every Zone reconfiguration (Section 3.3). It likewise listens for MCMs on its input and conforms to them, reconfiguring its own Zones as specified in Section 3.3; in this case it also configures the Zones of the output instrument by forwarding that configuration.
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

The subsections below specify the behaviors where the MPE Tuner adds detail beyond the specification.

### 8.1 Message Ordering

For each new note, the MPE Tuner outputs messages in the following order on the assigned Member Channel:

1. **Pitch Bend**: encoding the sum of the Tuning Pitch Bend and the initial averaged Expression Pitch Bend.
2. **CC #74**: the timbre Expression Value.
3. **Channel Pressure**: the Channel Pressure Expression Value.
4. **Note On**: the note message itself.

This ordering follows the MPE Specification's recommendation [1, §3.3.1] and ensures that the receiving instrument has the correct pitch and articulation state before the note begins sounding.

An implementation may omit any of the three control dimension messages whose value is unchanged since its last emission on that output channel, relying on the state retention of Section 6.1. In particular, in Non-MPE Input Mode CC #74 is never emitted on a Member Channel: the dimension cannot be controlled per note in that mode and is available only globally, on the Master Channel (Section 6.3).

### 8.2 Zone-Level Messages

Zone-level messages (Damper Pedal, Program Change, Reset All Controllers, and other messages listed in Table 1 of the MPE Specification) are forwarded on the Master Channel without modification. The MPE Tuner does not interpret or alter these messages.

### 8.3 Note Off Behavior

Upon Note Off, per-note control of the released note ceases: the note is removed from its channel's Expression Value averages (Section 6.1), consistent with the specification's statement that "control of a note ceases once Note Off has occurred" [1, §3.3.3]. While other notes remain active on the channel, the Tuner continues to update the channel's Pitch Bend — for tuning changes (Section 7) as well as for Expression Value changes (Section 6). The channel becomes available for reuse once all its notes have received Note Off messages.

A Note On with velocity 0 in the input is treated as a Note Off, following the MIDI 1.0 shorthand and the specification's recommendation "that this message be interpreted as Note Off velocity 64" [1, §3.3.2]. Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and channel reuse all depend on detecting note releases.

At Note Off, whether the Tuner emits a Channel Pressure reset depends on the input mode. The specification requires that "Channel Pressure must be set to zero immediately before a Note On or a Note Off wherever it is appropriate to the design of a controller" [1, §3.3.4]. In MPE Input Mode the output Channel Pressure passes through from the input sender, so the Tuner emits no reset of its own — it inherits the sender's behavior: a conforming sender's pre-release reset propagates to the output through the update mechanism of Section 6.2, and if the sender emits none, neither does the Tuner. In Non-MPE Input Mode the per-note Channel Pressure on an output Member Channel is the Tuner's own, synthesized from the input's Polyphonic Key Pressure (Section 3.4); here the Tuner is the controller to which the MPE Specification requirement [1, §3.3.4] applies, so it performs the reset itself, returning the channel's Channel Pressure to 0 as Section 6.3 requires. Deferring to the sender in MPE Input Mode and resetting in Non-MPE Input Mode both fall within the specification's "wherever it is appropriate" qualifier and are documented design choices.

---

## 9. Worked Examples
```

Add:

```markdown
## 9. Worked Examples
```

- [ ] **Step 3: Add the conformance pointer to Section 1.4**

Position: end of 1.4, as a new paragraph after the paragraph edited in Task 2 Step 3 (which now ends
`…reconfigure its Zones accordingly (Section C).`). This is the design's accepted mitigation for the
dissolution's coherence cost.

Remove:

```markdown
reconfigure its Zones accordingly (Section C).

---

## 2. Background: The MPE Specification
```

Add:

```markdown
reconfigure its Zones accordingly (Section C).

The obligations of output conformance to the MPE Specification are specified where they arise: configuration of Zones and Pitch Bend Sensitivity in Section C, Note On message ordering in Section 6.1.1, Master Channel forwarding in Section 3.5, Note Off behavior in Section 6.1, and the Channel Pressure reset at Note Off in Section 6.4.

---

## 2. Background: The MPE Specification
```

- [ ] **Step 4: Verify**

```bash
grep -c "Section 8" docs/architecture/tuner/mpe-tuner-paper.md   # expect 0
grep -c "^## 8\." docs/architecture/tuner/mpe-tuner-paper.md     # expect 0
grep -c "velocity 0" docs/architecture/tuner/mpe-tuner-paper.md  # expect 1
grep -c "Section 3.3" docs/architecture/tuner/mpe-tuner-paper.md # expect 0
```

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Dissolve Output Conformance section; add conformance pointer to 1.4 (reorg WI2-WI6)"
```

---

### Task 7: Renumber sections and cross-references

The single final sweep, per the design's [Numbering after the reorg]. All content is final; only numbers
change. Take a reference-count snapshot first — it is the verification baseline.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`

**Interfaces:**
- Consumes: the C-sentinel sections and references produced by Tasks 2–6.

- [ ] **Step 1: Snapshot reference counts (before)**

```bash
SCRATCH=/private/tmp/claude-501/-Users-calinburloiu-Development-microtonalist/fbfe8223-7c10-46fd-89b0-082b5a5e89c1/scratchpad
PAPER=docs/architecture/tuner/mpe-tuner-paper.md
count_refs() {
  perl -0777 -ne 'while (/Sections?\s+([C\d]+(?:\.\d+)*)(?:(?:,\s*|\s+and\s+)([C\d]+(?:\.\d+)*))?(?:\s+and\s+([C\d]+(?:\.\d+)*))?/g) { for my $s ($1,$2,$3) { print "$s\n" if defined $s } }' "$1" | sort | uniq -c | awk "{print \$2, \$1}" | sort
}
count_refs "$PAPER" > "$SCRATCH/refs-before.txt"
cat "$SCRATCH/refs-before.txt"
```

- [ ] **Step 2: Hand-fix the three plural constructs**

The sweep regex renumbers only the first number after `Section(s)`; the paper's three
`(Sections 5.2.2 and 5.2.3)` constructs get explicit edits to their **target** numbers first. This is
safe: `6.2.2`/`6.2.3` are not keys of the sweep map, so Step 3 will not re-map them.

Edit (replace_all): Remove `(Sections 5.2.2 and 5.2.3)` → Add `(Sections 6.2.2 and 6.2.3)`
(3 occurrences: Section 4.5 algorithm step 3, Section 5 preamble, Section 5.2 preamble).

- [ ] **Step 3: Run the sweep (references + headers, one pass each)**

One-pass hash map — no chained replacements possible. Slurp mode (`-0777`) so line-wrapped references
(e.g. `Section\n4.6`) are caught, preserving the whitespace.

```bash
perl -0777 -pi -e '
  BEGIN { our %m = (
    "3.4"=>"3.3", "3.5"=>"3.4",
    "4"=>"5", "4.1"=>"5.1", "4.2"=>"5.2", "4.3"=>"5.3", "4.4"=>"5.4", "4.5"=>"5.5", "4.6"=>"5.6",
    "4.7"=>"5.7", "4.7.1"=>"5.7.1", "4.7.2"=>"5.7.2", "4.7.3"=>"5.7.3", "4.7.4"=>"5.7.4", "4.7.5"=>"5.7.5",
    "5"=>"6", "5.1"=>"6.1", "5.2"=>"6.2", "5.2.1"=>"6.2.1", "5.2.2"=>"6.2.2", "5.2.3"=>"6.2.3", "5.3"=>"6.3",
    "6"=>"7", "6.1"=>"7.1", "6.1.1"=>"7.1.1", "6.2"=>"7.2", "6.3"=>"7.3", "6.4"=>"7.4",
    "7"=>"8"
  ); }
  s/(Sections?)(\s+)(\d+(?:\.\d+)*)/$1 . $2 . ($m{$3} \/\/ $3)/ge;
  s/^(\#{2,4} )(\d+(?:\.\d+)*)/$1 . ($m{$2} \/\/ $2)/gme;
' docs/architecture/tuner/mpe-tuner-paper.md
```

Map notes: `3.3` is deliberately **absent** (no `Section 3.3` text remains after Task 6; old 3.4/3.5
slide down to fill Zones' slot). `[1, §N]` citations never match (`Section` prefix required; headers
carry no `§`).

- [ ] **Step 4: Run the C → 4 pass**

```bash
perl -0777 -pi -e '
  s/^## C\. Configuration/## 4. Configuration/m;
  s/^### C\./### 4./gm;
  s/Section C\.([123])/Section 4.$1/g;
  s/Section C\b/Section 4/g;
' docs/architecture/tuner/mpe-tuner-paper.md
```

- [ ] **Step 5: Verify — greps, count diff, header list**

(a) No dangling sentinels or dissolved-section references, and no references to numbers that no longer
exist (`Section 3.5` and `Section 4.7*` have no post-reorg referent):

```bash
grep -nE "Section C|## C\.|### C\.|Section 8\b|Section 8\.|Section 3\.5|Section 4\.7" docs/architecture/tuner/mpe-tuner-paper.md
```

Expected: no output.

(b) Reference-count diff — map the before-snapshot through the same table and compare. (Shell state does
not persist between tool calls: redefine the helpers first.)

```bash
SCRATCH=/private/tmp/claude-501/-Users-calinburloiu-Development-microtonalist/fbfe8223-7c10-46fd-89b0-082b5a5e89c1/scratchpad
PAPER=docs/architecture/tuner/mpe-tuner-paper.md
count_refs() {
  perl -0777 -ne 'while (/Sections?\s+([C\d]+(?:\.\d+)*)(?:(?:,\s*|\s+and\s+)([C\d]+(?:\.\d+)*))?(?:\s+and\s+([C\d]+(?:\.\d+)*))?/g) { for my $s ($1,$2,$3) { print "$s\n" if defined $s } }' "$1" | sort | uniq -c | awk "{print \$2, \$1}" | sort
}
count_refs "$PAPER" > "$SCRATCH/refs-after.txt"
perl -ne '
  BEGIN { %m = (
    "3.4"=>"3.3", "3.5"=>"3.4",
    "4"=>"5", "4.1"=>"5.1", "4.2"=>"5.2", "4.3"=>"5.3", "4.4"=>"5.4", "4.5"=>"5.5", "4.6"=>"5.6",
    "4.7"=>"5.7", "4.7.1"=>"5.7.1", "4.7.2"=>"5.7.2", "4.7.3"=>"5.7.3", "4.7.4"=>"5.7.4", "4.7.5"=>"5.7.5",
    "5"=>"6", "5.1"=>"6.1", "5.2"=>"6.2", "5.2.1"=>"6.2.1", "5.2.2"=>"6.2.2", "5.2.3"=>"6.2.3", "5.3"=>"6.3",
    "6"=>"7", "6.1"=>"7.1", "6.1.1"=>"7.1.1", "6.2"=>"7.2", "6.3"=>"7.3", "6.4"=>"7.4",
    "7"=>"8",
    "C"=>"4", "C.1"=>"4.1", "C.2"=>"4.2", "C.3"=>"4.3"
  ); }
  my ($n, $c) = split; $e{$m{$n} // $n} += $c;
  END { print "$_ $e{$_}\n" for sort keys %e }
' "$SCRATCH/refs-before.txt" | sort > "$SCRATCH/refs-expected.txt"
diff "$SCRATCH/refs-expected.txt" "$SCRATCH/refs-after.txt" && echo COUNTS-OK
```

Expected: `COUNTS-OK` (empty diff).

(c) Header list must equal exactly:

```bash
grep -E "^#{1,4} " docs/architecture/tuner/mpe-tuner-paper.md
```

```
# MPE Tuner: A MIDI Polyphonic Expression Approach to Microtonal Intonation
## 1. Introduction
### 1.1 Motivation
### 1.2 Scope and Definitions
### 1.3 Control Dimensions
### 1.4 Overview of Operation
## 2. Background: The MPE Specification
### 2.1 Zones, Master Channels, and Member Channels
### 2.2 Per-Note Control via Channel Assignment
### 2.3 Pitch Bend Sensitivity
### 2.4 Channel Allocation Recommendations
### 2.5 Note On Setup and Message Ordering
### 2.6 Zone-Level Messages
### 2.7 Pressure
## 3. MPE Tuner Architecture
### 3.1 Signal Flow
### 3.2 Input Modes
### 3.3 Non-MPE to MPE Conversion
### 3.4 Master Channel Forwarding
## 4. Configuration
### 4.1 Input Mode
### 4.2 Zones
### 4.3 Pitch Bend Sensitivity
## 5. Allocation of Notes to Member Channels
### 5.1 Fundamental Invariant
### 5.2 Dual-Group Channel Partitioning
### 5.3 Group Size Allocation
### 5.4 High Expression Pitch Bend
### 5.5 Allocation Algorithm
### 5.6 Expression Value Computation for Shared Channels
### 5.7 Comparison with Standard MPE Allocation
#### 5.7.1 Channel Sharing Before Exhaustion
#### 5.7.2 Prioritizing Intonation Over Note Preservation
#### 5.7.3 Gentle Degradation via Averaging
#### 5.7.4 Same Note Number on Multiple Channels
#### 5.7.5 Master Channel Pitch Bend
## 6. Dropping Notes and Freeing Channels
### 6.1 Dropping Notes Due to Channel Exhaustion
### 6.2 Dropping Notes Due to High Expression Pitch Bend
#### 6.2.1 Divergence on a Shared Channel
#### 6.2.2 New Note with High Expression Pitch Bend on an Occupied Channel
#### 6.2.3 New Note Assigned to a Channel with a High-Bend Note
### 6.3 Summary of Note-Dropping Invariants
## 7. Expression Value Processing
### 7.1 Aggregation Model
#### 7.1.1 Message Ordering
### 7.2 MPE Input Mode
### 7.3 Non-MPE Input Mode
### 7.4 Channel Pressure Reset at Note Off
## 8. Real-Time Tuning Changes
## 9. Worked Examples
### 9.1 Basic Allocation in Quarter-Comma Meantone
### 9.2 Tuning Change During Performance
### 9.3 Note Dropping Under Channel Exhaustion
## 10. Summary
## References
## Appendix A: Channel Group Allocation Table
```

- [ ] **Step 6: Note stale sibling docs (do not fix — out of scope per the design)**

```bash
rtk proxy grep -rn "mpe-tuner-paper" --include="*.md" issues/ docs/ | grep -v 00154
```

Known stale citations (record in the PR description, leave unfixed):
`issues/00202-pbs-non-mpe-input/pbs-non-mpe-and-mcm-test-mode-plan.md` (stale path + `§3.3.2` numbering)
and `issues/00143-add-mpe-tuner/plan.md` (stale path).

- [ ] **Step 7: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "[#154] Renumber sections and cross-references after config reorg"
```

---

### Task 8: Final read-through and quote integrity check

**Files:**
- Read: `docs/architecture/tuner/mpe-tuner-paper.md` (whole file)

- [ ] **Step 1: Verify the design's key quotes survived intact**

Expected occurrence counts (the "combine" quote legitimately lives in both 2.2 and 5.7.5):

```bash
for q in \
  "control of a note ceases once Note Off has occurred" \
  "it must combine the data meaningfully" \
  "to all Member Channels in the Zone" \
  "wherever it is appropriate to the design of a controller" \
  "treated as a Note Off"; do
  printf '%-60s %s\n' "$q" "$(grep -c "$q" docs/architecture/tuner/mpe-tuner-paper.md)"
done
```

Expected: count `1` for every quote, except `it must combine the data meaningfully` → `2`.

- [ ] **Step 2: Read the full paper top to bottom**

Check: every `Section N` reference resolves to a section whose content matches the sentence's intent
(spot-check especially: all `Section 4`/`4.x` references now mean Configuration, all `Section 5`/`5.x`
mean Allocation); the new Section 4 reads as one coherent section; no doubled blank lines or stray `---`
around the deleted Section 8; Appendix A untouched.

- [ ] **Step 3: Commit fixes if any were needed; otherwise done**

If the read-through surfaced fixes: commit as
`[#154] Fix renumbering fallout found in final read-through`. Otherwise no commit — the branch is ready
for PR (use the `contributing` skill when opening it).

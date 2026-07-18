# Design: MPE Tuner Paper Reorg — Extract Configuration to a Dedicated Section, Dissolve Section 8

**Target document:** `docs/architecture/tuner/mpe-tuner-paper.md`

**Citations pinned at:** commit `44730ba` (`[#154] Apply paper review fixes (P1-P13, N1-N16) to MPE Tuner
paper (#241)`) — the newest `main` commit that touched the paper, merged into this branch so the working-tree
paper matches. (The branch's merge-base `fa4e5d6` did not touch the paper — it changed only
`paper-review-plan.md` — so `44730ba`, one commit further along `main`, is the meaningful pin.)

**Purpose:** this document specifies the target design of the reorganization — *what moves where and why*. It
is the design input from which an implementation plan (out of scope for this document) and the edit will be
generated; it is not itself the edit.

**Referencing convention:** sections are identified by **number and name**. If numbering has drifted since
`44730ba`, the name is authoritative. Quoted paper text is verbatim at `44730ba` and can be located by
searching for the quote. No line numbers are used anywhere in this document, by design.

**Numbering convention in this document.** This reorg makes two structural changes: it **extracts
Configuration into a new, dedicated Section 4** (Work item 1) and **dissolves Section 8** (Work items 2–6).
Both shift the numbers of the sections between them. To stay aligned with the paper the executing prompt
reads, section numbers below are **current (`44730ba`)** — with one exception: the **new Configuration
section**, referred to by name and numbered **4** (subsections **4.1 Input Mode / 4.2 Zones / 4.3 Pitch Bend
Sensitivity**). The resulting global renumber is defined once, authoritatively, under
[Numbering after the reorg](#numbering-after-the-reorg), and applied as a final step. Keep the clash
straight: the **new Section 4 is Configuration**, while the **old Section 4 (Allocation) becomes Section 5**.
On any conflict between a number and a section name, the **name wins**.

---

## Design overview

This reorg makes **two structural changes**.

**1 — Extract Configuration into a new, dedicated Section 4.** Input Mode, Zones, and Pitch Bend Sensitivity
are gathered under a new Section 4 ("Configuration"), placed after Section 3 ("MPE Tuner Architecture") and
before Allocation. The paper is otherwise sectioned by **pipeline stage** (input → allocation →
tuning/expression → output), but configuration is not a pipeline stage: these parameters are established
*before any note flows* and govern the whole pipeline. A config-time concern does not sit on the
pipeline-stage axis, so it earns its own section rather than a subsection of Architecture — which also gives
the substantive Pitch Bend Sensitivity material proper room.

**2 — Dissolve Section 8 ("MPE Tuner Output Conformance") entirely.** Its facts relocate to their
pipeline-stage homes, and old Sections 4–7 shift up by one. Because Section 4 is inserted above where
Section 8 is removed, **Worked Examples and Summary keep their numbers (9 and 10)** (see
[Numbering after the reorg](#numbering-after-the-reorg)).

The section is not wrong to exist so much as wrong about what it contains. It tries to be two things at
once: an *output message contract* and a *conformance checklist against RP-053*. The checklist job is what
drags input-side material into a section titled "Output Conformance" — you cannot claim conformance on Zone
Configuration without describing how the Zone was obtained. The paper is otherwise sectioned by
**pipeline stage** (input → allocation → tuning/expression → output); Section 8 is sectioned by **topic**,
and the topic axis lost.

Nothing is deleted that is not a verifiable restatement of text elsewhere in the paper. Section
["Facts inventory"](#facts-inventory) below is the completeness guarantee: every fact in Section 8 is
listed with a destination or a justification for deletion.

---

## Target structure

Content changes by location. Section numbers are **current (`44730ba`)** except the new Configuration
section; the resulting renumber is in [Numbering after the reorg](#numbering-after-the-reorg).

| Location | Change |
|---|---|
| 3.2 Input Modes | unchanged (taxonomy of the two modes) |
| 3.3 **Zones** | **extracted** into the new Configuration section (Work item 1) |
| **Configuration** — *new section (4)* | preamble + **4.1 Input Mode**, **4.2 Zones**, **4.3 Pitch Bend Sensitivity** (Work item 1) |
| 3.4 Non-MPE to MPE Conversion | unchanged in scope; cross-refs repointed (renumbers to 3.3) |
| 3.5 **Master Channel Note Forwarding** | retitled **Master Channel Forwarding** — notes, Pitch Bend, Zone-level controls (Work item 3; renumbers to 3.4) |
| 4 (Allocation) preamble | gains the velocity-0 convention (Work item 2) |
| 4.2 Dual-Group Channel Partitioning | gains the channel-reuse rule (Work item 5) |
| 4.7.5 Master Channel Pitch Bend | keeps its argument; forwarding mechanic moves to Master Channel Forwarding (Work item 3) |
| 6.1 Aggregation Model | gains Note Off removal + `[1, §3.3.3]`; new **Message Ordering** subsection (Work item 4) |
| **6.4 Channel Pressure Reset at Note Off** — *new subsection* | absorbs Section 8.3's Channel Pressure reset paragraph `[1, §3.3.4]` (Work item 6) |
| **8 MPE Tuner Output Conformance** | **removed** (Work items 2–6) |

---

## Numbering after the reorg

Inserting **Configuration** as Section 4 and removing Section 8 renumbers the sections between them. Because
the insertion is above the removal, **Worked Examples (9) and Summary (10) keep their numbers**; old
Sections 4–7 shift up by one, and Section 3's tail (old 3.4, 3.5) shifts down to fill the slot Zones vacates.

This is the **single authority** for target numbers; the Work items and tables elsewhere use current
numbering. Perform it as the **final** step: after the content moves, renumber the section headers to match
the map, then update every cross-reference to the **target** number of the section it denotes — resolving by
**meaning** (the section names disambiguate), not by blind text replacement.

**Watch the Section 4 clash:** the *new* Section 4 is **Configuration**; the *old* Section 4 (Allocation)
becomes **Section 5**. A reference to allocation must land on 5, never on the new Configuration section.

**Never renumber `[1, §N]`.** Those cite the MPE Specification (reference [1]), not this paper. Every `§` in
the paper belongs to a `[1, §…]` citation — the paper numbers its own sections only as `Section N`. Restrict
the sweep to `Section N`.

### Current → target map

| Current | Target | Section |
|---|---|---|
| 3.1, 3.2 | 3.1, 3.2 | Signal Flow, Input Modes — unchanged |
| **3.3 Zones** | **→ 4** | extracted into the new Configuration section |
| 3.4 | **3.3** | Non-MPE to MPE Conversion |
| 3.5 | **3.4** | Master Channel Forwarding (retitled — Work item 3) |
| *(new)* | **4** | **Configuration** — 4.1 Input Mode, 4.2 Zones, 4.3 Pitch Bend Sensitivity |
| 4 … 4.7.5 | **5 … 5.7.5** | Allocation of Notes to Member Channels |
| 5 … 5.2.3 | **6 … 6.2.3** | Dropping Notes and Freeing Channels |
| 6 … 6.3 (+ new 6.4) | **7 … 7.3 (+ new 7.4)** | Expression Value Processing (6.1 Aggregation → 7.1, plus new **7.1.1** Message Ordering — Work item 4; and new **7.4** Channel Pressure Reset at Note Off — Work item 6) |
| 7 | **8** | Real-Time Tuning Changes |
| **8** | — | MPE Tuner Output Conformance — **dissolved** (Work items 2–6; repoints in the Cross-references table) |
| 9, 10 | 9, 10 | Worked Examples, Summary — unchanged |

The `3.3` (Zones) cross-references need judgment, not a blind swap: each resolves to the new Configuration
section — usually **Section 4**, or a specific subsection (4.1 Input Mode / 4.2 Zones / 4.3 Pitch Bend
Sensitivity) when the sentence means one.

### Cross-reference checksum (as of `44730ba`)

Occurrences of each `Section N` the paper makes to itself, so the sweep can be verified complete:

| Current ref (count) | → target |
|---|---|
| 3.3 ×9 | 4 (or 4.1 / 4.2 / 4.3 by context) |
| 3.4 ×8 | 3.3 |
| 3.5 ×4 | 3.4 |
| 4 ×3, 4.1 ×3, 4.2 ×1, 4.4 ×1, 4.5 ×4, 4.6 ×4, 4.7.5 ×1 | 5, 5.1, 5.2, 5.4, 5.5, 5.6, 5.7.5 |
| 5 ×1, 5.1 ×3, 5.2 ×4, 5.2.1 ×3, 5.2.2 ×4, 5.2.3 ×2 | 6, 6.1, 6.2, 6.2.1, 6.2.2, 6.2.3 |
| 6 ×7, 6.1 ×5, 6.2 ×1, 6.3 ×4 | 7, 7.1, 7.2, 7.3 |
| 7 ×1 | 8 |
| 8 ×2, 8.1 ×2, 8.2 ×2 | dissolved — repoint per the Cross-references table |
| 3, 3.1, 3.2, 2.1, 1.3 | unchanged |

---

## Work item 1 — Extract Configuration into a new, dedicated Section 4

### Rationale

Section 3.3 already states the dual-source configuration model:

> "The Zone configuration may be established and changed in two ways: through the non-MIDI configuration
> interface, or in-band, through an MCM received on a Master Channel."

That pattern is **not Zone-specific** — it is the Tuner's configuration model, and Input Mode and Pitch
Bend Sensitivity follow the identical shape (non-MIDI interface establishes a base; MIDI messages override
it in-band, with per-parameter limitations). A separate section for PBS would re-explain the same model a
second time in parallel for one parameter. State the model once; list the parameters it governs.

### Structure

The new **Configuration** section (Section 4) opens with a short preamble stating the general model, then:

- **4.1 Input Mode**
- **4.2 Zones**
- **4.3 Pitch Bend Sensitivity**

The preamble carries what is common to all three: the Tuner is configured through a **non-MIDI
configuration interface**; the MPE Specification defines **defaults** for these parameters; a user may
**override** the non-MIDI configuration in-band via MCM and RPN 00 00 messages, subject to per-parameter
limitations stated in the subsections.

Subsectioning is required here, not cosmetic: the current Zones material (3.3) is already two dense
paragraphs, and the section is absorbing PBS's mode-split plus the defaults model.

### 4.1 Input Mode

Section 3.2 ("Input Modes") keeps defining **what the modes are**; 4.1 defines **how the mode is
selected**. This mirrors the split between what a thing *is* and how it is *configured*, as between
Section 2.1 (what Zones are) and 4.2 (how the Tuner's Zones are configured).

This subsection is nearly free — the current Zones section (3.3) already contains the mode-switching rules. Naming Input Mode
as a configuration parameter labels what is already there. Facts to gather:

- Set via the non-MIDI configuration interface (from Section 1.4 "Overview of Operation" and Section 3.1
  "Signal Flow", item 1 *Input Mode Detection*).
- Receipt of a valid MCM switches to MPE Input Mode (already in 3.3).
- MCMs deactivating all Zones revert to Non-MPE Input Mode, "restoring the output Zone configuration
  provided through the configuration interface" (already in 3.3).

### 4.2 Zones

Keeps **all** existing Section 3.3 Zone facts, unchanged in substance:

- One Zone configuration shared by input and output; role per input mode.
- Non-MPE Input Mode: output only, single Zone (Lower if enabled, else Upper); Upper ignored when two are
  defined.
- MCM validity rules `[1, §2.1.1]`, zero-Member-Channel deactivation, channel stealing by most-recent MCM.
- Output MCM emission on every Zone change.
- Reconfiguration state reset (dropped notes, cleared group assignments, Expression Value defaults).
- The Note Off emission choice for dropped notes.

**Add:** MCMs are emitted **at start-up** as well as on reconfiguration. This is the one genuinely new fact
in the Section 8 "Zone Configuration" bullet; everything else in that bullet restates 3.3.

### 4.3 Pitch Bend Sensitivity

This is the substantive addition. Section 8's PBS bullet is the **only** place in the paper specifying how
the Tuner interprets incoming PBS; it must not be lost.

**Fix the causality.** Section 8 currently says:

> "the MPE Tuner relies on the default Pitch Bend Sensitivity values that the MCM establishes — ±48
> semitones for Member Channels and ±2 semitones for the Master Channel [1, §2.4]."

This inverts the relationship, and the paper's own background section proves it. Section 2.3 ("Pitch Bend
Sensitivity") states:

> "Upon receiving an MCM, a receiver must set: **Master Channel Pitch Bend Sensitivity**: ±2 semitones
> (default). **Member Channel Pitch Bend Sensitivity**: ±48 semitones (default). These values may be
> changed via RPN 00 00."

And Section 2.1 ("Zones, Master Channels, and Member Channels"):

> "When a Zone configuration changes, receivers are required to stop all ongoing notes and to reset all
> controls to reasonable default values on each channel entering or leaving MPE control [1, §2.1.4]."

The defaults are **spec-defined properties of MPE**; the MCM is the **reset trigger** that applies them.
Write 4.3 in that direction: the defaults exist independently, and an MCM resets PBS to them.

**Facts to carry over from Section 8's PBS bullet:**

- Defaults: ±48 semitones Member Channels, ±2 semitones Master Channel `[1, §2.4]`.
- The Tuner listens for RPN 00 00 on its input and conforms to it when interpreting incoming Pitch Bend.
- **MPE Input Mode:** a sensitivity received on any input Member Channel applies Zone-wide, per the quote
  Section 8 already carries — *"[a] receiver must apply the last Pitch Bend Sensitivity message received on
  any Member Channel to all Member Channels in the Zone"* `[1, §2.4]`. On output the Tuner **mirrors the
  input**, forwarding each received message on its corresponding output Member Channel, and does *not*
  fan out. Keep the **`n²` flood rationale** verbatim in substance: a conforming MPE sender already
  addresses the message to each of the `n` Member Channels `[1, §2.4]`, so re-fanning `n` messages to `n`
  channels would produce an `n²` flood; per-channel forwarding already configures every output Member
  Channel.
- **Non-MPE Input Mode:** the input carries no Member Channels; a received RPN 00 00 applies to the output
  **Master Channel**, consistent with the redirection of the input's Pitch Bend there (Section 3.4). Output
  Member Channels retain ±48, changeable **only** through the non-MIDI configuration interface.

**Add: PBS to the reconfiguration reset list.** Section 3.3's reset enumeration currently reads:

> "the retained Expression Values and remembered input-channel control values (Section 6) return to their
> defaults — Expression Pitch Bend 0, Channel Pressure 0, and CC #74 64."

PBS is absent, even though the cited obligation `[1, §2.1.4]` is to reset **all** controls, and Section 2.3
names PBS explicitly as MCM-reset. Add PBS (±48 Member / ±2 Master) to that list.

### Decided: MCM/PBS emission ordering is a non-issue

An earlier draft of this analysis raised a concern that the Tuner's own MCM emission would wipe a
non-default configured Member PBS on the receiving instrument, requiring an RPN 00 00 re-emission after
every MCM. **Decision: this is not an issue, and the paper need not specify a re-emission obligation.**
Reasoning to preserve:

- In **Non-MPE Input Mode**, the Tuner emits MCM **+** PBS only at start-up or when the non-MIDI
  configuration changes, and it must emit both to completely configure the output. Correct configuration is
  therefore true by construction — there is no window in which an MCM strands a configured PBS.
- If an **MCM is received on the input** while in Non-MPE Input Mode, the Tuner will normally switch to MPE
  Input Mode, and resetting PBS to defaults is correct. A user who sends an MCM does so intentionally, knows
  an MCM resets PBS to defaults, and will send their own PBS values if they want others.

### Known limitation to record (may warrant a sentence in the paper)

**The empty-Zone MCM revert path.** When an MCM with zero Member Channels deactivates all Zones and the
Tuner reverts to Non-MPE Input Mode, there is no way to set output **Member Channel PBS** over MIDI — in
Non-MPE Input Mode the input has no Member Channel to receive RPN 00 00 on, and input PBS is redirected to
the Master Channel. **Decision: accept this limitation.** Consider one sentence in 4.3 acknowledging it,
so the constraint is documented rather than discovered.

> **Open question for the drafting step.** Section 3.3 says the revert restores "the output Zone
> configuration provided through the configuration interface", and Section 3.3 also requires an output MCM
> "[w]henever the Zone configuration changes — through either mechanism". If that restore emits MCM **+**
> PBS (the same pairing as start-up), the non-MIDI interface's Member PBS *is* re-applied and the
> limitation reduces to the general Non-MPE one — the *performer* cannot change Member PBS over MIDI,
> which is already stated. Decide whether the revert re-emits the PBS pair, and phrase the limitation to
> match. This affects only the wording of the limitation, not any other work item.

---

## Work item 2 — Velocity-0 convention → Allocation preamble

Section 8.3 currently carries:

> "A Note On with velocity 0 in the input is treated as a Note Off, following the MIDI 1.0 shorthand and
> the specification's recommendation "that this message be interpreted as Note Off velocity 64"
> [1, §3.3.2]. Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and
> channel reuse all depend on detecting note releases."

**Placement.** Section 3.2 ("Input Modes") was considered and rejected: it is a taxonomy of the two modes,
and a message-parsing rule has no business there. The rule is MIDI 1.0 baseline that any compliant device
honors — not a Tuner design decision — so it needs to be stated once, early, and not argued.

Destination: the **Section 4 ("Allocation of Notes to Member Channels") preamble**, the paragraph beginning:

> "The allocation rules in this section apply to notes that are candidates for tuning via per-channel Pitch
> Bend — that is, all notes received in Non-MPE Input Mode, and all notes received on a Member Channel in
> MPE Input Mode."

That paragraph is already a "how to read the rest of this section" scoping note; the register matches.

**Phrasing constraints:**

- Write it as a **global convention** — e.g. *"throughout this specification, a Note On with velocity 0 is
  treated as a Note Off [1, §3.3.2]"* — **not** as an allocation-local rule. This matters: the fact also
  governs Section 6's Expression Value averaging, and a global phrasing lets Section 6 inherit it without a
  second mention.
- **Drop the block quote**; a bracketed `[1, §3.3.2]` citation carries it.
- **Drop** the "Recognizing the shorthand is essential: occupancy tracking, Expression Value averaging, and
  channel reuse all depend on…" sentence. That is the section justifying its own existence — exactly the
  padding this reorg removes. One sentence is enough.

---

## Work item 3 — Section 3.5 "Master Channel Note Forwarding" → "Master Channel Forwarding"

### Rationale

The same passthrough rule is currently stated in **three** places under different headings. The unifying
axis is *Master Channel + passthrough*, not *notes + sender intent*:

| Concern | Currently in | Text |
|---|---|---|
| Notes | 3.5 | "Note On and Note Off messages received on a Master Channel of an enabled Zone are forwarded on the same Master Channel without modification" |
| Pitch Bend | 4.7.5 | "The MPE Tuner forwards Master Channel Pitch Bend as received, without modification." |
| Zone-level controls | 8.2 | "Zone-level messages … are forwarded on the Master Channel without modification." |

Retitle 3.5 to **"Master Channel Forwarding"** and let it own the mechanic for all three.

### 3.5 keeps all current facts

Nothing in the existing 3.5 is dropped. The note-specific material remains as the argument it already is,
now scoped as one concern within a broader rule:

- Master Channel notes bypass channel allocation; no Pitch Bend / CC #74 / Channel Pressure setup messages
  are emitted for a Master Channel Note On.
- Master Channel notes **do not receive a per-pitch-class tuning offset**; they sound in 12-EDO. Keep the
  pitch-class-invariant explanation.
- The threefold rationale: MPE Specification compliance, conservation of Member Channel resources,
  preservation of Polyphonic Key Pressure compatibility.

### 3.5 absorbs from 8.2 "Zone-Level Messages"

> "Zone-level messages (Damper Pedal, Program Change, Reset All Controllers, and other messages listed in
> Table 1 of the MPE Specification) are forwarded on the Master Channel without modification. The MPE Tuner
> does not interpret or alter these messages."

Move verbatim in substance. Section 2.6 ("Zone-Level Messages") already supplies the background, so this
reads as the established background-then-behavior pattern.

### 3.5 absorbs from 4.7.5 "Master Channel Pitch Bend" — without losing facts

Section 4.7.5 currently reads:

> "The MPE Tuner forwards Master Channel Pitch Bend as received, without modification. Master Pitch Bend is
> not used by the Tuner in computing tuning offsets; it is a Zone-level expressive control, belonging
> entirely to the performer. The Tuner's tuning offsets are applied exclusively through the Tuning Pitch
> Bend component of Member Channel Pitch Bend."

Split it:

- **Moves to 3.5:** the bare mechanic — *forwards Master Channel Pitch Bend as received, without
  modification*.
- **Stays in 4.7.5:** the RP-053 quote (*"If an MPE synthesizer receives Pitch Bend (for example) on both a Master and a
  Member Channel, it must combine the data meaningfully."* `[1, §2.3.2]`) **and** the argument — Master
  Pitch Bend is a Zone-level expressive control belonging entirely to the performer, is not an input to
  tuning, and tuning offsets are applied exclusively via the Tuning Pitch Bend component of Member Channel
  Pitch Bend.

This preserves both facts. Section 4.7 is "Comparison with Standard MPE Allocation" — the *argument* about
why Master Pitch Bend is not a tuning input is a legitimate comparison point and belongs there; the
*mechanic* does not. After the split, 4.7.5 keeps its quote and its point, and adds a cross-reference to
3.5 for the mechanic.

**Do not touch 4.7.4 ("Same Note Number on Multiple Channels").** It concerns the Expression Group and the
bent-then-restruck pattern, and has no Master Channel forwarding content.

### Widen the 3.5 closing line

Current:

> "In Non-MPE Input Mode the concept of a Master Channel does not apply to the input: every incoming note is
> allocated to a Member Channel regardless of the input channel number."

Once 3.5 covers Zone-level controls, this is too narrow — in Non-MPE Input Mode, Zone-level messages **do**
reach the output Master Channel, via Section 3.4's redirection. The distinction to draw is
**forwarding vs. redirection**: in Non-MPE Input Mode there is no input Master Channel to *forward from*,
so Section 3.4 ("Non-MPE to MPE Conversion") item 4 *redirects* channel messages to the output Master
Channel instead. Section 3.4 item 4 already makes exactly this point:

> "Non-MPE input has no Master Channel of its own from which Section 8.2's forwarding could operate; this
> redirection gives those messages their conformant Zone-level home on the output."

After the reorg that sentence's reference resolves to 3.5, making 3.4 ↔ 3.5 a clean pairing. Keep the
existing note-allocation clause; widen the surrounding statement to cover all three concerns.

---

## Work item 4 — Section 8.1 "Message Ordering" → new Section 6.1.1

Section 6.1 ("Aggregation Model") already defers to 8.1 explicitly:

> "Second, it enables an emission optimization: an implementation is not required to emit all three control
> dimensions before a Note On — it may emit only those whose values differ from the values the channel
> already holds (Section 8.1)."

The retention rule is what **licenses** the omission optimization, so the two belong together. Create
**6.1.1 "Message Ordering"** under Section 6.1 and move:

- The emission order on the assigned Member Channel: **1. Pitch Bend, 2. CC #74, 3. Channel Pressure,
  4. Note On**, with the `[1, §3.3.1]` citation and the rationale that the receiving instrument has correct
  pitch and articulation state before the note sounds.
- The omission rule: an implementation may omit any of the three control dimension messages whose value is
  unchanged since its last emission on that output channel, relying on the state retention of 6.1. With
  6.1.1 nested under 6.1, the cross-reference becomes local.

**Delete as restatement:** the trailing "In particular, in Non-MPE Input Mode CC #74 is never emitted on a
Member Channel…" — already stated in both Section 6.3 ("Non-MPE Input Mode") and Section 3.4 item 3.

---

## Work item 5 — Section 8.3 "Note Off Behavior" remainder

Section 8.3 is dissolved. Its content:

- **→ Section 6.1 "Aggregation Model":** the note is removed from its channel's Expression Value averages on
  Note Off, with the `[1, §3.3.3]` quote — *"control of a note ceases once Note Off has occurred"*.
- **→ Section 4.2 "Dual-Group Channel Partitioning":** "The channel becomes available for reuse once all its
  notes have received Note Off messages." Place it next to the existing group-persistence rule:

  > "The group assignment of a channel is determined at the moment a note is placed on it and persists for
  > the lifetime of that channel's occupancy."

  The two rules are the same occupancy lifecycle stated from opposite ends.
- **→ Allocation preamble (Section 4):** the velocity-0 convention (Work item 2).
- **Delete as restatement:** "While other notes remain active on the channel, the Tuner continues to update
  the channel's Pitch Bend — for tuning changes (Section 7) as well as for Expression Value changes
  (Section 6)." Already covered by Section 6.1's update-propagation rule and Section 7.

---

## Work item 6 — New Section 6.4 "Channel Pressure Reset at Note Off"

`#241` added a paragraph to Section 8.3 that is the one genuinely new fact in the dissolved Section 8: whether
the Tuner emits a Channel Pressure reset at Note Off, and why the answer depends on the input mode. Section 8
dissolves, so this paragraph needs a pipeline-stage home. Channel Pressure is an Expression Value, and its
Note Off reset is Expression Value emission behavior, so its home is **Section 6 ("Expression Value
Processing")**. Because the paragraph's substance is the *contrast between the two input modes* — a single
argument that reads as a unit and already cross-references both Section 6.2 and Section 6.3 — keep it whole as
a new subsection **6.4 "Channel Pressure Reset at Note Off"** (renumbers to **7.4**), a sibling of 6.2 and
6.3, rather than splitting it across them.

Move the paragraph verbatim in substance, preserving:

- The spec obligation: *"Channel Pressure must be set to zero immediately before a Note On or a Note Off
  wherever it is appropriate to the design of a controller"* `[1, §3.3.4]`.
- **MPE Input Mode:** output Channel Pressure passes through from the input sender, so the Tuner emits no
  reset of its own — a conforming sender's pre-release reset propagates through the update mechanism of
  Section 6.2, and if the sender emits none, neither does the Tuner.
- **Non-MPE Input Mode:** the per-note Channel Pressure on an output Member Channel is the Tuner's own,
  synthesized from the input's Polyphonic Key Pressure (Section 3.4); here the Tuner is the controller to
  which the `[1, §3.3.4]` obligation applies, so it performs the reset itself, returning Channel Pressure to 0
  as Section 6.3 requires.
- The closing point that deferring in MPE Input Mode and resetting in Non-MPE Input Mode both fall within the
  specification's *"wherever it is appropriate"* qualifier and are documented design choices.

The internal references to Sections 6.2 and 6.3 renumber to 7.2 and 7.3; the reference to Section 3.4 (the
Polyphonic Key Pressure conversion) renumbers to 3.3. This is the only Section 8 fact that lands in the
Expression Value Processing section.

---

## Cross-references to repoint

Every reference to Section 8 in the paper, with its resolution. Search for the parenthetical to locate each.

| In section | Current reference | Action |
|---|---|---|
| 3.3 Zones (→ 4.2) | "the receiving instrument adopts the same Zone structure **(Section 8)**" | Drop the cross-reference; absorb the start-up MCM fact into the Zones subsection (4.2). |
| 3.4 item 3 (CC #74) | "never sends CC #74 on a Member Channel **(Sections 6.3 and 8.1)**" | → "(Section 6.3)" |
| 3.4 item 4 | "where they act as Zone-level messages **(Section 8.2)**" | → Section 3.5 |
| 3.4 item 4 | "Non-MPE input has no Master Channel of its own from which **Section 8.2's** forwarding could operate" | → Section 3.5's |
| 3.4 item 4 | "the sensitivity of the Master Channel Pitch Bend to which the input's Pitch Bend is redirected **(Section 8)**" | → Section 4.3 |
| 6.1 Aggregation Model | "it may emit only those whose values differ from the values the channel already holds **(Section 8.1)**" | → Section 6.1.1, or inline now that it is adjacent. |
| 6.3 Non-MPE Input Mode | "the dimension is thus controllable only globally **(Section 8.1)**" | → Section 3.4 item 3 |

**Renumbering:** see [Numbering after the reorg](#numbering-after-the-reorg). Worked Examples and Summary
keep 9 and 10; old Sections 4–7 shift up by one; old 3.4/3.5 become 3.3/3.4; the new Configuration section
is Section 4. Check for prose references to any renumbered section before finalizing. Appendix A is
unaffected.

**Sweep after editing:** grep the paper for `Section 8` to confirm no dangling references survive (the paper
has no `§8` — every `§` is a `[1, §…]` spec citation and must **not** be renumbered). Then verify the
renumber against the checksum in [Numbering after the reorg](#numbering-after-the-reorg): no `Section 4`–
`Section 7` reference should still resolve to its old target. Also grep the repo outside the paper —
`issues/00202-pbs-non-mpe-input/pbs-non-mpe-and-mcm-test-mode-plan.md` already cites the paper by a stale
path and stale numbering (`docs/tuner/mpe-tuner-paper.md` §3.3.2), so other docs may cite these numbers too.
Updating stale sibling docs is **out of scope** for the reorg unless trivially cheap; note them rather than
fix them.

---

## Coherence risk to watch

After dissolution, no single section says "here is what conformant output looks like." A reader wanting the
output contract must assemble it from 4.3, 3.4, 3.5, 6.1.1, and 6.4. **This is the real cost of dissolution**
and it is accepted deliberately — the alternative (retitling Section 8 to "Output Message Contract" and
keeping only 8.1 and 8.2) leaves a thin two-subsection section whose parts both have better homes.

**Mitigation:** consider one compensating sentence in Section 1.4 ("Overview of Operation") or Section 10
("Summary") noting where output conformance is specified. Section 1.4 already ends with the input-mode
sentence and is the natural host. Optional — evaluate when drafting.

---

## Facts inventory

Completeness guarantee. Every fact in Section 8 at `44730ba`, its status, and its destination. **New** =
exists nowhere else in the paper and must not be lost. **Restatement** = verifiably duplicated elsewhere;
safe to delete.

| # | Fact | From | Status | Destination |
|---|---|---|---|---|
| 1 | Output MCMs establish Zone structure on the receiver; single- and dual-Zone `[1, §2.1]` | 8 intro | restatement of 3.3 | delete |
| 2 | **MCMs emitted at start-up** | 8 intro | **new** | 4.2 Zones |
| 3 | MCMs emitted on every Zone reconfiguration | 8 intro | restatement of 3.3 | delete |
| 4 | Tuner listens for input MCMs and reconfigures its own Zones | 8 intro | restatement of 3.3 | delete |
| 5 | Tuner forwards that configuration to the output instrument | 8 intro | restatement of 3.3 | delete |
| 6 | **PBS defaults ±48 Member / ±2 Master relied upon** `[1, §2.4]` | 8 intro | **new** (2.3 states the spec rule; this states the Tuner's reliance) | 4.3 — **with causality corrected** |
| 7 | **Listens for RPN 00 00 on input; conforms when interpreting incoming Pitch Bend** | 8 intro | **new** | 4.3 |
| 8 | **MPE Input Mode: sensitivity on any Member Channel applies Zone-wide** (+ `[1, §2.4]` quote) | 8 intro | **new** | 4.3 |
| 9 | **MPE Input Mode: mirrors input per-channel; no fan-out; `n²` flood rationale** | 8 intro | **new** | 4.3 |
| 10 | **Non-MPE Input Mode: RPN 00 00 → output Master Channel** | 8 intro | restatement of 3.4 item 4 | delete from 8; 3.4 item 4 keeps it, repointed |
| 11 | **Non-MPE Input Mode: output Member Channels retain ±48; changeable only via non-MIDI interface** | 8 intro | **new** | 4.3 (+ limitation note) |
| 12 | **Emission order: Pitch Bend → CC #74 → Channel Pressure → Note On** `[1, §3.3.1]` | 8.1 | **new** | 6.1.1 |
| 13 | Omission optimization for unchanged dimensions | 8.1 | half-restatement of 6.1 | merge into 6.1.1 |
| 14 | CC #74 never emitted on a Member Channel in Non-MPE Input Mode | 8.1 | restatement of 6.3 and 3.4 item 3 | delete |
| 15 | **Zone-level messages forwarded on Master Channel, unmodified; Tuner does not interpret or alter them** | 8.2 | **new** | 3.5 |
| 16 | **Note removed from Expression Value averages on Note Off** (+ `[1, §3.3.3]` quote) | 8.3 | **new** (the quote) | 6.1 |
| 17 | Channel keeps receiving Pitch Bend updates while other notes remain active | 8.3 | restatement of 6.1 and 7 | delete |
| 18 | **Channel available for reuse once all its notes have received Note Off** | 8.3 | **new** | 4.2 |
| 19 | **Note On velocity 0 treated as Note Off** `[1, §3.3.2]` | 8.3 | **new** | Allocation preamble (Work item 2), as a global convention |
| 20 | "Recognizing the shorthand is essential: occupancy tracking, …" | 8.3 | self-justifying prose | delete |
| 21 | **Channel Pressure reset at Note Off is input-mode-dependent** — MPE: pass-through, deferring to the sender via Section 6.2; Non-MPE: Tuner resets to 0 per Section 6.3 `[1, §3.3.4]` | 8.3 | **new** (added by #241) | new Section 6.4 (Work item 6) |

**Totals:** 12 new facts relocated, 1 merged, 8 deleted as restatement or padding.

### Additional fixes surfaced by this analysis (not from Section 8)

| Fact | Location | Action |
|---|---|---|
| PBS missing from the reconfiguration reset list | 4.2 (Zones) | **Add** PBS to the defaults enumeration — the `[1, §2.1.4]` obligation is to reset *all* controls. |
| Master Channel Pitch Bend forwarding mechanic | 4.7.5 | Move mechanic to 3.5; keep quote and argument in place. |

---

## Constraints for the executing prompt

- **Markdown only.** Do not modify Scala or any other committed source. The paper and the implementation
  are both work in progress; updating `MpeTuner` is out of scope.
- **Maintain the technical academic tone** of the paper.
- **Do not read `MpeTuner`** to resolve questions about intended behavior — this paper is the specification,
  not a description of the current implementation.
- Preserve the paper's existing capitalization of MPE terms (Zone, Master Channel, Member Channel, Pitch
  Bend, Tuning Pitch Bend, Expression Pitch Bend, Expression Values, Tuning, Tuner).
- Verify each quoted passage still matches before editing; the paper may have moved past `44730ba`.

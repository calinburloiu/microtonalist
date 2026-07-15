# Design: Polyphonic Expression Update to the MPE Tuner Paper

**Date:** 2026-07-15
**Source prompt:** [`../paper-prompt.md`](../paper-prompt.md)
**Target document:** [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md)

This design maps every fact from the source prompt (referenced by its section/letter ID) to a concrete
change in the paper. Only markdown files are modified; the `MpeTuner` implementation is out of scope.
Changes are applied in place after this document is approved — no separate implementation plan is written.

## Decisions log

Resolved with the author before writing this document:

1. **Term for per-note expressive quantities:** *Expression Value* (author's original suggestion).
2. **Rename:** *expressive pitch bend* → **Expression Pitch Bend** throughout, for parallelism with
   *Tuning Pitch Bend* and coherence with the Expression Value / Expression Group family. The derived
   term becomes **High Expression Pitch Bend**.
3. **Section 6 duplication (fact 2b):** replace subsections 6.1 (Zone Configuration) and 6.2 (Pitch Bend
   Sensitivity) with a short conformance enumeration; keep full subsections only where the MPE Tuner adds
   behavior.
4. **Appendix B (fact 2c):** remove entirely; do not fold the Master Channel pre-check into the
   Section 4.5 mermaid diagram.
5. **Section order (open question):** move "Real-Time Tuning Changes" before "MPE Tuner Output
   Conformance", with the new expression section between "Dropping Notes" and "Real-Time Tuning Changes".

## A. Terminology and capitalization (fact 1a)

New capitalized terms of art, used consistently throughout the paper:

| Term | Definition |
|---|---|
| **Tuning Pitch Bend** | The Pitch Bend component that encodes the tuning offset of the pitch class associated with an output Member Channel. |
| **Expression Pitch Bend** | The Pitch Bend component controlled by the performer in real time for expressive purposes. Renames every occurrence of "expressive pitch bend". |
| **High Expression Pitch Bend** | Renames "high expressive pitch bend" (Sections 4.4, 4.5, 5.2, 5.3). |
| **Expression Value** | The per-note expressive quantity of a control dimension: Expression Pitch Bend, Channel Pressure, or CC #74. Excludes Tuning Pitch Bend. |
| **MPE Input Mode / Non-MPE Input Mode** | Capitalized as terms (paper currently writes "MPE input mode"). |

Composition rule, stated formally before any ad-hoc use:

> **Pitch Bend = Tuning Pitch Bend + Expression Pitch Bend**

Capitalization policy: terms the MPE Specification capitalizes in running text remain capitalized as they
already are in the paper (Zone, Master Channel, Member Channel, Pitch Bend, Channel Pressure, Polyphonic
Key Pressure, Note On, Note Off). Standalone "channel", "note", and "active note" stay lowercase: the MPE
Specification is itself inconsistent (its §3.2 writes "active notes" lowercase despite defining "Active
Note"), and a full sweep would add noise without precision. Compound terms remain capitalized.

## B. New subsection 1.3 "Control Dimensions" (fact 1a)

Placed in the Introduction directly after 1.2 "Scope and Definitions"; current 1.3 "Overview of
Operation" becomes 1.4. Content:

- The three MPE control dimensions: Pitch Bend, Channel Pressure, and CC #74 (timbre).
- The Pitch Bend decomposition into Tuning Pitch Bend + Expression Pitch Bend, with both components
  defined.
- The Expression Value definition covering the three per-note expressive quantities.

The final paragraph of 1.2 (input pitch alterations treated exclusively as expressive) is rewritten in
the new terms and largely absorbed into this subsection.

## C. New subsection 3.3 "Zones" (fact 1b)

Placed after 3.2 "Input Modes"; current 3.3 and 3.4 shift to 3.4 and 3.5. Content:

- A single zone configuration (number of Member Channels per Zone) governs **both input and output** in
  MPE Input Mode.
- In Non-MPE Input Mode the input has no Zone structure, so the configuration affects output only, and
  only one output Zone is used: the Lower Zone when both are defined, the Upper Zone being ignored.
- The zone-routing sentence currently in 3.2 moves here.

## D. New Section 6 "Expression Value Processing" (facts 1.1a–d, 1.2a–e)

Placed between "Dropping Notes and Freeing Channels" (5) and "Real-Time Tuning Changes" (which moves to
7). Three subsections:

### 6.1 Aggregation model (facts 1.1a, 1.1b)

- Each output Member Channel's Expression Value for a control dimension is the **average** of the
  Expression Values of its active notes for that dimension. (1.1a)
- The output Pitch Bend of a Member Channel is the sum of its Tuning Pitch Bend and its averaged
  Expression Pitch Bend. (1.1a)
- A channel that becomes empty **preserves its latest Expression Values**; averaging applies only while
  at least one note is active. This supports the emit-on-change optimization and avoids a division by
  zero. (1.1b)
- An implementation is not required to emit all three control dimensions before a Note On; it may emit
  only those that changed. (1.1b)

### 6.2 MPE Input Mode (facts 1.1a, 1.1c, 1.1d)

- Every incoming note carries an Expression Value for each of the three control dimensions, taken from
  its input Member Channel; notes sharing an input channel share Expression Values. (1.1a)
- Fan-out/fan-in mapping: notes from the same input channel may map to different output channels when
  their pitch classes differ; notes of the same pitch class from different input channels may map to the
  same output channel. (1.1a)
- When an input Member Channel has no active notes, the Tuner remembers the latest values of the three
  control dimensions received on it; a new note arriving on that channel is initialized with those values
  as its Expression Values, which then participate in output averaging. Mirrors MPE Specification §3.3
  (cited). (1.1c)
- When a control dimension update arrives on an input channel with active notes, each active note's
  contribution to its output channel's average is updated; every output channel whose average changed
  gets the new value emitted. (1.1d)

### 6.3 Non-MPE Input Mode (facts 1.2a–e)

- Only Pitch Bend and Channel Pressure are used on output Member Channels, and only Channel Pressure
  carries an Expression Value. Member Channel Pitch Bend is Tuning Pitch Bend alone — there is no
  per-channel Expression Pitch Bend. Input Pitch Bend is forwarded to the Master Channel and applies to
  all channels; CC #74 never appears on output Member Channels and is forwarded to the Master Channel
  when received. (1.2a)
- A Member Channel Channel Pressure always originates from a converted Polyphonic Key Pressure; a Channel
  Pressure received on an input channel is always forwarded to the Master Channel. (1.2b)
- A Polyphonic Key Pressure is assumed to apply to an active note; one addressed to a note without a
  matching Note On on that input channel is ignored. Consequently a note's Polyphonic Key Pressure value
  is always 0 at Note On time. (1.2c)
- Multiple active notes on an output Member Channel have their Channel Pressure Expression Values
  averaged, as in MPE Input Mode — but unlike MPE Input Mode, preserving the latest value on an emptied
  channel has no observable effect, because the next Note On must reset the value to 0 (it originates
  from Polyphonic Key Pressure, which is always 0 at Note On). (1.2d, per decision 6)
- A Polyphonic Key Pressure update on an input channel updates that note's contribution to the output
  Channel Pressure average; every changed output average is emitted. (1.2e)

## E. Updates to existing sections

- **Abstract:** one added clause mentioning polyphonic expression handling across both input modes
  (light touch).
- **3.1 Signal Flow:** step 3 wording updated to the new terms and to mention Expression Value
  aggregation; diagram node possibly relabeled, no structural change.
- **3.4 Non-MPE to MPE Conversion** (current 3.3): item 1 gains a pointer to 6.3 for averaging; item 3's
  initialization bullets rewritten — Pitch Bend is Tuning Pitch Bend alone; Channel Pressure is 0 at
  onset; CC #74 is **not** emitted on Member Channels in Non-MPE Input Mode (controllable only globally
  via the Master Channel), replacing the current "initialize to 64" bullet. (2b comment on 6.3)
- **4.2 Expression Group** (fact 2a): add a phrase that the Expression Group may also provide
  supplemental channels when the Pitch Class Group is full.
- **4.6:** generalized from "Pitch Bend Computation for Shared Channels" to Expression Value averaging
  across all three control dimensions (Section 4.5 rationale (b) already asserts this generality), with
  Pitch Bend as the worked-out case; forward-references Section 6 for per-input-mode mechanics.
- **Section 7 "Real-Time Tuning Changes"** (moved before Output Conformance, per decision 5):
  recomputation wording updated — new Pitch Bend = new Tuning Pitch Bend + current averaged Expression
  Pitch Bend.
- **Section 8 "MPE Tuner Output Conformance"** (fact 2b, per decision 3): current 6.1 and 6.2 deleted; a
  short lead-in enumerates Zone Configuration and Pitch Bend Sensitivity as conforming to the MPE
  Specification with no tuner-specific behavior, citing the relevant spec sections. The two inaccurate
  claims flagged in the prompt (tuning-only bends rarely exceeding ±1 semitone; verifying the receiving
  instrument's sensitivity) disappear with 6.2. Message Ordering (now 8.1) gains: an implementation may
  omit dimensions unchanged since their last emission on that channel; in Non-MPE Input Mode CC #74 is
  never emitted on Member Channels. (2b comment on 6.3)
- **Section 9 "Worked Examples" and Section 10 "Summary":** terminology sweep and updated
  cross-references; brief mention of Expression Value averaging in the summary where it already discusses
  averaging (light touch).
- **Appendix B:** removed entirely (fact 2c, per decision 4). Appendix A stays.
- **Cross-reference renumbering sweep:** 3.3→3.4, 3.4→3.5, new 1.3 shifts old 1.3→1.4, new Section 6
  shifts 6→8 (conformance) and inserts 7 (tuning changes, moved), worked examples 8→9, summary 9→10.

## Fact coverage checklist

| Fact | Covered by |
|---|---|
| 1(a) | Design §A, §B |
| 1(b) | Design §C |
| 1.1(a) | Design §D 6.1, 6.2 |
| 1.1(b) | Design §D 6.1 |
| 1.1(c) | Design §D 6.2 |
| 1.1(d) | Design §D 6.2 |
| 1.2(a) | Design §D 6.3 |
| 1.2(b) | Design §D 6.3 |
| 1.2(c) | Design §D 6.3 |
| 1.2(d) | Design §D 6.3, decision 6 |
| 1.2(e) | Design §D 6.3 |
| 2(a) | Design §E (4.2) |
| 2(b) | Design §E (Section 8), decision 3 |
| 2(c) | Design §E (Appendix B), decision 4 |
| Open question (section order) | Decision 5, Design §E (Section 7) |

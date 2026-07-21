# Design: MPE Tuner Paper — Note On Messages for Already-Active Notes

**Date:** 2026-07-21

**Target document:** `docs/architecture/tuner/mpe-tuner-paper.md`

**Citations pinned at:** commit `c6d0fa1` (`Generalize default Note Off velocity`) — the working-tree HEAD of branch
`doc/mpe-tuner-paper-multiple-note-ons` at the time of writing. All quoted paper text is verbatim at `c6d0fa1`
and can be located by searching for the quote.

**Source prompt:** `issues/00154-mpe-tuner-poly-expr/paper/paper-multiple-note-ons-prompt.md`, which supplies the
two cases, the intended handling, and a set of researched MIDI 1.0 citations.

**Purpose:** this document specifies *what the paper must say and why*. It is the design input from which an
implementation plan and the edit are generated; it is not itself the edit.

**Referencing convention:** sections are identified by **number and name**. Numbers below are **current
(`c6d0fa1`)** except where a work item explicitly introduces a new number. On any conflict between a number and
a section name, the **name wins**. No line numbers are used anywhere in this document, by design.

**Scope note:** the implementation is not consulted. Per the source prompt, the MPE Tuner implementation in this
repository is work in progress and not yet aligned with the paper; this change updates the paper only.

---

## Design overview

The paper currently describes the allocation algorithm and the aggregation model as though every Note On
introduces a note that is not already sounding. It does not say what happens when a Note On arrives for a note
that is already active. Two distinct situations produce that:

1. **Same input channel.** A Note On repeats on the input channel that already carries an active note of that
   note number, with no intervening Note Off.
2. **Different input channels.** Note Ons with the same note number arrive on different input channels, and the
   allocation algorithm happens to place them on the same output Member Channel.

The two are not variants of one phenomenon. They differ in whether the notes are the *same note* as far as the
Tuner's internal bookkeeping is concerned, and that difference is what the whole change turns on. Making it
precise requires naming the bookkeeping key, which the paper has never done.

The change therefore introduces one new concept — **Note Identity** — and the mechanism that follows from it,
**reference counting**. Case 1 is then the case of a repeated Note On for an *existing* identity, and Case 2 the
case of two *distinct* identities colliding on one output channel. Everything else in this design is a
consequence of that split.

MIDI 1.0 supports both halves. It establishes that repeated Note Ons for the same key number and channel are
legal and that receiver behavior is deliberately unspecified `[2, p. A-4]`, and it places on the transmitter an
obligation to emit one Note Off per Note On sent `[2, p. A-4; p. 25]`. The first justifies treating Case 2 as an
acknowledged limitation rather than a defect; the second is what forces reference counting to exist at all.

---

## Terminology decision

The source prompt writes **"note identify"** throughout. This is a typo for **"note identity"**, and the paper
will use **Note Identity**, capitalized as a defined term, consistent with the paper's treatment of Tuning,
Tuner, Zone, Master Channel, Member Channel, Expression Values, Pitch Class Group, Expression Group, and High
Expression Pitch Bend.

**Note Identity** is defined as the pair **(input channel, note number)**.

It applies in **both input modes**. This is worth stating explicitly because Non-MPE Input Mode merges its
*controls* across input channels (Section 3.3, item 2: "the Tuner treats non-MPE input as one merged control
stream rather than preserving per-input-channel independence"), which might suggest the input channel is
discarded entirely. It is not: Section 7.3 already tracks Polyphonic Key Pressure per input channel — "If it
addresses a note number for which no Note On was issued on that input channel, it is ignored" — so per-input-
channel note bookkeeping is already present in Non-MPE Input Mode and Note Identity merely names it.

---

## Corrections to the source prompt

Two errors in the source prompt are corrected rather than carried into the paper.

1. **Case 1, third bullet** reads "A Note Off for an already active note from an input channel will be forwarded
   to the same output channel where it was allocated. Reference counting is **incremented** for that note
   identity and if it reaches 0, the note is deallocated." A count that is incremented cannot reach 0. The Note
   Off path **decrements**.

2. **Case 1, first bullet** describes the duplicate Note On as overriding "the Expression Values for that note
   with the newest values coming from the same input channel", and adds that "[t]he average computed based on
   them might be updated as a result of that". Under Section 7.2's update propagation the override cannot change
   anything: the existing note and the duplicate draw from the same input Member Channel's state, and that state
   has already been propagated to the note as it arrived. The average therefore never moves. The paper states
   the rule in the override form — which is the correct rule, and remains correct for an implementation that
   snapshots Expression Values at Note On rather than propagating them — and then records that under Section 7.2
   it is a no-op. See Work item 3.

---

## Work item 1 — New Section 5.1, *Note Identity and Reference Counting*

**Placement.** A new first subsection of Section 5, *Allocation of Notes to Member Channels*, inserted after the
existing section preamble (the paragraph beginning "The channel allocation strategy is the central
contribution", the paragraph beginning "The allocation rules in this section apply", and the velocity-0 note)
and before the current Section 5.1, *Fundamental Invariant*.

**Why Section 5 and not Section 7.** Note Identity is consumed by both the allocation algorithm (Section 5) and
the aggregation model (Section 7). Section 5 comes first, so defining it there avoids a forward reference. It is
also where it does the most work: reference counting gates whether the allocation algorithm runs at all.

**Why a numbered subsection rather than preamble prose.** Section 6 and the new Section 7.6 both need to cite
it, and the paper cites by number throughout.

### Content

**Definition.** A note is identified internally by the pair (input channel, note number). Note Identity is a
construct of the Tuner's bookkeeping, not a MIDI construct.

**Why the input channel belongs in the identity.** In MPE Input Mode the input Member Channel is the carrier of
per-note expression (Section 7.2: every incoming note's Expression Values are "taken from the state of its input
Member Channel"). Two notes of the same note number arriving on different input channels therefore hold
independent Expression Values, and collapsing them into one identity would discard one note's expression. In
Non-MPE Input Mode the input channel likewise discriminates Polyphonic Key Pressure (Section 7.3).

**Why the note number belongs in the identity.** Immediate: notes of different note numbers are different notes.

**Reference counting.** Each active Note Identity carries a count, incremented on each Note On and decremented on
each Note Off. Three rules follow:

- The allocation algorithm of Section 5.5, *Allocation Algorithm* (renumbered 5.6) runs **only** on the
  transition from 0 to 1. This is what prevents a duplicate Note On from triggering a reallocation.
- A Note On that raises the count above 1 bypasses allocation entirely and is forwarded on the output Member
  Channel already bound to that identity.
- Deallocation occurs **only** on the transition to 0. Retaining the binding until then is what allows later
  Note Offs to be forwarded to the correct output channel, and it keeps the identity present in that channel's
  Expression Value averages (Section 7.1) for as long as any Note On remains unmatched.

**Consequence for output.** Because every incoming Note Off is forwarded on the bound channel and the count
admits exactly as many Note Offs as Note Ons, the Tuner emits exactly one Note Off per Note On it forwarded.
This satisfies the MIDI 1.0 transmitter obligation: "The transmitter, however, must send a corresponding Note
Off message for every Note On sent" `[2, p. A-4]`, restated as "The correct procedure of sending a Note-Off for
each and every Note-On must still be followed" `[2, p. 25]`. The obligation is per Note On, not per note number,
and the specification explains why: "If the transmitter were to send only one Note Off message, and if the
receiver in fact assigned the two Note On messages to different voices, then one note would linger"
`[2, p. A-4]`.

### Two clarifications beyond the source prompt

Both are forced by reference counting rather than chosen, and both were approved for inclusion.

**(1) What "count of active notes" counts.** Tie-breaking criterion (b) of Section 5.5 — "prefer the channel
with the lowest count of active notes" — and its reuse in Section 6.1 must be read as counting **distinct active
Note Identities**, not the sum of reference counts. The paper's own justification for criterion (b) fixes this:
it exists because "every note added to a shared channel forfeits independent expressive control, because the
channel's three Expression Values … are each reduced to an average over the per-note Expression Values of all
active notes on the channel". A duplicated identity contributes **one** term to that average, so it adds no
further loss of independence and must not be counted twice. The same reading applies to Section 6.1's use of the
criterion when selecting a channel to free.

Placement: stated in the new Section 5.1, with a pointer from criterion (b) in Section 5.5 (renumbered 5.6).

**(2) Note Offs for identities with no active count.** An incoming Note Off for a Note Identity that holds no
active count has no channel binding, and is discarded. The paper does not say this today; reference counting
makes the gap conspicuous, because dropping a note (Section 6) clears its count and binding while the
performer's own Note Offs for it may still be in flight. Without the rule, such a Note Off has no defined
destination.

This is consistent with the paper's existing posture on dropped notes: the Tuner has already emitted Note Offs
for them (Section 6), so forwarding the performer's later Note Offs as well would exceed the one-Note-Off-per-
Note-On obligation rather than satisfy it.

### Renumbering

Inserting a new Section 5.1 shifts every existing subsection of Section 5 by one:

| Current | Name | Becomes |
|---|---|---|
| — | *Note Identity and Reference Counting* (new) | 5.1 |
| 5.1 | Fundamental Invariant | 5.2 |
| 5.2 | Dual-Group Channel Partitioning | 5.3 |
| 5.3 | Group Size Allocation | 5.4 |
| 5.4 | High Expression Pitch Bend | 5.5 |
| 5.5 | Allocation Algorithm | 5.6 |
| 5.6 | Expression Value Computation for Shared Channels | 5.7 |
| 5.7 | Comparison with Standard MPE Allocation | 5.8 |
| 5.7.1 – 5.7.6 | (subsections of the above) | 5.8.1 – 5.8.6 |

**Cross-reference impact at `c6d0fa1`,** counted across the whole paper:

| Reference | Occurrences | Becomes |
|---|---|---|
| `Section 5.1` | 6 | `Section 5.2` |
| `Section 5.2` | 1 | `Section 5.3` |
| `Section 5.3` | 0 | `Section 5.4` |
| `Section 5.4` | 2 | `Section 5.5` |
| `Section 5.5` | 7 | `Section 5.6` |
| `Section 5.6` | 4 | `Section 5.7` |
| `Section 5.7.1` | 1 | `Section 5.8.1` |
| `Section 5.7.5` | 2 | `Section 5.8.5` |
| `Section 5.7.6` | 1 | `Section 5.8.6` |

Total: 24 cross-references plus 7 headings. There are no bare `Section 5.7` references — all four occurrences
are the subsection forms listed above.

**Implementation constraint (critical).** The renumber must be applied in **descending order** — 5.7→5.8 first,
then 5.6→5.7, and so on down to 5.1→5.2. Ascending order double-shifts: rewriting 5.1→5.2 first would cause the
subsequent 5.2→5.3 pass to catch the references just created.

---

## Work item 2 — Section 6 amendment, Note Off count when dropping

**Target.** The paragraph in Section 6, *Dropping Notes and Freeing Channels*, beginning "In every dropping case,
the Tuner emits an explicit Note Off message for each dropped note".

**Change.** "For each dropped note" is imprecise once a note may carry a reference count above 1. The Tuner emits
**one Note Off per Note On it forwarded** for that note — that is, as many Note Offs as the identity's reference
count. Cite `[2, p. A-4]`, which both states the obligation and endorses the redundancy it can produce: "Since
there is no harm or negative side effect in sending redundant Note Off messages this is the recommended
practice."

Add that dropping clears the identity's reference count and channel binding, which is what engages clarification
(2) of Work item 1 for any of the performer's Note Offs that arrive afterwards.

**Unchanged.** The neutral release velocity of 64 already specified in that paragraph applies to each emitted
Note Off.

---

## Work item 3 — New Section 7.6, *Duplicate Note On Messages*

**Placement.** A new final subsection of Section 7, *Expression Value Processing*, after Section 7.5, *Message
Ordering*. It follows Section 7.5 because both cases are stated partly in terms of what is and is not emitted
alongside the duplicate Note On, which Section 7.5 governs. No renumbering results.

### Preamble

Establish that a Note On may arrive for a note already sounding on an output Member Channel, that MIDI 1.0
contemplates this, and that receiver behavior is deliberately undefined:

> "If an instrument receives two or more Note On messages with the same key number and MIDI channel, it must
> make a determination of how to handle the additional Note Ons. It is up to the receiver as to whether the same
> voice or another voice will be sounded, or if the messages will be ignored." `[2, p. A-4]`

Then distinguish the two cases by Note Identity (Section 5.1): same identity in Case 1, distinct identities in
Case 2.

### Case 1 — Note Ons from the same input channel

**Aggregation.** The duplicate is merged into the existing note. It contributes one term to the channel's
Expression Value averages, not two. The rule is stated in override form: the note's Expression Values are
overridden with the latest values of its input channel. Immediately after, record that under Section 7.2's
update propagation this is a **no-op** — the note already holds those values, because they reached it as they
arrived on the input channel — so no average changes.

**Emission.** Because no average changes, no control dimension message *need* accompany the duplicate Note On,
under the optimization Section 7.5 permits ("An implementation *may* omit any of the three control dimension
messages whose value is unchanged"). Phrase this as permission, not obligation, matching Section 7.5.

**Allocation.** Cross-reference Section 5.1: the Note On is forwarded on the bound output channel and the
reference count is incremented; the allocation algorithm does not run.

**Interaction with note dropping.** Because no new note is placed on the channel, the channel's set of active
identities is unchanged, so:

- The allocation-time High Expression Pitch Bend rules of Sections 6.2.2 and 6.2.3 do not engage. Both are
  predicated on a note being *assigned* to a channel, which does not occur here.
- Invariant 2 of Section 6.3 — an active note with a High Expression Pitch Bend is always the sole active note
  on its channel — is preserved automatically. A duplicate Note On for a high-bend note leaves it the sole
  identity on the channel.

This interaction is not in the source prompt but must be stated, because a reader who has just read Section 6.2
will otherwise expect a duplicate Note On onto an occupied channel to free it.

**Musical rationale.** From the source prompt: the case preserves the performer's intention to mark a note as
active more than once. Some receivers sound an additional voice, some retrigger, some ignore — which is exactly
the latitude `[2, p. A-4]` grants, so the paper should present this as the specification's own expectation
rather than as an assumption about synthesizer behavior.

### Case 2 — Note Ons from different input channels on the same output Member Channel

**Identity.** Distinct identities, discriminated by input channel, notwithstanding the shared note number.

**Aggregation.** Each identity is a separate term in the output channel's averages, carrying its own Expression
Values. Reference counts remain 1 each, absent Case-1 duplication on top.

**How it is reached.** Only through Step 3 of the allocation algorithm — assignment to a channel that already
holds active notes of the same pitch class — since Steps 1 and 2 assign unoccupied channels.

**Framing as an acknowledged limitation.** The output device receives two Note Ons with the same note number on
one channel, and its response is undefined `[2, p. A-4]`. The Tuner neither avoids nor attenuates this. The
justification has three parts:

- Two independent uncommon events must coincide: the performer sounding the same note number on two input
  channels, *and* the allocator reaching Step 3, which itself requires the Pitch Class Group to already hold
  that pitch class and the Expression Group to be at full capacity.
- In exchange, each note keeps its own share of the aggregation — expressive independence is preserved in the
  average, which merging the identities would destroy.
- Each Note On is matched by its own Note Off (Section 5.1), so the downstream voice count reconciles however
  the receiver resolved the duplicates.

State plainly that this does not fully preserve the performer's intention, and that it surfaces a limitation of
the design rather than a defect with a defined remedy. The paper is candid about its trade-offs elsewhere
(Sections 5.7 and 3.4) and should be here.

---

## Work item 4 — Section 7.3 amendment, the Polyphonic Key Pressure invariant

**Target.** In Section 7.3, *Non-MPE Input Mode*, the sentence "Consequently, a note's Polyphonic Key Pressure
value is always 0 at the time its Note On is issued." The same claim recurs later in that section — "because
per-note Channel Pressure originates from Polyphonic Key Pressure, which is always 0 at Note On" — and in
Section 3.3, item 3, under **Channel Pressure**.

**Problem.** Case 1 falsifies the claim as written. A note may accumulate converted Polyphonic Key Pressure and
then receive a duplicate Note On, at which point its Channel Pressure Expression Value is not 0.

**Change (decided).** Qualify the invariant to the Note On that **allocates** the note — the transition from
reference count 0 to 1 (Section 5.1). A duplicate Note On does not re-initialize the note.

**Rationale for this over the alternative.** Reading "override the Expression Values" literally would reset the
note's Channel Pressure to its onset default of 0 on every Note On, preserving the invariant verbatim. It was
rejected: it discards live pressure mid-note and emits an audible pressure drop the performer never gestured.
The chosen reading is also the one consistent with the merge model — a duplicate increments a count, it does not
restart a note.

**Check the primary Section 3.3 statement too**, since the Non-MPE onset default of Channel Pressure 0 is stated
there as well and carries the same implicit assumption.

---

## Work item 5 — New Section 9.6, *Duplicate Note On Messages*

**Placement.** A new final subsection of Section 9, *Worked Examples*, after Section 9.5. Update the Section 9
preamble, which currently enumerates what the examples trace ("the allocation algorithm …, real-time retuning
…, the Expression Value aggregation model …, and the two circumstances in which notes are dropped"), to include
duplicate Note Ons.

**Mode.** Entirely **MPE Input Mode**, consistent with Sections 9.3 and 9.5. Case 2 is only fully expressive in
MPE Input Mode, where per-input-channel Expression Values exist; mixing modes within one example would obscure
both parts. The Non-MPE Polyphonic Key Pressure interaction is covered in prose by Work item 4 and needs no
example.

**Configuration.** Reuse Section 9.3's setup for continuity: Lower Zone with 4 Member Channels (Channels 2–5),
so `a` = 2 and `b` = 2; quarter-comma meantone; `T(E) ≈ −13.7` cents; `T(G) ≈ −3.4` cents; `t` = 50 cents. Under
Section 4.2's shared Zone configuration the input Member Channels are 2–5 as well, as in Section 9.3.

### Part 1 — Same input channel

Input Channel 2 begins at the defaults of Section 4.2: Expression Pitch Bend 0, CC #74 64, Channel Pressure 0.

1. **Note On E4 on input Channel 2.** Identity (2, E4); count 0 → 1, so allocation runs. Step 1 assigns an
   unoccupied channel; criterion (e) prefers the note's own input channel, giving output Channel 2, which joins
   the Pitch Class Group. Pitch Bend `T(E) = −13.7` cents is emitted before the Note On; CC #74 and Channel
   Pressure already hold their defaults on that channel and may be omitted (Section 7.5).
2. **Channel Pressure 80 on input Channel 2.** Update propagation (Section 7.2) carries it to the note; output
   Channel 2 holds one identity, so its average is 80 and Channel Pressure 80 is emitted.
3. **Second Note On E4 on input Channel 2.** Identity (2, E4) again; count 1 → 2. Allocation is bypassed and the
   Note On is forwarded on output Channel 2. The note's Expression Values are overridden with input Channel 2's
   current state — Expression Pitch Bend 0, CC #74 64, Channel Pressure 80 — which is what it already holds, so
   no average changes and no control dimension message need precede the Note On. Output: the Note On alone.
4. **Note Off E4 on input Channel 2.** Count 2 → 1; forwarded on output Channel 2. The identity remains active,
   so it stays in the channel's averages, nothing is recomputed, and nothing follows the Note Off.
5. **Note Off E4 on input Channel 2.** Count 1 → 0; forwarded. The identity leaves the averages, emptying the
   channel, and the retention rule of Section 7.1 fixes what it keeps — Expression Pitch Bend 0, CC #74 64,
   Channel Pressure 80. None changes, so the Note Off is emitted alone. Channel Pressure is *not* zeroed: in MPE
   Input Mode the Tuner defers to the sender (Section 7.4). Output Channel 2 is deallocated.

Close with the tally: two Note Ons in, two out; two Note Offs in, two out.

### Part 2 — Different input channels

Part 2 does **not** continue from Part 1. It starts from a fresh Zone of the same configuration, with every
Member Channel unoccupied and no Note Off history. This must be stated explicitly in the paper: were Part 2 read
as continuing, output Channel 2 would carry Part 1's retained Channel Pressure 80 and a last Note Off, which
would change both the tie-breaking at criterion (d) and which setup messages the emission optimization permits
omitting.

Input Channel 2 carries Expression Pitch Bend +10 cents; input Channel 3 carries −20 cents; input Channels 4
and 5 are at default expression.

1. **Note On E4 on input Channel 2.** Identity (2, E4). Step 1 → output Channel 2, joining the Pitch Class Group.
2. **Note On G4 on input Channel 2.** Identity (2, G4) — same input channel, different note number, so a
   different identity. Pitch class G is absent from the Pitch Class Group, which has spare capacity, so Step 1
   applies. Criterion (e) prefers input Channel 2, but it is occupied, so selection falls to the lowest channel
   number: output Channel 3. The Pitch Class Group is now full.
3. **Note On C4 on input Channel 4.** Pitch Class Group full → Step 2 → output Channel 4, joining the Expression
   Group.
4. **Note On A4 on input Channel 5.** Step 2 → output Channel 5. The Expression Group is now full and all four
   Member Channels are occupied.
5. **Note On E4 on input Channel 3.** Identity (3, E4) — **distinct** from (2, E4), because the input channel
   differs. Count 0 → 1, so allocation runs: Step 1 fails (Pitch Class Group full), Step 2 fails (Expression
   Group full), and Step 3 assigns the channel already holding pitch class E — output Channel 2. Section 6.2
   does not intervene: neither note's bend approaches `t` = 50 cents.

Output Channel 2 now carries two Note Ons for note number E4. Its Expression Pitch Bend is the average of the
two identities' values, `(+10 + −20) / 2 = −5`, emitted as Pitch Bend `T(E) − 5 = −18.7` cents. Each identity
holds reference count 1; no merging occurred, because the identities differ.

**Points to draw out:**

- The receiver's response to the two same-numbered Note Ons on one channel is undefined `[2, p. A-4]`. This is
  the acknowledged limitation of Section 7.6, arrived at concretely.
- Each identity keeps its own share of the average — the compensation for that ambiguity.
- Each Note On will be matched by its own Note Off, so the voice count reconciles downstream however the
  receiver resolved them.
- The trace also exhibits the fan-out Section 7.2 describes alongside the fan-in: input Channel 2's two notes
  reached output Channels 2 and 3, and its Expression Pitch Bend of +10 cents applies to both — output Channel 3
  emits `T(G) + 10 = +6.6` cents. One sentence; it is the natural counterpart of the collision being traced.
- The coincidence required for Case 2 to occur at all: the performer sounded E4 on two input channels *and* the
  allocator reached Step 3, which needed the Pitch Class Group to already hold E and the Expression Group to be
  full.

---

## Work item 6 — Reference [2] amendment

**Target.** The reference [2] entry, which currently ends "Page references follow the internal pagination of the
MIDI 1.0 Detailed Specification."

**Change.** Add that page references of the form `A-N` follow the separate pagination of the appendix
*Additional Explanations and Application Notes*, which numbers its pages independently of the main body.

**Why required.** This change introduces the paper's first `[2, p. A-4]` citations, and that appendix does not
share the main body's pagination. Without the note, `p. A-4` is unlocatable from the existing convention
statement. This is mechanical rather than discretionary: it is forced by the decision to cite the appendix, and
the appendix is where the specification's only direct treatment of repeated Note Ons lives.

---

## Citations introduced

| Citation | Text | Used by |
|---|---|---|
| `[2, p. A-4]` | "If an instrument receives two or more Note On messages with the same key number and MIDI channel, it must make a determination of how to handle the additional Note Ons. It is up to the receiver as to whether the same voice or another voice will be sounded, or if the messages will be ignored." | Work item 3, both cases |
| `[2, p. A-4]` | "The transmitter, however, must send a corresponding Note Off message for every Note On sent. If the transmitter were to send only one Note Off message, and if the receiver in fact assigned the two Note On messages to different voices, then one note would linger. Since there is no harm or negative side effect in sending redundant Note Off messages this is the recommended practice." | Work items 1 and 2 |
| `[2, p. 25]` | "The correct procedure of sending a Note-Off for each and every Note-On must still be followed." | Work item 1 |

The last already appears in the paper's vicinity — Section 3.5 cites `[2, pp. 24–25]` for the neighboring
material — so it is consistent with existing usage.

**Not used.** `[2, p. 22]`'s "Should more than one Note-On message be sent for a given channel, the receivers
response is not specified" is scoped to Omni-Off/Mono operation, and the MPE Tuner requires Mode 3 on its output
(Section 3.5). Citing it for the general case would misrepresent its scope. `[2, p. 22]`'s Mono-mode legato
passage concerns a *different* note number and is likewise inapplicable.

**Known gap.** The source prompt's research found no specification text comparing same-channel against
cross-channel duplicate note numbers, and none stating that a single Note Off silences all instances of a
repeated note number. Case 2's framing must therefore rest on `[2, p. A-4]`'s "unspecified receiver behavior"
and on the transmitter obligation, not on a citation that directly addresses the cross-channel case. The paper
must not imply stronger specification support than exists.

---

## Out of scope

- **The implementation.** Per the source prompt, `MpeTuner` and its collaborators are not consulted or changed.
- **Structural reorganization** beyond the single insertion into Section 5 and the two appends to Sections 7 and
  9. In particular, Section 7.6 is *appended*, not interleaved with Sections 7.1–7.5.
- **Revisiting the aggregation model or the allocation algorithm themselves.** This change describes how they
  behave under duplicate Note Ons; it does not alter either.
- **Non-MPE Input Mode worked example** for duplicate Note Ons — covered in prose by Work item 4.

---

## Decisions record

Four decisions were taken during design; each is settled and reflected above.

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Where should the new material live? | Split: new Section 5.1 for identity and reference counting, new Section 7.6 for the two cases | Each half sits with the machinery it governs; avoids a forward reference from Section 5 into Section 7 |
| 2 | Non-MPE Input Mode: does a duplicate Note On reset a note's accumulated Channel Pressure? | No — the note persists; Section 7.3's "always 0 at Note On" is qualified to the allocating Note On | Resetting discards live pressure mid-note and emits an unrequested audible drop |
| 3 | How many Note Offs when dropping a note with reference count > 1? | One per Note On forwarded | MIDI 1.0 obligates the transmitter per Note On and endorses redundant Note Offs `[2, p. A-4]`; one Note Off risks a lingering voice on a receiver that layered |
| 4 | Should Section 9 gain a worked example? | Yes — new Section 9.6 covering both cases, in MPE Input Mode | Reference counting is a new mechanism and Case 2 is only observable through a specific coincidence; matches Section 9's existing granularity |

The two clarifications beyond the source prompt — what criterion (b) counts, and Note Offs for identities with
no active count — were reviewed and approved for inclusion.

# MPE Tuner Paper — Note Ons for Already-Active Notes: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Date:** 2026-07-21

**Branch:** `doc/mpe-tuner-paper-multiple-note-ons`

**Pinned at:** commit `ed16036` (`Design duplicate Note On handling for MPE Tuner paper`) — repository HEAD when this
plan was written. The target document `docs/architecture/tuner/mpe-tuner-paper.md` is unmodified since `c6d0fa1`
(`Generalize default Note Off velocity`); every line number, quotation and reference count below was verified against
the working tree at `ed16036`. If the paper has been modified since, re-run the verification commands of Task 1 before
starting.

**Design input:** `issues/00154-mpe-tuner-poly-expr/paper/paper-multiple-note-ons-design.md` (committed as `ed16036`).
Its decisions are settled; this plan implements them and does not re-open them.

**Goal:** Update the MPE Tuner paper so that it specifies how the Tuner handles a Note On message that arrives for a
note which is already active, in both of the two circumstances that produce one.

**Architecture:** The change introduces one new concept — **Note Identity**, the pair (input channel, note number) —
and the **reference counting** mechanism that follows from it, in a new Section 5.1; then applies the consequences at
the four sites that depend on them (Section 6, a new Section 7.6, Section 7.3 with Section 3.3, and a new worked
example in Section 9.6), plus a pagination note on reference [2]. Inserting a new Section 5.1 forces a renumber of the
existing Sections 5.1–5.7 to 5.2–5.8, which is executed as its own task, first, and mechanically verified before any
new prose is written.

**Tooling:** Markdown, `sed` (BSD/macOS), `grep`, `git`. No code, no build, no tests.

---

## Global Constraints

- **Only one file is edited:** `docs/architecture/tuner/mpe-tuner-paper.md`. Do **not** touch the implementation.
  Per the design's *Out of scope* section, `MpeTuner` and its collaborators are neither consulted nor changed; the
  implementation in this repository is work in progress and is not yet aligned with the paper.
- **No other repository file references the paper's section numbers.** `docs/architecture/README.md` and
  `docs/architecture/tuner/README.md` link the file but cite no section of it; the `Section N` references in
  `docs/architecture/tuner/mpe-spec.md` are to the MPE Specification's own sections, not the paper's. The renumber of
  Task 1 is therefore contained entirely within the paper. Do not "fix" section numbers in any other file.
- **Register.** The paper is a formal specification paper. Match it: normative `shall` where an obligation is stated,
  defined terms bolded on first use and capitalized thereafter, cross-references by section number in the form
  `(Section 7.2)` / `Sections 6.2.2 and 6.2.3`, bracketed citations `[1, §3.3.1]` and `[2, p. 19]`, spec quotations in
  blockquotes followed by their citation. Technical academic tone; precise and not verbose.
- **Line width.** Wrap new and rewrapped prose at **120 columns**, matching Sections 3.x, 5.5, 6.x, 7.x and 9.3. Do not
  reflow paragraphs you are not otherwise editing.
- **Terminology.** The design input corrects the source prompt's typo "note identify"; the term is **Note Identity**
  throughout, and a duplicate Note Off **decrements** the reference count (the prompt said "incremented", which cannot
  reach 0).
- **Typography.** The paper uses the en dash `—` for parenthetical asides, `−` (U+2212 minus) for negative cent values
  (`−13.7`), `♯` for sharps, and backticks for symbols and computed values (`` `t` ``, `` `T(E) − 5 = −18.7` ``).
  Reproduce these exactly; do not substitute ASCII hyphens.
- **Commits.** One commit per task, message in the repository's imperative style (see `git log --oneline`). Stage only
  `docs/architecture/tuner/mpe-tuner-paper.md`, except Task 0 which stages this plan.

---

## Task ordering and why it differs from the design's work-item numbering

The design lists six work items. This plan reorders them for three reasons, and the order is load-bearing:

1. **The renumber runs first, alone (Task 1), before any new prose exists.** Every new passage in Tasks 3–8 cites
   post-renumber numbers (`Section 5.6` for the Allocation Algorithm, `Section 5.7` for Expression Value Computation
   for Shared Channels). If new prose were inserted before the renumber, the renumber's `sed` passes would rewrite the
   new citations too and shift them wrongly. Renumbering an untouched document is mechanically verifiable against the
   counts in this plan; renumbering a document that has grown new text is not.
2. **The renumber is split from the insertion of the new Section 5.1** (design Work item 1 becomes Tasks 1 and 3). They
   are independently reviewable: Task 1 is a purely mechanical, count-verifiable transformation with no prose judgment
   in it, and a reviewer can reject the new Section 5.1's wording while accepting the renumber.
3. **Reference [2]'s pagination note (design Work item 6) is pulled forward to Task 2**, ahead of every task that adds
   an `[2, p. A-4]` citation. This keeps each commit self-consistent: no commit ever contains a citation that the
   paper's own reference-convention statement cannot locate.

| Task | Design work item | Deliverable |
|---|---|---|
| 0 | — | Commit this plan |
| 1 | 1 (part A) | Renumber Sections 5.1–5.7 → 5.2–5.8 |
| 2 | 6 | Reference [2] appendix pagination note |
| 3 | 1 (part B) | New Section 5.1 + criterion (b) pointer |
| 4 | 2 | Section 6 Note Off count when dropping |
| 5 | 3 | New Section 7.6, *Duplicate Note On Messages* |
| 6 | 4 | Section 7.3 and Section 3.3 invariant qualification |
| 7 | 5 | New Section 9.6 worked example + Section 9 preamble |
| 8 | — | Whole-document consistency verification |

---

## Corrections to the design document's reference counts

The design's cross-reference impact table was re-counted at `ed16036`. Two figures in it are low. **Use the figures in
this plan, not the design's.**

1. **`Section 5.6` occurs 5 times, not 4.** The fifth is **line-wrapped across lines 647–648**, inside Section 6.2.1:

   ```
   sharing the channel would receive an unintended pitch deviation due to the averaged Pitch Bend computation (Section
   5.6), and the note that develops High Expression Pitch Bend will have its final Expression Pitch Bend diluted due to
   ```

   A line-based `grep "Section 5.6"` does not see it, which is how the design missed it. It is the **only** wrapped
   section reference in the paper — confirmed by `grep -n -A1 -E "Sections?$"`, which returns exactly this one hit.
   Task 1 handles it with a dedicated `Edit`, not with `sed`.

   **Total cross-references: 25, not 24.**

2. **13 heading lines are renumbered, not 7.** The design's "7 headings" counts only the `###` level (5.1–5.7). There
   are also 6 `####` headings (5.7.1–5.7.6), which its renumber table does list. Task 1 renumbers all 13.

Everything else in the design's table is confirmed exactly: `Section 5.1` × 6, `Section 5.2` × 1, `Section 5.3` × 0,
`Section 5.4` × 2, `Section 5.5` × 7, `Section 5.7.1` × 1, `Section 5.7.5` × 2, `Section 5.7.6` × 1, and no bare
`Section 5.7` reference.

---

## Task 0: Commit this plan

**Files:**
- Create: `issues/00154-mpe-tuner-poly-expr/paper/paper-multiple-note-ons-plan.md` (this file)

- [ ] **Step 1: Confirm the paper is untouched since `c6d0fa1`**

Run: `git log -1 --format='%h %s' -- docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `c6d0fa1 Generalize default Note Off velocity`

If it differs, stop and re-verify the counts in Task 1 Step 1 before proceeding.

- [ ] **Step 2: Commit**

```bash
git add issues/00154-mpe-tuner-poly-expr/paper/paper-multiple-note-ons-plan.md
git commit -m "Plan duplicate Note On handling for MPE Tuner paper"
```

---

## Task 1: Renumber Sections 5.1–5.7 to 5.2–5.8

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md` (headings at lines 377–578; 25 cross-references throughout)

**Interfaces:**
- Produces: the section numbers every later task cites. After this task, *Fundamental Invariant* is Section 5.2,
  *Dual-Group Channel Partitioning* is 5.3, *Group Size Allocation* is 5.4, *High Expression Pitch Bend* is 5.5,
  *Allocation Algorithm* is **5.6**, *Expression Value Computation for Shared Channels* is **5.7**, *Comparison with
  Standard MPE Allocation* is 5.8 with subsections 5.8.1–5.8.6. Section 5.1 is left vacant and is filled by Task 3.

**Nothing but numbers changes in this task.** No sentence is reworded, no content added.

- [ ] **Step 1: Record the pre-renumber counts**

Run:

```bash
grep -oE "Section 5\.[0-9](\.[0-9])?" docs/architecture/tuner/mpe-tuner-paper.md | sort | uniq -c
```

Expected — exactly this, totalling 24 line-visible references:

```
   6 Section 5.1
   1 Section 5.2
   2 Section 5.4
   7 Section 5.5
   4 Section 5.6
   1 Section 5.7.1
   2 Section 5.7.5
   1 Section 5.7.6
```

Run: `grep -n -A1 -E "Sections?\$" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: exactly one hit — line 647 ending in `(Section`, line 648 beginning `5.6),`. This is the 25th reference.

If either result differs, the document has changed since `ed16036`; stop and recount before continuing.

- [ ] **Step 2: Fix the line-wrapped reference first**

Do this before the `sed` passes, while the reference is still unambiguously identifiable. Use `Edit` with:

`old_string`:

```
computation (Section
5.6), and the note
```

`new_string`:

```
computation (Section
5.7), and the note
```

After this edit line 648 begins `5.7),` with no `Section` token on the line, so none of the `sed` passes below can
touch it.

- [ ] **Step 3: Renumber the headings, in descending order**

The `-e` expressions are evaluated in the order given, and descending order is what makes this safe: once a line has
been rewritten by an earlier expression, no later expression matches it again. `^### ` cannot match a `#### ` line
(the fourth character is `#`, not a space), so the two heading levels never interfere.

```bash
sed -i '' \
  -e 's/^#### 5\.7\./#### 5.8./' \
  -e 's/^### 5\.7 /### 5.8 /' \
  -e 's/^### 5\.6 /### 5.7 /' \
  -e 's/^### 5\.5 /### 5.6 /' \
  -e 's/^### 5\.4 /### 5.5 /' \
  -e 's/^### 5\.3 /### 5.4 /' \
  -e 's/^### 5\.2 /### 5.3 /' \
  -e 's/^### 5\.1 /### 5.2 /' \
  docs/architecture/tuner/mpe-tuner-paper.md
```

- [ ] **Step 4: Renumber the cross-references, in descending order**

Same descending-order rule. Ascending order would double-shift: rewriting `5.1`→`5.2` first would make the subsequent
`5.2`→`5.3` pass catch the references it had just created. Note that `Section 5\.7` correctly carries `Section 5.7.1`
to `Section 5.8.1` without a separate expression.

```bash
sed -i '' \
  -e 's/Section 5\.7/Section 5.8/g' \
  -e 's/Section 5\.6/Section 5.7/g' \
  -e 's/Section 5\.5/Section 5.6/g' \
  -e 's/Section 5\.4/Section 5.5/g' \
  -e 's/Section 5\.3/Section 5.4/g' \
  -e 's/Section 5\.2/Section 5.3/g' \
  -e 's/Section 5\.1/Section 5.2/g' \
  docs/architecture/tuner/mpe-tuner-paper.md
```

- [ ] **Step 5: Re-count the cross-references**

Run:

```bash
grep -oE "Section 5\.[0-9](\.[0-9])?" docs/architecture/tuner/mpe-tuner-paper.md | sort | uniq -c
```

Expected — each count carried intact to its new number, and **no `Section 5.1`**, which Task 3 will introduce:

```
   6 Section 5.2
   1 Section 5.3
   2 Section 5.5
   7 Section 5.6
   4 Section 5.7
   1 Section 5.8.1
   2 Section 5.8.5
   1 Section 5.8.6
```

A `Section 5.1` or `Section 5.9` in this output means the passes ran out of order — `git checkout
docs/architecture/tuner/mpe-tuner-paper.md` and restart the task.

- [ ] **Step 6: Verify the wrapped reference and the headings**

Run: `grep -n -A1 -E "Sections?\$" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: line 647 ending in `(Section`, line 648 beginning `5.7),`.

Run: `grep -n "^#\{3,4\} 5\." docs/architecture/tuner/mpe-tuner-paper.md`

Expected — 13 heading lines, 5.2 through 5.8.6, with no 5.1:

```
377:### 5.2 Fundamental Invariant
387:### 5.3 Dual-Group Channel Partitioning
400:### 5.4 Group Size Allocation
413:### 5.5 High Expression Pitch Bend
421:### 5.6 Allocation Algorithm
524:### 5.7 Expression Value Computation for Shared Channels
538:### 5.8 Comparison with Standard MPE Allocation
542:#### 5.8.1 Channel Sharing Before Exhaustion
552:#### 5.8.2 Prioritizing Intonation Over Note Preservation
556:#### 5.8.3 Gentle Degradation via Averaging
560:#### 5.8.4 Same Note Number on Multiple Channels
568:#### 5.8.5 Master Channel Pitch Bend
576:#### 5.8.6 Fixed Channel Mode
```

- [ ] **Step 7: Verify no prose changed**

Run: `git diff --stat docs/architecture/tuner/mpe-tuner-paper.md`

Expected: 37 insertions and 37 deletions on that file — 13 heading lines plus 24 prose lines. Those 24 lines carry 25
cross-references: line 256 carries two (`Section 5.7.5` and `Section 5.1`), and line 648 carries the wrapped one fixed
in Step 2. Then run:

```bash
git diff -U0 docs/architecture/tuner/mpe-tuner-paper.md | grep -E "^[+-]" | grep -v "^[+-][+-]" | grep -civ "5\."
```

Expected: `0` — every changed line contains a `5.x` token, i.e. no line was touched for any other reason.

- [ ] **Step 8: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Renumber MPE Tuner paper Sections 5.1-5.7 as 5.2-5.8"
```

---

## Task 2: Add the appendix pagination note to reference [2]

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:998` (reference `[2]` under `## References`)

**Interfaces:**
- Produces: the convention that makes the `[2, p. A-4]` citations of Tasks 3, 4, 5 and 7 locatable. Those citations
  point into the appendix *Additional Explanations and Application Notes*, which paginates independently of the main
  body, so the existing single-sentence convention statement cannot resolve them.

- [ ] **Step 1: Amend the reference entry**

Use `Edit` on line 998.

`old_string`:

```
Third Edition. Page references follow the internal pagination of the MIDI 1.0 Detailed Specification.
```

`new_string`:

```
Third Edition. Page references follow the internal pagination of the MIDI 1.0 Detailed Specification. Page references of the form `A-N` follow the separate pagination of the appendix *Additional Explanations and Application Notes*, which numbers its pages independently of the main body.
```

Note that the reference entries are single long lines and are **not** wrapped at 120 columns; keep this one on one
line, consistent with entry [1].

- [ ] **Step 2: Verify**

Run: `grep -c "Additional Explanations and Application Notes" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1`

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Document appendix pagination for MIDI 1.0 reference"
```

---

## Task 3: Add Section 5.1, *Note Identity and Reference Counting*

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md` — insert a new `### 5.1` between the Section 5 preamble
  (ending at line 375, the velocity-0 note) and `### 5.2 Fundamental Invariant` (line 377 post-renumber)
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:472` (tie-breaking criterion (b), inside Section 5.6)
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:491-499` (the motivation for criterion (b), inside Section 5.6)

**Interfaces:**
- Consumes: the post-renumber numbering established by Task 1 — in particular *Allocation Algorithm* = Section 5.6 and
  *Expression Value Computation for Shared Channels* = Section 5.7; and the reference [2] pagination note from Task 2,
  which locates the `[2, p. A-4]` citations introduced here.
- Produces: the term **Note Identity** = (input channel, note number); the **reference count**; the 0→1 allocation
  rule, the >1 bypass rule, the →0 deallocation rule, and the discard rule for a Note Off against an identity with no
  active count; the one-Note-Off-per-Note-On guarantee; and the reading of "count of active notes" as a count of
  distinct identities. Tasks 4, 5, 6 and 7 all cite `Section 5.1` for these.

**Keep Section 5.1 to the general principles.** At this point in the paper the reader has met neither the allocation
algorithm, nor Expression Value averaging, nor note dropping, so Section 5.1 must define the concept and state the
rules, and leave the justifications to the sections that own them. Concretely: the averaging argument behind the
identity reading of criterion (b) goes to Section 5.6 (Step 3 below), the rationale for discarding a Note Off against a
cleared identity goes to Section 6 (Task 4), the Non-MPE note-bookkeeping caveat goes to Section 7.3 (Task 6), and
everything about how a duplicate Note On is actually processed goes to Section 7.6 (Task 5). A few forward references
remain, and that is fine; a chain of forward-dependent reasoning is not.

- [ ] **Step 1: Insert the new section**

The insertion point is immediately after the velocity-0 paragraph that closes the Section 5 preamble (line 375,
beginning "Throughout this paper, a Note On with velocity 0") and immediately before `### 5.2 Fundamental Invariant`.
Use `Edit` with `old_string` `### 5.2 Fundamental Invariant` and an `new_string` consisting of the block below
followed by that same heading, preserving the blank line between them.

Insert verbatim:

```markdown
### 5.1 Note Identity and Reference Counting

The allocation procedure needs a notion of *which note* an incoming message addresses. A note is identified internally
by the pair (input channel, note number), called its **Note Identity**. Note Identity is a construct of the Tuner's own
bookkeeping and has no counterpart in MIDI 1.0 or in the MPE Specification; it exists because the Tuner must decide, for
every incoming Note On, whether the message begins a new note or refers to one that is already active.

Both components are essential. The note number belongs in the identity for the immediate reason that notes of different
note numbers are different notes. The input channel belongs in it because it is the carrier of per-note information in
both input modes — of a note's Expression Values in MPE Input Mode (Section 7.2), and of the Polyphonic Key Pressure
addressed to a note in Non-MPE Input Mode (Section 7.3) — so two notes of the same note number arriving on different
input channels are independent notes, and merging them would discard one of them.

Each active Note Identity carries a **reference count**, incremented on each Note On the Tuner receives for it and
decremented on each Note Off. Four rules govern the count:

- The allocation algorithm of Section 5.6 runs **only** on the transition from 0 to 1.
- A Note On that raises the count above 1 bypasses allocation entirely and is forwarded on the output Member Channel
  already bound to that identity. Section 7.6 specifies the treatment of such duplicate Note On messages in full.
- Deallocation occurs **only** on the transition to 0. Retaining the binding until then is what allows every later Note
  Off to be forwarded to the correct output channel, and it keeps the identity present in that channel's Expression
  Value averages (Section 7.1) for as long as any Note On remains unmatched.
- A Note Off for an identity that holds no active count has no channel binding and is discarded. This arises chiefly
  after a note has been dropped by the Tuner's own decision (Section 6).

The counting exists to satisfy an obligation MIDI 1.0 places on transmitters: "The transmitter, however, must send a
corresponding Note Off message for every Note On sent" [2, p. A-4], restated at [2, p. 25]. The obligation is per Note
On, not per note number, and the specification gives the reason: "If the transmitter were to send only one Note Off
message, and if the receiver in fact assigned the two Note On messages to different voices, then one note would linger"
[2, p. A-4]. Because every incoming Note Off is forwarded on the bound channel, and the count admits exactly as many
Note Offs as Note Ons, the Tuner emits exactly one Note Off for every Note On it forwarded.

Throughout this paper, a channel's **count of active notes** means the number of distinct active Note Identities on it,
not the sum of their reference counts.
```

- [ ] **Step 2: Point criterion (b) at the new definition**

Use `Edit` on line 472 (inside Section 5.6's tie-breaking list). A bare citation suffices here — Section 5.1 defines
what "count of active notes" means, and Step 3 supplies the justification where the paper already motivates the
criterion.

`old_string`:

```
- **(b)** Among those, prefer the channel with the lowest count of active notes.
```

`new_string`:

```
- **(b)** Among those, prefer the channel with the lowest count of active notes (Section 5.1).
```

- [ ] **Step 3: Justify the identity reading where criterion (b) is motivated**

The reason the count is of *identities* is the averaging argument, and Section 5.6 already sets that argument out in
criterion (b)'s motivation, citing Section 5.7. The clarification belongs there, next to the reasoning it depends on,
rather than in Section 5.1 where the reader has met neither the criterion nor the averaging. Add a third sub-bullet.

Use `Edit`. `old_string`:

```
    * On freeing, the same criterion drops the fewest notes, keeping the impact as low as possible.
```

`new_string`:

```
    * The count is of distinct Note Identities (Section 5.1), because that is what the averaging counts: an identity
      whose reference count exceeds 1 still contributes a single term to each average, so it adds no further loss of
      independence and must not be counted twice.
    * On freeing, the same criterion drops the fewest notes, keeping the impact as low as possible. Section 6.1 reuses
      the criterion, and this reading of it, when selecting a channel to free.
```

- [ ] **Step 4: Verify the section is in place and numbered correctly**

Run: `grep -n "^#\{3,4\} 5\." docs/architecture/tuner/mpe-tuner-paper.md`

Expected: 14 heading lines now, beginning `### 5.1 Note Identity and Reference Counting` and continuing
`### 5.2 Fundamental Invariant` … `#### 5.8.6 Fixed Channel Mode`.

Run: `grep -c "Section 5\.1" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `2` — the criterion (b) citation from Step 2 and the sub-bullet from Step 3. (Tasks 4–7 add the rest.)

- [ ] **Step 5: Verify the new section's own cross-references resolve**

Run:

```bash
grep -oE "Section [0-9]+(\.[0-9]+)*" docs/architecture/tuner/mpe-tuner-paper.md | sort -u -V
```

Expected: every number listed appears as a heading in the document. Section 5.1 cites only Sections 5.6, 6, 7.1, 7.2,
7.3 and 7.6 — all of which exist, except **Section 7.6, which Task 5 creates**. That is the only forward reference this
task leaves open; it is closed two tasks later and is expected here.

- [ ] **Step 6: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Define Note Identity and reference counting in MPE Tuner paper"
```

---

## Task 4: Amend Section 6 — one Note Off per forwarded Note On

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:591-595` (the paragraph in Section 6 beginning "In every dropping
  case")

**Interfaces:**
- Consumes: `Section 5.1` (reference count; discard rule for an identity with no active count) from Task 3; the
  `[2, p. A-4]` pagination convention from Task 2.
- Produces: nothing later tasks depend on. The neutral release velocity of 64 already specified in this paragraph is
  retained unchanged and now applies to *each* emitted Note Off.

**Why:** "an explicit Note Off message for each dropped note" is imprecise once a note may carry a reference count
above 1. Design decision 3: one Note Off per Note On forwarded.

- [ ] **Step 1: Replace the paragraph**

Use `Edit`. `old_string` — the paragraph exactly as it stands at lines 591–595:

```
In every dropping case, the Tuner emits an explicit Note Off message for each dropped note; when dropping is triggered by
an incoming note, those Note Off messages precede that note's Note On. Because a dropped note ends by the Tuner's
decision rather than by a release gesture, no release velocity exists for it; the emitted Note Off should therefore carry
the neutral velocity of 64 (40H) that MIDI 1.0 recommends when release velocity information is unavailable
[2, pp. 10–11]. (Note Off emission upon Zone reconfiguration is a distinct case, governed by Section 4.2.)
```

`new_string`:

```
In every dropping case, the Tuner emits an explicit Note Off message for each Note On it forwarded for the dropped note
— that is, as many Note Offs as the note's reference count (Section 5.1), which is one in the ordinary case of a note
whose Note On was not repeated. Emitting fewer would risk leaving a voice sounding on a receiver that assigned the
repeated Note Ons to separate voices, and MIDI 1.0 endorses the redundancy that the per-Note-On rule can otherwise
produce: "Since there is no harm or negative side effect in sending redundant Note Off messages this is the recommended
practice" [2, p. A-4]. When dropping is triggered by an incoming note, those Note Off messages precede that note's Note
On. Because a dropped note ends by the Tuner's decision rather than by a release gesture, no release velocity exists for
it; each emitted Note Off should therefore carry the neutral velocity of 64 (40H) that MIDI 1.0 recommends when release
velocity information is unavailable [2, pp. 10–11]. Dropping also clears the note's reference count and its channel
binding, so a Note Off the performer sends for it afterwards is discarded (Section 5.1): the Tuner has already emitted
that note's Note Offs, and forwarding the performer's later ones would exceed the one-Note-Off-per-Note-On obligation
rather than satisfy it. (Note Off emission upon Zone reconfiguration is a distinct case, governed by Section 4.2.)
```

- [ ] **Step 2: Verify**

Run: `grep -c "as many Note Offs as the note's reference count" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1`

Run: `grep -c "for each dropped note" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `0` — the imprecise phrasing is gone.

Run: `grep -c "neutral velocity of 64 (40H)" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1` — the release-velocity rule survived the rewrite.

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Specify one Note Off per forwarded Note On when dropping notes"
```

---

## Task 5: Add Section 7.6, *Duplicate Note On Messages*

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md` — append a new `### 7.6` after Section 7.5, *Message Ordering*
  (which ends at line 823, the emission-optimization sentence), immediately before the `---` that closes Section 7

**Interfaces:**
- Consumes: `Section 5.1` (Note Identity, reference counting, one-Note-Off-per-Note-On) from Task 3; the `[2, p. A-4]`
  pagination convention from Task 2; post-renumber `Section 5.6` = *Allocation Algorithm* from Task 1.
- Produces: `Section 7.6` and its two subsections **`Section 7.6.1`** (same input channel) and **`Section 7.6.2`**
  (different input channels). Tasks 6 and 7 cite these exact numbers. It also closes the forward reference to
  Section 7.6 left open by Task 3.

**Placement note:** append after Section 7.5 rather than interleaving. Both cases are stated partly in terms of what is
and is not emitted alongside the duplicate Note On, which Section 7.5 governs. No renumbering results.

**Numbering note:** the subsections are numbered `7.6.1` / `7.6.2` because the paper numbers every `####` heading
(compare 5.8.1–5.8.6 and 6.2.1–6.2.3) and cites by number throughout. The "Case 1" / "Case 2" labels are kept in the
heading text so that prose and the Section 9.6 worked example can refer to them by name as well as by number.

- [ ] **Step 1: Insert the new section**

Use `Edit` with `old_string` being the last sentence of Section 7.5 plus the separator that follows it:

```
An implementation may omit any of the three control dimension messages whose value is unchanged since its last emission on that output channel, relying on the state retention rule of Section 7.1.

---
```

and `new_string` being that same sentence, a blank line, the block below verbatim, a blank line, and then `---`:

```markdown
### 7.6 Duplicate Note On Messages

A Note On may arrive for a note that is already sounding on an output Member Channel. MIDI 1.0 contemplates this
directly, and leaves the receiver's response deliberately undefined:

> "If an instrument receives two or more Note On messages with the same key number and MIDI channel, it must make a
> determination of how to handle the additional Note Ons. It is up to the receiver as to whether the same voice or
> another voice will be sounded, or if the messages will be ignored." [2, p. A-4]

Two circumstances produce the situation, and Note Identity (Section 5.1) is what distinguishes them. In the first the
Note Ons share an input channel and therefore denote the *same* identity; in the second they arrive on different input
channels and denote *distinct* identities that the allocation algorithm has placed on one output Member Channel.

#### 7.6.1 Case 1: Note Ons from the Same Input Channel

A Note On arrives on the input channel that already carries an active note of that note number, with no intervening
Note Off. The identity is unchanged, and the duplicate is merged into the existing note: the note remains a single term
in its output channel's Expression Value averages rather than becoming two. Its Expression Values are overridden with
the latest values of its input channel. Under the update propagation of Section 7.2 that override is a **no-op** — the
note already holds those values, having received them as they arrived on the input channel — so no average changes. The
rule is stated in override form because it remains the correct rule for an implementation that instead snapshots
Expression Values at Note On.

Because no average changes, no control dimension message need accompany the duplicate Note On; an implementation may
omit all three under the emission optimization of Section 7.5.

Allocation is bypassed. Following Section 5.1, the identity's reference count is incremented, the allocation algorithm
of Section 5.6 does not run, and the Note On is forwarded on the output Member Channel already bound to the identity.

The channel's set of active identities is likewise unchanged, which settles the interaction with note dropping. The
allocation-time High Expression Pitch Bend rules of Sections 6.2.2 and 6.2.3 do not engage: both are predicated on a
note being *assigned* to a channel, and no assignment occurs here. Invariant 2 of Section 6.3 — an active note with a
High Expression Pitch Bend is always the sole active note on its channel — is preserved automatically, since a
duplicate Note On for a high-bend note leaves it the sole identity on the channel.

Forwarding the duplicate rather than suppressing it preserves the performer's intention to mark the note as active more
than once. What the receiving instrument makes of it — sounding an additional voice, retriggering the existing one, or
ignoring the message — is exactly the latitude [2, p. A-4] grants it, so the outcome is the specification's own
expectation rather than an assumption the Tuner makes about the instrument downstream.

#### 7.6.2 Case 2: Note Ons from Different Input Channels

Note Ons with the same note number arrive on different input channels, and the allocation algorithm places them on the
same output Member Channel. The identities are **distinct**, discriminated by input channel notwithstanding the shared
note number, so no merging occurs: each is a separate term in the output channel's Expression Value averages, carrying
its own Expression Values, and each holds its own reference count — 1 apiece, absent duplication of the kind covered by
Section 7.6.1 on top.

The situation is reachable only through Step 3 of the allocation algorithm (Section 5.6), the assignment of a new note
to a channel that already holds active notes of its pitch class; Steps 1 and 2 assign unoccupied channels, and Step 4
frees one before assigning it.

The output device then receives two Note On messages with the same note number on one channel, and its response is
undefined [2, p. A-4]. The Tuner neither avoids this outcome nor attenuates it, for three reasons.

- Reaching it requires two independent and individually uncommon events to coincide: the performer sounding the same
  note number on two input channels, *and* the allocator reaching Step 3, which itself requires the Pitch Class Group
  already to hold that pitch class and the Expression Group to be at full capacity.
- Each note keeps its own share of the aggregation. Expressive independence survives in the average, whereas merging
  the two identities would destroy it outright.
- Each Note On is matched by its own Note Off (Section 5.1), so the downstream voice count reconciles however the
  receiver resolved the duplicates.

This does not fully preserve the performer's intention, and the design does not claim that it does: Case 2 is an
acknowledged limitation of the allocation strategy rather than a defect with a defined remedy. MIDI 1.0 offers no
guidance comparing same-channel with cross-channel duplication of a note number, so the position rests on the undefined
receiver behavior quoted above and on the transmitter obligation of Section 5.1 — and on nothing stronger.
```

- [ ] **Step 2: Verify placement and numbering**

Run: `grep -n "^#\{3,4\} 7\." docs/architecture/tuner/mpe-tuner-paper.md`

Expected, in this order: `### 7.1 Aggregation Model`, `### 7.2 MPE Input Mode`, `### 7.3 Non-MPE Input Mode`,
`### 7.4 Channel Pressure Reset at Note Off`, `### 7.5 Message Ordering`, `### 7.6 Duplicate Note On Messages`,
`#### 7.6.1 Case 1: Note Ons from the Same Input Channel`,
`#### 7.6.2 Case 2: Note Ons from Different Input Channels`.

- [ ] **Step 3: Verify Section 7 still closes correctly**

Run: `sed -n '/^## 8\. Real-Time Tuning Changes/{=;q;}' docs/architecture/tuner/mpe-tuner-paper.md`

Expected: a line number. Then confirm the two lines before it are a blank line and `---` — i.e. the horizontal rule
that separated Sections 7 and 8 was preserved, not consumed by the insertion.

Run: `grep -c "^### 7.6 Duplicate Note On Messages" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1` — and it must appear *before* `## 8. Real-Time Tuning Changes`, not after.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Specify duplicate Note On handling in MPE Tuner paper"
```

---

## Task 6: Qualify the "Polyphonic Key Pressure is 0 at Note On" invariant

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:747-749` (Section 7.3, the sentence "Consequently, a note's
  Polyphonic Key Pressure value is always 0 at the time its Note On is issued")
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:752-755` (Section 7.3, the recurrence "which is always 0 at Note
  On")
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:215-219` (Section 3.3, item 3, the **Channel Pressure** bullet)

**Interfaces:**
- Consumes: `Section 5.1` (the 0→1 allocating transition) from Task 3; `Section 7.6.1` from Task 5.
- Produces: nothing later tasks depend on.

**Why:** Case 1 falsifies the claim as written. A note may accumulate converted Polyphonic Key Pressure and then
receive a duplicate Note On, at which point its Channel Pressure Expression Value is not 0. Design decision 2 qualifies
the invariant to the *allocating* Note On rather than resetting the note; resetting would discard live pressure
mid-note and emit an audible pressure drop the performer never gestured.

- [ ] **Step 1: Qualify the primary statement in Section 7.3**

Use `Edit`. `old_string`:

```
note number for which no Note On was issued on that input channel, it is ignored. Consequently, a note's Polyphonic Key
Pressure value is always 0 at the time its Note On is issued.
```

`new_string`:

```
note number for which no Note On was issued on that input channel, it is ignored. Note bookkeeping in this mode is
therefore per input channel, as the Note Identity of Section 5.1 requires: item 2 of Section 3.3 merges the
channel-global *controls* arriving on the input channels, not the notes themselves. Consequently, a note's Polyphonic
Key Pressure value is always 0 at the time of the Note On that *allocates* it — the Note On that raises its reference
count from 0 to 1 (Section 5.1). A duplicate Note On for an already-active note does not re-initialize the note, and
therefore leaves in place whatever pressure the note has accumulated since (Section 7.6.1).
```

- [ ] **Step 2: Qualify the recurrence later in Section 7.3**

Use `Edit`. `old_string`:

```
unlike in MPE Input Mode — the retained value can have no observable effect on a subsequent note: because per-note
Channel Pressure originates from Polyphonic Key Pressure, which is always 0 at Note On, the channel's Channel Pressure
must be reset to 0 by the time of the next Note On.
```

`new_string`:

```
unlike in MPE Input Mode — the retained value can have no observable effect on a subsequent note: because per-note
Channel Pressure originates from Polyphonic Key Pressure, which is always 0 at an allocating Note On — and a Note On on
an emptied channel is necessarily an allocating one — the channel's Channel Pressure must be reset to 0 by the time of
the next Note On.
```

- [ ] **Step 3: Qualify the primary statement in Section 3.3, item 3**

The same onset assumption is stated in Section 3.3, item 3, under **Channel Pressure**. Use `Edit`. `old_string`:

```
      ordering is in any case uncommon and of questionable musical meaning, since Polyphonic Key Pressure models
      pressure on a key that is already held.
```

`new_string`:

```
      ordering is in any case uncommon and of questionable musical meaning, since Polyphonic Key Pressure models
      pressure on a key that is already held. Onset here means the Note On that allocates the note; a duplicate Note On
      for a note that is already active does not re-initialize it, and the note keeps the pressure it has accumulated
      (Sections 5.1 and 7.6.1).
```

- [ ] **Step 4: Verify no unqualified statement of the invariant remains**

Run: `grep -n "always 0 at" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: exactly two hits, both qualified — `always 0 at the time of the Note On that *allocates* it` and
`always 0 at an allocating Note On`. Any surviving `always 0 at Note On` or `always 0 at the time its Note On is
issued` is a miss.

Run: `grep -c "does not re-initialize" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `2` — one in Section 7.3, one in Section 3.3.

Run: `grep -c "not the notes themselves" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1` — the Non-MPE note-bookkeeping caveat relocated here from Section 5.1.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Qualify Polyphonic Key Pressure onset invariant to allocating Note On"
```

---

## Task 7: Add Section 9.6 worked example and update the Section 9 preamble

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md:853-857` (Section 9 preamble)
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md` — append a new `### 9.6` after Section 9.5 (which ends at
  line 976), immediately before the `---` that closes Section 9

**Interfaces:**
- Consumes: `Section 5.1` from Task 3; `Section 7.6.1` and `Section 7.6.2` from Task 5; post-renumber `Section 5.6` =
  *Allocation Algorithm* from Task 1.
- Produces: nothing later tasks depend on.

**Mode and configuration (fixed by the design, do not vary):** entirely **MPE Input Mode**, consistent with Sections
9.3 and 9.5, because Case 2 is only fully expressive where per-input-channel Expression Values exist. The Non-MPE
Polyphonic Key Pressure interaction is covered in prose by Task 6 and needs no example. The configuration reuses
Section 9.3's for continuity: Lower Zone, 4 Member Channels (Channels 2–5), so `a` = 2 and `b` = 2; quarter-comma
meantone; `t` = 50 cents. Under Section 4.2's shared Zone configuration the input Member Channels are 2–5 as well.

**Density (match Sections 9.1–9.5, do not exceed).** The existing examples run one to three sentences per numbered
step, and Section 9.4 manages a full note-drop in four. Keep to that. In particular, do not restate facts the reader
already has: which control values are at their defaults and may therefore be omitted (Section 7.5 and Section 9.3
establish this), the full recital of a tie-breaking criterion when only its outcome matters, that MPE Input Mode does
not zero Channel Pressure at Note Off (Section 9.3 step 6 makes this point at length already), or the contents of the
retention rule. State the assignment, the count transition, and the emitted messages; cite the section that explains
why. Steps that only fill channels to set up the collision should be merged rather than enumerated.

**Numeric values (verify, do not recompute differently).** Quarter-comma meantone tempers the fifth to 696.578 cents.
`T(E) ≈ −13.7` cents (4 fifths less 2 octaves = 386.31, against 12-EDO's 400) — the same value Section 9.3 already
uses. `T(G) ≈ −3.4` cents (696.578 against 700). Derived: `T(E) + 10 = −3.7`, `T(E) − 5 = −18.7`, `T(G) + 10 = +6.6`.

- [ ] **Step 1: Update the Section 9 preamble**

Use `Edit`. `old_string` — note `Section 5.6` here is the post-Task-1 form:

```
The following examples trace concrete message sequences through the Tuner, illustrating in turn the allocation algorithm
(Section 5.6), real-time retuning (Section 8), the Expression Value aggregation model (Section 7), and the two
circumstances in which notes are dropped (Section 6).
```

`new_string`:

```
The following examples trace concrete message sequences through the Tuner, illustrating in turn the allocation algorithm
(Section 5.6), real-time retuning (Section 8), the Expression Value aggregation model (Section 7), the two
circumstances in which notes are dropped (Section 6), and the handling of Note On messages that arrive for notes
already active (Section 7.6).
```

- [ ] **Step 2: Insert the worked example**

Use `Edit` with `old_string` being the last two lines of Section 9.5 plus the separator that follows:

```
a note with a High Expression Pitch Bend is always the sole active note on its channel. Channels 4 and 5 are unaffected;
E3 and E4 keep their own tuning and expression.

---
```

and `new_string` being those same two lines, a blank line, the block below verbatim, a blank line, and then `---`:

```markdown
### 9.6 Duplicate Note On Messages

This example is in MPE Input Mode, so criterion (e) of Section 5.6 prefers the note's own input channel. It reuses the
configuration of Section 9.3: a Lower Zone with 4 Member Channels (Channels 2–5), giving `a` = 2 and `b` = 2, in
quarter-comma meantone, with `T(E) ≈ −13.7` cents, `T(G) ≈ −3.4` cents and `t` = 50 cents. The two parts are
independent traces, each starting from a freshly configured Zone.

**Part 1 — the same input channel.** Input Channel 2 starts at the defaults of Section 4.2.

1. **Note On E4 on input Channel 2.** Identity (2, E4), reference count 0 → 1, so allocation runs (Section 5.1). Step 1
   assigns output Channel 2, which joins the Pitch Class Group. Pitch Bend `T(E) = −13.7` cents precedes the Note On.

2. **Channel Pressure 80 on input Channel 2.** Propagated to the note (Section 7.2); output Channel 2 holds one
   identity, so its average is 80 and Channel Pressure 80 is emitted.

3. **A second Note On E4 on input Channel 2**, the first still active. The identity is unchanged, so the count goes
   1 → 2, allocation is bypassed, and the Note On is forwarded on output Channel 2 (Section 7.6.1). Overriding the
   note's Expression Values with input Channel 2's current state changes nothing, so no average moves and the Note On
   is emitted alone.

4. **Note Off E4 on input Channel 2.** Count 2 → 1, forwarded on output Channel 2. The identity stays active and stays
   in the channel's averages, so nothing follows the Note Off.

5. **Note Off E4 on input Channel 2.** Count 1 → 0, forwarded. The identity leaves the averages, emptying the channel;
   the retention rule of Section 7.1 leaves all three values unchanged, so the Note Off is again emitted alone. Output
   Channel 2 is deallocated.

Two Note Ons entered and two were forwarded, two Note Offs entered and two were forwarded — the reconciliation
Section 5.1 requires, whatever the instrument made of the repeated Note On.

**Part 2 — different input channels.** This part does not continue from Part 1: it starts from a freshly configured
Zone, so no channel carries Part 1's retained Channel Pressure or its Note Off history, either of which would change
the tie-breaking and the setup messages that may be omitted. Input Channel 2 carries an Expression Pitch Bend of
+10 cents and input Channel 3 one of −20 cents.

1. **Note On E4 on input Channel 2.** Identity (2, E4). Step 1 assigns output Channel 2, which joins the Pitch Class
   Group, emitting Pitch Bend `T(E) + 10 = −3.7` cents.

2. **Note On G4 on input Channel 2.** Identity (2, G4): the same input channel, but a different note number and hence a
   different identity. Step 1 applies, and input Channel 2 being occupied, it assigns output Channel 3 — filling the
   Pitch Class Group.

3. **Note On C4 on input Channel 4, then A4 on input Channel 5.** Step 2 assigns output Channels 4 and 5, filling the
   Expression Group. All four Member Channels are now occupied.

4. **Note On E4 on input Channel 3.** Identity (3, E4) — **distinct** from (2, E4), because the input channel differs —
   so its count goes 0 → 1 and allocation runs. Steps 1 and 2 fail, both groups being full, so Step 3 assigns the
   channel already holding pitch class E: output Channel 2. Neither note's bend approaches `t`, so Section 6.2 does not
   intervene.

Output Channel 2 now carries two Note Ons for note number E4, one per identity, and its Expression Pitch Bend is the
average of the two, `(+10 + −20) / 2 = −5`, emitted as Pitch Bend `T(E) − 5 = −18.7` cents. Both reference counts
remain 1: the identities differ, so no merging occurred. How the instrument resolves the two same-numbered Note Ons is
undefined [2, p. A-4] — the acknowledged limitation of Section 7.6.2, reached here only because the performer sounded
E4 on two input channels *and* the allocator had exhausted both groups. The trace also shows the fan-out that
accompanies this fan-in (Section 7.2): input Channel 2's +10 cents reaches output Channel 3 as well, which emits
`T(G) + 10 = +6.6` cents.
```

- [ ] **Step 3: Verify placement and numbering**

Run: `grep -n "^#\{3,4\} 9\." docs/architecture/tuner/mpe-tuner-paper.md`

Expected: six `###` headings, `### 9.1` … `### 9.6 Duplicate Note On Messages`, and **no `####` heading**. Section 9
has no subsections anywhere else, so the two parts of this example are introduced by bold lead-ins
(`**Part 1 — the same input channel.**`) in the manner of Section 7.5's `**Note On.**` / `**Note Off.**`, not by
headings of their own.

- [ ] **Step 4: Verify the arithmetic in the example**

Run:

```bash
grep -oE "T\(E\)[^`]*|T\(G\)[^`]*" docs/architecture/tuner/mpe-tuner-paper.md | sort -u
```

Confirm by inspection that every expression involving `T(E)` evaluates with `T(E) = −13.7` and every one involving
`T(G)` with `T(G) = −3.4`: `T(E) = −13.7`, `T(E) + 10 = −3.7`, `T(E) − 5 = −18.7`, `T(G) + 10 = +6.6`. Section 9.3's
pre-existing `T(E) + 20 = +6.3` and Section 9.5's `T(E) + 101 = +87.3` must also still be present and unchanged.

- [ ] **Step 5: Verify Section 9 still closes correctly**

Run: `sed -n '/^## 10\. Summary/{=;q;}' docs/architecture/tuner/mpe-tuner-paper.md`

Expected: a line number, with a blank line and `---` on the two lines before it.

- [ ] **Step 6: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Add worked example for duplicate Note On messages"
```

---

## Task 8: Whole-document consistency verification

**Files:**
- Modify (only if a check fails): `docs/architecture/tuner/mpe-tuner-paper.md`

No new content. This task confirms the document is internally consistent after seven edits, and fixes anything the
per-task checks could not see.

- [ ] **Step 1: Every cross-reference resolves to an existing heading**

Run:

```bash
comm -23 \
  <(grep -oE "Section [0-9]+(\.[0-9]+)*" docs/architecture/tuner/mpe-tuner-paper.md \
      | sed 's/Section //' | sort -u -V) \
  <(grep -oE "^#{2,4} [0-9]+(\.[0-9]+)*" docs/architecture/tuner/mpe-tuner-paper.md \
      | sed -E 's/^#+ //' | sort -u -V)
```

Expected: no output. Any line printed is a cross-reference to a section that does not exist.

Also check the wrapped reference, which the grep above cannot see:

Run: `grep -n -A1 -E "Sections?\$" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: line 647-equivalent ending in `(Section`, next line beginning `5.7),` — pointing at *Expression Value
Computation for Shared Channels*, which is the section that paragraph means.

- [ ] **Step 2: Section 5 numbering is complete and gapless**

Run: `grep -n "^#\{3,4\} 5\." docs/architecture/tuner/mpe-tuner-paper.md`

Expected: 14 headings — `5.1 Note Identity and Reference Counting`, `5.2 Fundamental Invariant`, `5.3 Dual-Group
Channel Partitioning`, `5.4 Group Size Allocation`, `5.5 High Expression Pitch Bend`, `5.6 Allocation Algorithm`,
`5.7 Expression Value Computation for Shared Channels`, `5.8 Comparison with Standard MPE Allocation`, and
`5.8.1`–`5.8.6`.

- [ ] **Step 3: The three new sections are each cited from elsewhere**

Run:

```bash
grep -oE "Section 5\.1|Section 7\.6(\.[12])?" docs/architecture/tuner/mpe-tuner-paper.md | sort | uniq -c
```

Expected: every one of `Section 5.1`, `Section 7.6`, `Section 7.6.1` and `Section 7.6.2` appears at least once. A new
section that nothing cites means a pointer was missed. Specifically, `Section 5.1` is cited from Section 5.6 twice
(criterion (b) and its motivation) and from Sections 6, 7.3, 7.6.1, 7.6.2 and 9.6; `Section 7.6` from Sections 5.1 and
9; `Section 7.6.1` from Sections 3.3, 7.3, 7.6.2 and 9.6; `Section 7.6.2` from Section 9.6.

- [ ] **Step 4: The new citations use the documented pagination convention**

Run: `grep -c "\[2, p\. A-4\]" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `7` — two in Section 5.1 (the transmitter obligation and the lingering-note rationale), one in Section 6
(endorsed redundancy), one in the Section 7.6 preamble blockquote, one in Section 7.6.1 (the receiver's latitude), one
in Section 7.6.2 (undefined response), and one in Section 9.6. The requirement is that every `A-N` citation is covered
by the reference [2] note added in Task 2, which the next command confirms.

Run: `grep -c "Additional Explanations and Application Notes" docs/architecture/tuner/mpe-tuner-paper.md`

Expected: `1`

- [ ] **Step 5: No stale claims survive**

Run:

```bash
grep -n "for each dropped note\|always 0 at Note On\|always 0 at the time its Note On is issued" \
  docs/architecture/tuner/mpe-tuner-paper.md
```

Expected: no output.

- [ ] **Step 6: Line width**

Run:

```bash
awk 'length > 120 {print FILENAME":"FNR": "length}' docs/architecture/tuner/mpe-tuner-paper.md
```

Expected: only lines that were already over 120 before this branch — the abstract, Sections 1.x and 2.x prose, Section
3.3's item-1 paragraph, Sections 5.3, 5.7, 5.8.x, 8, 9.1, 9.2, 10 and the reference entries. Compare against the same
command run on `c6d0fa1`:

```bash
git show c6d0fa1:docs/architecture/tuner/mpe-tuner-paper.md | awk 'length > 120 {print FNR": "length}'
```

No **newly** written line may exceed 120 columns.

- [ ] **Step 7: Review the full diff**

Run: `git diff c6d0fa1 -- docs/architecture/tuner/mpe-tuner-paper.md`

Read it end to end. Confirm that the only changes are: the 13 renumbered headings, the 25 renumbered cross-references,
the new Sections 5.1, 7.6 and 9.6, the criterion (b) pointer, the Section 6 paragraph, the three qualified Polyphonic
Key Pressure statements, the Section 9 preamble, and the reference [2] entry. Anything else is an accident of a `sed`
pass and must be reverted.

- [ ] **Step 8: Commit only if Steps 1–7 required a fix**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md
git commit -m "Fix cross-reference consistency in MPE Tuner paper"
```

If nothing needed fixing, skip this step — do not create an empty commit.

---

## Self-review against the design

**Spec coverage.** All six design work items are implemented: work item 1 by Tasks 1 and 3, work item 2 by Task 4, work
item 3 by Task 5, work item 4 by Task 6, work item 5 by Task 7, work item 6 by Task 2. Both "clarifications beyond the
source prompt" that the design approved are present: the identity reading of criterion (b) is stated as a convention in
Section 5.1 and cited at criterion (b) (Task 3, Steps 1–2), and the discard rule for a Note Off with no active count is
the fourth counting rule in Section 5.1 (Task 3, Step 1). The design's two corrections to the source prompt are
carried: the count **decrements** on Note Off (Task 3), and the Expression Value override is stated in override form
and then recorded as a no-op under Section 7.2 (Task 5).

**Deviation from the design on placement, deliberate.** The design's Work item 1 places the *justifications* for both
clarifications inside Section 5.1. This plan states the rules there but puts each justification with the material it
depends on: the averaging argument for criterion (b) in Section 5.6 (Task 3, Step 3), and the already-emitted-Note-Offs
argument for the discard rule in Section 6 (Task 4). The Non-MPE input-channel bookkeeping caveat moves from Section
5.1 to Section 7.3 (Task 6) for the same reason. Neither clarification is dropped, and neither of the design's four
recorded decisions is affected — the placements moved here are not among them. The motive is readability: Section 5.1
is the first subsection of Section 5, so a reader reaching it has not yet met the allocation algorithm, Expression
Value averaging, or note dropping, and cannot evaluate arguments resting on all three.

**Out of scope, honored.** No implementation file is touched. No structural reorganization beyond one insertion into
Section 5 and one append each to Sections 7 and 9. Neither the aggregation model nor the allocation algorithm is
altered. No Non-MPE worked example is added.

**Citations.** The three citations the design introduces all appear: `[2, p. A-4]` for undefined receiver behavior
(Task 5), `[2, p. A-4]` for the transmitter obligation and for endorsed redundancy (Tasks 3 and 4), and `[2, p. 25]`
for the Note-Off-per-Note-On procedure (Task 3). The design's *Not used* list is respected — `[2, p. 22]` appears
nowhere in this plan, because its "receivers response is not specified" text is scoped to Omni-Off/Mono operation while
the Tuner requires Mode 3 on its output (Section 3.5). The design's *Known gap* is respected: Section 7.6.2's closing
paragraph states plainly that MIDI 1.0 offers no guidance on the cross-channel case, so the paper claims no stronger
specification support than exists.

**Number consistency.** Every task cites post-renumber numbers. *Allocation Algorithm* is `Section 5.6` and *Expression
Value Computation for Shared Channels* is `Section 5.7` in all new prose. The only new subsection numbers are `7.6.1`
and `7.6.2`, cited consistently from Tasks 6 and 7; Section 9.6's two parts carry no numbers of their own, since
Section 9 has no subsections elsewhere, and are referred to as "Part 1" and "Part 2" in running text only.

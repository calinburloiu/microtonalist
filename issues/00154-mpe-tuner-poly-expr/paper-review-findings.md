# Review Findings: `docs/architecture/tuner/mpe-tuner-paper.md`

Review date: 2026-07-16. Produced by two parallel reviews: (1) internal consistency / language / flow,
(2) fact-check against `docs/architecture/tuner/mpe-spec.md` (treated as ground truth for RP-053).

Line numbers refer to the paper as of commit `d846ca7` (branch `doc/review-mpe-tuner-paper`). Verify line
numbers before editing if the file has changed since.

**Status: findings awaiting user approval. Do not apply any change without explicit approval per finding ID.**

---

## Overall verdict

The paper is in very good shape. Every direct quote traces to the spec; all channel numbers, RPN numbers,
and default sensitivities are correct; the deliberate departures from MPE recommendations are honestly
declared and legal. The problems: one normative contradiction (M1), two internal-consistency defects
(M2, M3), several completeness gaps that matter for an implementation reference (S1–S6), and polish items.

---

## Major findings

### M1. Section 8.3 contradicts both the spec and the paper's own averaging model (line 673)

Found independently by both reviews. "Upon Note Off, the MPE Tuner ceases controlling the Pitch Bend for
the released note's channel" scopes cessation to the *channel*, but the spec's statement (§3.3) is
per-*note* and receiver-side ("The note will cease to be affected by Pitch Bend messages on its Channel
after the Note Off message occurs"). Taken literally, a shared channel with remaining active notes would
stop receiving tuning-change (Section 7) and averaging (Section 6.1, line 570: updates sent "because a
note entered or left the average") updates.

**Proposed fix:** reword 8.3 to:
> Upon Note Off, per-note control of the released note ceases (the note is removed from the channel's
> Expression Value averages, consistent with the specification's statement that "control of a note ceases
> once Note Off has occurred" [1, §3.3.3]); while other notes remain active, the Tuner continues to update
> the channel's Pitch Bend. The channel becomes available for reuse once all its notes have received
> Note Off messages.

### M2. Allocation algorithm wording contradicts the dynamic group model (lines 302, 307, flowchart 323/331, example 681)

Section 4.2 (lines 273–275) says unoccupied channels belong to *no* group and join a group only when a
note lands on them, yet Step 1 asks whether "the Pitch Class Group contains an unoccupied channel" —
unsatisfiable under 4.2's model. Same problem in Step 2 and both flowchart questions; worked example 9.1
(line 681) says "The Pitch Class Group has 5 channels" (static phrasing).

**Proposed fix:** rephrase Steps 1–2 and the flowchart in *capacity* terms. E.g. Step 1:
> If the Pitch Class Group has spare capacity (fewer than `a` occupied channels are assigned to it) *and*
> no channel assigned to it holds an active note of the new note's pitch class, assign the new note to an
> unoccupied Member Channel, which thereby joins the Pitch Class Group.

Analogously for Step 2 and the flowchart nodes; in 9.1: "The Pitch Class Group has a capacity of 5 channels."

### M3. Worked example 9.1 step 4 is not derivable from the tie-break rules (line 689)

"Assign to Channel 6, Expression Group": with channels 5–8 unoccupied, criteria (a)–(d) degenerate and
criterion (e) (lowest channel number in Non-MPE mode) yields Channel **5**, not 6. The example also never
states its input mode, on which criterion (e) depends.

**Proposed fix:** change to "Assign to Channel 5, Expression Group"; propagate to 9.2 step 5 (Channel 5
instead of 6, line 701); open Section 9 stating the examples assume Non-MPE Input Mode so criterion (e)
resolves to the lowest channel number.

---

## Spec-compliance gaps (omissions)

### S1. Background (Section 2) misses four spec facts the paper's own design relies on

- **Polyphonic Key Pressure rules** (spec §2.5: forbidden on Member Channels; discretionary on the Master
  Channel, for compatibility with non-MPE-aware devices) — used by Sections 3.4, 3.5, 6.3 but never
  introduced in the Background.
- **Zone-level vs. note-level message classification** (spec §2.3 / Table 1: messages like Damper Pedal
  "should be sent only on a Zone's Master Channel"; a synthesizer receiving them on a Member Channel "must
  ignore it") — the entire justification for Master-Channel redirection (3.4 item 2, 8.2).
- **Master Channel Note On/Off permission** (spec §3.2: "For the sake of MIDI 1.0 compatibility, Note
  On/Off messages are permitted on the Master Channel, and a synthesizer must respond to these") —
  Section 3.5 is built entirely on it; belongs in 2.4.
- **Receiver state-tracking obligation** (spec §3.3: control values "must be tracked and stored on all
  Member Channels, even when no note is playing, to provide an initial state for a new note") — quoted
  only at line 592; belongs in 2.5.

**Proposed fix:** add a short subsection (e.g., "2.6 Pressure and Zone-Level Messages", or two subsections)
plus one sentence each in 2.4 and 2.5 for the latter two facts.

### S2. Polyphonic Key Pressure handling in MPE Input Mode is undefined (Sections 3.5, 6.2)

**Largely retracted on re-check (2026-07-16).** The Master Channel case is *not* undefined: Section 3.5
forwards Master Channel messages "without modification" (line 218) and its rationale point 3 (lines 237–239)
explicitly preserves the ability to use Polyphonic Key Pressure on Master Channel notes. Only one residual
gap survives — the paper never states that PKP arriving on an *input Member Channel* (illegal per spec §2.5)
is discarded. That is minor (a conforming sender never emits it) and, being a Member-Channel rule, does not
belong in Section 3.5 (Master Channel Note Forwarding), where the first draft wrongly placed it.

**Revised fix (downgraded from major/release to follow-up):** add a single sentence to Section 6.2 noting
that PKP on an input Member Channel is discarded and never re-emitted; contrast it with the Master Channel
case already handled by Section 3.5. Dropping S2 entirely is also defensible — it concerns illegal input the
paper is under no obligation to specify.

### S3. Non-MPE conversion doesn't cover other channel-wide messages (lines 182–186)

Section 3.4 item 2 redirects only Pitch Bend, CC #74, and Channel Pressure. Damper Pedal (CC #64),
Modulation, Volume, Program Change/Bank Select, Reset All Controllers, etc. are addressed only in
Section 8.2, which is framed as Master-Channel *forwarding* — but non-MPE input has no Master Channel to
forward from. Pitch Bend Sensitivity (RPN 00 00) is a further case: in Non-MPE Input Mode it applies to the
output Master Channel (see S5), so the conversion should say so explicitly here rather than leaving it to
Section 8 alone.

**Proposed fix:** add item 4 to Section 3.4: all other Channel Voice/Mode messages received on non-MPE
input channels are redirected to the selected output Zone's Master Channel; state explicitly that a Pitch Bend
Sensitivity message (RPN 00 00) is likewise forwarded to the Master Channel, where it configures the Master
Channel Pitch Bend's sensitivity (cross-reference S5 / Section 8).

### S4. Velocity-0 Note On shorthand never addressed

Occupancy tracking, averaging, and channel freeing all depend on recognizing Note Offs; a velocity-0
Note On in the input must be treated as Note Off (spec §3.3.2 recommends interpreting it as Note Off with
velocity 64) or allocation state corrupts.

**Proposed fix:** one sentence in Section 3.1 or 8.3.

### S5. Pitch Bend Sensitivity semantics incomplete (line 650)

The Section 8 bullet is generic and mode-agnostic. Three points, **split by input mode**:

(a) In MPE Input Mode, a sensitivity received on *any* input Member Channel applies zone-wide to the
interpretation of incoming Pitch Bend (spec §2.4: "A receiver must apply the last Pitch Bend Sensitivity
message received on any Member Channel to all Member Channels in the Zone").

(b) **Corrected (2026-07-16).** The earlier draft said the Tuner should emit the sensitivity to every Member
Channel individually on output. That is a *sender* recommendation (spec §2.4); applied naively by an
MPE-in/MPE-out processor it floods. A conforming MPE input already sends the message to all `n` Member
Channels, so re-fanning each received message across all `n` output channels emits `n²` messages. Correct
behavior: in MPE Input Mode forward each received message on its *corresponding* output Member Channel
(per-channel pass-through), which already covers every channel. In Non-MPE Input Mode the input has no Member
Channels; a sensitivity received there applies to the **output Master Channel** (matching the redirection of
the input's Pitch Bend to that channel, Section 3.4), and the output Member Channels keep the MCM default ±48
— in this mode their sensitivity can be modified only through the non-MIDI configuration interface. *(This
also answers a separate question: the paper never stated that Non-MPE-mode PBS applies to the output Master
Channel — that gap is now folded into this fix.)*

(c) After emitting an MCM — which resets sensitivities to ±2/±48 — any non-default sensitivity must be
re-emitted (on the Master Channel in Non-MPE mode; on each Member Channel in MPE mode — a one-time burst, not
a per-message action).

**Proposed fix:** rewrite Section 8's Pitch Bend Sensitivity bullet with (a)–(c) as corrected above, split by
input mode.

### S6. Note Off emission on freeing is asserted only in an example (line 712)

Example 9.3 says "(Note Off sent for E)" but the normative text never states that dropping a note emits
Note Off; Section 3.3 (line 170) makes Note Off emission an implementation choice only for Zone
reconfiguration.

**Proposed fix:** state in Section 5.1 that freeing a channel emits Note Off messages for its active notes
before the new Note On.

---

## Minor precision & consistency

- **P1** (line 495): "note dropping never occurs" with 15 Member Channels is contradicted by Section 5.2
  (High-Bend drops are independent of channel count). Fix: "note dropping **due to channel exhaustion**
  never occurs".
- **P2** (line 47): "contributes exclusively to the Expression Pitch Bend" is false in Non-MPE mode, where
  input Pitch Bend goes to the Master Channel (lines 146–147, 603–606). Fix: "…contributes exclusively to
  the expression domain — as Expression Pitch Bend in MPE Input Mode, or as Master Channel Pitch Bend in
  Non-MPE Input Mode (Section 3.4)".
- **P3** (line 709): "it needs a Pitch Class Group channel" contradicts the Expression Group's overflow
  role (4.2). Fix: "it cannot share a channel — it needs a channel of its own".
- **P4** (lines 310–312 + flowchart node at 336): Step 3 presents sharing as unconditional; 5.2.2/5.2.3
  later convert it into freeing when a High Bend is involved. Fix: append to Step 3 "…subject to the High
  Expression Pitch Bend rules of Section 5.2, which may require freeing the channel instead."
- **P5** (lines 523–526): the sole-note invariant paragraph sits inside 5.2.2, forward-references 5.2.3,
  omits 5.2.2's own contribution to the derivation, and duplicates invariant 2 of 5.3. Fix: move it after
  5.2.3 (or fold into 5.3), citing all three subsections.
- **P6** (lines 210–215): Master-Channel PKP is overstated as a guaranteed capability; the spec makes it
  discretionary for senders and only "may be recognized" by receivers. Fix: soften ("may retain per-note
  pressure where the receiving implementation recognizes Polyphonic Key Pressure on the Master Channel"),
  framing the spec's motivation as compatibility.
- **P7** (lines 418–422): the §2.2.1 one-channel-per-note statement is called a "recommendation" though it
  sits in the spec's normative section. Fix: acknowledge its placement and ground the departure in the
  spec's own allowance for sharing (§1.2: "If the number of active notes exceeds the number of available
  Channels, two or more notes will have to share a Channel").
- **P8** (line 470): "among all active notes" is ambiguous (could include Master-Channel notes exempt from
  allocation). Fix: "…among the active notes on the Zone's Member Channels".
- **P9** (line 116): the "swooping" cause is paraphrased narrower than the spec (any stale/arbitrary value
  corrected after note start, not only a previous note's Pitch Bend). Fix: reword to the general
  formulation.
- **P10** (line 154 vs. 182–186): multi-channel non-MPE input silently collapses independent
  per-input-channel Pitch Bend/CC states onto one Master Channel (last-writer-wins). Not a violation; add
  one sentence acknowledging the input is treated as a single merged control stream.
- **P11** (spec §3.3.4): "Channel Pressure must be set to zero immediately before a Note On or a Note Off
  wherever it is appropriate" is honored at onset but never discussed for Note Off. Fix: one sentence
  noting the behavior is inherited from the input sender (MPE mode) — a documented choice, using the
  spec's "wherever appropriate" qualifier.
- **P12** (lines 351–352): criterion (d)'s gloss "idle the longest" is wrong for occupied candidates
  (Steps 3–4). Fix: "the channel whose most recent Note Off is oldest", noting channels with no Note Off
  history fall through to criterion (e).
- **P13** (lines 55–58): Overview item 3 states the Pitch Bend sum unconditionally though Non-MPE mode has
  no Expression component. Fix: add "(in Non-MPE Input Mode the Expression component is absent;
  Section 3.4)".

---

## Language & style nits

- **N1** (lines 394–396): "can neither relax X **or** Y … nor Z" → "can relax neither X nor Y, nor
  override Z".
- **N2** (line 261): missing comma + weak modal: "…simultaneously, which would compromise…".
- **N3** (line 455): sole first-person sentence "We maintain the principle…" → "Dropping is treated as a
  last-resort measure…" (also fixes "the last resort measure" vs. line 317's "a last-resort measure").
- **N4** (line 377): colloquial "so it is its turn to be reused" → "making it the natural candidate for
  reuse".
- **N5** (line 649): "It equally listens" → "It likewise listens".
- **N6** (line 209): faulty predication: "A note placed on the Master Channel … is a deliberate choice" →
  "Placing a note on the Master Channel is a deliberate choice by the MPE sender: …".
- **N7** (lines 199–201): "aftertouch" appears only here (undefined); use "Polyphonic Key Pressure"; tone
  down "of dubious musical meaning" → "of questionable musical meaning".
- **N8** (line 263): normative "may not share" (ambiguous permission/prohibition) → "must not share".
- **N9** (line 18): "the twelve keys of a standard MIDI keyboard" → "the twelve keys **per octave** of a
  standard MIDI keyboard".
- **N10** (line 16): gloss mismatch: EDO = equal divisions of the octave → "twelve-tone equal temperament
  (12 equal divisions of the octave, 12-EDO)".
- **N11** (line 22 vs. 36): CC #74 glossed "(timbre)" then "(timbre or slide)"; give the full gloss at
  first mention only.
- **N12** (lines 75, 94, 650): RPN notation inconsistent ("Registered Parameter Number 00 06" vs "RPN 0");
  standardize (e.g., "RPN 00 06" / "RPN 00 00").
- **N13** (lines 45, 404–406, 557–560): the Pitch Bend formula appears in three formats with trivially
  different wording; unify (have 6.1 reference 4.6's formula).
- **N14** (lines 104, 446): silent quote alterations (dropped "However," / "(for example)"); use bracketed
  elisions for fidelity.
- **N15** (misc): line 110 heading "Note-On Setup" vs "Note On" elsewhere; MCM expanded four times (lines
  60, 75, 134, 168 — expand once); line 269 stray capitalized "Channels" outside quotes; "freeing a
  channel" defined twice (lines 317 and 465 — keep one, cross-reference the other); missing blank line
  before the "- **(a)**" bullet list at line 362 (some renderers won't recognize the list); mixed
  "twelve"/"12" in prose; mixed self-reference ("this paper" vs "the specification herein") — pick one.
- **N16** (lines 22, 36): "Member Channel" and Zone terminology used in Section 1 before being defined in
  2.1; add "(Section 2.1)" at first use.

---

## Findings outside the paper

1. **Likely error in `mpe-spec.md`**: Table 1 lists "Reset All Controllers (CC **#127**)" — MIDI 1.0
   defines Reset All Controllers as CC **#121** (#127 is Poly Mode On). The paper cites no number, so it
   is unaffected, but the spec summary should be verified against the published RP-053 and corrected.
2. **Recommended follow-up**: verify the paper against the actual Scala implementation (e.g., the MPE
   channel allocator revised in commit `c23ad7c`) — the paper declares itself "a reference for software
   implementations"; a paper↔code consistency pass would close the loop.
3. Since tie-break criterion (e) depends on input mode, state each worked example's input mode explicitly
   (folded into M3's fix).

---

## Verified as correct (coverage summary)

Zone structure (Master Channels 1/16, members ascending from 2 / descending from 15); MCM = RPN 00 06
mechanics, zero-member deactivation, most-recent-message precedence, up-to-15-members rule, and
reset-on-reconfiguration obligations; default Pitch Bend Sensitivity ±2 Master / ±48 Member; all verbatim
quotes (modulo N14); the Note On setup ordering Pitch Bend → CC #74 → Channel Pressure → Note On; Channel
Pressure = 0 at onset; Master-Channel note forwarding legality; PKP→Channel-Pressure conversion legality;
Master Pitch Bend passthrough; state retention mirroring spec §3.3; reverting to Non-MPE Input Mode when
MPE is off (legal — manufacturer-defined); dual 7-member zones matching the spec's Example Two; Appendix A
group-size arithmetic; worked examples 9.2–9.3 (modulo M3's knock-on in 9.2 and P3).

# MPE Tuner: Paper ↔ Implementation Gap Report

Comparison between the specification in `docs/architecture/tuner/mpe-tuner-paper.md` and the
implementation in `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/`
(`MpeTuner.scala`, `MpeChannelAllocator.scala`, `MpeZone.scala`), plus the supporting
`ScMidiChannelStateTracker` in `sc-midi`. Both artifacts are work in progress for #154; this
report inventories the gaps in each direction.

**Baseline**: implementation as of commit `a90e563` (2026-07-21); paper as of commit `5d77eff`
(2026-07-26). All implementation line references were read and verified at `a90e563`, and they
resolve unchanged at the branch head: `59403d1` shortened a TODO comment near the top of
`MpeTuner.scala` by one line, and `11549bb` re-wrapped it to restore the original alignment.
`MpeChannelAllocator.scala` was never affected.

The implementation has not moved since the previous revision of this report: at the baseline the
three tuner sources are **byte-identical to `32d66f9`**, the previous revision's baseline, the only
change in between being `75a70ee`, which added `shallRespondToResetMessages` to
`ScMidiChannelStateTracker` (see N4); after the baseline only the TODO comment noted above
changed, and its two commits cancel out. The
paper has moved three times: `a90e563` inserted §5.1 (Note Identity and Reference Counting), §3.5 (MIDI
Channel Modes), §7.5 (Message Ordering, promoted from §7.1.1 and extended to Note Off), §7.6
(Duplicate Note On Messages), §5.8.6, §8.1 and three worked examples — renumbering most of
Sections 5 and 7 — `09ce128` closed six paper-side gaps this report had raised, adding §3.6
(Channels Outside Every Zone) and the RPN wire protocol paragraph of the §4 preamble; and `5d77eff`
reversed two of those six, replacing §3.4's Member Channel redirection rule and the §4 preamble's
pass-through of uninterpreted RPNs with discard rules.

**ID scheme**: `P*` paper → missing in impl, `C*` conflicts, `I*` impl → not in paper, `B*` impl
bugs, `A*` paper ambiguities. `N1`–`N5` were introduced in the previous revision as *unassessed*
material new to the paper; they have now been assessed and keep their IDs so earlier
cross-references still resolve, but they are filed under the section matching their verdict. Items
refiled into another section likewise keep their IDs — `I1` and `I2` moved this way in the
2026-07-25 revision, and `I3` in this one, back from Section 5 to Section 2. No ID is new to this
revision.

## Summary

| ID | Direction | Topic | Severity |
|----|-----------|-------|----------|
| P1 | Paper → missing in impl | Per-note Expression Value model for CP and CC #74 (no per-note updates, no averaging) | Major |
| P2 | Paper → missing in impl | Note's initial Expression Pitch Bend not seeded from remembered input-channel state | Major |
| P3 | Paper → missing in impl | Expression update propagation maps input channel → "last added note" instead of the actual notes | Major |
| P4 | Paper → missing in impl | Nothing emitted after a Note Off: no re-emission when a note leaves the average, and none of the §7.5 Note Off ordering | Major |
| P5 | Paper → missing in impl | Poly Pressure → Channel Pressure conversion does not average on shared channels | Medium |
| P6 | Paper → missing in impl | Non-MIDI runtime configuration interface (input mode, zones, member PBS) | Out of scope |
| N1 | Paper → missing in impl | No reference counting per Note Identity; allocator keys notes by note number alone | Major |
| N5 | Paper → missing in impl | Criterion (b) under-counts identities that share a note number on one channel | Minor |
| C1 | Conflict | CC #74 emitted on Member Channels in Non-MPE Input Mode (paper forbids it) | Medium |
| C2 | Conflict | Channel Pressure reset happens before Note On instead of before the last note's Note Off (Non-MPE mode) | Medium |
| C3 | Conflict | MCM reconfiguration resets *both* zones; paper preserves the untouched zone | Medium |
| C4 | Conflict | With all Zones deactivated, impl still forwards notes and passes controls through; paper requires no output at all | Medium |
| C5 | Conflict | Master Channel CC #74 / Channel Pressure not forwarded unmodified in MPE mode | Major |
| C6 | Conflict | Uninterpreted RPN and NRPN traffic routed instead of discarded, and routed inconsistently with each other | Medium |
| N4 | Conflict | MIDI Mode messages 124–127 redirected to the Master Channel; §3.5 requires discarding them | Medium |
| I2 | Conflict | Out-of-zone notes and controls in MPE mode allocated to / passed through the first enabled zone; §3.6 requires discarding them | Medium |
| I3 | Conflict | Zone-level messages on an input Member Channel redirected to the Master Channel; §3.4 now requires discarding them | Medium |
| I5 | Impl → not in paper | Non-Channel messages (SysEx, system) passed through | None (impl-specific) |
| I6 | Impl → not in paper | MCMs also emitted for disabled zones at start-up | None (impl-specific) |
| I1 | Impl bug | Active Tuning reset to Standard on incoming MCM | Major |
| B1 | Impl bug | Dropped notes leave stale entries in `channelNoteMap` | Major |
| N2 | Impl bug | Duplicate Note On re-runs allocation: leaks a channel (same input channel) or corrupts the average and frees a channel early (different input channels) | Major |

**Changes in this revision (2026-07-26).** The implementation is still unchanged, so every impl-side
citation continues to hold. The paper moved *against* the code for the first time: `5d77eff`
reversed two of the six gaps `09ce128` had closed, after a re-reading of the MPE Specification's
Table 1 and §2.3.1 established that redirection — defensible for a processor — is the treatment the
specification tells a receiver not to adopt.

- **Reopened as a conflict**: **I3** returns from Section 5 to Section 2. §3.4 now requires
  Zone-level messages arriving on an input Member Channel to be **discarded**; the implementation
  redirects them to the Zone's Master Channel. Medium.
- **Inverted, not retracted**: **C6** stands, but its required fix inverts — uninterpreted RPN
  traffic is to be discarded outright rather than routed coherently — and it widens to both input
  modes and to NRPNs, so it rises from Minor to Medium.
- **Newly aligned**: the implementation's hardcoded `inputChannel == 0 || inputChannel == 15` MCM
  test is exactly §4.2's amended rule. The previous revision called it merely "equivalent" to a
  Master-Channel test; under the specification's fifteen-member configuration the two are *not*
  equivalent, and the code has the right one. See Section 5.
- **Unaffected**: C5, N4, I2 and C4 turn on Master Channel or out-of-zone behaviour, which neither
  rule change touches.

**Changes in the previous revision (2026-07-25).** No finding was retracted on the evidence; the
implementation is unchanged, so every impl-side citation still holds. What moved is the paper and
the author's disposition of six items:

- **Closed on the paper side by `09ce128`**: **I3** (Zone-level messages arriving on an input Member
  Channel), **I4** (the RPN wire protocol) and **A1** (criterion (d)'s no-Note-Off-history
  convention) are now specified, and have moved to Section 5. *(I3 has since been reopened by
  `5d77eff`, which reversed the rule; it is back in Section 2.)*
- **Settled in the paper's favour, turning into implementation work**: **I2** — out-of-zone notes are
  to be discarded, which is also the mechanism by which a fully deactivated Zone configuration
  produces silence — is now a conflict (Section 2); **C4** is widened from note output to all Channel
  Voice and Channel Mode output by the same new §3.6, and raised to Medium.
- **Reclassified**: **I1** is an implementation bug and has moved to Section 4. **P6** is out of
  scope for #154. **I5** and **I6** are implementation-specific choices the paper deliberately leaves
  open, so they need no paper change and no code change.
- **New**: **C6**, found while verifying the §4 preamble's RPN paragraph against `processCc`.

The previous revision's own changes stand: **N1** and **N5** are confirmed gaps, **N4** a confirmed
conflict, **N2** a Major implementation bug rather than a missing feature, and **N3** (Note Off
velocity 64 and dropped-note ordering) already aligned, in Section 5.

---

## 1. In the paper, missing from the implementation

### P1. Per-note Expression Value model for Channel Pressure and CC #74 (§1.3, §5.7, §7.1–7.3)

The paper's central polyphonic-expression model: each note carries three Expression Values
(Expression Pitch Bend, Channel Pressure, CC #74), and each output Member Channel emits the
**average** of its active notes' values per dimension.

In the implementation, only the Expression Pitch Bend dimension participates in this model
(`computeOutputPitchBend`, `MpeTuner.scala:635-648`, averages `pitchBendCents` over active notes).
For CP and CC #74:

- `MutableMpeExpression` has `pressure` and `slide` fields, but they are set once at allocation and
  **never updated afterwards**. Confirmed at `MpeChannelAllocator.scala:412`: `doAllocate` calls
  `MutableMpeExpression(expressivePitchBendCents)`, passing *only* the bend, so `pressure` and
  `slide` always take their defaults (0 and 64). TODO at `MpeChannelAllocator.scala:61`.
- At Note On, `MpeTuner` seeds the output channel's CC #74 / CP directly from the tracker's
  last-known per-*input-channel* value (`MpeTuner.scala:242-246`), bypassing the per-note model —
  no averaging with other notes already on the shared output channel.
- Incoming CC #74 / CP updates are fanned out raw to all output channels fed by the input channel
  (`forwardToMemberChannel`, `MpeTuner.scala:596-600`), overwriting the whole channel value.
  Per §7.2, they should update only the contributions of the notes originating from that input
  channel and re-emit the recomputed average.

Acknowledged by `MpeChannelAllocator.scala:81-82` (`TODO #154 Add a channelExpression field …
averaged across all channel notes`).

Worked example §9.3 (new in `a90e563`) traces the full three-dimension averaging through a concrete
sequence and is the most direct test oracle for this item.

### P2. Initial Expression Pitch Bend not seeded from remembered input-channel state (§7.2)

§7.2 "State retention on input channels": when a note arrives on an input Member Channel, its
Expression Values are initialized from the channel's remembered control state. A conforming MPE
sender sends Pitch Bend *before* Note On, so this is the normal path, not an edge case.

The implementation tracks input Pitch Bend (`ScMidiChannelStateTracker`), but
`MpeTuner.scala:221` calls `alloc.allocate(midiNote, preferredChannel = preferredChannel)`
**without** `expressivePitchBendCents`, so the parameter takes its default of `0.0`
(`MpeChannelAllocator.scala:247`) — the note always starts with Expression PB = 0. Two
consequences:

- Pitch Bend sent by the sender before Note On on an input Member Channel is silently lost
  (at that moment `outputChannelsFor` is empty, so nothing is emitted, and the tracked value is
  never read back).
- §6.2.2 ("new note *with* a High Expression Pitch Bend on an occupied channel") is dead code:
  `dropExistingNotesForHighBend` supports it (`MpeChannelAllocator.scala:429-447` — the
  `newHighBend` disjunct at line 438), but the new note's bend is always 0, so only the §6.2.3 half
  (`existingHighBend`) can trigger.

Worked example §9.3 step 1 is explicit that a note is initialized from its input channel's
remembered Pitch Bend, Channel Pressure, and CC #74.

### P3. Update propagation: input channel → note mapping (§7.2)

§7.2 "Update propagation": a control message on an input Member Channel updates the contribution
of *each note active on that input channel*. The implementation instead assumes the **most
recently added note** on the output channel is the one being bent
(`MpeChannelAllocator.updateExpressivePitchBend`, lines 306-324 — `state.lastAddedNote` at lines
312 and 321; `TODO #154` at lines 293-294: "Bad assumption that the last note is being bent. To map
incoming channel to output channel.").

This is wrong whenever notes from different input channels share an output channel (fan-in): a
bend from input channel X may be applied to a note that arrived from input channel Y. The paper's
fan-in averaging (multiple input channels → one output channel, §7.2 paragraph 2) is therefore
unimplemented for all three dimensions.

Two additions in `a90e563` sharpen this item:

- §7.6.2 names cross-input-channel fan-in of the *same note number* onto one output channel as an
  acknowledged, reachable situation with two independent identities — precisely the case the
  "last added note" assumption resolves wrongly. Worked example §9.6 Part 2 traces it. See also N2,
  which shows that case is not merely mis-attributed but unrepresentable in `ChannelState`.
- §9.5 traces the fan-in case for the divergence rule: a Pitch Bend on input Channel 2 must be
  attributed to the note that arrived from input Channel 2, not to the channel's last-added note,
  or the wrong note is dropped under §6.2.1.

### P4. Nothing emitted after a Note Off (§7.1, §7.5)

Two requirements, both unmet by the same omission.

§7.1: "Each time an Expression Value of an output Member Channel changes — whether because a note
entered or left the average …— the new value is sent on that channel."

§7.5 (new in `a90e563`) also fixes the **ordering** for the release side, inverting the Note On
order: Note Off first, then Pitch Bend, CC #74, Channel Pressure recomputed over the remaining
notes. The rationale is symmetric to the Note On case — emitting the recomputed values first would
drag the still-sounding released note toward values computed for a set it no longer belongs to. The
sole exception is the Non-MPE Channel Pressure reset of §7.4, which precedes the Note Off (see C2).

In `MpeTuner.processNoteOff` (lines 257-273) the note is released from the allocator (line 265) and
a Note Off is forwarded (line 266) — and that is the whole body. The remaining notes' new average
Pitch Bend / CP / CC #74 is **not** recomputed and emitted at all. The "note entered" direction
works (the pre-Note-On Pitch Bend at `MpeTuner.scala:233-235` includes the new note in the
average); the "note left" direction is missing entirely, so the §7.5 Note Off ordering has nothing
to order.

### P5. Poly Pressure → Channel Pressure averaging in Non-MPE mode (§3.3 item 1, §7.3)

The conversion itself is implemented (`processPolyPressure`, `MpeTuner.scala:563-586`; the Non-MPE
branch is lines 576-585), including discarding Poly Pressure for inactive notes — the
for-comprehension at lines 579-584 yields nothing when the note is untracked. But when multiple
notes share the output channel, the paper requires the converted values to be **combined by
averaging**; line 583 emits `ChannelPressureScMidiMessage(outChannel, pressure)` with the raw value
for the addressed note, overwriting the channel's CP. It also never records the value in the note's
`MpeExpression` (same root cause as P1).

§7.3 was tightened in `a90e563`: note bookkeeping in Non-MPE mode is explicitly **per input
channel**, as the Note Identity of §5.1 requires. The implementation already satisfies that half —
the lookup at lines 580-581 goes through `channelNoteMap.get(inputChannel)` and only then
`notes.get(midiNote)` — so the Poly Pressure path is correctly identity-scoped. See N1.

### P6. Non-MIDI runtime configuration interface (§4, §4.1, §4.3)

**Out of scope.** The author has placed the runtime configuration interface outside the scope of
#154; the gap is recorded here for completeness and carries no implementation work under this issue.
The paper needs no change either — it is the implementation that has yet to catch up.

The paper repeatedly assumes a non-MIDI configuration interface that can act **at runtime**:
re-entering Non-MPE Input Mode ("re-entered only through the non-MIDI configuration interface",
§4.1), changing Zones, changing Member Channel PBS in Non-MPE mode ("can be changed only through
the non-MIDI configuration interface", §4.3), and re-activating a Zone after full deactivation
(§4.1).

The implementation exposes configuration only via constructor parameters (`initialZones`,
`initialInputMode`, lines 62-63) and `reset()` (lines 110-111, which restores those initial
values). `zones`, `inputMode` and `tuning` (lines 93-103) are read-only accessors; there are no
setters for input mode, zones, or PBS, so none of the above runtime transitions is possible. (This
may be intended to live at the `TunerProcessor`/session layer, but nothing implements it today.)

### N1. No reference counting per Note Identity (§5.1)

§5.1 defines a note's **Note Identity** as the pair (input channel, note number) and gives each
active identity a **reference count**: allocation runs only on the 0 → 1 transition, deallocation
only on the transition to 0, and a Note Off for an identity with no active count is discarded. The
stated purpose is MIDI 1.0's per-Note-On transmitter obligation [2, p. A-4] — exactly one forwarded
Note Off per forwarded Note On.

The two halves land differently:

- **Note Identity is already there, at the `MpeTuner` level.** `channelNoteMap` is
  `mutable.Map[Int, mutable.Map[MidiNote, Int]]` keyed by input channel and then by note
  (`MpeTuner.scala:83`, with the comment at lines 77-82 spelling out the layout). `trackNote`
  (524-526), `untrackNote` (528-534) and the Poly Pressure lookup (580-581) all key on the pair.
- **Note Identity is *not* there at the `MpeChannelAllocator` level.** `ChannelState._notes` is a
  `LinkedHashMap[MidiNote, MutableMpeExpression]` (`MpeChannelAllocator.scala:104`) keyed by note
  number alone. Two distinct identities that the allocator places on one output channel therefore
  collide in a single map entry — see N2, second sub-finding.
- **Reference counting is absent entirely.** `trackNote` uses `.update(midiNote, outChannel)`,
  which overwrites, and `untrackNote` uses `.remove(midiNote)`, which deletes outright. There is no
  count anywhere, so allocation runs on *every* Note On and deallocation on the *first* Note Off.

The consequences are N2. Note that §5.1's fourth rule — a Note Off for an identity with no active
count is discarded — is *accidentally* satisfied: `processNoteOff`'s `case None` branch (lines
269-270) logs and drops. The branch is marked `$COVERAGE-OFF$` as defensive, but under §5.1 it is
a specified path.

### N5. Criterion (b) under-counts identities sharing a note number (§5.6)

§5.6 criterion (b) prefers the channel with the fewest active notes, and `a90e563` fixed the
counting semantics: the count is of **distinct Note Identities**, since an identity whose reference
count exceeds 1 still contributes a single term to each average and "must not be counted twice".

`bestCandidate` uses `s.notes.size` (`MpeChannelAllocator.scala:464`), i.e. `_notes.keySet.size`.
Because no reference counts exist (N1), over-counting cannot occur — that half is vacuously
satisfied. But because `_notes` is keyed by note number alone, two *distinct* identities sharing a
note number on one channel count as **one**, so the criterion under-counts and may pick a channel
that is in fact more heavily loaded than a rival. Minor, and it disappears once N1's identity
keying reaches the allocator.

---

## 2. Direct conflicts (paper and implementation both specify, and disagree)

**Resolution policy**: the paper is the source of truth. Every conflict in this section is to be
resolved by changing the implementation; where the code's behaviour is the one worth keeping, the
paper is amended first and the finding is then re-derived from the amended text (as happened to I2
in the 2026-07-25 revision). The amendment can also run the other way: `5d77eff` amended §3.4
*against* the code, turning the previously aligned I3 back into a conflict.

### C1. CC #74 on Member Channels in Non-MPE Input Mode (§3.3 item 3, §7.3)

Paper: "the Tuner never sends CC #74 on a Member Channel" in Non-MPE mode; §7.3: "CC #74 never
appears on an output Member Channel."

Implementation (`MpeTuner.scala:242-243`): `val slide = if (inputMode == Mpe) tracker.cc(…) else 64`
followed by an unconditional `buffer += CcScMidiMessage(outChannel, ScMidiCc.MpeSlide, slide)` —
so CC #74 = 64 is emitted on the allocated Member Channel before **every** Note On in Non-MPE mode,
justified in the code comment (lines 237-241) by MPE spec §3.3.5 ("Initial-64"). One of the two
documents must yield: either the paper should permit the neutral Initial-64 seeding, or the
implementation should stop emitting it.

`a90e563` did not resolve this, but it added a partial concession in the paper's own vocabulary:
§4.2 now describes CC #74 64 as "the centered initial value the MPE Specification prescribes for a
bipolar third dimension [1, §3.3.5]" — the same justification the code comment gives — while
keeping it as a *retained* default rather than an emitted message. Combined with the emission
optimization of §7.5, a channel that already holds 64 would in any case be permitted to omit the
message, which narrows the practical disagreement to channels whose retained CC #74 differs.

### C2. Channel Pressure reset placement in Non-MPE mode (§7.4)

Paper §7.4 (rewritten and substantially expanded in `a90e563`): in Non-MPE mode the Tuner is the
controller and performs the CP reset **at Note Off**, and the rule is now stated precisely — the
reset happens only when the released note is the **last active note on its channel** (when others
remain, the recomputed value is simply their average, reduced but not zeroed), and it is the one
control message emitted **before** the Note Off rather than after it (§7.5), the specification
requiring Channel Pressure to be zeroed "immediately before a Note On or a Note Off" [1, §3.3.4].

Implementation: emits CP = 0 before every Note On in Non-MPE mode (`MpeTuner.scala:245-246`) and
emits **nothing at Note Off** (`processNoteOff`, lines 257-273). The receiver state is correct by
the next Note On (satisfying §7.3's weaker phrasing), but between a Note Off and the next Note On
the downstream channel keeps the stale CP, affecting the release tail — exactly what §7.4's chosen
design avoids. Worked example §9.3 step 6 states the contrast explicitly.

### C3. Scope of state reset on MCM reconfiguration (§4.2)

Paper: a Zone reconfiguration resets state only for channels "entering or leaving MPE control";
"Channels of a Zone untouched by the reconfiguration keep their notes and state."

Implementation (`processMcm`, `MpeTuner.scala:361-399`): `stopAllNotes` (line 375) iterates all of
`channelNoteMap` and so drops notes on **both** zones, and `resetState()` (line 381) clears
`channelNoteMap` and the tracker and recreates **both** allocators (lines 144-148), even when the
MCM touches only one zone and no overlap resolution occurs. Notes and retained Expression Values in
the untouched zone are lost, contrary to the paper.

### C4. Behavior when all Zones are deactivated (§4.1, §3.6)

Paper, as tightened by `09ce128`: with no Zone enabled "every channel lies outside the Zone
structure, so every Channel Voice and Channel Mode message the Tuner receives is discarded under
Section 3.6 — not only notes"; the only messages the Tuner emits are the configuration messages of
§4.2, and the only input it still acts upon is a valid MCM.

Implementation: with no enabled zone `createAllocator` returns `None` (line 719), so
`processMemberNoteOn`'s `case None` branch (lines 251-253) forwards the Note On **as-is** on its
input channel. Controls fare no better: `resolveZoneMasterChannel` returns `Some(inputChannel)` for
a channel in no zone (lines 630-631), so CCs and Program Change pass through unchanged instead of
being discarded. The conflict therefore covers the whole message stream, not just notes — hence the
raise from Minor to Medium. It is the degenerate case of I2, and the same fix resolves both.

### C5. Master Channel CC #74 and Channel Pressure forwarding in MPE mode (§3.4)

Paper §3.4: Master Channel Pitch Bend and Zone-level messages are "forwarded on the Master Channel
without modification". Per the MPE spec (§2.5–2.6, quoted in paper §2.5/2.6), CC #74 and Channel
Pressure on a Master Channel are Zone-level controls. `a90e563` added exactly one exception to this
forwarding rule — the MIDI Mode messages 124–127 (§3.5, see N4) — and CC #74 and Channel Pressure
are not among them. Neither `09ce128` nor `5d77eff` disturbed that rule: their §3.4 paragraphs
govern Zone-level messages arriving on a *Member* Channel (I3), not the Master Channel forwarding at
issue here.

Implementation: only Pitch Bend and Poly Pressure special-case the Master Channel
(`processPitchBend` lines 281-283, `processPolyPressure` lines 573-575). CC #74 and Channel
Pressure go through `forwardToMemberChannel` unconditionally in MPE mode (`processCc` lines
343-346, `processChannelPressure` lines 552-554), which forwards to `outputChannelsFor(channel)`
(lines 543-545) — for a Master Channel that is the set of tracked master-channel notes. Result:

- With no active Master Channel notes, `outputChannelsFor` returns the empty set and the `foreach`
  in `forwardToMemberChannel` (line 597) emits nothing: Master CC #74 / CP is **silently dropped**
  instead of forwarded as a Zone-level control.
- With active Member Channel notes in the zone, the Zone-level control never reaches them (it is
  not re-emitted on the Master Channel).

### C6. Uninterpreted RPN and NRPN traffic not discarded (§4 preamble, §4.2)

Raised in the previous revision against `09ce128`'s pass-through rule; `5d77eff` replaced that rule
with a discard rule, which inverts the required fix, widens the finding to both input modes and to
NRPNs, and raises it from Minor to Medium. The §4 preamble now states that the Tuner interprets only
RPN 00 00 and RPN 00 06 and "discards the whole of their traffic — selector, Data Entry, Data
Increment and Decrement, and RPN Null" for every other Registered and Non-Registered Parameter
Number, in both input modes.

Implementation: none of that traffic is discarded, and RPNs and NRPNs are not even routed alike.

- **Uninterpreted RPN selectors** (CC #101/#100) match the second selector case (`processCc`, lines
  329-334): redirected to the Master Channel in Non-MPE mode, passed through unchanged on the input
  channel in MPE mode (line 333).
- **NRPN selectors** (CC #99/#98) match no case at all and fall to the catch-all (lines 350-351), so
  they reach the Master Channel in *both* modes — the opposite of the RPN treatment in MPE mode.
- **Their Data Entry** (CC #6/#38) fails both `isMcmRpn` and `isPbsRpn` and reaches the same
  catch-all, arriving on the Master Channel where it is applied downstream to whatever parameter was
  last selected there. The guards themselves are sound: `ScMidiChannelStateTracker` models NRPN
  selection, so `rpnSelector` returns `RpnSelector.Nrpn(...)` and neither guard fires spuriously.
- **Data Increment/Decrement** (CC #96/#97) likewise reach the catch-all.

In MPE mode the RPN case therefore splits selector from value across two channels; in Non-MPE mode
both reach the Master Channel together, which is coherent but still not the specified discard.

The same catch-all mishandles an **invalid MCM**. The suppression case (line 328) matches whenever
the tracked selector is RPN 00 06, on **any** input channel, so the selector is swallowed even off
Channels 1 and 16; the following Data Entry MSB then fails the `inputChannel == 0 || inputChannel ==
15` guard (line 335), reaches the catch-all, and arrives on the output Master Channel as a bare Data
Entry. §4.2 now requires an invalid MCM to be ignored "in its entirety: neither its selector nor its
Data Entry is relayed".

One fix serves all of it: gate every RPN/NRPN wire message — selectors, Data Entry, Data
Increment/Decrement and RPN Null — on the tracked selector being one of the two interpreted RPNs on
a channel where that RPN is valid, and drop it otherwise.

### N4. MIDI Mode messages 124–127 not discarded (§3.5, §5.8.6)

§3.5 (new in `a90e563`) makes the Tuner fixed-mode on both sides and requires that the **MIDI Mode
messages 124–127** (Omni Off, Omni On, Mono On, Poly On) be **discarded in both input modes** —
neither forwarded nor emitted. This is the sole exception to Master Channel forwarding (§3.4) and
to Non-MPE redirection (§3.3 item 4); §5.8.6 records it as a deliberate departure from
transparency, justified because a Mode 4 (monophonic) Member Channel downstream would turn every
shared allocation into an unintended note drop.

The implementation has no notion of Channel Mode messages at all. `JavaMidiConverters` maps every
`0xB0` status message to `CcScMidiMessage` regardless of controller number
(`JavaMidiConverters.scala:208`), and `ScMidiCc` defines constants only for 120, 121 and 123 — none
for 122 or 124–127. Controller numbers 124–127 therefore fall through `processCc`'s catch-all
(lines 350-351) and are **redirected to the zone's Master Channel** like any other CC. A Mono On
reaching the output Zone's Master Channel is precisely the outcome §5.8.6 forecloses.

**The 120–123 half is aligned** — see Section 5. Note the asymmetry: the paper distinguishes
124–127 from 120–123, while the code's catch-all treats all eight identically, so the fix needs an
explicit controller-number branch rather than a change of default.

### I2. Out-of-zone notes and controls in MPE mode (§3.6, §5 intro)

Filed as a paper gap in earlier revisions; `09ce128` settled it, in the direction the author chose,
so it is now a conflict. **Paper** §3.6: in MPE Input Mode a channel that is neither the Master
Channel nor a Member Channel of an enabled Zone lies outside the Zone structure, and every Channel
Voice and Channel Mode message received on it is discarded — notes "neither forwarded nor
allocated" (§5 intro), channel-global controls and Zone-level messages neither redirected nor
passed through. MCMs are the sole exception.

**Implementation**: `getAllocatorForInput` (`MpeTuner.scala:722-738`) falls through both zone tests
to `lowerAllocator.orElse(upperAllocator)` (line 735), so an out-of-zone note is allocated into the
first enabled zone and tuned as though it had arrived inside it; `resolveZoneMasterChannel` (lines
630-631) returns `Some(inputChannel)`, so out-of-zone controls pass through unchanged on their
original channel.

This is also the mechanism C4 depends on: deactivating every Zone leaves every channel out of zone,
so a correct discard here yields the silence §4.1 requires, and one fix serves both.

### I3. Zone-level messages on an input Member Channel in MPE mode (§3.4, §2.6)

Closed as aligned in the previous revision, when `09ce128` specified the redirection the code
performs. `5d77eff` reversed that decision, so it is a conflict again and returns to this section.

**Paper** §3.4, as amended: in MPE Input Mode a Zone-level message arriving on an input *Member*
Channel is **discarded**, following the receiver obligation of [1, §2.3.1] ("it must ignore it") and,
for Program Change, [1, §2.3.3] ("a receiver operating in Mode 3 should ignore Program Change
messages received on Member Channels"). Three exemptions: the three control dimensions (per-note
under §7.2), Pitch Bend Sensitivity (note-level as well as Zone-level per [1, Table 1], handled by
§4.3), and an MCM
on Channel 1 or 16 (§4.2). The MIDI Mode messages are discarded on every channel (§3.5, N4).

**Implementation**: generic CCs reach `processCc`'s catch-all and are redirected to the zone's Master
Channel (`MpeTuner.scala:350-351`), as is Program Change (`processShortMessage`, lines 181-185).
`resolveZoneMasterChannel` maps any in-zone channel — Master and Member alike — to
`zone.masterChannel`, discarding the distinction in its `case Some((zone, _))` wildcard (lines
628-629). So a Damper Pedal sent on input Member Channel 4 arrives on the output Master Channel and
sustains the whole Zone, precisely the outcome the amended §3.4 cites as its motive.

The information the fix needs is already there: `findZoneForChannel` returns
`Some((zone, isMaster))` (line 628) and only the wildcard throws `isMaster` away. The Non-MPE path is
unaffected — `resolveZoneMasterChannel` branches on `inputMode` first (line 625), and §3.3 item 4's
redirection stands, for the reason the amended §3.4 gives: a non-MPE input has no Member Channels.

---

## 3. In the implementation, not in the paper

Both entries below are **implementation-specific by decision**: the paper deliberately says nothing,
the implementation is free to choose, and neither side has work to do. They are kept on record so a
later reader does not re-open them as gaps.

### I5. Non-Channel messages passed through

`process` forwards any non-`ShortMessage` (SysEx, system common/real-time) unchanged
(`MpeTuner.scala:128-133`), and `processShortMessage`'s own catch-all (lines 186-187) passes
through any `ShortMessage` with no typed match. The paper still never mentions System messages;
§3.5 covers Channel Mode messages only.

### I6. MCM emission for disabled zones at start-up

`configurationMessages` (lines 154-163) calls `mcmMessages` unconditionally for each zone (lines
157 and 159), so a disabled zone gets a zero-member (deactivation) MCM at start-up/reset — note the
contrast with `pitchBendSensitivityMessages`, which does guard on `zone.isEnabled` (line 701). §4.2
says the Tuner emits "the MCM(s) describing its Zone configuration" — whether that includes
explicit deactivation MCMs for zones that were never active is left open, and emitting them is a
defensible reading. No paper change intended.

---

## 4. Implementation bugs

### I1. Active Tuning reset to Standard on incoming MCM

Filed under "not in the paper" in earlier revisions; assessed as an implementation bug, so it is
refiled here. The paper needs no change — §8 already treats the Tuning as changed only by the
performer.

`resetState()` sets `_tuning = Tuning.Standard` (`MpeTuner.scala:143`) and is called from
`processMcm` (line 381). So an in-band Zone reconfiguration silently discards the performer's
active Tuning; subsequent notes play in 12-EDO until the next `tune()` call. Nothing in §4.2 or §8
sanctions this. Resetting the Tuning is appropriate in `reset()` (full re-initialization, line 112),
not in the MCM path.

### B1. Dropped notes leave stale entries in `channelNoteMap`

When the allocator drops notes (channel exhaustion or high bend), `MpeTuner` emits their Note Offs
(`emitDroppedNoteOffs`, lines 653-659) but **never removes them from `channelNoteMap`** — the
method body writes only to `buffer`. Tracking added at `trackNote` (line 230) is undone only in
`untrackNote` on a sender Note Off, or wholesale in `resetState`. Consequences:

- The sender's eventual Note Off for a dropped note finds the stale entry, so `untrackNote` returns
  `Some(outChannel)` and a **duplicate** downstream Note Off is emitted (line 266). The
  accompanying `release` is a no-op, because `ChannelState.removeNote` guards on
  `_notes.remove(midiNote).isDefined` (`MpeChannelAllocator.scala:190`).
- In MPE mode, `outputChannelsFor(inputChannel)` (lines 543-545) still lists the output channel of
  the dropped note, so expressive Pitch Bend / CC #74 / CP fan-out targets a channel that may by
  now be occupied by a **different pitch class** — directly violating the intonation guarantees
  both the paper (§5.2) and the code aim for.

**The paper now prescribes the fix.** `a90e563` added the rule to §5.1 and §6: dropping a note
clears its reference count *and* its channel binding, so a Note Off the performer sends for it
afterwards is **discarded**. Forwarding it "would exceed the one-Note-Off-per-Note-On obligation
rather than satisfy it" (§6). The first consequence above is thus also a direct conflict with §5.1
rule 4.

### N2. Duplicate Note On re-runs allocation (§5.1, §7.6)

`processMemberNoteOn` calls `alloc.allocate(...)` unconditionally (line 221) — there is no check
for an already-active note, because there are no reference counts (N1). §5.1 and §7.6.1 require the
opposite: a Note On that raises the count above 1 must bypass allocation entirely and be forwarded
on the channel already bound to the identity. Tracing the two cases of §7.6 through the code gives
two distinct failures.

**Case 1 — same input channel (§7.6.1): a leaked channel and a hanging note.** With E4 active on
input channel 2 → output channel 2, a second Note On for E4 on input channel 2 re-enters
`allocate`. Step 1 is skipped (`pitchClassInPCG` is true, line 253); Step 2 fires whenever the
Expression Group has capacity (line 260) and returns a **different** output channel, say 3. Then:

- `trackNote(2, E4, 3)` (line 230) **overwrites** `channelNoteMap(2)(E4)`, destroying the binding
  to output channel 2.
- The first Note Off routes to channel 3, releases it, and removes the map entry; the second Note
  Off finds nothing and is dropped at lines 269-270.
- Output channel 2 still holds E4 in its `ChannelState._notes` and is never released: the channel
  stays **occupied and unusable** until the next `reset()`/MCM, and its Note On is never matched
  downstream — a **hanging note**.
- Two Note Ons were forwarded but only one Note Off, violating the very MIDI 1.0 obligation
  [2, p. A-4] that §5.1 cites the counting to satisfy.

If Step 2 has no capacity and Step 3 shares the same channel instead, there is no leak, but
`ChannelState.addNote`'s `_notes(midiNote) = expression` (line 178) **replaces** the existing
entry, resetting the note's accumulated expression — contrary to §7.6.1's "the note keeps whatever
pressure it has accumulated".

**Case 2 — different input channels (§7.6.2): unrepresentable, and a premature free.** Two
identities `(2, E4)` and `(3, E4)` that Step 3 places on one output channel collide in
`ChannelState._notes`, which is keyed by `MidiNote` alone (line 104). The second `addNote`
overwrites the first's expression and `notes.size` stays 1. Consequences:

- `computeOutputPitchBend` averages over `alloc.activeNotes(channel)` (line 637) and so sees **one**
  term where §7.6.2 requires two — the emitted bend is the second note's alone, not the average.
- `dropExistingNotesForHighBend` reads `state.expressionFor(n)` for the pre-add snapshot (line 437),
  which after the overwrite returns the *new* note's bend — the high-bend test consults the wrong
  note.
- The first Note Off removes the single entry, so the channel reports **unoccupied** while a note is
  still sounding on it. A subsequent allocation can then place a **different pitch class** on that
  channel — a pitch-class invariant violation (§5.2).

`channelNoteMap` handles Case 2 correctly (it keys on input channel), so the two forwarded Note Offs
do reconcile in count; the damage is confined to the allocator.

Both cases resolve the same way: give `ChannelState` identity-keyed notes and reference counts
(N1), then gate `allocate` on the 0 → 1 transition.

---

## 5. Verified aligned

Spot-checked; no gap found. Entries marked **↺** were flagged for re-checking in the previous
revision because `a90e563` changed the paper text under them, and have now been re-verified.
Entries marked **✎** were open findings closed by the paper edits of `09ce128`: the behavior did
not change, the paper now specifies it. `I3` was marked ✎ here in the previous revision; `5d77eff`
reversed the rule under it, so it has been reopened and now sits in Section 2.

- **✎ I4.** The RPN wire protocol — selector suppression for the two interpreted RPNs (`processCc`
  lines 321-338, the empty-body case at line 328), `applyPbsUpdate`'s selector re-sent before the
  forwarded Data Entry (lines 482-487), and `mcmMessages`' RPN Null termination (lines 689-698) — is
  now the specified behavior in the §4 preamble. That paragraph's treatment of *uninterpreted* RPNs
  was rewritten by `5d77eff` and is not satisfied: see C6.
- **✎ A1.** Criterion (d)'s convention — no Note Off history counts as the oldest possible last Note
  Off and wins the criterion; several such channels tie and fall to (e) — is now stated in §5.6 and
  in its motivation bullet. The implementation conforms without special-casing: `ChannelState`
  initializes `_lastNoteOffTime = 0L` (`MpeChannelAllocator.scala:108`), `removeNote` (lines 189-198)
  is the only other writer and always stores a `nextTime()` value ≥ 1 (the counter pre-increments,
  lines 226-231), so `0` is an unambiguous never-released marker, and `bestCandidate`'s `minBy` tuple
  (lines 461-469) orders term (d) ahead of the two (e) terms. Note that no worked example exercises
  criterion (d) — §9.1 step 4 lost its walkthrough in `a90e563` — so the convention rests on §5.6's
  prose alone.

- Input modes, one-way in-band switch to MPE on MCM (§3.2, §4.1) — `processMcm` sets
  `_inputMode = Mpe` (line 398) and nothing sets it back except `reset()`.
- Master Channel note / Pitch Bend / Poly Pressure forwarding, no tuning offset on Master notes
  (§3.4) — except the CC #74 / CP case in C5. **↺** §3.4's new MIDI Mode exception is a separate
  finding (N4); the Pitch Bend and Poly Pressure paths themselves are unaffected.
- Non-MPE conversion: Pitch Bend / CC #74 / CP / other CCs / Program Change redirected to the
  routing zone's Master Channel, lower zone preferred (§3.3 items 2 and 4, §4.2). Uninterpreted RPNs
  were part of this list until `5d77eff` excepted them from item 4's redirection — that half is now
  C6.
  **↺** Item 4's new clauses check out for 120–123: they are redirected like any other CC by the
  catch-all, and neither `MpeTuner` nor its tracker clears note state on them — `MpeTuner`
  constructs `ScMidiChannelStateTracker()` with defaults (line 88), and `75a70ee` made
  `shallRespondToResetMessages` default to `false`, so All Sound Off / All Notes Off / Reset All
  Controllers are recorded but leave tracked state untouched, exactly as §3.5 requires. The 124–127
  clause is N4.
- **↺ N3.** Note Off velocity and ordering for Tuner-originated Note Offs (§6, new in `a90e563`) —
  **aligned on both counts**. `emitDroppedNoteOffs` (line 657) and `stopAllNotes` (line 520)
  construct `NoteOffScMidiMessage` without a velocity, taking
  `NoteOffScMidiMessage.DefaultVelocity` = 64, the neutral release velocity §6 requires. Ordering
  also holds: in `processMemberNoteOn` the dropped-note Note Offs are emitted at line 226, ahead of
  the Pitch Bend (235), CC #74 (243), Channel Pressure (246) and Note On (249) of the incoming
  note — §6's "those Note Off messages precede every message emitted for that note, its control
  dimension setup messages included". The §6.2.1 divergence path matches §9.5 step 3 too:
  `processPitchBend` emits the drops at line 296 before `emitTuningPitchBend` at line 298.
  The velocity-0-Note-On-as-Note-Off path (line 175) likewise builds a default-velocity Note Off,
  matching §5's "release velocity 64" [1, §3.3.2].
- Poly Pressure discarded on input Member Channels in MPE mode (lines 569-575); discarded for
  inactive notes in Non-MPE mode (§7.2, §7.3). **↺** §7.3's new per-input-channel bookkeeping
  requirement is satisfied — the lookup keys on `(inputChannel, midiNote)` (lines 580-581).
- MCM validity — the hardcoded `inputChannel == 0 || inputChannel == 15` at line 335 is exactly
  §4.2's amended rule, which keys validity on channel number rather than on the channel's current
  role. The previous revision glossed it as "equivalent" to a Master-Channel test; the two are *not*
  equivalent, since a fifteen-member Upper Zone makes Channel 1 a Member Channel while leaving it a
  valid Lower-Zone MCM target [1, §2.1.2.3], and the code has the right test. Zero-member
  deactivation, overlap resolution with most-recent-wins, MCM re-emission for the adjusted other
  zone (lines 389-395), PBS defaults restored on the reconfigured zone, explicit Note Offs before
  the MCM (§4.2, within the scope issue of C3).
- PBS (RPN 00 00): MSB/LSB patching preserving the other half (`patchPbs`, lines 443-446), Non-MPE →
  Master Channel routing (lines 410-415), MPE per-channel forwarding rather than zone-wide fan-out,
  zone-wide member update with pitch-bend recomputation (lines 490-493) (§4.3). The wire encoding of
  that forwarding is I4.
- Group sizing table (§5.4 / Appendix A) — `MpeZoneStructure.expressionGroupSize` matches exactly.
- Allocation algorithm steps 1–4, including step-3 candidates restricted to same-pitch-class
  occupied channels with `preferredChannel` disabled (lines 266-275) and step-4 boundary-channel
  exclusion with both edge cases (`freeChannel`, lines 485-510) (§5.6, §6.1). **↺** §5.6's new
  precondition — the algorithm runs *only* on a 0 → 1 reference-count transition — is **not**
  satisfied; that is N2. The steps themselves are unchanged and remain correct.
- Tie-break criteria (a)–(e) ordering, including MPE-mode input-channel preference only where the
  channel is an unoccupied candidate (`bestCandidate`, lines 461-469; `preferredChannel` passed as
  `None` at lines 273, 499 and 503) (§5.6). Criterion (d)'s treatment of channels with no Note Off
  history is now explicit in the paper — see the A1 entry above. **↺** Criterion (b)'s newly
  specified counting semantics is N5.
- High Expression Pitch Bend threshold t = 50 cents (`ExpressionPitchBendThreshold`, line 519)
  (§5.5); divergence dropping §6.2.1 (modulo P3's wrong note identification); §6.2.3 freeing on
  allocation to a high-bend channel (`existingHighBend`, line 437).
- Note On velocity 0 treated as Note Off (§5, intro) — lines 173-175.
- Note On message ordering PB → CC #74 → CP → Note On (§7.5, formerly §7.1.1) — lines 235, 243,
  246, 249. The unchanged-value omission is an explicitly optional optimization and is simply not
  implemented. The **Note Off** half of §7.5 is new and unimplemented — see P4.
- Real-time tuning change: single Pitch Bend per occupied Member Channel, Expression PB average
  preserved (`updateTuningOnZone` → `emitTuningPitchBend`, lines 665-682) (§8). §8.1 (transport
  latency) is expository and imposes no implementation requirement.
- Pitch bend clamping to the zone's member PBS range (`computeOutputPitchBend`, line 646).

## 6. Existing `TODO #154` markers cross-referenced

| Location | TODO | Covered by |
|---|---|---|
| `MpeTuner.scala:67-68` | Warn on Non-MPE mode with both zones enabled | Related to §4.2 "Upper Zone is ignored" (paper-conformant today; TODO is an ergonomics improvement, not a gap) |
| `MpeChannelAllocator.scala:61` | Note expression not currently updated | P1 |
| `MpeChannelAllocator.scala:81-82` | Add averaged `channelExpression` to `AllocationResult` | P1 |
| `MpeChannelAllocator.scala:102-103, 152-155` | LinkedHashMap / `lastAddedNote` removal after dropping-logic fix | P3, and N1 (the same map needs identity keying) |
| `MpeChannelAllocator.scala:223` | TreeMap ordering question | Not paper-related |
| `MpeChannelAllocator.scala:293-294` | "Bad assumption that the last note is being bent" | P3 |

Not covered by any existing TODO: P2, P4, P5, N1, N5, C1–C6, N4, I1, I2, I3, B1, N2. (P6 is out of
scope; I5 and I6 are implementation-specific; I4 and A1 were closed on the paper side by `09ce128`
and need no code work. I3 was closed there too, but `5d77eff` reopened it as a conflict requiring
code work.)

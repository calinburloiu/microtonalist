# MPE Tuner: Paper ↔ Implementation Gap Report

Comparison between the specification in `docs/architecture/tuner/mpe-tuner-paper.md` and the
implementation in `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/`
(`MpeTuner.scala`, `MpeChannelAllocator.scala`, `MpeZone.scala`), plus the supporting
`ScMidiChannelStateTracker` in `sc-midi`. Both artifacts are work in progress for #154; this
report inventories the gaps in each direction.

Line references are as of commit `32d66f9`.

## Summary

| ID | Direction | Topic | Severity |
|----|-----------|-------|----------|
| P1 | Paper → missing in impl | Per-note Expression Value model for CP and CC #74 (no per-note updates, no averaging) | Major |
| P2 | Paper → missing in impl | Note's initial Expression Pitch Bend not seeded from remembered input-channel state | Major |
| P3 | Paper → missing in impl | Expression update propagation maps input channel → "last added note" instead of the actual notes | Major |
| P4 | Paper → missing in impl | No re-emission of channel Expression Values when a note leaves the average (Note Off) | Medium |
| P5 | Paper → missing in impl | Poly Pressure → Channel Pressure conversion does not average on shared channels | Medium |
| P6 | Paper → missing in impl | Non-MIDI runtime configuration interface (input mode, zones, member PBS) | Medium |
| C1 | Conflict | CC #74 emitted on Member Channels in Non-MPE Input Mode (paper forbids it) | Medium |
| C2 | Conflict | Channel Pressure reset happens before Note On instead of at Note Off (Non-MPE mode) | Medium |
| C3 | Conflict | MCM reconfiguration resets *both* zones; paper preserves the untouched zone | Medium |
| C4 | Conflict | With all Zones deactivated, impl forwards notes as-is; paper says no note output | Minor |
| C5 | Conflict | Master Channel CC #74 / Channel Pressure not forwarded unmodified in MPE mode | Major |
| I1 | Impl → not in paper | Active Tuning reset to Standard on incoming MCM | Major (likely impl bug) |
| I2 | Impl → not in paper | Out-of-zone notes in MPE mode allocated to the first enabled zone | Minor (paper underspecified) |
| I3 | Impl → not in paper | Zone-level CCs / Program Change on input *Member* Channels redirected to Master (MPE mode) | Minor (paper underspecified) |
| I4 | Impl → not in paper | RPN selector suppression / re-emission wire protocol for MCM and PBS | Minor (doc-only) |
| I5 | Impl → not in paper | Non-Channel messages (SysEx, system) passed through | Minor (doc-only) |
| I6 | Impl → not in paper | MCMs also emitted for disabled zones at start-up | Minor (doc-only) |
| B1 | Impl bug (vs. both) | Dropped notes leave stale entries in `channelNoteMap` | Major |
| A1 | Paper ambiguity | Tie-break criterion (d) semantics for channels with no Note Off history | Minor |

---

## 1. In the paper, missing from the implementation

### P1. Per-note Expression Value model for Channel Pressure and CC #74 (§1.3, §5.6, §7.1–7.3)

The paper's central polyphonic-expression model: each note carries three Expression Values
(Expression Pitch Bend, Channel Pressure, CC #74), and each output Member Channel emits the
**average** of its active notes' values per dimension.

In the implementation, only the Expression Pitch Bend dimension participates in this model
(`computeOutputPitchBend`, `MpeTuner.scala:635-648`, averages `pitchBendCents` over active notes).
For CP and CC #74:

- `MutableMpeExpression` has `pressure` and `slide` fields, but they are set once at allocation
  (to defaults — `MpeTuner` never passes real values) and **never updated afterwards**
  (`MpeChannelAllocator.scala:61`: `TODO #154 Note expression is not currently updated`).
- At Note On, `MpeTuner` seeds the output channel's CC #74 / CP directly from the tracker's
  last-known per-*input-channel* value (`MpeTuner.scala:242-246`), bypassing the per-note model —
  no averaging with other notes already on the shared output channel.
- Incoming CC #74 / CP updates are fanned out raw to all output channels fed by the input channel
  (`forwardToMemberChannel`, `MpeTuner.scala:596-600`), overwriting the whole channel value.
  Per §7.2, they should update only the contributions of the notes originating from that input
  channel and re-emit the recomputed average.

Acknowledged by `MpeChannelAllocator.scala:81-82` (`TODO #154 Add a channelExpression field …
averaged across all channel notes`).

### P2. Initial Expression Pitch Bend not seeded from remembered input-channel state (§7.2)

§7.2 "State retention on input channels": when a note arrives on an input Member Channel, its
Expression Values are initialized from the channel's remembered control state. A conforming MPE
sender sends Pitch Bend *before* Note On, so this is the normal path, not an edge case.

The implementation tracks input Pitch Bend (`ScMidiChannelStateTracker`), but
`MpeTuner.scala:221` calls `alloc.allocate(midiNote, preferredChannel = …)` **without**
`expressivePitchBendCents` — the note always starts with Expression PB = 0. Two consequences:

- Pitch Bend sent by the sender before Note On on an input Member Channel is silently lost
  (at that moment `outputChannelsFor` is empty, so nothing is emitted, and the tracked value is
  never read back).
- §6.2.2 ("new note *with* a High Expression Pitch Bend on an occupied channel") is dead code:
  `dropExistingNotesForHighBend` supports it (`MpeChannelAllocator.scala:429-447`), but the new
  note's bend is always 0, so only the §6.2.3 half (existing high-bend note) can trigger.

### P3. Update propagation: input channel → note mapping (§7.2)

§7.2 "Update propagation": a control message on an input Member Channel updates the contribution
of *each note active on that input channel*. The implementation instead assumes the **most
recently added note** on the output channel is the one being bent
(`MpeChannelAllocator.updateExpressivePitchBend`, lines 306-324; `TODO #154` at lines 293-294:
"Bad assumption that the last note is being bent. To map incoming channel to output channel.").

This is wrong whenever notes from different input channels share an output channel (fan-in): a
bend from input channel X may be applied to a note that arrived from input channel Y. The paper's
fan-in averaging (multiple input channels → one output channel, §7.2 paragraph 2) is therefore
unimplemented for all three dimensions.

### P4. No re-emission when a note leaves the average (§7.1)

§7.1: "Each time an Expression Value of an output Member Channel changes — whether because a note
entered or left the average …— the new value is sent on that channel."

On Note Off (`MpeTuner.processNoteOff`, lines 257-273) the note is released from the allocator and
a Note Off is forwarded, but the remaining notes' new average Pitch Bend (or CP) is **not**
recomputed and emitted. The "note entered" direction works (the pre-Note-On Pitch Bend at
`MpeTuner.scala:233-235` includes the new note in the average); the "note left" direction is
missing.

### P5. Poly Pressure → Channel Pressure averaging in Non-MPE mode (§3.3 item 1, §7.3)

The conversion itself is implemented (`processPolyPressure`, `MpeTuner.scala:576-585`), including
discarding Poly Pressure for inactive notes. But when multiple notes share the output channel, the
paper requires the converted values to be **combined by averaging**; the implementation forwards
the raw value for the addressed note, overwriting the channel's CP. It also never records the
value in the note's `MpeExpression` (same root cause as P1).

### P6. Non-MIDI runtime configuration interface (§4, §4.1, §4.3)

The paper repeatedly assumes a non-MIDI configuration interface that can act **at runtime**:
re-entering Non-MPE Input Mode ("re-entered only through the non-MIDI configuration interface",
§4.1), changing Zones, changing Member Channel PBS in Non-MPE mode ("can be changed only through
the non-MIDI configuration interface", §4.3), and re-activating a Zone after full deactivation
(§4.1).

The implementation exposes configuration only via constructor parameters (`initialZones`,
`initialInputMode`) and `reset()` (which restores the initial values). There are no setters for
input mode, zones, or PBS, so none of the above runtime transitions is possible. (This may be
intended to live at the `TunerProcessor`/session layer, but nothing implements it today.)

---

## 2. Direct conflicts (paper and implementation both specify, and disagree)

### C1. CC #74 on Member Channels in Non-MPE Input Mode (§3.3 item 3, §7.3)

Paper: "the Tuner never sends CC #74 on a Member Channel" in Non-MPE mode; §7.3: "CC #74 never
appears on an output Member Channel."

Implementation: emits CC #74 = 64 on the allocated Member Channel before **every** Note On in
Non-MPE mode (`MpeTuner.scala:242-243`), justified in the code comment by MPE spec §3.3.5
("Initial-64"). One of the two documents must yield: either the paper should permit the neutral
Initial-64 seeding, or the implementation should stop emitting it.

### C2. Channel Pressure reset placement in Non-MPE mode (§7.4)

Paper §7.4: in Non-MPE mode the Tuner is the controller and performs the CP reset **at Note Off**,
"returning the channel's Channel Pressure to 0". Rationale: the release tail after Note Off should
not keep a stale converted-Poly-Pressure value.

Implementation: emits CP = 0 before every Note On in Non-MPE mode (`MpeTuner.scala:245-246`) and
emits **nothing at Note Off** (`processNoteOff`, lines 257-273). The receiver state is correct by
the next Note On (satisfying §7.3's weaker phrasing), but between a Note Off and the next Note On
the downstream channel keeps the stale CP, affecting the release tail — exactly what §7.4's chosen
design avoids.

### C3. Scope of state reset on MCM reconfiguration (§4.2)

Paper: a Zone reconfiguration resets state only for channels "entering or leaving MPE control";
"Channels of a Zone untouched by the reconfiguration keep their notes and state."

Implementation (`processMcm`, `MpeTuner.scala:361-399`): `stopAllNotes` drops notes on **both**
zones (line 375) and `resetState()` (line 381) recreates **both** allocators and clears all
tracking, even when the MCM touches only one zone and no overlap resolution occurs. Notes and
retained Expression Values in the untouched zone are lost, contrary to the paper.

### C4. Behavior when all Zones are deactivated (§4.1)

Paper: with all Zones deactivated "the Tuner produces no note output until a Zone is
re-activated."

Implementation: with no enabled zone there is no allocator, and Note Ons are **forwarded as-is**
on their input channel (`MpeTuner.scala:251-253`). (Non-note controls are variously dropped or
passed; the paper leaves those unspecified.)

### C5. Master Channel CC #74 and Channel Pressure forwarding in MPE mode (§3.4)

Paper §3.4: Master Channel Pitch Bend and Zone-level messages are "forwarded on the Master Channel
without modification". Per the MPE spec (§2.5–2.6, quoted in paper §2.5/2.6), CC #74 and Channel
Pressure on a Master Channel are Zone-level controls.

Implementation: only Pitch Bend and Poly Pressure special-case the Master Channel
(`processPitchBend` lines 281-283, `processPolyPressure` lines 573-575). CC #74 and Channel
Pressure go through `forwardToMemberChannel` unconditionally in MPE mode (`processCc` lines
343-346, `processChannelPressure` lines 552-554), which forwards to `outputChannelsFor(channel)` —
for a Master Channel that is the set of tracked master-channel notes. Result:

- With no active Master Channel notes, Master CC #74 / CP is **silently dropped** instead of
  forwarded as a Zone-level control.
- With active Member Channel notes in the zone, the Zone-level control never reaches them (it is
  not re-emitted on the Master Channel).

---

## 3. In the implementation, not in the paper

### I1. Active Tuning reset to Standard on incoming MCM

`resetState()` sets `_tuning = Tuning.Standard` (`MpeTuner.scala:143`) and is called from
`processMcm` (line 381). So an in-band Zone reconfiguration silently discards the performer's
active Tuning; subsequent notes play in 12-EDO until the next `tune()` call. Nothing in §4.2 or §8
sanctions this — §8 treats the Tuning as changed only by the performer. Resetting tuning is
appropriate in `reset()` (full re-initialization) but almost certainly a bug in the MCM path.

### I2. Out-of-zone notes in MPE mode fall back to the first enabled zone

`getAllocatorForInput` (`MpeTuner.scala:722-738`): in MPE mode, a note on a channel belonging to
**no** zone is allocated via `lowerAllocator.orElse(upperAllocator)` (line 735). The paper's
allocation rules (§5, intro) cover only notes "received on a Member Channel in MPE Input Mode" and
never say what to do with out-of-zone notes (drop? pass through, as `resolveZoneMasterChannel`
does for controls at lines 630-631? allocate?). Paper gap; the implementation's choice should be
either documented or changed.

### I3. Zone-level messages on input *Member* Channels in MPE mode redirected to Master

Generic CCs (Damper, Modulation, …) and Program Change received on a Member Channel in MPE mode
are redirected to the zone's Master Channel (`processCc` catch-all, lines 350-351;
`processShortMessage` Program Change, lines 181-184). The paper specifies Master Channel
*forwarding* (§3.4) and quotes the MPE spec rule that receivers must **ignore** Zone-level
messages on Member Channels (§2.6), but never says what the Tuner does when its *input* carries
such messages on a Member Channel. Redirecting (impl) vs. dropping (MPE receiver semantics) is a
real behavioral choice the paper should record.

### I4. RPN selector suppression and re-emission wire protocol

The implementation suppresses the sender's RPN selector CCs for MCM and PBS and re-emits the full
RPN sequences itself (`processCc` lines 321-338; `applyPbsUpdate` lines 482-487, re-sending the
PBS RPN selector before each Data Entry to guard against interleaving; `mcmMessages` with RPN Null
termination, lines 689-698). §4.3 only says the Tuner forwards "each received message … 1:1" —
the actual (and more robust) wire behavior is undocumented and technically deviates from a literal
1:1 forward.

### I5. Non-Channel messages passed through

`process` forwards any non-`ShortMessage` (SysEx, system common/real-time) unchanged
(`MpeTuner.scala:128-133`). The paper never mentions System messages.

### I6. MCM emission for disabled zones at start-up

`configurationMessages` (lines 154-163) unconditionally emits an MCM for each zone, so a disabled
zone gets a zero-member (deactivation) MCM at start-up/reset. §4.2 says the Tuner emits "the
MCM(s) describing its Zone configuration" — whether that includes explicit deactivation MCMs for
zones that were never active is unspecified. (Emitting them is defensible; worth one sentence in
the paper.)

---

## 4. Implementation issues surfaced by the comparison (inconsistent with both)

### B1. Dropped notes leave stale entries in `channelNoteMap`

When the allocator drops notes (channel exhaustion or high bend), `MpeTuner` emits their Note Offs
(`emitDroppedNoteOffs`, lines 653-659) but **never removes them from `channelNoteMap`** (tracking
added at `trackNote`, line 230, is only undone in `untrackNote` on a sender Note Off, or wholesale
in `resetState`). Consequences:

- The sender's eventual Note Off for a dropped note produces a **duplicate** downstream Note Off
  (and a no-op `release`).
- In MPE mode, `outputChannelsFor(inputChannel)` (lines 543-545) still lists the output channel of
  the dropped note, so expressive Pitch Bend / CC #74 / CP fan-out targets a channel that may by
  now be occupied by a **different pitch class** — directly violating the intonation guarantees
  both the paper (§5.1) and the code aim for.

### A1. Tie-break criterion (d): channels with no Note Off history

Paper §5.5 criterion (d): "Channels with no Note Off history cannot be discriminated by this
criterion; when it fails to single out a channel, selection falls to criterion (e)." This reads as
if never-released channels are *incomparable* under (d). The implementation encodes "no history"
as `lastNoteOffTime = 0` (`bestCandidate`, `MpeChannelAllocator.scala:461-469`), which makes a
never-released channel **beat** any channel with a real Note Off. For a mixed candidate set
(some with history, some without) the two readings pick different channels. The worked example
(§9.1 step 4) only exercises the all-without-history case, where they agree. The paper should
state which semantics is intended (the implementation's "never released counts as oldest" is a
reasonable reading of the MPE spec's recommendation).

---

## 5. Verified aligned (spot-checked, no gap found)

- Input modes, one-way in-band switch to MPE on MCM (§3.2, §4.1).
- Master Channel note / Pitch Bend / Poly Pressure forwarding, no tuning offset on Master notes
  (§3.4) — except the CC #74 / CP case in C5.
- Non-MPE conversion: Pitch Bend / CC #74 / CP / other CCs / Program Change / unknown RPNs
  redirected to the routing zone's Master Channel, lower zone preferred (§3.3 items 2 and 4, §4.2).
- Poly Pressure discarded on input Member Channels in MPE mode; discarded for inactive notes in
  Non-MPE mode (§7.2, §7.3).
- MCM validity (Master Channels only — hardcoded 0/15, equivalent), zero-member deactivation,
  overlap resolution with most-recent-wins, MCM re-emission for the adjusted other zone, PBS
  defaults restored on the reconfigured zone, explicit Note Offs before the MCM (§4.2, within the
  scope issue of C3).
- PBS (RPN 00 00): MSB/LSB patching preserving the other half, Non-MPE → Master Channel routing,
  MPE per-channel 1:1 forwarding (modulo I4), zone-wide member update with pitch-bend
  recomputation (§4.3).
- Group sizing table (§5.3 / Appendix A) — `MpeZoneStructure.expressionGroupSize` matches exactly.
- Allocation algorithm steps 1–4, including step-3 candidates restricted to same-pitch-class
  occupied channels with `preferredChannel` disabled, and step-4 boundary-channel exclusion with
  both edge cases (§5.5, §6.1).
- Tie-break criteria (a)–(e) ordering, including MPE-mode input-channel preference only where the
  channel is an unoccupied candidate (§5.5) — modulo A1.
- High Expression Pitch Bend threshold t = 50 cents (§5.4); divergence dropping §6.2.1 (modulo
  P3's wrong note identification); §6.2.3 freeing on allocation to a high-bend channel.
- Note On velocity 0 treated as Note Off (§5, intro).
- Note On message ordering PB → CC #74 → CP → Note On (§7.1.1); the unchanged-value omission is an
  explicitly optional optimization and is simply not implemented.
- Real-time tuning change: single Pitch Bend per occupied Member Channel, Expression PB average
  preserved (§8).
- Pitch bend clamping to the zone's member PBS range (`computeOutputPitchBend`).

## 6. Existing `TODO #154` markers cross-referenced

| Location | TODO | Covered by |
|---|---|---|
| `MpeTuner.scala:67-68` | Forbid/warn Non-MPE mode with both zones enabled | Related to §4.2 "Upper Zone is ignored" (paper-conformant today; TODO is an ergonomics improvement, not a gap) |
| `MpeChannelAllocator.scala:61` | Note expression not currently updated | P1 |
| `MpeChannelAllocator.scala:81-82` | Add averaged `channelExpression` to `AllocationResult` | P1 |
| `MpeChannelAllocator.scala:102-103, 152-155` | LinkedHashMap / `lastAddedNote` removal after dropping-logic fix | P3 |
| `MpeChannelAllocator.scala:223` | TreeMap ordering question | Not paper-related |
| `MpeChannelAllocator.scala:293-294` | "Bad assumption that the last note is being bent" | P3 |

Not covered by any existing TODO: P2, P4, P5, P6, C1–C5, I1–I3, B1, A1.

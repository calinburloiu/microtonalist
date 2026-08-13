# MPE Tuner Routing and Filtering Conformance (Design)

- **Date**: 2026-08-12
- **Revised**: 2026-08-13 — two refinements found while writing the implementation plan (Sections 5 C3 and 5 C6), and
  Section 7's delivery rules corrected: no phase PR resolves #250, and phases 1, 2 and 4 may run in parallel
- **Issue**: [#250](https://github.com/calinburloiu/microtonalist/issues/250) — "Make MPE Tuner MIDI message routing
  and filtering conform to the paper"
- **Base commit**: `271cfb314cd537cdb6fb655de6799ec1d30262c8`
- **Prompt this design answers**:
  [`2026-08-03-routing-conformance-prompt.md`](2026-08-03-routing-conformance-prompt.md)
- **Source of truth**: the MPE Tuner paper,
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../../docs/architecture/tuner/mpe-tuner-paper.md), except where this
  document states an amendment to it
- **MPE Specification**: [`docs/architecture/tuner/mpe-spec.md`](../../docs/architecture/tuner/mpe-spec.md)

Gap identifiers (`P7`, `C3`–`C6`, `N4`, `I1`–`I3`) and section labels (`§2.2(d)`–`§2.2(f)`) are inherited verbatim from
the prompt, which inherits them from the cycle-1 prompt. They are the shared vocabulary of issue #250's body, the paper
and the gap report, and are deliberately not renumbered.

## 1. Problem

Cycle 1 of #154 (PR #251) implemented the MPE Tuner's polyphonic expression model. Cycle 2, this issue, covers the MIDI
message routing and filtering the paper requires and the implementation does not yet provide: seven gaps, enumerated in
the prompt's "Implementation gaps" section and in the `TODO #250` at `MpeTuner.scala:644-651`.

Five of the seven share a single root cause. `resolveZoneMasterChannel` (`MpeTuner.scala:652-661`) collapses three
independent facts — *which Zone a channel belongs to*, *whether it is that Zone's Master or a Member*, and *whether it
belongs to any Zone at all* — into one `Option[Int]`. Its `case Some((zone, _))` wildcard discards the Master/Member
distinction, which is **I3**; its `case None => Some(inputChannel)` passes out-of-zone traffic through unchanged, which
is **I2** and, when no Zone is enabled at all, **C4**. `allocatorFor` (lines 787-803) compounds the second by falling
back to `lowerAllocator.orElse(upperAllocator)`, so an out-of-zone note is allocated into whichever Zone happens to be
enabled. **C5** is the same conflation seen from the other side: the Master Channel role is never consulted for CC #74
or Channel Pressure, so both are handed to an allocator that holds no Master Channel note and emits nothing. **N4** is
adjacent: the code has no notion of Channel Mode messages, so 124–127 fall through `processCc`'s catch-all and are
redirected like ordinary CCs.

The remaining two are independent. **C6** and §2.2(f) concern RPN/NRPN traffic — where it must go, and how it must be
grouped. **C3** and **I1** concern the scope of the state reset performed on Zone reconfiguration.

Making the **channel role** an explicit, total value is therefore the shared fix for the first five, and the structure
the other two hang off.

## 2. Decisions taken before design

Four questions were settled with the author before the design was fixed. They are recorded here because three of them
depart from what the prompt or the paper says today.

1. **§2.2(f) applies in both input modes.** The prompt presents atomic RPN/NRPN sequence grouping as a cycle-1 design
   requirement its author added, not something the paper states, and invites the conflict to be raised. There is one:
   §4's preamble scopes consume-and-re-emit to "the only two Registered or Non-Registered Parameter Numbers the Tuner
   interprets", and §3.5's table then gives uninterpreted RPN/NRPN a Zone-level verdict of "Forwarded unmodified on the
   same Master Channel" — which holding selectors back and re-emitting them is not. The author chose to apply grouping
   in both modes and amend the paper accordingly (Section 6).
2. **The MSB-before-LSB RPN selector order is normalized across all three emitters**, including `sc-midi`'s
   `PitchBendSensitivityMessages.create`, which drags `MonophonicPitchBendTuner`'s output and tests along. The prompt
   originally placed this out of scope and was revised to bring it in.
3. **`ScMidiChannelStateTracker` gains a per-channel `reset(channel: Int)`**, rather than the Tuner snapshotting and
   replaying around the existing global `reset()`.
4. **The work ships as four phased pull requests**, each a sub-issue of #250 (Section 7).

## 3. Architecture

### 3.1 New component: `MpeMessageRouting`

A new `private[tuner]` file `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeMessageRouting.scala`,
pure and free of mutable state:

```scala
enum MpeChannelRole {
  case NonMpeInput(routingZone: MpeZone)  // Non-MPE Input Mode, some Zone enabled
  case Master(zone: MpeZone)              // MPE Input Mode, Master Channel of an enabled Zone
  case Member(zone: MpeZone)              // MPE Input Mode, Member Channel of an enabled Zone
  case Outside                            // under no Zone's control, in either input mode
}

enum MpeRoutingVerdict {
  case Discard                              // emit nothing
  case ForwardOn(channel: Int)              // relay unmodified, on the same channel or redirected
  case ForwardRpnSequenceOn(channel: Int)   // §2.2(f): the full selector, then this value CC
  case Interpret                            // MpeTuner acts on it
}

object MpeMessageRouting {
  def roleOf(inputMode: MpeInputMode, zones: MpeZones, channel: Int): MpeChannelRole
  def route(role: MpeChannelRole, message: ChannelScMidiMessage, rpnSelector: RpnSelector): MpeRoutingVerdict
  def rpnSequence(selector: RpnSelector, ccNumber: Int, ccValue: Int, outputChannel: Int): Seq[MidiMessage]
}
```

`route` **is** §3.5's table: one `case` per row, the role supplying the column. Section 4 gives the matrix it
implements.

`Outside` deliberately covers three situations that want the identical verdict — a channel in no enabled Zone under MPE
input, every channel when no Zone is enabled at all, and every channel in Non-MPE Input Mode with no Zone enabled. This
makes the prompt's observation that **C4 is the degenerate case of I2** structural rather than incidental: one enum case
resolves both, and there is no separate `NoZone` role to keep consistent with it.

`route` needs the tracker's RPN selector to tell an MCM Data Entry from a PBS one from uninterpreted traffic. That is
available and current: `processShortMessage` feeds `tracker.send(scMessage)` at `MpeTuner.scala:177` *before*
dispatching, so by the time routing runs the selector already includes the CC being routed.

### 3.2 No separate RPN sequencer

§2.2(f) needs no state machine of its own. `ScMidiChannelStateTracker` already models the selector as
`RpnSelector.None | Rpn(msb, lsb) | Nrpn(msb, lsb)` and maintains it per channel. Rendering a complete sequence is
therefore a pure function of `(selector, value CC, output channel)` — `rpnSequence` above — with no second copy of the
state to keep in sync with the tracker's. It lives in `MpeMessageRouting.scala` alongside the table it serves.

### 3.3 Changes in `MpeTuner`

Three methods that conflate the roles are removed: `resolveZoneMasterChannel` (and the `TODO #250` above it),
`isMasterChannel`, and `allocatorFor`'s `lowerAllocator.orElse(upperAllocator)` fallback. `processShortMessage` becomes
classify-then-act:

```scala
val role = MpeMessageRouting.roleOf(_inputMode, _zones, msg.channel)
MpeMessageRouting.route(role, msg, tracker.rpnSelector(msg.channel)) match {
  case Discard                  => logger.trace(…)
  case ForwardOn(ch)            => buffer += msg.mapChannel(_ => ch).asJava
  case ForwardRpnSequenceOn(ch) => buffer ++= MpeMessageRouting.rpnSequence(…, ch)
  case Interpret                => interpret(buffer, msg, role)
}
```

`interpret` keeps today's per-message-class handlers, each now receiving the role rather than re-deriving it.
`allocatorFor` takes a role instead of a channel, which makes `processMemberNoteOn`'s `case None` branch — the
hanging-note failure of **C4** — unreachable; it is deleted rather than patched. Non-channel messages (System
Exclusive, System Common, System Real-Time) bypass routing and are forwarded, as today.

`MpeTuner.scala` is expected to shrink from 838 lines to roughly 700.

### 3.4 Changes in `sc-midi`

- `ScMidiChannelStateTracker.reset(channel: Int)` — a per-channel counterpart to the existing global `reset()`, needed
  by **C3**.
- `ScMidiCc` constants for the Channel Mode messages it lacks: `LocalControl` (122), `OmniModeOff` (124), `OmniModeOn`
  (125), `MonoModeOn` (126), `PolyModeOn` (127). Only 124–127 are discarded; 122 completes the set and is forwarded
  like 120, 121 and 123.
- `PitchBendSensitivityMessages.create` — MSB-before-LSB ordering for both the selector and the closing Null.

## 4. The routing table

Cells in **bold** change; the rest is current behaviour, preserved. `z` is the role's Zone.

| Message received | `Member(z)` | `Master(z)` | `NonMpeInput(z)` | `Outside` |
|---|---|---|---|---|
| Note On / Note Off | Interpret — allocate | ForwardOn(same) | Interpret — allocate | **Discard** (I2, C4) |
| Pitch Bend | Interpret — Expression Value | ForwardOn(same) | ForwardOn(z.masterChannel) | **Discard** |
| Channel Pressure | Interpret — Expression Value | **ForwardOn(same)** (C5) | ForwardOn(z.masterChannel) | **Discard** |
| CC #74 | Interpret — Expression Value | **ForwardOn(same)** (C5) | ForwardOn(z.masterChannel) | **Discard** |
| Polyphonic Key Pressure | Discard | ForwardOn(same) | Interpret — to Channel Pressure | **Discard** |
| Program Change, Bank Select | **Discard** (I3) | ForwardOn(same) | ForwardOn(z.masterChannel) | **Discard** |
| CC #120, #121, #122, #123 | **Discard** (I3) | ForwardOn(same) | ForwardOn(z.masterChannel) | **Discard** |
| CC #124–127 (MIDI Mode) | **Discard** (N4) | **Discard** (N4) | **Discard** (N4) | **Discard** |
| Selector CC #101/#100/#99/#98 | **Discard** | **Discard** | **Discard** | **Discard** |
| Data Entry MSB, MCM selected, channel 1 or 16 | Interpret — MCM | Interpret — MCM | Interpret — MCM | Interpret — MCM |
| Data value, MCM selected, any other channel | **Discard** (C6) | **Discard** (C6) | **Discard** (C6) | **Discard** |
| Data Entry MSB/LSB, PBS selected | Interpret — PBS | Interpret — PBS | Interpret — PBS | **Discard** |
| Data value, any other selector | **Discard** (I3, C6) | **ForwardRpnSequenceOn(same)** | **ForwardRpnSequenceOn(z.masterChannel)** | **Discard** |
| Data value, no selector ever set | **Discard** | **Discard** | **Discard** | **Discard** |
| Any other CC | **Discard** (I3) | ForwardOn(same) | ForwardOn(z.masterChannel) | **Discard** |

"Data value" means Data Entry MSB (CC #6), Data Entry LSB (CC #38), Data Increment (CC #96) or Data Decrement
(CC #97).

Four rules the table cannot express:

1. **The MCM test precedes the role test.** §3.5 accepts an MCM "on MIDI Channels 1 and 16 only, whatever their current
   role", and §3.7 makes it the sole exception to the outside-zone discard. The channel-number guard
   (`channel == 0 || channel == 15`) is therefore evaluated first, so an MCM still reconfigures a Tuner whose Zones are
   all disabled — which is what keeps in-band re-activation working (§4.1).
2. **Data Entry LSB with the MCM selected is discarded.** The MPE Specification's MCM uses the Data Entry MSB alone
   ("the LSB of Data Entry is not used"). Today such a message reaches `processCc`'s catch-all and is forwarded.
3. **Data Increment/Decrement on an interpreted parameter (MCM or PBS) is discarded.** Neither the paper nor the MPE
   Specification covers it. Forwarding it would desync the Tuner's stored Pitch Bend Sensitivity from the value the
   downstream receiver holds, since the Tuner does not interpret the increment. Discarding is the conservative rule;
   it is a decision recorded here, not a requirement derived from the paper.
4. **Discards are logged at `trace`.** This matches the existing discarded-Note-Off line at `MpeTuner.scala:304`.
   Out-of-zone and wrong-level traffic is normal in a mixed rig and would flood at any higher level.

Three cells that today live inside `interpret`-style handlers become plain forwards, because that is all the handlers
do for them: a Master Channel Note On/Note Off (`MpeTuner.scala:203-208, 275-276`), and a Non-MPE Pitch Bend
(lines 331-338), which only rewrites the channel. Moving them into `ForwardOn` removes the Master Channel special case
from `processNoteOn` and `processNoteOff` and the mode branch from `processPitchBend`, leaving `interpret` to handle
only genuinely interpreted messages: note allocation, the three Expression Value dimensions at Member level, the
Non-MPE Polyphonic Key Pressure conversion, the MCM, and PBS. The output is byte-for-byte identical.

`route` is exhaustive over `MpeChannelRole`, so the compiler catches an unhandled combination. No new exceptions are
introduced: every verdict is total.

## 5. Gap-by-gap resolution

### I3 — Zone-level messages on an input Member Channel

The `Member(z)` column: discard, with three exemptions that §3.5 states explicitly — the three per-note control
dimensions (Pitch Bend, Channel Pressure, CC #74), Pitch Bend Sensitivity, and an MCM on Channel 1 or 16. Polyphonic
Key Pressure is already discarded there today and stays so.

### I2 and C4 — channels outside every Zone

The `Outside` column: discard everything but a valid MCM. Because the verdict is reached in `route`, *before* any
allocator lookup, the prompt's warning holds — the discard is not placed in `processMemberNoteOn`'s `case None`, which
is unreachable while any Zone is enabled. Three routes to the current pass-through close at once: the Note On forwarded
without a matching Note Off (`MpeTuner.scala:249-253`), the CCs and Program Change emitted on their original channel
via `resolveZoneMasterChannel`'s `case None`, and the PBS Data Entry re-emitted by `processPbs`'s `case None`
(lines 457-458).

### C5 — Master Channel CC #74 and Channel Pressure

The `Master(z)` column forwards both unmodified on the arrival channel, as §3.4 and §3.5's first table row require.
Removes the `TODO #250` markers at `MpeTuner.scala:372-373` and `583-587`.

### N4 — MIDI Mode messages 124–127

Discarded at every role in both input modes, from the new `ScMidiCc` constants. The paper distinguishes 124–127 from
120–123 while the current catch-all treats all eight alike, so this is an explicit controller-number branch rather than
a change of default.

### C6 and §2.2(f) — uninterpreted RPN/NRPN traffic, and invalid MCMs

Three parts:

- **Invalid MCM ignored in its entirety** (§4.2). Selector CCs are discarded universally, so the selector half is
  already covered; the Data Entry half is covered by the "MCM selected, any other channel" row. Today the selector is
  swallowed on every channel while the Data Entry escapes to the catch-all and reaches the output Master Channel as a
  bare value.
- **Discarded at note level.** The "any other selector" row under `Member(z)`.
- **Grouped into complete sequences.** Selectors are consumed, never relayed; each value CC re-emits the full selector
  ahead of itself on the output channel, via `rpnSequence`.

Two sub-decisions:

- **No closing RPN Null for uninterpreted parameters.** §4's Null rule governs sequences the Tuner *originates*.
  Appending one to a relayed sequence would invent protocol the sender never sent, and would have to be an NRPN Null
  for NRPN traffic.
- **A value CC with an incomplete selector is discarded.** `RpnSelector.None` is the plain case: no complete sequence
  can be formed, and relaying a bare Data Entry is precisely what §4's Null exists to prevent. The same applies to a
  *half-set* selector — one whose MSB or LSB is still Null, which the tracker produces after a lone CC #101 or
  CC #100 — because `ScMidiChannelStateTracker.writeDataEntry` itself refuses to record a value for one. If the
  tracker will not record it, the Tuner will not relay it.

### P7 — the forwarded Pitch Bend Sensitivity sequence

`applyPbsUpdate` (`MpeTuner.scala:504-527`) appends the closing Null (`CC #101 = 0x7F`, `CC #100 = 0x7F`). All three
emitters — `applyPbsUpdate`, `mcmMessages`, and `PitchBendSensitivityMessages.create` — are normalized to
MSB-then-LSB for both the selector and the Null, matching the MIDI 1.0 RPN procedure as it is conventionally written
(CC #101, CC #100, CC #6, CC #38).

### I1 — the active Tuning survives an MCM

`_tuning = Tuning.Standard` moves out of `resetState()` (`MpeTuner.scala:153`) into `reset()`, where full
re-initialization belongs. Nothing in §4.2 or §8 sanctions discarding the performer's Tuning on an in-band
reconfiguration.

### C3 — scope of the state reset on MCM reconfiguration

The affected channels are those whose Zone assignment changes across the update:

```scala
def assignmentOf(zones: MpeZones, ch: Int): Option[(MpeZoneType, Boolean)]  // (zone type, isMaster)
val affected = (0 until 16).filter(ch => assignmentOf(before, ch) != assignmentOf(after, ch))
```

Comparing *assignments* rather than differencing the set of MPE-controlled channels matters: a channel handed from
Lower Member to Upper Member by overlap resolution has both left and entered MPE control, and a set difference over
"channels under MPE control" would miss it. This is the paper's "entering or leaving MPE control", which §4.2 warns is
not the same as "belonging to the addressed Zone".

Then, in `processMcm`:

- `stopAllNotes(buffer)` becomes `stopNotesOn(buffer, affected)`. Member Channel notes on affected channels get one
  Note Off per forwarded Note On, as §5.1 requires; Master Channel notes on an affected Master Channel get one each.
  `TODO #254` stays in place and stays accurate — the rescoping does not give the tracker a reference count.
- `tracker.reset()` becomes `affected.foreach(tracker.reset)`.
- The allocators are rebuilt through a new companion factory
  `MpeChannelAllocator.retaining(newZone, from, retainedChannels, droppedInputChannels)`, which constructs an
  allocator for the new Zone structure and transplants each retained channel's `MpeChannelState` — notes, reference
  counts, Expression Values, pitch class, group — together with its `noteChannels` bindings.

**A note is dropped when either its output channel or its input channel is affected.** Scoping the drop to output
Member Channels alone is not enough, which is why `retaining` takes `droppedInputChannels` as well: a note that
arrived on input channel 7 and was allocated to output channel 2 survives a reconfiguration shrinking the Lower Zone
from 10 Member Channels to 4, because output channel 2 is retained — but input channel 7 is now `Outside`, so the
performer's Note Off is discarded and the note hangs forever. That is the same failure class **C4** describes, and it
would be reintroduced by the very fix meant to remove it.

Two edges are recorded rather than defended against:

- **An over-subscribed group is self-correcting.** Shrinking a Zone from 10 to 3 Member Channels takes the Expression
  Group from 3 to 2, so retained channels can leave a group holding more occupied channels than its new size allows.
  No invariant breaks: the allocation algorithm reads the counts only to decide whether a group has room
  (`MpeChannelAllocator.scala:192, 198`), so an over-subscribed group simply admits no new channel until notes are
  released.
- **Pitch Bend Sensitivity resets at Zone granularity, not per channel.** The paper lists PBS under the same
  per-channel "affected" scope as the rest, but `MpeZone` models PBS as one Master and one Member value per Zone, so
  per-channel scoping is not representable. The prompt confirms this half is already conformant — the addressed Zone
  is rebuilt as `MpeZone(zoneType, memberCount)` with the specification's defaults, while `MpeZones.update` preserves
  the PBS of a Zone shrunk by overlap resolution — so it is left untouched. The limitation is noted here because it is
  a genuine divergence between the paper's wording and what the model can express.

## 6. Paper amendment

Decision 1 of Section 2 puts the implementation ahead of the paper on uninterpreted RPN/NRPN traffic, so the paper is
amended in the same pull request as phase 3. Three surgical edits, concise and in the paper's existing register:

1. **§4, preamble (paper lines 399 and 401).** The clause scoping consume-and-re-emit to the two interpreted parameters
   is widened to admit that uninterpreted Registered and Non-Registered Parameter traffic is likewise re-emitted as
   complete sequences — selector followed by the value message — but without the closing RPN Null, which remains
   reserved for the parameters the Tuner originates. Line 401's "forwarded unmodified on the same Master Channel"
   takes the same qualification as the table row in edit 2.
2. **§3.5, table row "All other RPN and all NRPN messages" (paper line 314).** The Zone-level cell's "Forwarded
   unmodified on the same Master Channel (Section 4)" becomes re-emission of the complete sequence on that same Master
   Channel.
3. **§3.3, item 4, final sentence (paper line 239-240).** The Non-MPE redirection of uninterpreted parameter traffic
   gains the same qualification.

The note-level verdict of *Discarded* is unchanged, and no other section is touched.

## 7. Delivery

Four sub-issues of #250, one pull request each. The largest change lands once the router exists.

| Phase | Sub-issue | Scope | Gaps | Touches |
|---|---|---|---|---|
| 1 | #259 | PBS sequence closure and RPN selector order | P7 | `MpeTuner`, `sc-midi` `PitchBendSensitivity`, `MonophonicPitchBendTuner` tests |
| 2 | #260 | Channel role and the routing table | I2, C4, I3, C5, N4 | new `MpeMessageRouting.scala`, `MpeTuner`, `ScMidiCc` |
| 3 | #261 | RPN/NRPN sequencing and MCM validity | C6, §2.2(f) | `MpeMessageRouting`, `MpeTuner`, paper amendment |
| 4 | #262 | MCM reset scoping | C3, I1 | `MpeTuner`, `MpeChannelAllocator.retaining`, `ScMidiChannelStateTracker.reset(channel)` |

Phase 3 depends on phase 2's `route` and branches from it. Phases 1, 2 and 4 are independent and may be branched from
`main` and developed **in parallel**. Phase 4 must therefore derive its Zone assignments from the existing
`findChannelRole` rather than from `roleOf`, which phase 2 introduces; since phase 2 also deletes `findChannelRole`,
whichever of the two merges second repoints `assignmentOf` at `MpeMessageRouting.roleOf`. Git merges those two
changes cleanly and the result does not compile, so that merge must be followed by a compile.

**No phase PR resolves #250.** Each resolves its own sub-issue; #250 is closed by hand once all four have merged.
Because the merge order is not fixed, each phase removes only its own items from the `TODO #250` marker and from the
"Subject to change" bullet in [`docs/architecture/tuner/README.md`](../../docs/architecture/tuner/README.md), and
whichever merges last removes the marker and the bullet outright. Once all four have merged, all four `TODO #250`
markers are gone, `TODO #253` and `TODO #254` still stand and are still accurate, and that README's "Key types"
section has gained `MpeMessageRouting`.

## 8. Testing

Strict red/green/refactor throughout, per the repository workflow. No `ignore`d tests remain in the `tuner` or
`sc-midi` suites, so each phase begins by writing its own failing tests.

- **`MpeMessageRoutingTest`** (new) holds §3.5's table as a test: `TableDrivenPropertyChecks` over
  `(role, message, selector) → verdict`, one row per cell of Section 4's matrix. It is pure — no allocator, no
  `process()`, no MIDI plumbing — so a conformance question is answered by reading one row against one line of the
  paper. This is the payoff for extracting the router.
- **`MpeTunerTest`** carries the end-to-end confirmation that verdicts reach the wire. Per its ScalaDoc
  (`MpeTunerTest.scala:31-55`) the work belongs in the `process() - Zone-level Messages`, `MCM Processing` and
  `PBS Processing` categories, each split by input mode, added as new `// ---- … ----` subgroups rather than new
  `behavior of` blocks.
- **`MpeChannelAllocatorTest`** covers `retaining`: notes, reference counts, Expression Values, pitch class and group
  survive on retained channels; dropped channels take their notes with them; a note whose input channel left MPE
  control goes even when its output channel is retained; an over-subscribed group admits no new channel and breaks
  nothing.
- **`ScMidiChannelStateTrackerTest`** covers `reset(channel)` leaving the other fifteen channels untouched.
- **`PitchBendSensitivityTest`** and **`MonophonicPitchBendTunerTest`** absorb the MSB-first reordering.

Coverage is verified with the `scoverage-inspector` skill at the end of each phase, with the new files meeting the 80%
target for new code.

## 9. Out of scope

- The Expression Value model of cycle 1 — averaging, fan-out, retention on empty channels, reference counting, and the
  Note On / Note Off emission ordering. Touched only where a gap above genuinely depends on it.
- `TODO #253` (`MpeTuner.scala:261-263`) — an Expression Pitch Bend seeded from a raw Pitch Bend that predates a member
  PBS change. Neither absorbed nor deleted.
- `TODO #254` (`MpeTuner.scala:566-568`) — one Note Off per active Master Channel note rather than one per forwarded
  Note On. `stopAllNotes` is rescoped by **C3** but the TODO stays and stays accurate.

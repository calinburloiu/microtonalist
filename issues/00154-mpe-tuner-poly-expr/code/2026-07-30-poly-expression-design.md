# Polyphonic Expression for the MPE Tuner — Design

- **Date**: 2026-07-30
- **Issue**: [#154](https://github.com/calinburloiu/microtonalist/issues/154)
- **Base commit**: `bec59b8e18e6f2f4bf0651f48c9003aeb228d82c`
- **Source of truth**: [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md)
- **Prompt**: [`code-prompt.md`](code-prompt.md)

Throughout this document, `§` always refers to a section of the **MPE Tuner paper**. Sections of this design are
referred to by name, never by number, so the two numbering schemes cannot be confused.

---

## Scope and Delivery

The work described by the prompt splits into two bodies that touch overlapping code but are conceptually independent:

1. **The polyphonic-expression model** — the per-note Expression Value model of §1.3, §5.7 and §7, together with the
   Note Identity and reference counting of §5.1 that it depends on.
2. **MIDI message routing and filtering conformance** — the message tables of §3.4–§3.7, the Zone reconfiguration
   scope of §4.2, and the RPN/NRPN handling of §4.

This design covers **only the first**, delivered as cycle 1. The second becomes cycle 2, with its own issue, branch,
prompt and pull request.

### Cycle 1 plan phases

The implementation plan produced from this design has two phases.

**Phase 1 — code.** Everything specified below: the `MpeChannelAllocator` rewrite, the `MpeTuner` wiring, the paper
note on simultaneous High Expression Pitch Bend, the "both zones enabled in Non-MPE Input Mode" warning, and the tests.
Plan execution **stops at the end of Phase 1** so the author can review the code and merge the cycle-1 pull request.

**Phase 2 — hand-off, executed only after the cycle-1 pull request is merged into `main`.** Produce the cycle-2 prompt
as a filtered and updated derivative of `code-prompt.md`, with every line-number reference and every statement about
the current state of the implementation re-derived against merged `main`, and open the follow-up GitHub issue for
cycle 2. Deferring this until after the merge is the whole point: a prompt written before the merge would cite line
numbers and describe code that no longer exists.

### Gaps closed by cycle 1

P1, P2, P3, P4, P5 (Expression Value model, seeding, update propagation, Note Off emission, Poly Pressure averaging);
N1, N2, N5 (Note Identity, reference counting, duplicate Note On, identity-based active-note counting); B1 (stale
entries for dropped notes); C1, C2 (CC #74 on Member Channels and Channel Pressure reset placement in Non-MPE Input
Mode). The `MpeTuner` TODO about Non-MPE input with both Zones enabled is also resolved here.

### Gaps deferred to cycle 2

P7 (RPN Null closing a forwarded Pitch Bend Sensitivity sequence), C3 (scope of state reset on MCM reconfiguration),
C4 (behavior when all Zones are deactivated), C5 (Master Channel CC #74 and Channel Pressure forwarding), C6
(uninterpreted RPN/NRPN traffic), N4 (MIDI Mode messages 124–127), I1 (active Tuning reset on incoming MCM), I2
(out-of-zone notes and controls), I3 (Zone-level messages on an input Member Channel) — together with prompt sections
2.2(d), 2.2(e) and 2.2(f).

---

## Terminology Alignment

The code currently says "expressive pitch bend" where the paper says **Expression Pitch Bend**. Since the prompt
already mandates renaming `updateExpressivePitchBend` to `updateExpressionPitchBend`, the rename is applied
consistently across `MpeChannelAllocator` and `MpeTuner`: `isHighExpressionPitchBend`, `hasHighExpressionPitchBend`,
`ExpressionPitchBendThreshold`, and the ScalaDoc prose. The `MpeExpression` trait ScalaDoc gains the **Expression
Value** definition of §1.3 — the performer-controlled values of the three control dimensions, excluding the Tuning
Pitch Bend, which belongs to the tuning domain rather than the expression domain.

---

## New and Changed Types

All of these live in `MpeChannelAllocator.scala`.

```scala
/** A note with its origin: the pair (input channel, note number) of §5.1. */
case class NoteIdentity(inputChannel: Int, midiNote: MidiNote)

/**
 * Which of an output Member Channel's Expression Values changed as a result of an operation.
 * `None` means unchanged; `Some(v)` means changed to `v`.
 */
case class MpeExpressionUpdate(pitchBendCents: Option[Double] = None,
                               pressure: Option[Int] = None,
                               slide: Option[Int] = None)

object MpeExpressionUpdate {
  val Unchanged: MpeExpressionUpdate = MpeExpressionUpdate()
}

/** An `MpeExpressionUpdate` addressed to a specific output Member Channel. */
case class ChannelExpressionUpdate(channel: Int, update: MpeExpressionUpdate)

/** A dropped note and the number of Note Off messages owed for it (§6, §5.1). */
case class DroppedNote(noteIdentity: NoteIdentity, referenceCount: Int)

case class DroppedNotes(channel: Int, notes: Seq[DroppedNote], group: ChannelGroup)

case class AllocationResult(channel: Int,
                            update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                            droppedNotes: Option[DroppedNotes] = None,
                            isDuplicate: Boolean = false)

case class ReleaseResult(channel: Int,
                         update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                         pressureWasReset: Boolean = false)

case class ExpressionUpdateResult(channelUpdates: Seq[ChannelExpressionUpdate] = Nil,
                                  droppedNotes: Seq[DroppedNotes] = Nil)
```

`DroppedNote` carries the reference count because §6 requires the Tuner to emit "as many Note Offs as the note's
reference count" — one per Note On it forwarded.

A single `ExpressionUpdateResult` serves all three update methods rather than one type per dimension.
`droppedNotes` is always empty for `updatePressure` and `updateSlide`; only `updateExpressionPitchBend` can populate
it, through the divergence rule of §6.2.1.

---

## `MpeChannelAllocator`

The allocator becomes input-channel-aware and owns the whole Expression Value model. It remains **unaware of the input
mode** (prompt §2.1(i)): every mode-dependent decision reaches it as an argument.

### `ChannelState`

`_notes` is keyed by `NoteIdentity` instead of `MidiNote`, mapping to a per-note record:

```scala
private class NoteState(val expression: MutableMpeExpression,
                        var referenceCount: Int,
                        var onsetTime: Long)
```

The map becomes a plain `mutable.HashMap`. `lastAddedNote` and the `LinkedHashMap` that backed it are removed: the one
remaining need for recency among notes — choosing the survivor under the divergence rule — is served by `onsetTime`,
which is explicit and does not depend on map iteration order.

`ChannelState` additionally holds the channel's **aggregate**, one value per dimension
(`pitchBendCents: Double`, `pressure: Int`, `slide: Int`):

- While the channel has at least one active note, each aggregate is the average of its active notes' corresponding
  Expression Values (§5.7, §7.1). An identity contributes **one** term regardless of its reference count (§5.6
  criterion (b), §7.6.1).
- The two integer dimensions are averaged in `Double` and rounded **half up** to the emitted `Int`.
- When the channel empties, the aggregate is **left untouched** — the retention rule of §7.1. This is what gives every
  dimension a defined value at all times and what makes the emission optimization of §7.5 possible.

The allocator also holds a `NoteIdentity → channel` map, which is `MpeTuner`'s `channelNoteMap` moved here (prompt
§2.1(a)). Unlike the old map it is maintained on **every** path that removes a note, dropping included, which is what
closes B1.

### `allocate`

```scala
def allocate(noteIdentity: NoteIdentity,
             expression: Option[MpeExpression] = None,
             preferredChannel: Option[Int] = None): AllocationResult
```

`expression` is optional, and it applies only to a fresh allocation:

| Case | `Some(e)` | `None` |
|---|---|---|
| Fresh allocation (count 0 → 1) | seed the note's Expression Values from `e` | seed from the defaults of `MpeExpression` |
| Duplicate (count ≥ 1) | ignored | ignored |

`MpeTuner` passes `Some(...)` in MPE Input Mode and `None` in Non-MPE Input Mode. Ignoring it on the duplicate path is
what §7.6.1 requires of both modes: in MPE Input Mode the override it describes "is a **no-op** — the note already holds
those values, having received them as they arrived on the input channel"; in Non-MPE Input Mode there is nothing to
override from, so "the note keeps whatever pressure it has accumulated since the Note On that allocated it."

`preferredChannel` is **kept as a separate parameter** rather than derived from `noteIdentity.inputChannel`. Tie-break
criterion (e) of §5.6 is input-mode-dependent — MPE Input Mode prefers the note's own input channel when that channel is
itself among the candidates, Non-MPE Input Mode prefers the lowest channel number — and only `MpeTuner` knows the mode.
Deriving the preference inside the allocator would make a Non-MPE note arriving on input channel 3 prefer output Member
Channel 3, contrary to §5.6(e). The parameter looks redundant next to the identity but is what keeps the allocator
input-mode-unaware while leaving §5.6(e) exact.

Behavior:

- **Duplicate.** If the identity already holds an active count, the count is incremented and nothing else happens: the
  result is `MpeExpressionUpdate.Unchanged` with no dropped notes and `isDuplicate = true`. The allocation algorithm of
  §5.6 does not run and no note is dropped (§7.6.1: allocation is bypassed; the High Expression Pitch Bend rules of
  §6.2.2 and §6.2.3 are predicated on an assignment that does not occur here). No Expression Value is written either:
  §7.6.1 states the MPE-Input-Mode override is a no-op, because §7.2's update propagation has kept the note current, and
  the identity remains a single term in the averages whatever its reference count — so the aggregate cannot move and
  needs no recomputation. Since no Expression Pitch Bend moves, the divergence rule of §6.2.1 cannot engage here either.
- **Fresh allocation.** Steps 1–4 of §5.6 run as they do today, with two corrections: candidate ranking counts
  **distinct Note Identities** rather than note numbers (N5, §5.6 criterion (b)), and both the channel's notes and the
  identity → channel map are updated. The result reports the channel's Expression Value changes and any notes dropped
  by §6.2.2 / §6.2.3 or by the channel freeing of §6.1.

### `release`

```scala
def release(noteIdentity: NoteIdentity,
            resetPressureOnEmpty: Boolean = false): Option[ReleaseResult]
```

The output Member Channel is **not** a parameter: the allocator owns the identity → channel binding and resolves it
itself. `None` is returned when the identity holds no active count, which is the signal to discard the Note Off under
§5.1 rule 4; otherwise `ReleaseResult.channel` carries the resolved channel. This is a deliberate departure from prompt
§2.1(g), which kept the channel as a parameter: passing it would make the caller a second source of truth for a binding
the allocator already holds, and it would force the caller into a preparatory `channelOf` lookup on every Note Off. The
duplicate case needs no channel either — every Note On for an identity beyond the first is forwarded on the channel
already bound to it (§5.1), so the binding never varies over an identity's lifetime.

The reference count is decremented. Deallocation — removal from the channel's notes, from the identity → channel map,
and the accompanying recomputation — happens **only on the transition to 0** (§5.1). A decrement that leaves the count
at 1 or above changes no average, because the identity remains a single term in it.

`resetPressureOnEmpty` means "if this release empties the channel, zero its Channel Pressure instead of retaining it".
Phrased as a conditional rather than as an unconditional reset, it keeps the allocator input-mode-unaware while
implementing §7.4: the reset applies only when the released note is the last active note on its channel, and when other
notes remain the recomputed value is simply their average. `MpeTuner` passes `true` in Non-MPE Input Mode and `false`
in MPE Input Mode, where the dimension passes through from the sender and the Tuner emits no reset of its own.

`pressureWasReset` on the result tells `MpeTuner` that this particular Channel Pressure value must be emitted
**before** the Note Off — the sole exception to the Note Off ordering of §7.5.

`channelOf` remains a public accessor for inspection and tests, but the Note Off path no longer calls it: `release`
answers both questions — whether the identity is live and where it lives — in one call.

### The three update methods

```scala
def updateExpressionPitchBend(inputChannel: Int, pitchBendCents: Double): ExpressionUpdateResult
def updatePressure(inputChannel: Int, pressure: Int): ExpressionUpdateResult
def updatePressure(noteIdentity: NoteIdentity, pressure: Int): ExpressionUpdateResult
def updateSlide(inputChannel: Int, slide: Int): ExpressionUpdateResult
```

Each input-channel form performs the **fan-out** of §7.2: it updates the contribution of *every* note active on that
input channel, wherever the pitch-class invariant has placed it, then recomputes each affected output channel's
aggregate and reports only the channels whose value actually changed. This replaces the "most recently added note is
the one being bent" assumption that P3 identifies as wrong under fan-in.

`updatePressure` has a second overload keyed by `NoteIdentity`, for the Polyphonic Key Pressure of §7.3, which
addresses a single note rather than a channel. The channel form is implemented as a loop over the identity form. An
identity that is not active yields an empty result, which is how "a Polyphonic Key Pressure addressed to a note number
for which no Note On was issued on that input channel is ignored" (§7.3) falls out without a separate check.

All three share one private helper parameterised by dimension: locate the affected identities, write the new
contribution, recompute, diff.

### The divergence rule (§6.2.1)

`updateExpressionPitchBend` applies §6.2.1 per affected output channel, after writing the new contributions.

Let `H` be the set of identities on the channel whose Expression Pitch Bend now exceeds the threshold `t`.

Invariant 2 of §6.3 holds of the state the Tuner was in **immediately before this Pitch Bend message was applied**: an
identity with a High Expression Pitch Bend is always the sole active identity on its channel. Consequently a channel
that then held more than one identity held none with a high bend, and every element of `H` on such a channel acquired
its high bend from this very message — necessarily from the input channel the message arrived on, since no other
identity's contribution was written. Therefore:

- If the channel holds more than one identity and `H` is non-empty, the identity in `H` with the **greatest
  `onsetTime`** survives and **every other identity on the channel is dropped** — those in `H` and those outside it
  alike.
- Otherwise nothing is dropped.

The single-element `H` case is §6.2.1 as written: one note develops a high bend and its co-residents are dropped
(worked example §9.5). The multi-element case arises when the notes sharing the channel also share an input channel,
so a single Pitch Bend message gives all of them the high bend at once. The paper does not cover it; the rule adopted
here retains the most recently sounded of them, which preserves the performer's gesture on one voice. See *Paper
Amendment* below.

In both cases the surviving identity is left alone on its channel, so invariant 2 of §6.3 — which the incoming message
had just broken — holds again of the state that follows the drop.

### Public accessors

`MpeTuner` needs, and the tests use:

- `channelOf(noteIdentity): Option[Int]` — the output Member Channel bound to an active identity.
- `channelExpression(channel): MpeExpression` — the channel's aggregate, retained when the channel is empty.
- `activeNotes(channel): Set[NoteIdentity]` — replaces the `Set[MidiNote]` accessor.
- `activeAllocations: Seq[(NoteIdentity, Int)]` — every active identity with its channel, for `stopAllNotes`.

`expressionFor(channel, midiNote)` becomes `expressionFor(noteIdentity)`, and the per-note averaging that
`MpeTuner.computeOutputPitchBend` performs today disappears in favour of `channelExpression`.

---

## `MpeTuner`

### Removed

`channelNoteMap`, `trackNote`, `untrackNote`, `outputChannelsFor` and `forwardToMemberChannel` are deleted — the first
three because the allocator now owns the mapping, the last two because expressive controls no longer fan out by raw
channel rewriting but through the allocator's update methods. `computeOutputPitchBend` keeps only the tuning-plus-
expression sum and the Pitch Bend Sensitivity clamping, reading the expression half from `channelExpression`.

### Note On on a Member Channel

```
identity   = NoteIdentity(inputChannel, midiNote)
expression = if (MPE) Some(ImmutableMpeExpression(trackerPitchBendCents, trackerPressure, trackerSlide)) else None
preferred  = if (MPE && zone.memberChannels.contains(inputChannel)) Some(inputChannel) else None
result     = alloc.allocate(identity, expression, preferred)

emit dropped Note Offs                          // §6: before every message emitted for the new note
if (!result.isDuplicate) emit Pitch Bend
result.update.slide    -> emit CC #74           // only when changed
result.update.pressure -> emit Channel Pressure // only when changed
emit Note On
```

Seeding the Expression Values from `ScMidiChannelStateTracker` in MPE Input Mode is what closes P2 and what makes
§6.2.2 — a new note arriving *with* a High Expression Pitch Bend — reachable at all.

The message order is §7.5's: Pitch Bend, CC #74, Channel Pressure, Note On. Dropped notes' Note Offs precede all of
them, because emitting the setup messages first "would retune the notes being dropped on their way out, to values
computed for the very note they are making room for" (§6). Each dropped note gets `referenceCount` Note Off messages
at the neutral release velocity 64 that §6 prescribes for a note ended by the Tuner's own decision.

**Why Pitch Bend is emitted unconditionally on a fresh allocation.** The allocator reports changes to *Expression
Values*; what goes on the wire for Pitch Bend is Tuning Pitch Bend + Expression Pitch Bend (§1.3), and the tuning half
is invisible to the allocator. A freshly allocated channel may have been unoccupied, in which case it retains the bend
of a note of a **different pitch class** and has also missed every `tune()` that ran while it was empty —
`updateTuningOnZone` emits only on channels that have a pitch class assigned. So an unchanged Expression Pitch Bend
does not imply an unchanged output Pitch Bend, and omitting the message would leave the new note on the previous pitch
class's offset: the swooping artifact of §2.5.

A duplicate Note On, by contrast, moves nothing: the allocator reports `Unchanged` for all three dimensions, so the
Note On is emitted alone, matching §9.6 Part 1 step 3 — "no average moves and the Note On is emitted alone".

CC #74 and Channel Pressure have no tuning component: the emitted value *is* the Expression Value, so an unchanged
report genuinely means the channel already holds it, and §7.1/§7.5 permit the omission. In Non-MPE Input Mode CC #74
is never an Expression Value and its aggregate never moves, so the message never reaches a Member Channel — which is
how C1 is closed, without a mode-specific branch.

### Note Off on a Member Channel

```
identity = NoteIdentity(inputChannel, midiNote)
alloc    = getAllocatorForInput(inputChannel)    // same resolution as the Note On path

alloc.release(identity, resetPressureOnEmpty = (inputMode == NonMpe)) match {
  case None => discard                                  // §5.1 rule 4
  case Some(result) =>
    if (result.pressureWasReset) emit Channel Pressure          // §7.5 exception, before the Note Off
    emit Note Off on result.channel
    result.update.pitchBendCents -> emit Pitch Bend             // §7.5: after the Note Off,
    result.update.slide          -> emit CC #74                 //   control dimensions keeping
    if (!result.pressureWasReset) result.update.pressure -> emit Channel Pressure   // their relative order
}
```

Emitting the recomputed values at all is what closes P4; placing the Non-MPE Channel Pressure reset before the Note Off
is what closes C2. The discard branch closes B1's first consequence: after the Tuner drops a note it clears the
identity's count and channel binding, so the performer's later Note Off finds nothing and is discarded rather than
producing a duplicate downstream Note Off (§5.1 rule 4, §6). That branch is now a specified path exercised by tests, so
its `$COVERAGE-OFF$` markers are removed — they are dead under Scala 3 in any case.

No need to log anymore when a Note Off is discarded.

### Master Channel notes in MPE Input Mode

These are forwarded as-is and were previously recorded in `channelNoteMap` purely so `stopAllNotes` could find them.
They now need no map of their own: `ScMidiChannelStateTracker` already tracks active notes per input channel, and
because Master Channel notes are forwarded on the same channel they arrived on, `tracker.activeNotes(masterChannel)`
is exactly the set to emit Note Offs for. `stopAllNotes` therefore iterates the allocators' `activeAllocations` for
Member Channel notes and the tracker's active notes on each enabled Zone's Master Channel for the rest.

### Expression paths

| Input mode | Message | Handling |
|---|---|---|
| MPE | Pitch Bend on an input Member Channel | `alloc.updateExpressionPitchBend(inputChannel, cents)`; emit dropped Note Offs, then Pitch Bend per changed channel |
| MPE | Channel Pressure on an input Member Channel | `alloc.updatePressure(inputChannel, value)`; emit per changed channel |
| MPE | CC #74 on an input Member Channel | `alloc.updateSlide(inputChannel, value)`; emit per changed channel |
| Non-MPE | Polyphonic Key Pressure | `alloc.updatePressure(NoteIdentity(inputChannel, midiNote), value)`; emit per changed channel |
| Non-MPE | Pitch Bend, Channel Pressure, CC #74 | redirected to the output Master Channel, unchanged from today (§3.3 item 2) |

The MPE rows close P1 and P3; the Non-MPE row closes P5. Pitch Bend cents conversion continues to use the Zone's Member
Channel Pitch Bend Sensitivity. Master Channel Pitch Bend and Polyphonic Key Pressure keep their current handling —
their conformance gaps (C5) belong to cycle 2.

### Configuration warning

The outstanding TODO is resolved by logging a warning when the Tuner is configured in Non-MPE Input Mode with both
Zones enabled: §4.2 routes non-MPE input to a single Zone — the Lower Zone when it is enabled, otherwise the Upper
Zone — so with both enabled the Upper Zone is unreachable and its Member Channels are wasted. Logged once at construction and again on `reset()`, which is where the initial
configuration is re-applied.

---

## Paper Amendment

§6.2.1 gains a short note, in the paper's register, recording the case it does not currently cover: when several notes
sharing an output Member Channel acquire a High Expression Pitch Bend from the same input channel — the Pitch Bend
being a channel message that belongs to all of them — the most recently sounded is retained and the others are dropped.
Retaining one preserves the performer's gesture on a voice rather than silencing the channel, and leaving exactly one
note restores invariant 2 of §6.3.

No other paper change is made: the paper is the source of truth, and every other behavior specified here is already in
it.

---

## Testing

Test-driven: each behavior gets a failing test before its implementation.

### Ignored tests to activate

Twelve tests are already present as red and ignored. Eleven are activated unchanged by replacing `ignore` with `it`:

| Location | Behavior | Gap |
|---|---|---|
| `MpeTunerTest.scala:520` | `tune()` recomputes Pitch Bend as new tuning offset + current Expression Pitch Bend | P1 |
| `:1052` | Member Channel Pitch Bend seeded from the input channel at Note On | P2 |
| `:1421`, `:1445`, `:1470` | Averaging of Expression Pitch Bend, Channel Pressure and CC #74 across a channel's notes | P1 |
| `:1497`, `:1513`, `:1530` | Fan-out of each dimension from one input channel to several output channels | P1, P3 |
| `:1687`, `:1704`, `:1721`, `:1738` | High Expression Pitch Bend truth table when freeing a channel | P2 |
| `:1758` | Divergence on a shared channel drops the co-resident note | §6.2.1 |
| `:2315` | Intonation of a note with an Expression Pitch Bend preserved across a PBS change | P1 |

The twelfth, `:1802` ("drop all notes on a shared channel with a common input channel when a high expression pitch bend
is received on it"), is **rewritten**: under the rule adopted above it expects the most recently sounded note (E5) to
survive and only E4 to receive a Note Off.

### New tests

- **Reference counting and duplicate Note On, same input channel** — worked example §9.6 Part 1: two Note Ons and two
  Note Offs in, two of each out; allocation runs once; the duplicate emits the Note On alone; the channel is
  deallocated only on the final Note Off. (N1, N2 case 1)
- **Duplicate Note On, different input channels** — worked example §9.6 Part 2: two distinct identities on one output
  channel, each a separate term in the averages, both reference counts 1. (N2 case 2)
- **Note Off recomputation and ordering** — §7.5 and worked example §9.3 steps 5–6: Note Off first, then the values
  recomputed over the remaining notes; nothing emitted when the released note was the channel's last, since retention
  leaves all three unchanged. (P4)
- **Non-MPE Channel Pressure reset** — §7.4: emitted before the Note Off, only when the released note is the last on
  its channel; when others remain, their reduced average is emitted after the Note Off instead. (C2)
- **No CC #74 on a Member Channel in Non-MPE Input Mode** — across Note On, Note Off and Poly Pressure paths. (C1)
- **Dropped note's later Note Off discarded** — no duplicate downstream Note Off, and no stale binding steering a
  later expressive update at the dropped note's former channel. (B1, §5.1 rule 4)
- **Identity-based active-note counting** — two identities sharing a note number on one channel count as two for
  criterion (b). (N5)
- **Simultaneous high bend from a common input channel** — the rewritten `:1802`, plus the survivor being the most
  recently sounded. (§6.2.1 amendment)
- **End-to-end worked examples** — §9.3 (three-dimension averaging through six steps) and §9.5 (divergence with
  ordering), as single tests tracing the paper's own traces.

### `MpeChannelAllocatorTest`

Roughly fifty `allocate` / `release` call sites migrate to Note Identity and the new result types. The migration is
mechanical and is kept terse behind small helpers in the test class, so the existing allocation and tie-breaking tests
stay readable and continue to assert the same behavior. New allocator-level tests cover reference counting, the
identity-keyed `ChannelState`, aggregation and retention, the reset-pressure-on-empty flag, and each update method's
fan-out and diff reporting.

### Placement

New `MpeTunerTest` cases go into the `behavior of` block matching their category and input mode, in the most fitting
`// ---- … ----` subgroup, per that file's own organization ScalaDoc. Averaging and fan-out cases belong to
`process() - Expression`; dropping cases to `process() - Note Dropping`; reference counting and duplicate Note On to
`process() - Basic`.

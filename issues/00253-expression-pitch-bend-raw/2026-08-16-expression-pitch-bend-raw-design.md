# Expression Pitch Bend Stored Raw (Design)

- **Date**: 2026-08-16
- **Issue**: [#253](https://github.com/calinburloiu/microtonalist/issues/253) — "MPE Tuner: store Expression Pitch Bend
  raw instead of in cents, so it cannot go stale after a member PBS change"
- **Base commit**: `979a1d49750abb6065ef2f4d0261fc6126393ca0`
- **Working tree caveat at the time of writing**: `docs/architecture/tuner/mpe-tuner-paper.md` carries an uncommitted
  edit that splits Section 4.2's third paragraph into three (the "either output or input channel is affected" rule and
  the Note-Off rationale). Section 9 below amends that section and assumes the edit is present.

This design records decisions that were settled in the issue, three revisions agreed during brainstorming, and one
defect discovered while reading the code that the issue does not mention and that is now in scope.

---

## 1. Problem and the decision it rests on

Two code paths disagree about a note's Expression Pitch Bend after a member Pitch Bend Sensitivity (PBS) change:
`MpeTuner.applyPbsUpdate` re-emits every occupied Member Channel's Pitch Bend from the **stored cents**, while
`MpeTuner.inputExpressionOf` re-derives cents from the tracker's **raw** 14-bit value under the **new** PBS. Nothing
rewrites either side, so the two disagree until the sender transmits a fresh Pitch Bend.

**The raw Pitch Bend value is canonical.** Expression Pitch Bend was stored in cents so that a note's expressive
deviation would survive a PBS change unchanged. That requirement is dropped. A PBS change now *reinterprets* the held
bend rather than conserving it, which is what an ordinary MIDI receiver does and what a performer widening the bend
range mid-phrase should expect; a sender wanting a specific deviation afterwards sends a fresh Pitch Bend.

Dropping the requirement does more than fix the disagreement — it removes the possibility of one. The two paths stop
being two conversions that must be kept in step and become the same expression:

- `processPitchBend` → `alloc.updateExpressionPitchBend(inputChannel, msg.value)`
- `inputExpressionOf` → `tracker.pitchBend(inputChannel)`

Neither mentions PBS, so neither can go stale. Two further benefits follow from the data model becoming integral:
`MpeChannelAllocator.diff` compares all three dimensions exactly, and a channel's aggregate becomes exactly what reaches
the wire, modulo the tuning term.

The scenario the issue's original write-up called a defect — member PBS moving from ±2 to ±48 turning a note that
"should start at 25 cents" into one at roughly 600 cents — is under this decision the *specified* behaviour. A raw value
of 1024 means 25 cents at ±2 and 600 cents at ±48, and every note already sounding on that channel is reinterpreted the
same way. The paths agree, which is the whole of the fix.

---

## 2. Data model — `MpeExpression.scala`

Expression Pitch Bend becomes a signed 14-bit MIDI Pitch Bend value (−8192…8191, 0 = no bend), held exactly as received
on the input Member Channel:

| Now | After |
|---|---|
| `MpeExpression.pitchBendCents: Double` | `pitchBend: Int` |
| `MpeExpression.DefaultPitchBendCents: Double = 0.0` | `DefaultPitchBend: Int = PitchBendScMidiMessage.NoPitchBendValue` |
| `MutableMpeExpression(pitchBendCents, …)` | `MutableMpeExpression(pitchBend, …)` |
| `ImmutableMpeExpression(pitchBendCents, …)` | `ImmutableMpeExpression(pitchBend, …)` |
| `MpeExpressionUpdate.pitchBendCents: Option[Double]` | `pitchBend: Option[Int]` |

The ScalaDoc on `pitchBend` is where the *why* lives: the value is held as received and is **reinterpreted, not
rescaled**, when Pitch Bend Sensitivity changes. That paragraph is what stops the dropped requirement being "fixed" back
to conservation later.

`MpeChannelState` follows:

- `pitchBendCentsOf` / `setPitchBendCents` → `pitchBendOf` / `setPitchBend`;
- `averageExpression` rounds the pitch bend half up, as it already does for `pressure` and `slide`, and stays a
  recomputation over the current notes rather than an accumulation, so repeated add/remove cannot drift;
- `expressionFor` copies an `Int` like the other two dimensions.

Non-MPE Input Mode is unaffected: `MpeMessageRouting.routeControlDimension` forwards a Pitch Bend received there to the
Master Channel and never asks the Tuner to interpret it, so a note allocated in that mode keeps
`DefaultPitchBend` for its whole life.

---

## 3. `MpeChannelAllocator` — threshold in, cents out

The allocator and its companion reference neither `PitchBendSensitivity` nor cents. The High Expression Pitch Bend
threshold arrives from `MpeTuner` in raw units.

```scala
private[tuner] class MpeChannelAllocator(private val zone: MpeZoneStructure,
                                         expressionPitchBendThreshold: Int,
                                         retainedStates: Map[Int, MpeChannelState] = Map.empty)
```

The threshold parameter has **no default** — a default would have to be derived from a PBS the allocator must not know.
`MpeZoneStructure`'s ScalaDoc records that it was split from `MpeZone` precisely so the allocator could depend on the
structure without holding a reference to Pitch Bend Sensitivity configuration that changes over an `MpeTuner`'s
lifetime; that separation is what this parameter preserves.

**Accessors.**

```scala
/** The raw Expression Pitch Bend magnitude above which a note counts as having a High Expression Pitch Bend. */
def expressionPitchBendThreshold: Int = _expressionPitchBendThreshold

/**
 * Assigns a new threshold and re-applies the divergence rule to every occupied channel, since the reclassification
 * can leave a high-bend note sharing its channel.
 */
def setExpressionPitchBendThreshold(threshold: Int): MpeExpressionUpdateResult
```

The setter is deliberately **not** the conventional `expressionPitchBendThreshold_=`: Scala requires an assignment
setter to return `Unit`, and splitting assignment from re-evaluation would let a caller perform one without the other.
Making the pair atomic removes that failure mode entirely. `reapplyDivergenceRule()` therefore stays **private** and is
called only from the setter; it reuses the existing `updateExpressionValues` with a no-op write and
`applyDivergenceRule` as its `afterWrite`, so no second traversal or second rule is written:

```scala
private def reapplyDivergenceRule(): MpeExpressionUpdateResult =
  updateExpressionValues(noteChannels.keys.toSeq, (_, _) => (), applyDivergenceRule)
```

That reuse also gives the pass the existing ordering (channels ordered by the earliest onset among their notes) and the
existing "report only channels whose aggregate actually changed" behaviour for free.

**Other changes in the file.**

- `isHighExpressionPitchBend` and `hasHighExpressionPitchBend` move from the companion to the instance, since they now
  read the var. The companion keeps `ChannelGroup` and `retaining`; the `ExpressionPitchBendThreshold` cents constant
  leaves the file.
- `updateExpressionPitchBend(inputChannel: Int, pitchBend: Int)`.
- `diff` compares all three dimensions with `!=`. The `DoubleMath.fuzzyEquals` / `DefaultCentsTolerance` comparison and
  the paragraph of ScalaDoc justifying it go away, together with the Guava import. `DefaultCentsTolerance` itself stays
  in `tuner/package.scala`, where `Tuning` uses it.
- `MpeChannelAllocator.retaining(zone, from, affectedChannels)` **keeps its current signature**. The threshold is part
  of the state it transplants: the rebuilt allocator adopts `from.expressionPitchBendThreshold`, and `MpeTuner` then
  applies the Zone's new threshold through the atomic setter, which is also what re-evaluates the transplanted notes.
  One place computes the new threshold; one call applies it.
- The class-level ScalaDoc's Expression Value description states the units.

---

## 4. `MpeTuner` — sole owner of cents and PBS

`object MpeTuner` gains the constant, the sentinel and the conversion:

```scala
/** The paper's High Expression Pitch Bend threshold `t`: an absolute pitch deviation of half a semitone. */
private val HighExpressionPitchBendThresholdCents: Double = 50.0

/**
 * The threshold used when the threshold in cents is not below the Member Channel Pitch Bend Sensitivity range — a
 * sender configuring, say, ±0 semitones 20 cents, where no bend the Pitch Bend range can express deviates by more
 * than `t`. Its value is one greater than the largest magnitude a signed 14-bit Pitch Bend can take, so the strict
 * `>` of the classification is false for every value, `MinValue` included: nothing is a High Expression Pitch Bend
 * at such a range, and `convertCentsToValue` — whose `require` rejects a value beyond the sensitivity — is never
 * called with one.
 */
private val UnreachableExpressionPitchBendThreshold: Int = -PitchBendScMidiMessage.MinValue

private def expressionPitchBendThresholdOf(pbs: PitchBendSensitivity): Int =
  if (HighExpressionPitchBendThresholdCents >= pbs.totalCents) UnreachableExpressionPitchBendThreshold
  else PitchBendScMidiMessage.convertCentsToValue(HighExpressionPitchBendThresholdCents, pbs)
```

A single threshold serves both signs even though `convertCentsToValue` scales negatives by 8192 and positives by 8191.
The discrepancy is below one raw unit (85.32 against 85.33 at ±48) and therefore invisible after rounding at every
sensitivity of practical interest.

The rest of the class:

- `createAllocator(zone)` computes the threshold from `zone.memberPitchBendSensitivity` and passes it to the
  constructor.
- `inputExpressionOf` reads `tracker.pitchBend(inputChannel)` with no conversion and loses its now-unused `zone`
  parameter. The `// TODO #253` comment above it goes.
- `processPitchBend` calls `alloc.updateExpressionPitchBend(msg.channel, msg.value)`; it no longer needs
  `currentZone(alloc)`.
- `computeOutputPitchBend` sums in raw units. The tuning offset still needs its cents-domain clamp before conversion,
  `convertCentsToValue` having a `require` that throws when the value exceeds the PBS range; the sum is then clamped to
  the same interval expressed in raw units:

  ```scala
  val pbs = zone.memberPitchBendSensitivity
  val tuningValue = PitchBendScMidiMessage.convertCentsToValue(
    clampValue(tuningOffsetCents, -pbs.totalCents, pbs.totalCents), pbs)
  clampValue(tuningValue + alloc.channelExpression(channel).pitchBend,
    PitchBendScMidiMessage.MinValue, PitchBendScMidiMessage.MaxValue)
  ```

---

## 5. Re-evaluation when the threshold moves

Because classification now depends on PBS, a member PBS change can turn sounding notes into High Expression Pitch Bend
notes with no note or Pitch Bend message arriving. The paper's "Summary of Note-Dropping Invariants" states that a
high-bend note "is always the sole active note on its channel", maintained **at all times**, so the Tuner must act
rather than wait for the next event.

There are two triggers, and both go through `setExpressionPitchBendThreshold`:

1. **An explicit member PBS change** — `applyPbsUpdate` with `isMaster == false`. Master PBS does not affect Member
   Channel interpretation, and in Non-MPE Input Mode all PBS input is treated as master PBS, so neither reaches this
   path.
2. **An MCM** — it resets the addressed Zone's PBS to the specification's defaults (Section 6), which moves the
   threshold on every channel the reconfiguration retained.

The pass only ever drops. A change in the other direction (±48 → ±2) reclassifies high-bend notes as ordinary ones;
nothing is restored, because nothing was retained.

### 5.1 Several notes crossing at once

A PBS change reclassifies every sounding note in the Zone simultaneously, so a shared channel can end up with more than
one high-bend note. **The note with the latest onset survives and the rest of the channel is dropped** — no new code:
`applyDivergenceRule` already computes `highBendIdentities.maxBy(state.onsetTimeOf)`, which is the main reason to reuse
it rather than write a second rule.

The *reason* differs from the existing case. Today several notes go high together only when they share an input channel
and therefore carry identical bends, which is why the paper says the rule "has no single bending note to protect".
Under a PBS change the co-resident notes may come from different input channels with genuinely different bends, several
of which cross the widened threshold at once. The resolution is the same; the justification is that the latest onset is
the note the performer is most likely still shaping, and that leaving exactly one note restores the invariant.

### 5.2 Message ordering

Both triggers emit in the same shape, which is the paper's Section 7.5 relative order for the control dimensions after
a Note Off:

> Note Offs → Pitch Bends → CC #74 → Channel Pressure

A single helper on `MpeTuner` renders it from an `MpeExpressionUpdateResult` plus the Zone's allocator: it emits the
dropped notes' Note Offs, then `updateTuningOnZone`, then only the slide and pressure of each changed channel. The
Pitch Bend dimension of the result is deliberately not emitted by the helper — `updateTuningOnZone` re-emits one Pitch
Bend on every occupied Member Channel of the Zone immediately after, which both subsumes it and is required in its own
right, since a PBS change re-encodes the tuning term on *every* occupied channel, not only the ones a drop touched.
Emitting both would duplicate the message on exactly the channels that changed, and `MpeTunerTest` asserts exact Pitch
Bend counts after a PBS change.

`applyPbsUpdate` therefore becomes: update `_zones` → emit the Pitch Bend Sensitivity RPN sequence on the destination
channel → `alloc.setExpressionPitchBendThreshold(…)` → emit through the helper.

The pass's slide and pressure emission stays `diff`-driven and therefore conditional: the PBS change itself moves no
CC #74 or Channel Pressure value; the *drops* it triggers do, because removing a note removes its term from all three of
its channel's averages, and notes sharing a channel may come from different input channels with different values. When
the pass drops nothing the result is empty and nothing is emitted; when a dropped note's slide and pressure matched its
co-residents', the averages do not move and those dimensions stay silent. This mirrors the existing divergence path,
where `updateExpressionPitchBend` computes `diff` after `applyDivergenceRule` has run.

Drops from this pass are logged under a new `DropReason` case — a member Pitch Bend Sensitivity change reclassified the
note — which covers both triggers, an MCM's reset being a member PBS change like any other.

---

## 6. Pitch Bend Sensitivity on an MCM (in scope)

Two related defects on the MCM path are folded into this change. Both are reachable only in MPE Input Mode: in Non-MPE
Input Mode an MCM is treated as affecting every channel, so nothing is retained.

### 6.1 The Zone shrunk by overlap resolution keeps a sensitivity the receiver has dropped

`MpeZones.update` preserves the Pitch Bend Sensitivity of a Zone that overlap resolution shrank — its ScalaDoc says so
explicitly — while `processMcm` re-emits that Zone's MCM whenever overlap resolution changed it. An MCM resets the
addressed Zone's Pitch Bend Sensitivity at the receiver (MPE spec §2.4), so from that moment the Tuner encodes its
output against a sensitivity the receiver no longer holds.

The fix mirrors, in the Tuner's model, what the message it emits does at the receiver. `MpeZone` gains a small helper:

```scala
/** The Zone as a receiver holds it after an MCM, which resets its Pitch Bend Sensitivity to the defaults. */
def withDefaultPitchBendSensitivities: MpeZone
```

and `processMcm` applies it to the other Zone exactly where it decides to re-emit that Zone's MCM. It stays out of
`MpeZones.update`: that method is also how `applyPbsUpdate` stores a sensitivity, and `MpeZones.apply` performs the
same shrink at construction with no MCM on the wire, where preserving is correct. The reset belongs to the emission
decision, and only `MpeTuner` makes it. The zone the MCM addressed needs nothing: `processMcm` already builds it as
`MpeZone(zoneType, memberCount)`, which carries the defaults.

Re-applying the reset Zone through `_zones.update` cannot disturb the Zone just configured: `zonesAfter` is already
non-overlapping, so `wouldOverlap` is false and no shrink is re-triggered.

`reset()` needs no equivalent: `emitConfiguration` emits both Zones' MCM *and* Pitch Bend Sensitivity sequences, so the
receiver ends up holding exactly what the model holds.

### 6.2 Retained channels keep a Pitch Bend the receiver now reads differently

`processMcm` never re-emits Pitch Bend on the channels a reconfiguration retains, although the receiver has just reset
its Pitch Bend Sensitivity for that Zone. A note retained across an MCM that moved member PBS from ±2 to ±48 therefore
keeps sounding at 24 times its intended deviation.

The `updateTuningOnZone` call introduced in Section 5.2 fixes this as a side-effect of running the same emission helper
on the MCM path, so no further mechanism is needed.

### 6.3 The resulting `processMcm`

1. `stopNotesOn(affected)` — unchanged, and still emitted **before** the MCM, while the old Zone structure holds.
2. `_zones = zonesAfter`, with the other Zone's sensitivities reset when overlap resolution changed it (Section 6.1).
3. `affected.foreach(tracker.reset)` and `rebuildAllocator` for both Zones — unchanged; each rebuilt allocator carries
   the threshold transplanted from its predecessor.
4. Emit the MCM sequence(s) — unchanged.
5. For each Zone's allocator, Lower before Upper as `tune()` already orders them:
   `setExpressionPitchBendThreshold(expressionPitchBendThresholdOf(zone.memberPitchBendSensitivity))`, emitted through
   the Section 5.2 helper. On an allocator built fresh by `createAllocator` the value is already in place and the
   allocator holds no notes, so the call returns an empty result and the branch needs no condition.
6. `_inputMode = MpeInputMode.Mpe` — unchanged.

One ordering consequence is worth stating because it contradicts a sentence of the paper: the reclassification drops are
*caused* by the MCM's own Pitch Bend Sensitivity reset, so their Note Offs necessarily **follow** the MCM, whereas the
reconfiguration drops of step 1 precede it.

---

## 7. Rounding and equivalence with the current output

The sum is raw-to-raw and introduces no conversion of its own — the expression component is never converted, in either
direction. Two roundings remain, and only one of them is new:

- **The tuning offset.** `Tuning` defines offsets in cents, so the tuning term is converted once per emission. This
  rounding exists in the current code too.
- **The channel average**, now that it is stored as an `Int`. This is the only new one, it applies only to channels
  holding more than one note, and it is at most half a step. At the ±48-semitone Member Channel default one step is
  4800 / 8191 ≈ 0.59 cents, so the error is at most ≈ 0.29 cents, against a pitch JND of roughly 5–10 cents. (The issue
  quotes ≈ 0.15 cents, derived from a "0.29 cents per step" figure that appears in the current `diff` ScalaDoc and is
  itself half the true step; both figures double, and the conclusion is unchanged.)

For a channel holding a single note — the common case — the emitted value is not merely close to today's but
**identical**. `convertCentsToValue` is linear within a sign branch, and adding an integer commutes with rounding, so
`round(tuning + expr)` and `round(tuning) + expr` agree whenever `expr` is an integer, which it now always is. Worked at
±48 semitones with a tuning offset of −14 cents:

| Expression | Today | Under this design |
|---|---|---|
| raw 43 (≈ 25.2 cents) | `round(11.2 / 4800 × 8191) = 19` | `−24 + 43 = 19` |
| raw 10 (≈ 5.9 cents) | `round(−8.1 / 4800 × 8192) = −14` | `−24 + 10 = −14` |

The two can differ by one step only when the tuning term and the total fall on opposite sides of zero, because
`convertCentsToValue` scales negatives by 8192 and positives by 8191. That asymmetry is pre-existing and affects the
current code equally.

---

## 8. Tests

`MpeTunerTest`'s fixture already takes cents and converts internally (`noteOn(channel, note, velocity, pbCents = …)`,
`pitchBend(channel, cents)`), so most call sites need no change. Where a raw value must appear, use a helper rather than
a magic number, and pin the helper itself with at least one explicit golden value — 25 cents at ±48 semitones is 43 —
so the assertions do not become tautologies against the production conversion.

`MpeChannelAllocatorTest` gets simpler: its factory methods pass an explicit integer threshold, its high-bend cases set
raw values against it, and cents and `PitchBendSensitivity` disappear from the suite.

**Changed:**

- "preserve intonation of active note with expression pitch bend after PBS change" (`MpeTunerTest`, "PBS Processing -
  MPE Input") asserts the requirement being dropped. Rename and invert it: the tuning component is preserved under the
  new sensitivity while the expressive component is reinterpreted. With E4 at a −14-cent offset, an expression of 293
  cents at ±48 (raw 500) and a change to ±24, the emitted value becomes `−48 + 500 = 452`, i.e. −14 cents of tuning plus
  the 146.5 cents the held raw value now means.
- The MCM tests that hold notes through a reconfiguration gain Pitch Bend expectations, since retained channels are now
  retuned (Section 6.2).

**New:**

- *Regression for the reported defect*, in "PBS Processing - MPE Input": send a Pitch Bend on an input Member Channel,
  change the member PBS with `sendPbsMsb`, then send a Note On on that channel with no intervening Pitch Bend, and
  assert the new note's Expression Pitch Bend agrees with what an already-sounding note on that input channel carries —
  both being the raw value the tracker holds. Agreement between the two paths is the property under test; neither the
  old cents nor the new cents is privileged. A tuning whose relevant pitch class has a zero offset makes the emitted
  Pitch Bend equal to the raw expression value, which is the cleanest way to observe it.
- *Several notes crossing at once*: co-resident notes from **different** input channels, so their bends differ, carried
  across the threshold by one PBS change; the latest-onset note survives and the rest of the channel is dropped.
- *Conditional slide and pressure*: those dimensions are emitted from the pass only when a drop actually moved the
  channel's average, and never when nothing was dropped.
- *Degenerate range*: a member PBS at or below the threshold in cents (e.g. ±0 semitones 20 cents) classifies nothing as
  high-bend and throws nothing.
- *MCM*: the Zone shrunk by overlap resolution has its Pitch Bend Sensitivity reset to the defaults (Section 6.1); a
  note retained across an MCM is re-emitted with a Pitch Bend encoded against the reset sensitivity (Section 6.2); a
  retained note reclassified as high-bend by that reset drops its co-residents.

The `tuner` module suite must pass and coverage must not regress, per the `scoverage-inspector` skill's policy.

---

## 9. Documentation

**Paper.** The behaviour being dropped was never specified in the paper: its "Pitch Bend Sensitivity" section says only
that the Tuner relies on the PBS defaults "both when interpreting incoming Pitch Bend and when encoding Pitch Bend on
its output", and is silent on already-sounding notes. Conserving cents across a PBS change was an implementation
artifact, and the replacement is what a plain reading of MIDI gives. The "High Expression Pitch Bend" section already
defines `t` as an absolute pitch deviation, which is evaluated against whatever sensitivity is in force by definition;
the "Aggregation Model" and "MPE Input Mode" sections describe a channel's value as an average without fixing units,
which is right for a design paper. **None of those change.** Two sections do:

- **"Divergence on a Shared Channel"** — its second paragraph attributes a multi-note crossing to notes sharing an input
  channel and therefore "the *same* Pitch Bend message". Extend it: a member Pitch Bend Sensitivity change — including
  the reset an MCM performs — reclassifies every sounding note in the Zone at once, since the threshold is a deviation
  in cents and the held bend is not rescaled, so notes may cross it with no Pitch Bend message arriving; unlike the
  same-input-channel case, the notes crossing together may carry *different* bends, having arrived on different input
  channels; the resolution is unchanged — retain the note with the latest onset, drop the rest of the channel, leaving
  exactly one active note and restoring invariant 2. The "Dropping arises in two circumstances" opening of the parent
  section stands: a PBS change is a new trigger for an existing circumstance, not a third circumstance.
- **"Zones" (Section 4.2)** — alongside "Channels of a Zone untouched by the reconfiguration keep their notes and
  state", record that the Pitch Bend Sensitivity reset is scoped to each Zone whose MCM the Tuner emits, not to the set
  of channels entering or leaving MPE control. Retained channels are therefore reclassified against the new threshold
  and retuned, and the drops that reclassification causes follow the MCM rather than preceding it, unlike the drops of
  the reconfiguration itself.

**Elsewhere:**

- `docs/architecture/tuner/README.md` — remove the "Subject to change" bullet for TODO #253, and state in the
  `MpeChannelAllocator` description that Expression Pitch Bend is held in raw units with the high-bend threshold
  injected by `MpeTuner`.
- Remove the `// TODO #253` comment above `MpeTuner.inputExpressionOf`.
- ScalaDoc: `MpeExpression.pitchBend` (the *why*, per Section 2), `MpeChannelAllocator.diff` (the tolerance rationale
  goes away), the allocator's class-level Expression Value description, the threshold accessors,
  `MpeChannelAllocator.retaining` (the threshold is transplanted), `MpeZone.withDefaultPitchBendSensitivities`, and
  `MpeTuner.computeOutputPitchBend`.

---

## 10. Acceptance criteria

- [ ] `MpeExpression` and everything derived from it carry Expression Pitch Bend as `Int` raw units; no `Double` and no
      cents remain in `MpeExpression.scala`, `MpeChannelState.scala` or `MpeChannelAllocator.scala`.
- [ ] `MpeChannelAllocator` and its companion reference neither `PitchBendSensitivity` nor cents; the high-bend
      threshold arrives from `MpeTuner` in raw units, through the constructor and through
      `setExpressionPitchBendThreshold`.
- [ ] `MpeChannelAllocator.diff` compares all three dimensions exactly; `DefaultCentsTolerance` and the Guava
      `DoubleMath` import are gone from the file.
- [ ] `inputExpressionOf` and `processPitchBend` both read the tracker's raw Pitch Bend with no conversion.
- [ ] On a single-note channel the emitted Pitch Bend is unchanged from the current implementation, except where the
      tuning term and the total fall on opposite sides of zero.
- [ ] A member PBS change reassigns the threshold, re-applies the divergence rule across the Zone's occupied channels,
      emits the resulting Note Offs, and still emits exactly one recomputed Pitch Bend per occupied Member Channel.
- [ ] When a PBS change carries several notes on one channel across the threshold, the latest-onset note survives and
      the rest of the channel is dropped — covered by a test with co-resident notes from *different* input channels, so
      their bends differ.
- [ ] Slide and Channel Pressure are emitted from that pass only when a drop actually moved the channel's average, and
      never when nothing was dropped.
- [ ] A threshold at or above the PBS range classifies nothing as high-bend, and throws nothing.
- [ ] An MCM resets the Pitch Bend Sensitivity of every Zone whose MCM the Tuner emits, the Zone shrunk by overlap
      resolution included.
- [ ] Notes retained across an MCM are reclassified against the new threshold and their channels retuned, with the
      reclassification drops emitted after the MCM.
- [ ] The regression test passes; the intonation-preservation test is inverted; `tuner` module tests pass and coverage
      does not regress.
- [ ] `tuner` architecture README bullet removed, `// TODO #253` removed, ScalaDocs updated as listed above.
- [ ] The paper's "Divergence on a Shared Channel" and "Zones" sections extended as described; no other paper section
      changed.

---

## 11. Out of scope

- **`MpeTuner.stopNotesOn`'s Master Channel branch** emits one Note Off per active note rather than one per forwarded
  Note On (TODO #254). Untouched.
- **Whether a conforming receiver's own §2.1.4 obligations make some of the retained-channel handling redundant.** The
  paper already commits to emitting deliberately redundant messages for robustness against receivers that do not fully
  conform, and this design follows that commitment rather than revisiting it.

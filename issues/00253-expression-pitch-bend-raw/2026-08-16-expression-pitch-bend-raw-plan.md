# Expression Pitch Bend Stored Raw — Implementation Plan

- **Date**: 2026-08-16
- **Issue**: [#253](https://github.com/calinburloiu/microtonalist/issues/253) — "MPE Tuner: store Expression Pitch Bend
  raw instead of in cents, so it cannot go stale after a member PBS change"
- **Base commit**: `979a1d49750abb6065ef2f4d0261fc6126393ca0`
- **Spec**: [`2026-08-16-expression-pitch-bend-raw-design.md`](2026-08-16-expression-pitch-bend-raw-design.md) — the
  approved design. Read it alongside this plan; every section reference below (`Design §N`) points into it.
- **Working tree at the time of writing**: `docs/architecture/tuner/mpe-tuner-paper.md` carries one uncommitted edit
  that splits Section 4.2's third paragraph into three. Task 4 builds **on top of** that edit and must not revert it.
  Nothing was committed and no branch was created while this plan was written.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store a note's Expression Pitch Bend as the raw signed 14-bit value received on the input Member Channel, so
that the seeding path and the re-emission path can no longer disagree after a Pitch Bend Sensitivity change, and
re-evaluate the High Expression Pitch Bend classification whenever a member sensitivity change moves the threshold.

**Architecture:** `MpeExpression` and everything derived from it carry an `Int` Pitch Bend instead of a `Double` in
cents. `MpeChannelAllocator` becomes integral end to end and receives the High Expression Pitch Bend threshold in raw
units from `MpeTuner`, which stays the sole owner of cents and of `PitchBendSensitivity`. Because classification now
depends on the sensitivity, a member PBS change — explicit, or the reset an MCM performs — re-derives the threshold,
re-applies the divergence rule across the Zone, and emits the resulting Note Offs, retuning, and moved control
dimensions through one shared helper.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`), Metals MCP for
compilation and symbol lookup, scoverage for coverage.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Module**: all production changes live in `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/`; all
  test changes in `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/`. No other module changes.
- **Run tests** with `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`. A single class:
  `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`.
- **Compile** with `mcp__metals__compile-module` (`module = "tuner"`); fall back to `sbtn "tuner/compile"`.
- **Strict TDD** per `CLAUDE.md`: red → green → refactor. A red step must fail on an *assertion*, never on a compile
  error, so each red step below states the thinnest production edits needed to make the suite compile.
- **Scala conventions** (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines, no
  `new`, no `return`, `enum` over sealed traits, ScalaDoc on every public identifier, `_name` for the backing variable
  of a `name` accessor.
- **Test conventions** (`docs/development/test-conventions.md`): `// Given` / `// When` / `// Then` comments, no `if`
  in tests, place each new case in the `behavior of` block its class ScalaDoc prescribes. `MpeTunerTest`'s ScalaDoc
  fixes the category → input-mode → subgroup structure; `MpeChannelAllocatorTest`'s asks that each section name start
  with the class name.
- **License headers**: `.scala` files are covered by the `addlicense` pre-commit hook — never write or edit a header by
  hand. `Read` skips the ~15-line header, so files appear to start at ~line 17 with real line numbers preserved.
- **Coverage floor**: the `tuner` module is at `coverageSettings(stmt = 80, branch = 80)` in `build.sbt`. Never lower
  it. Verify with the `scoverage-inspector` skill (Task 5).
- **Branch and PR**: work on a branch created per the `contributing` skill; do not open a PR until Task 5 is green.
- **Do not touch**: `MpeTuner.stopNotesOn`'s Master Channel branch (TODO #254) — out of scope per Design §11.

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `MpeExpression.scala` | Modify | The Expression Value model, now integral: `pitchBend: Int` on the trait, its two implementations and `MpeExpressionUpdate`. Its ScalaDoc is where the "reinterpreted, not rescaled" decision lives. |
| `MpeChannelState.scala` | Modify | Per-channel/per-note mutable state; `pitchBendOf` / `setPitchBend`, and an average that rounds all three dimensions half up. |
| `MpeChannelAllocator.scala` | Modify | Allocation + Expression Values, now free of cents and `PitchBendSensitivity`. Holds the injected raw threshold, its atomic setter, and an exact three-way `diff`. |
| `MpeZone.scala` | Modify | Adds `MpeZone.withDefaultPitchBendSensitivities`, the model-side mirror of what an emitted MCM does at the receiver. |
| `MpeTuner.scala` | Modify | Sole owner of cents and PBS: derives the raw threshold, reads and sums raw Pitch Bend, and drives the re-evaluation pass on both of its triggers. |
| `MpeChannelAllocatorTest.scala` | Modify | Loses cents and `PitchBendSensitivity` entirely; factories pass an explicit integer threshold. |
| `MpeTunerTest.scala` | Modify | Gains the raw-value helper, the regression test, the re-evaluation tests and the MCM tests. |
| `MpeZoneTest.scala` | Modify | Covers `withDefaultPitchBendSensitivities`. |
| `docs/architecture/tuner/mpe-tuner-paper.md` | Modify | Sections 6.2.1 and 4.2 extended. |
| `docs/architecture/tuner/README.md` | Modify | TODO #253 bullet removed; allocator description states the units and the injection. |

---

## Task 1: Raw Expression Pitch Bend, end to end

The data-model change is atomic: `MpeExpression.pitchBendCents: Double` cannot become `pitchBend: Int` without
`MpeChannelState`, `MpeChannelAllocator` and `MpeTuner` moving in the same edit, and any halfway state would be red
production code. This task therefore carries the whole model change and both suites' conversion. It deliberately does
**not** add the re-evaluation pass (Task 2) or the MCM fixes (Task 3): after it, a member PBS change reinterprets held
bends without reclassifying them, which no test asserts yet.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeExpression.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelState.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: `PitchBendScMidiMessage.{MinValue, MaxValue, NoPitchBendValue, convertCentsToValue}` and
  `PitchBendSensitivity.totalCents` from `sc-midi`; `clampValue(Int, Int, Int)` and `clampValue(Double, Double, Double)`
  from the `org.calinburloiu.music.scmidi` package object.
- Produces, for Tasks 2 and 3:
  - `MpeExpression.pitchBend: Int`, `MpeExpression.DefaultPitchBend: Int`
  - `MutableMpeExpression(pitchBend: Int, pressure: Int, slide: Int)`,
    `ImmutableMpeExpression(pitchBend: Int, pressure: Int, slide: Int)`
  - `MpeExpressionUpdate(pitchBend: Option[Int], pressure: Option[Int], slide: Option[Int])`
  - `MpeChannelState.pitchBendOf(noteIdentity: MpeNoteIdentity): Int`,
    `MpeChannelState.setPitchBend(noteIdentity: MpeNoteIdentity, pitchBend: Int): Unit`
  - `MpeChannelAllocator(zone: MpeZoneStructure, initialExpressionPitchBendThreshold: Int, retainedStates: Map[Int, MpeChannelState] = Map.empty)`
  - `MpeChannelAllocator.expressionPitchBendThreshold: Int`
  - `MpeChannelAllocator.updateExpressionPitchBend(inputChannel: Int, pitchBend: Int): MpeExpressionUpdateResult`
  - `MpeTuner.expressionPitchBendThresholdOf(pbs: PitchBendSensitivity): Int` (private, in `object MpeTuner`)
  - `MpeTunerTest.rawPitchBend(cents: Double, pbs: PitchBendSensitivity): Int` and
    `Fixture.pitchBendValue(channel: Int, value: Int): Seq[MidiMessage]`

### Why the constructor parameter is not named `expressionPitchBendThreshold`

Design §3 writes the constructor as `MpeChannelAllocator(zone, expressionPitchBendThreshold: Int, retainedStates)`
alongside a `def expressionPitchBendThreshold` accessor. Scala 3 rejects that pair — verified with `scala-cli`:

```
Double definition:
private[this] val expressionPitchBendThreshold: Int in class ... and
def expressionPitchBendThreshold: Int in class ...
```

The parameter is therefore named `initialExpressionPitchBendThreshold`, following the project's own
`initialZones` / `_zones` / `zones` pattern in `MpeTuner`. Nothing else about Design §3 changes: no default value, the
threshold still arrives from `MpeTuner` in raw units, and `retaining` still keeps its signature.

---

- [ ] **Step 1: Convert `MpeChannelAllocatorTest` to the raw model (red)**

Apply the following to `MpeChannelAllocatorTest.scala`. It will not compile until Steps 3–6 land; that is expected,
and Step 7 is where the suite is first run.

Replace the six factory methods (lines ~32–47) and the two bend constants (lines ~55–58) with:

```scala
  /**
   * The raw High Expression Pitch Bend threshold every allocator in this suite is built with. A plain round number
   * rather than one derived from a Pitch Bend Sensitivity: the allocator classifies against whatever threshold it is
   * given and knows nothing of cents. `MpeTunerTest` covers the derivation from a sensitivity.
   */
  private val threshold: Int = 100

  /** Lower Zone with 15 members: PCG=12, EG=3, channels 1..15 */
  private def allocator15: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 15), threshold)

  /** Lower Zone with 7 members: PCG=5, EG=2, channels 1..7 */
  private def allocator7: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 7), threshold)

  /** Lower Zone with 4 members: PCG=2, EG=2, channels 1..4 */
  private def allocator4: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 4), threshold)

  /** Lower Zone with 3 members: PCG=1, EG=2, channels 1..3 */
  private def allocator3: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 3), threshold)

  /** Lower Zone with 2 members: PCG=1, EG=1, channels 1..2 */
  private def allocator2: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 2), threshold)

  /** Lower Zone with 1 member: PCG=1, EG=0, channels 1..1 */
  private def allocator1: MpeChannelAllocator = MpeChannelAllocator(MpeZone(MpeZoneType.Lower, 1), threshold)
```

and

```scala
  /** A raw Expression Pitch Bend above `threshold`, so a High Expression Pitch Bend. */
  private val highPitchBend: Int = 200

  /** A raw Expression Pitch Bend below `threshold`, so not a High Expression Pitch Bend. */
  private val lowPitchBend: Int = 50
```

Then apply these mechanical replacements across the whole file:

| Find | Replace with |
|---|---|
| `highPitchBendCents` | `highPitchBend` |
| `lowPitchBendCents` | `lowPitchBend` |
| `expressionPitchBendCents: Double = MpeExpression.DefaultPitchBendCents` (extension `allocateNote`, ~line 67) | `expressionPitchBend: Int = MpeExpression.DefaultPitchBend` |
| `ImmutableMpeExpression(expressionPitchBendCents)` (~line 71) | `ImmutableMpeExpression(expressionPitchBend)` |
| `.pitchBendCents` on any `MpeExpression` | `.pitchBend` |
| the named argument `pitchBendCents = 20.0` (~line 1162) | `pitchBend = 20` |
| `MpeExpressionUpdate(pitchBendCents = Some(x))` | `MpeExpressionUpdate(pitchBend = Some(x))` |
| every `MpeChannelAllocator(zone)` in the `MpeChannelAllocator.retaining` section (~lines 1160, 1187, 1211, and each later one) | `MpeChannelAllocator(zone, threshold)` |
| every remaining `Double` literal passed or asserted as an Expression Pitch Bend (`10.0`, `-20.0`, `30.0`, `20.0`, `0.0`, `-5.0`) | the same numeral as an `Int` (`10`, `-20`, `30`, `20`, `0`, `-5`) |

**Three literals are threshold-relative and must not be carried across as their own numeral** — the old suite compared
them against the production constant of 50 cents, and reusing them against the new threshold of 100 would silently
invert what they test. Replace them by name:

| Line | Find | Replace with |
|---|---|---|
| ~1095 | `alloc.updateExpressionPitchBend(1, 100.0)` | `alloc.updateExpressionPitchBend(1, highPitchBend)` |
| ~1103 | `MpeExpressionUpdate(pitchBendCents = Some(100.0))` | `MpeExpressionUpdate(pitchBend = Some(highPitchBend))` |
| ~1117 | `alloc.updateExpressionPitchBend(2, -100.0)` | `alloc.updateExpressionPitchBend(2, -highPitchBend)` |
| ~1134 | `alloc.updateExpressionPitchBend(2, 50.0)` | `alloc.updateExpressionPitchBend(2, threshold)` |

The last one is the "not drop a co-resident note for a bend exactly at the High Expression Pitch Bend threshold" case:
its whole point is that the comparison is strict, so it must pass exactly the threshold the allocator holds.

Two further cases need more than a rename.

Replace the test at lines ~875–891 — its premise (a `Double` average whose last bits drift) no longer exists — with:

```scala
  it should "report no Expression Pitch Bend change when releasing a note leaves the average unchanged" in {
    // Given
    // Three notes of the same pitch class share the single Member Channel, all with the same Expression Pitch
    // Bend, so the channel's average is a sum of three equal terms divided by three.
    val alloc = allocator1
    val expression = Some(ImmutableMpeExpression(10))
    alloc.allocate(MpeNoteIdentity(1, C4), expression)
    alloc.allocate(MpeNoteIdentity(2, C4), expression)
    val third = MpeNoteIdentity(3, C4)
    alloc.allocate(third, expression)
    // When
    // Releasing one leaves two terms averaging to the same value. Now that the dimension is integral this is
    // exact, so `diff` can compare it with `!=` like the other two.
    val result = alloc.release(third).value
    // Then
    result.update.pitchBend shouldBe None
  }
```

Extend the half-up rounding test at lines ~910–925 so it covers the newly integral dimension. Replace its body with:

```scala
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = alloc.allocate(MpeNoteIdentity(1, C4), Some(ImmutableMpeExpression(11, 32, 48)))
    alloc.allocate(MpeNoteIdentity(2, C5))
    // When
    // Both groups are full and the pitch class is already present, so the third C shares the oldest channel.
    // All three dimensions average to exactly .5, which truncation would round down and half-even would round
    // to the even neighbour.
    val shared = alloc.allocate(MpeNoteIdentity(3, C3), Some(ImmutableMpeExpression(-20, 97, 97)))
    // Then
    shared.channel shouldBe first.channel
    val expression = alloc.channelExpression(shared.channel)
    expression.pitchBend shouldBe -4 // (11 + -20) / 2 = -4.5
    expression.pressure shouldBe 65 // (32 + 97) / 2 = 64.5
    expression.slide shouldBe 73 // (48 + 97) / 2 = 72.5
```

Finally, append this case to the `behavior of "MpeChannelAllocator.retaining"` section, in the "Retained channels"
subgroup:

```scala
  it should "transplant the High Expression Pitch Bend threshold of the allocator it rebuilds" in {
    // Given
    // The threshold is part of the state `retaining` carries over: `MpeTuner` applies the Zone's new threshold
    // afterwards, through the setter that also re-evaluates the transplanted notes.
    val zone = MpeZone(MpeZoneType.Lower, 7)
    val alloc = MpeChannelAllocator(zone, threshold)
    // When
    val rebuilt = MpeChannelAllocator.retaining(MpeZone(MpeZoneType.Lower, 4), alloc, affectedChannels = Set(5, 6, 7))
    // Then
    rebuilt.expressionPitchBendThreshold shouldEqual threshold
  }
```

- [ ] **Step 2: Add the raw-value helpers and the two `MpeTunerTest` cases (red)**

In `MpeTunerTest.scala`, add the helper next to the other `extract*` helpers (after `extractScMidiMessages`, ~line 213):

```scala
  /**
   * The raw signed 14-bit Pitch Bend value a deviation in cents takes under a Pitch Bend Sensitivity. Assertions
   * that mention a raw value pin it with an explicit golden number as well, so they do not become tautologies
   * against the production conversion.
   */
  private def rawPitchBend(cents: Double, pbs: PitchBendSensitivity = defaultPbs): Int =
    PitchBendScMidiMessage.convertCentsToValue(cents, pbs)
```

Add a raw send to `Fixture`, and route the cents-based one through it (replacing the existing `pitchBend` at
~lines 232–235):

```scala
    /** Sends a Pitch Bend carrying an exact raw value, for cases where the value itself is what matters. */
    def pitchBendValue(channel: Int, value: Int): Seq[MidiMessage] =
      tuner.process(PitchBendScMidiMessage(channel, value).asJava)

    def pitchBend(channel: Int, cents: Double): Seq[MidiMessage] =
      pitchBendValue(channel, PitchBendScMidiMessage.convertCentsToValue(cents, defaultPbs))
```

In `behavior of "MpeTuner - PBS Processing - MPE Input"`, subgroup
`// ---- Pitch-bend recomputation after PBS change ----`, replace the test named
`"preserve intonation of active note with expression pitch bend after PBS change"` (lines ~3467–3482) with its
inversion — the requirement it asserted is the one Design §1 drops:

```scala
  it should "reinterpret the Expression Pitch Bend of an active note under the new PBS, preserving its tuning" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // E4 on input Member Channel 1 with an Expression Pitch Bend of 293 cents at the default ±48 semitones,
      // which is raw 500. The tuning offset of E in quarter-comma meantone is -14 cents.
      private val exprCents = 293.0
      rawPitchBend(exprCents) shouldEqual 500
      private val noteOutput = noteOn(1, E4, 100, pbCents = Some(exprCents))
      private val noteChannel = extractNoteOns(noteOutput).head.channel

      // When - The member PBS narrows from ±48 to ±24 semitones.
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)

      // Then
      // The tuning term is re-encoded against the new sensitivity while the held raw bend carries over
      // untouched: -14 cents is -48 raw at ±24, and the raw 500 now means 146.5 cents rather than 293. A
      // sensitivity change reinterprets a held bend rather than conserving its deviation.
      private val pitchBends = extractPitchBends(pbsOutput).filter(_.channel == noteChannel)
      pitchBends should have size 1
      pitchBends.head.value shouldEqual rawPitchBend(-14.0, PitchBendSensitivity(24)) + 500
      pitchBends.head.value shouldEqual 452
    }
```

Add the regression test for the reported defect immediately after it, in the same subgroup:

```scala
  it should "seed a new note's Expression Pitch Bend with the same raw value an active note of its input " +
    "channel carries, after a member PBS change" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // 25 cents at the default ±48 semitones is raw 43, and C has a zero tuning offset in quarter-comma
      // meantone, so an emitted Pitch Bend on a C channel is exactly the raw expression value.
      rawPitchBend(25.0) shouldEqual 43
      pitchBend(1, 25.0)
      private val firstOutput = noteOn(1, C4)
      private val firstChannel = extractNoteOns(firstOutput).head.channel

      // When
      // The member PBS changes, then a second note arrives on the same input channel with no intervening Pitch
      // Bend, so it is seeded from the raw value the tracker still holds.
      private val pbsOutput = sendPbsMsb(tuner, channel = 1, semitones = 24)
      private val secondOutput = noteOn(1, C5)
      private val secondChannel = extractNoteOns(secondOutput).head.channel

      // Then
      // The two paths agree, both being the tracker's raw value. Neither the old cents nor the new cents is
      // privileged: agreement is the property under test.
      secondChannel should not equal firstChannel
      extractPitchBends(pbsOutput).filter(_.channel == firstChannel).map(_.value) shouldEqual Seq(43)
      extractPitchBends(secondOutput).filter(_.channel == secondChannel).map(_.value) shouldEqual Seq(43)
    }
```

- [ ] **Step 3: Make `MpeExpression.scala` integral**

Add the import and rewrite the four members. Keep `DefaultPressure`, `DefaultSlide`, `ImmutableMpeExpression.Default`
and `MpeChannelExpressionUpdate` exactly as they are.

```scala
import org.calinburloiu.music.scmidi.message.PitchBendScMidiMessage
```

```scala
  /**
   * Expression Pitch Bend as a signed 14-bit MIDI Pitch Bend value (-8192 to 8191), excluding any tuning
   * offset. 0 means no bend.
   *
   * The value is held exactly as received on the input Member Channel, in the units the wire carries, and is
   * '''reinterpreted, not rescaled''', when the Member Channel Pitch Bend Sensitivity changes: one and the same
   * raw value means 25 cents at ±2 semitones and 600 cents at ±48, and every note sounding on that channel is
   * reinterpreted alike. That is what an ordinary MIDI receiver does, and a sender wanting a specific deviation
   * after a sensitivity change sends a fresh Pitch Bend.
   *
   * Storing cents instead would conserve a note's deviation across a sensitivity change, but it would also make
   * this value one of two conversions that have to be kept in step — the other being the one that seeds a new
   * note from its input channel's raw Pitch Bend — which is the disagreement #253 reported. Raw removes the
   * possibility of a disagreement rather than fixing one instance of it, and it makes the model integral, so
   * that [[MpeChannelAllocator.diff]] can compare all three dimensions exactly.
   */
  def pitchBend: Int
```

```scala
  /** Default Expression Pitch Bend value (no bend). */
  val DefaultPitchBend: Int = PitchBendScMidiMessage.NoPitchBendValue
```

```scala
private[tuner] class MutableMpeExpression(var pitchBend: Int = MpeExpression.DefaultPitchBend,
                                          var pressure: Int = MpeExpression.DefaultPressure,
                                          var slide: Int = MpeExpression.DefaultSlide) extends MpeExpression
```

```scala
private[tuner] case class ImmutableMpeExpression(pitchBend: Int = MpeExpression.DefaultPitchBend,
                                                 pressure: Int = MpeExpression.DefaultPressure,
                                                 slide: Int = MpeExpression.DefaultSlide) extends MpeExpression
```

```scala
private[tuner] case class MpeExpressionUpdate(pitchBend: Option[Int] = None,
                                              pressure: Option[Int] = None,
                                              slide: Option[Int] = None)
```

- [ ] **Step 4: Make `MpeChannelState.scala` integral**

Four edits:

```scala
  /** The average of the active notes' Expression Values, rounding all three dimensions half up. */
  private def averageExpression: ImmutableMpeExpression = {
    val noteStates = _notes.values
    val count = _notes.size
    ImmutableMpeExpression(
      pitchBend = Math.round(noteStates.map(_.expression.pitchBend).sum.toDouble / count).toInt,
      pressure = Math.round(noteStates.map(_.expression.pressure).sum.toDouble / count).toInt,
      slide = Math.round(noteStates.map(_.expression.slide).sum.toDouble / count).toInt)
  }
```

```scala
  /** An immutable snapshot of the Expression Values of an active note on this channel. */
  def expressionFor(noteIdentity: MpeNoteIdentity): MpeExpression = {
    val expression = _notes(noteIdentity).expression
    ImmutableMpeExpression(expression.pitchBend, expression.pressure, expression.slide)
  }

  /** The Expression Pitch Bend of an active note, read without copying the other two dimensions. */
  def pitchBendOf(noteIdentity: MpeNoteIdentity): Int = _notes(noteIdentity).expression.pitchBend

  /** Sets the Expression Pitch Bend of an active note, invalidating the channel's aggregate. */
  def setPitchBend(noteIdentity: MpeNoteIdentity, pitchBend: Int): Unit = {
    _notes(noteIdentity).expression.pitchBend = pitchBend
    _isExpressionStale = true
  }
```

In `addNote`:

```scala
    val noteExpression = MutableMpeExpression(expression.pitchBend, expression.pressure, expression.slide)
```

- [ ] **Step 5: Make `MpeChannelAllocator.scala` integral and give it the injected threshold**

Remove `import com.google.common.math.DoubleMath` from the file header. Leave `DefaultCentsTolerance` in
`tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/package.scala` alone — `Tuning.merge` and
`Tuning.almostEquals` still use it; only this file stops referring to it.

Change the constructor and add the backing variable and the getter. The setter and `reapplyDivergenceRule` come in
Task 2 — do **not** add them here.

```scala
 * @param zone                              The MPE zone to allocate channels for.
 * @param initialExpressionPitchBendThreshold The raw Expression Pitch Bend magnitude above which a note counts
 *                                          as having a High Expression Pitch Bend. Supplied by [[MpeTuner]],
 *                                          which is the only component that knows the Member Channel Pitch Bend
 *                                          Sensitivity the threshold's definition in cents is evaluated
 *                                          against. It has no default for that reason.
 * @param retainedStates                    (unchanged)
 */
private[tuner] class MpeChannelAllocator(private val zone: MpeZoneStructure,
                                         initialExpressionPitchBendThreshold: Int,
                                         retainedStates: Map[Int, MpeChannelState] = Map.empty) {
```

Add, next to the other private fields (after `_time`):

```scala
  private var _expressionPitchBendThreshold: Int = initialExpressionPitchBendThreshold
```

Add, in the "State inspection accessors" region:

```scala
  /**
   * The raw Expression Pitch Bend magnitude above which a note counts as having a High Expression Pitch Bend.
   * Read by [[MpeChannelAllocator.retaining]], which transplants it into the allocator it rebuilds.
   */
  def expressionPitchBendThreshold: Int = _expressionPitchBendThreshold
```

Move the two classification helpers from the companion into the class (they now read the var), placing them just above
`diff`:

```scala
  private def isHighExpressionPitchBend(pitchBend: Int): Boolean =
    Math.abs(pitchBend) > _expressionPitchBendThreshold

  private def hasHighExpressionPitchBend(state: MpeChannelState): Boolean =
    state.noteIdentities.exists(n => isHighExpressionPitchBend(state.pitchBendOf(n)))
```

Rename and retype the update method:

```scala
  /**
   * Applies an Expression Pitch Bend received on an input channel to every note active on it, wherever the
   * pitch-class invariant has placed those notes, and applies the divergence rule to each affected output
   * channel.
   *
   * @param inputChannel The input channel the Pitch Bend arrived on.
   * @param pitchBend    The new Expression Pitch Bend, as the raw signed 14-bit value received.
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def updateExpressionPitchBend(inputChannel: Int, pitchBend: Int): MpeExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel),
      (state, noteIdentity) => state.setPitchBend(noteIdentity, pitchBend),
      applyDivergenceRule)
```

Retype the two high-bend readers:

```scala
    val dropped = dropExistingNotesForHighBend(state, existingIdentities, actualExpression.pitchBend, time)
```

```scala
  private def dropExistingNotesForHighBend(state: MpeChannelState,
                                           existingIdentities: Set[MpeNoteIdentity],
                                           newPitchBend: Int,
                                           time: Long): Option[MpeDroppedNotes] = {
    if (existingIdentities.isEmpty) {
      None
    } else {
      val existingHighBend = existingIdentities.exists { noteIdentity =>
        isHighExpressionPitchBend(state.pitchBendOf(noteIdentity))
      }
      val newHighBend = isHighExpressionPitchBend(newPitchBend)
      if (existingHighBend || newHighBend) {
        Some(dropIdentities(state, existingIdentities.toSeq, time))
      } else {
        None
      }
    }
  }
```

```scala
    val highBendIdentities = identities.filter { noteIdentity =>
      isHighExpressionPitchBend(state.pitchBendOf(noteIdentity))
    }
```

Make `diff` exact, dropping the tolerance paragraph entirely:

```scala
  /**
   * Reports which of the three dimensions changed between two aggregates. All three are integers and compare
   * exactly: a channel's aggregate is what reaches the wire, modulo the tuning term, so a difference here is
   * exactly a message that has to go out.
   */
  private def diff(before: MpeExpression, after: MpeExpression): MpeExpressionUpdate =
    MpeExpressionUpdate(
      pitchBend = Option.when(after.pitchBend != before.pitchBend)(after.pitchBend),
      pressure = Option.when(after.pressure != before.pressure)(after.pressure),
      slide = Option.when(after.slide != before.slide)(after.slide))
```

Update the class-level ScalaDoc's Expression Values bullet to state the units, replacing the first bullet of the
`'''Expression Values'''` list:

```scala
 *  - each active [[MpeNoteIdentity]] carries its own [[MpeExpression]] — with the Expression Pitch Bend in raw
 *    signed 14-bit units, see [[MpeExpression.pitchBend]] — and a reference count, one per Note On forwarded
 *    for it;
```

In the companion: delete the `ExpressionPitchBendThreshold` constant and the two helper defs that moved into the class,
and pass the transplanted threshold in `retaining`:

```scala
    MpeChannelAllocator(zone, from.expressionPitchBendThreshold, retainedStates)
```

Extend `retaining`'s ScalaDoc with a sentence recording the transplant, right after the "A retained channel may end up
in a group…" paragraph:

```scala
   * The High Expression Pitch Bend threshold is transplanted too: the rebuilt allocator adopts `from`'s, and
   * [[MpeTuner]] then applies the reconfigured Zone's own threshold through
   * `setExpressionPitchBendThreshold`, which is also what re-evaluates the transplanted notes. One place
   * computes the new threshold and one call applies it.
```

- [ ] **Step 6: Make `MpeTuner.scala` the sole owner of cents and PBS**

In `object MpeTuner`, after `AllChannels`:

```scala
  /** The paper's High Expression Pitch Bend threshold `t`: an absolute pitch deviation of half a semitone. */
  private val HighExpressionPitchBendThresholdCents: Double = 50.0

  /**
   * The threshold used when the threshold in cents is not below the Member Channel Pitch Bend Sensitivity range —
   * a sender configuring, say, ±0 semitones 20 cents, where no bend the Pitch Bend range can express deviates by
   * more than `t`. Its value is one greater than the largest magnitude a signed 14-bit Pitch Bend can take, so the
   * strict `>` of the classification is false for every value, `MinValue` included: nothing is a High Expression
   * Pitch Bend at such a range, and [[PitchBendScMidiMessage.convertCentsToValue]] — whose `require` rejects a
   * value beyond the sensitivity — is never called with one.
   */
  private val UnreachableExpressionPitchBendThreshold: Int = -PitchBendScMidiMessage.MinValue

  /**
   * The raw Expression Pitch Bend magnitude an [[MpeChannelAllocator]] classifies a High Expression Pitch Bend
   * against, for a given Member Channel Pitch Bend Sensitivity.
   *
   * A single threshold serves both signs even though [[PitchBendScMidiMessage.convertCentsToValue]] scales
   * negatives by 8192 and positives by 8191: the discrepancy is below one raw unit — 85.32 against 85.33 at ±48
   * semitones — and so invisible after rounding at every sensitivity of practical interest.
   */
  private def expressionPitchBendThresholdOf(pbs: PitchBendSensitivity): Int =
    if (HighExpressionPitchBendThresholdCents >= pbs.totalCents) UnreachableExpressionPitchBendThreshold
    else PitchBendScMidiMessage.convertCentsToValue(HighExpressionPitchBendThresholdCents, pbs)
```

In the class:

```scala
  private def createAllocator(zone: MpeZone): Option[MpeChannelAllocator] = {
    if (zone.isEnabled) {
      Some(MpeChannelAllocator(zone, expressionPitchBendThresholdOf(zone.memberPitchBendSensitivity)))
    } else {
      None
    }
  }
```

Drop the `// TODO #253` comment above `inputExpressionOf` and take the Pitch Bend raw:

```scala
  /**
   * The Expression Values a note arriving on an input Member Channel starts with, taken from the control
   * state remembered for that channel — the state-tracking obligation the MPE Specification places on
   * receivers, so that a Pitch Bend, CC #74 or Channel Pressure sent before the Note On is not lost.
   *
   * The Pitch Bend is taken exactly as the tracker holds it, with no conversion and no reference to the Zone's
   * Pitch Bend Sensitivity, which is what keeps it from disagreeing with the value already stored for the notes
   * active on the same input channel (see [[MpeExpression.pitchBend]]).
   */
  private def inputExpressionOf(inputChannel: Int): MpeExpression = ImmutableMpeExpression(
    pitchBend = tracker.pitchBend(inputChannel),
    pressure = tracker.channelPressure(inputChannel),
    slide = tracker.cc(inputChannel, ScMidiCc.MpeSlide))
```

In `processNoteOn`, drop the now-unused argument (keep `val zone = currentZone(alloc)`, still needed by
`preferredChannel`):

```scala
      val expression = Option.when(isMpeInput)(inputExpressionOf(inputChannel))
```

In `processPitchBend`, drop the conversion:

```scala
  private def processPitchBend(buffer: mutable.Buffer[MidiMessage], msg: PitchBendScMidiMessage,
                               role: MpeChannelRole): Unit = {
    // Per-note Pitch Bend on an input Member Channel: the note's Expression Pitch Bend, stored as received. The
    // allocator fans the update out by itself to every output channel holding a note of this input channel.
    allocatorFor(role).foreach { alloc =>
      emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(msg.channel, msg.value),
        alloc, DropReason.OnPitchBend)
    }
  }
```

Sum in raw units:

```scala
  /**
   * The Pitch Bend value emitted on an output Member Channel: the Tuning Pitch Bend of the channel's pitch class
   * plus the channel's aggregated Expression Pitch Bend, summed in raw signed 14-bit units.
   *
   * Only the tuning term is converted, [[Tuning]] defining its offsets in cents. It is clamped in the cents domain
   * first, [[PitchBendScMidiMessage.convertCentsToValue]] carrying a `require` that rejects a value beyond the
   * sensitivity, and the sum is then clamped to the same interval expressed in raw units. The expression term is
   * never converted in either direction: the allocator already holds it in the units the wire carries.
   */
  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val pbs = zone.memberPitchBendSensitivity
    val tuningValue = PitchBendScMidiMessage.convertCentsToValue(
      clampValue(tuningOffsetCents, -pbs.totalCents, pbs.totalCents), pbs)
    clampValue(tuningValue + alloc.channelExpression(channel).pitchBend,
      PitchBendScMidiMessage.MinValue, PitchBendScMidiMessage.MaxValue)
  }
```

Rename the two remaining `pitchBendCents` reads — in `processNoteOff`:

```scala
          if (result.update.pitchBend.isDefined) emitPitchBend(buffer, outChannel, alloc)
```

and in `emitExpressionUpdate`:

```scala
    if (update.pitchBend.isDefined) emitPitchBend(buffer, channel, alloc)
```

- [ ] **Step 7: Compile, then run the two suites and watch them fail for the right reason**

Run: `mcp__metals__compile-module` with `module = "tuner"`
Expected: success. Iterate on compile errors until it builds — a red step must not fail on compilation.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`
Expected: PASS. This suite was converted, not extended, so it should be green as soon as it compiles.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS, including the two cases from Step 2. If the regression test fails, the seeding path is still
converting — recheck `inputExpressionOf`.

- [ ] **Step 8: Run the whole module and commit**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeExpression.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelState.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#253] Store Expression Pitch Bend raw instead of in cents

The seeding path re-derived cents from the tracker's raw Pitch Bend under the
current member PBS while the re-emission path used the stored cents, so the two
disagreed after a member PBS change. Storing the raw value removes the second
conversion, and with it the possibility of a disagreement.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Re-evaluate the divergence rule on a member PBS change

Classification now depends on the sensitivity, so a member PBS change can turn sounding notes into High Expression
Pitch Bend notes with no note or Pitch Bend message arriving. The paper's "Summary of Note-Dropping Invariants"
requires the sole-note invariant **at all times**, so the Tuner acts rather than waiting for the next event
(Design §5).

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces, for Task 3:
  - `MpeChannelAllocator.setExpressionPitchBendThreshold(threshold: Int): MpeExpressionUpdateResult`
  - `MpeTuner.applyExpressionPitchBendThreshold(buffer: mutable.Buffer[MidiMessage], alloc: MpeChannelAllocator): Unit`
    (private)
  - `DropReason.OnMemberPbsChange`

- [ ] **Step 1: Write the allocator's failing tests**

Append a new section at the end of `MpeChannelAllocatorTest.scala`, before the closing brace of the class:

```scala
  behavior of "MpeChannelAllocator - High Expression Pitch Bend threshold"

  it should "re-apply the divergence rule when a lowered threshold reclassifies a shared channel's notes" in {
    // Given
    // Two C notes from different input channels share the Pitch Class Group channel, with different bends, both
    // below the current threshold of 100.
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val channel = alloc.allocate(first, Some(ImmutableMpeExpression(60))).channel
    alloc.allocate(MpeNoteIdentity(3, D4))
    alloc.allocate(second, Some(ImmutableMpeExpression(90))).channel shouldBe channel
    // When
    // Both cross the new threshold at once, which the same-input-channel case never produces.
    val result = alloc.setExpressionPitchBendThreshold(50)
    // Then
    // The latest onset survives, the rest of the channel goes, and the channel's aggregate is reported.
    alloc.expressionPitchBendThreshold shouldEqual 50
    result.droppedNotes should have size 1
    result.droppedNotes.head.channel shouldBe channel
    result.droppedNotes.head.notes.map(_.noteIdentity) shouldEqual Seq(first)
    alloc.activeNotes(channel) should contain theSameElementsAs Set(second)
    result.channelUpdates shouldEqual Seq(
      MpeChannelExpressionUpdate(channel, MpeExpressionUpdate(pitchBend = Some(90))))
  }

  it should "drop nothing when a raised threshold leaves every note below it" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = MpeNoteIdentity(1, C4)
    val second = MpeNoteIdentity(2, C5)
    val channel = alloc.allocate(first, Some(ImmutableMpeExpression(60))).channel
    alloc.allocate(MpeNoteIdentity(3, D4))
    alloc.allocate(second, Some(ImmutableMpeExpression(90))).channel shouldBe channel
    // When
    val result = alloc.setExpressionPitchBendThreshold(150)
    // Then
    // The pass only ever drops: nothing is restored, because nothing was retained.
    result shouldEqual MpeExpressionUpdateResult()
    alloc.activeNotes(channel) should contain theSameElementsAs Set(first, second)
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL — the module does not compile, `setExpressionPitchBendThreshold` being undefined. Proceed straight to
Step 3; the assertion-level red comes after it compiles.

- [ ] **Step 3: Add the setter to `MpeChannelAllocator`**

Add both members next to `expressionPitchBendThreshold` (the getter added in Task 1):

```scala
  /**
   * Assigns a new High Expression Pitch Bend threshold and re-applies the divergence rule to every occupied
   * channel, since the reclassification can leave a high-bend note sharing its channel — an invariant the paper's
   * "Summary of Note-Dropping Invariants" section requires at all times, so it cannot wait for the next event.
   *
   * Assignment and re-evaluation are one operation rather than a setter plus a separate pass: Scala requires an
   * assignment setter (`expressionPitchBendThreshold_=`) to return `Unit`, which would leave the caller no way to
   * receive the drops, and splitting the two would let a caller perform one without the other.
   *
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def setExpressionPitchBendThreshold(threshold: Int): MpeExpressionUpdateResult = {
    _expressionPitchBendThreshold = threshold
    reapplyDivergenceRule()
  }
```

and, next to the other private helpers (just below `applyDivergenceRule`):

```scala
  /**
   * Re-applies the divergence rule across every occupied channel, writing no new Expression Value. Reusing
   * [[updateExpressionValues]] with a no-op write gives the pass the same channel ordering — by the earliest
   * onset among a channel's notes — and the same "report only channels whose aggregate actually changed"
   * behaviour as an ordinary Expression Value update, so no second traversal and no second rule is written.
   */
  private def reapplyDivergenceRule(): MpeExpressionUpdateResult =
    updateExpressionValues(noteChannels.keys.toSeq, (_, _) => (), applyDivergenceRule)
```

Extend `applyDivergenceRule`'s ScalaDoc with the second way several notes can go high at once, replacing its last
paragraph:

```scala
   * Several notes can acquire a high bend at once in two ways. They may share an input channel, the Pitch Bend
   * being a channel message that belongs to all of them, in which case their bends are identical. Or the
   * threshold itself may move under them, a member Pitch Bend Sensitivity change reinterpreting every held bend
   * at once, in which case the co-residents may come from different input channels and carry genuinely different
   * bends. The resolution is the same either way: retaining the most recently sounded preserves the note the
   * performer is most likely still shaping, and leaving exactly one note restores the invariant that a high-bend
   * note is the sole note on its channel.
```

- [ ] **Step 4: Run the allocator tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Write the `MpeTuner` failing tests**

First declare the two note constants these tests need, next to the other note constants near the top of the class
(~line 68):

```scala
  private val C6: MidiNote = MidiNote(C5 + 12)
  private val Cs4: MidiNote = MidiNote(C4 + 1)
```

Then add a new subgroup to `behavior of "MpeTuner - PBS Processing - MPE Input"`, after
`// ---- Pitch-bend recomputation after PBS change ----` and before `// ---- Revert on reset ----`:

```scala
  // ---- Reclassification after a member PBS change ----

  it should "drop all but the latest-onset note when a member PBS change carries several notes on one channel " +
    "past the threshold" in new Fixture(tuner3MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Lower Zone with 3 Member Channels (PCG=1, EG=2). At ±2 semitones the threshold is 2048 raw. C4 takes the
      // Pitch Class Group channel, C5 and C3 the Expression Group ones, and C6 then shares the oldest channel —
      // channel 1 — with C4, from a different input channel.
      sendPbsMsb(tuner, channel = 1, semitones = 2)
      noteOn(1, C4)
      noteOn(2, C5)
      noteOn(3, C3)
      noteOn(2, C6)
      pitchBendValue(1, 500)
      pitchBendValue(2, 700)
      tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(2)

      // When
      // Widening the range to ±48 semitones lowers the threshold to 85 raw, so both notes on channel 1 cross it
      // at once — carrying different bends, having arrived on different input channels.
      private val output = sendPbsMsb(tuner, channel = 1, semitones = 48)

      // Then
      // The latest-onset note survives and the rest of the channel is dropped.
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(1, C4))
      // One recomputed Pitch Bend per occupied Member Channel, C having a zero tuning offset in quarter-comma
      // meantone: channel 1 now carries C6's bend alone.
      extractPitchBends(output) shouldEqual Seq(
        PitchBendScMidiMessage(1, 700),
        PitchBendScMidiMessage(2, 700),
        PitchBendScMidiMessage(3, 0))
    }

  it should "emit no CC #74 or Channel Pressure from a member PBS change that drops nothing" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given - A single note carrying both other control dimensions.
      noteOn(1, E4, pressure = Some(90), slide = Some(30))
      // When
      private val output = sendPbsMsb(tuner, channel = 1, semitones = 24)
      // Then
      // The sensitivity change moves neither dimension by itself, and no drop occurred, so only the recomputed
      // Pitch Bend goes out.
      extractSlides(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
      extractPitchBends(output) should have size 1
    }

  it should "emit the CC #74 and Channel Pressure a reclassification drop moved" in
    new Fixture(tuner3MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // As above, but the two co-residents of channel 1 carry different values in the other two dimensions, so
      // dropping one moves the channel's averages.
      sendPbsMsb(tuner, channel = 1, semitones = 2)
      noteOn(1, C4, pressure = Some(20), slide = Some(20))
      noteOn(2, C5)
      noteOn(3, C3)
      noteOn(2, C6, pressure = Some(100), slide = Some(100))
      pitchBendValue(1, 500)
      pitchBendValue(2, 700)

      // When
      private val output = sendPbsMsb(tuner, channel = 1, semitones = 48)

      // Then - Channel 1 keeps C6 alone, so both dimensions take its values.
      extractSlides(output) shouldEqual Seq(CcScMidiMessage(1, ScMidiCc.MpeSlide, 100))
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(1, 100))
    }

  it should "classify nothing as a High Expression Pitch Bend at a member PBS range no bend can exceed the " +
    "threshold in" in new Fixture(tuner3MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // At ±2 semitones the threshold is 2048 raw, so C4 and C5 coexist on channel 1 with bends below it. C#4
      // occupies a channel of its own, its -24-cent offset exceeding the degenerate range set below.
      sendPbsMsb(tuner, channel = 1, semitones = 2)
      noteOn(1, C4)
      noteOn(2, Cs4)
      noteOn(3, C3)
      noteOn(2, C5)
      pitchBendValue(1, 500)
      pitchBendValue(2, 700)

      // When
      // ±0 semitones 20 cents is a range in which no Pitch Bend value can deviate by more than the 50-cent
      // threshold, so the threshold becomes unreachable rather than a value the conversion would reject.
      sendPbsMsb(tuner, channel = 1, semitones = 0)
      private val output = sendPbsLsb(tuner, channel = 1, cents = 20)

      // Then
      // Nothing is reclassified and nothing throws: the tuning term is clamped into the degenerate range before
      // conversion, and the sum is clamped in raw units.
      tuner.zones.lower.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(0, 20)
      extractNoteOffs(output) shouldBe empty
      extractPitchBends(output) shouldEqual Seq(
        PitchBendScMidiMessage(1, 600),
        PitchBendScMidiMessage(2, -7492),
        PitchBendScMidiMessage(3, 0))
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The first and third cases fail on their Note Off / CC assertions (nothing is reclassified yet); the
second and fourth pass already. The suite compiles: every symbol they use exists.

- [ ] **Step 7: Add the emission helper, the drop reason and the `applyPbsUpdate` wiring**

In `MpeTuner`, add the new `DropReason` case at the end of the enum:

```scala
  /**
   * A member Pitch Bend Sensitivity change — an explicit Pitch Bend Sensitivity message, or the reset an MPE
   * Configuration Message performs — moved the High Expression Pitch Bend threshold and reclassified the note.
   */
  case OnMemberPbsChange extends DropReason("member Pitch Bend Sensitivity change reclassified the note")
```

Add the two helpers next to `emitExpressionUpdateResult`:

```scala
  /**
   * Re-derives a Zone's High Expression Pitch Bend threshold from its current member Pitch Bend Sensitivity, hands
   * it to the allocator — which re-applies the divergence rule as part of the assignment — and emits the result.
   *
   * Both triggers of a member sensitivity change go through here: an explicit Pitch Bend Sensitivity message, and
   * the reset an MPE Configuration Message performs, an MCM's reset being a member Pitch Bend Sensitivity change
   * like any other.
   */
  private def applyExpressionPitchBendThreshold(buffer: mutable.Buffer[MidiMessage],
                                                alloc: MpeChannelAllocator): Unit = {
    val threshold = expressionPitchBendThresholdOf(currentZone(alloc).memberPitchBendSensitivity)
    emitThresholdUpdateResult(buffer, alloc.setExpressionPitchBendThreshold(threshold), alloc)
  }

  /**
   * Emits the consequences of a member Pitch Bend Sensitivity change on one Zone, in the relative order the
   * paper's "Message Ordering" section gives the control dimensions after a Note Off: Note Offs, Pitch Bends,
   * CC #74, Channel Pressure.
   *
   * The Pitch Bend dimension of `result` is deliberately not emitted here. [[updateTuningOnZone]] re-emits one
   * Pitch Bend on every occupied Member Channel of the Zone, which both subsumes it and is required in its own
   * right, a sensitivity change re-encoding the tuning term on ''every'' occupied channel rather than only on the
   * ones a drop touched; emitting both would duplicate the message on exactly the channels that changed.
   *
   * CC #74 and Channel Pressure stay `diff`-driven and therefore conditional: the sensitivity change moves neither
   * by itself, but a drop removes a note's term from all three of its channel's averages, and notes sharing a
   * channel may come from different input channels with different values. When nothing is dropped the result is
   * empty and neither is emitted.
   */
  private def emitThresholdUpdateResult(buffer: mutable.Buffer[MidiMessage], result: MpeExpressionUpdateResult,
                                        alloc: MpeChannelAllocator): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, DropReason.OnMemberPbsChange))
    updateTuningOnZone(buffer, alloc)
    result.channelUpdates.foreach { channelUpdate =>
      emitSlide(buffer, channelUpdate.channel, channelUpdate.update)
      emitPressure(buffer, channelUpdate.channel, channelUpdate.update)
    }
  }
```

Replace the tail of `applyPbsUpdate`:

```scala
    // A member sensitivity change reinterprets every held Expression Pitch Bend at once, so it can turn sounding
    // notes into High Expression Pitch Bend notes with no note or Pitch Bend message arriving. Re-derive the
    // threshold and re-apply the divergence rule before the Zone's occupied channels are retuned. Master
    // sensitivity does not affect Member Channel interpretation, and in Non-MPE Input Mode all Pitch Bend
    // Sensitivity input is treated as master, so neither reaches this path.
    if (!isMaster) {
      val alloc = if (updatedZone.zoneType == MpeZoneType.Lower) lowerAllocator else upperAllocator
      alloc.foreach(applyExpressionPitchBendThreshold(buffer, _))
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#253] Re-apply the divergence rule when a member PBS change moves the threshold

The High Expression Pitch Bend threshold is a deviation in cents, so a member
Pitch Bend Sensitivity change reclassifies every sounding note in the Zone with
no note or Pitch Bend message arriving. Assigning the threshold and re-applying
the rule are one atomic call, so a caller cannot do one without the other.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: An MCM resets Pitch Bend Sensitivity and re-evaluates retained notes

Two related defects on the MCM path, both reachable only in MPE Input Mode (Design §6). The Zone shrunk by overlap
resolution keeps a sensitivity the receiver has dropped, and retained channels keep a Pitch Bend the receiver now reads
against a different range. The second is fixed as a side-effect of running Task 2's helper on the MCM path.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeZone.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeZoneTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`

**Interfaces:**
- Consumes: `MpeTuner.applyExpressionPitchBendThreshold` from Task 2; `MpeZone.DefaultMasterPitchBendSensitivity` and
  `MpeZone.DefaultMemberPitchBendSensitivity`.
- Produces: `MpeZone.withDefaultPitchBendSensitivities: MpeZone` (public, on the `MpeZone` case class).

- [ ] **Step 1: Write the failing test for `withDefaultPitchBendSensitivities`**

Append to the `behavior of "MpeZone"` section of `MpeZoneTest.scala`:

```scala
  it should "return the Zone with the specification's default Pitch Bend Sensitivities" in {
    // Given
    val zone = MpeZone(MpeZoneType.Upper, 4,
      masterPitchBendSensitivity = PitchBendSensitivity(12),
      memberPitchBendSensitivity = PitchBendSensitivity(24))
    // When
    val reset = zone.withDefaultPitchBendSensitivities
    // Then
    reset.zoneType shouldEqual MpeZoneType.Upper
    reset.memberCount shouldEqual 4
    reset.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
    reset.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
  }
```

Add `import org.calinburloiu.music.scmidi.PitchBendSensitivity` to the file if it is not already imported.

- [ ] **Step 2: Run it to verify it fails**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeZoneTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL — `withDefaultPitchBendSensitivities` is not a member of `MpeZone`.

- [ ] **Step 3: Add `withDefaultPitchBendSensitivities`**

In the `MpeZone` case class body, below the `require`:

```scala
  /**
   * The Zone as a receiver holds it after an MPE Configuration Message, which resets the addressed Zone's Pitch
   * Bend Sensitivity to the specification's defaults (MPE spec Section 2.4).
   *
   * This is deliberately not folded into `MpeZones.update`: that method is also how a Pitch Bend Sensitivity
   * message stores a sensitivity, and `MpeZones.apply` performs the same overlap shrink at construction with no
   * MCM on the wire, where preserving the sensitivity is correct. The reset belongs to the decision to emit an
   * MCM, which only [[MpeTuner]] makes.
   */
  def withDefaultPitchBendSensitivities: MpeZone = copy(
    masterPitchBendSensitivity = MpeZone.DefaultMasterPitchBendSensitivity,
    memberPitchBendSensitivity = MpeZone.DefaultMemberPitchBendSensitivity)
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeZoneTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Write the failing `MpeTunerTest` cases**

Add these to `behavior of "MpeTuner - MCM Processing - MPE Input"`, in the
`// ---- Effects on active notes / other state ----` subgroup, after the existing
`"reset PBS to defaults when MCM is received"` case:

```scala
  it should "reset the Pitch Bend Sensitivity of the Zone shrunk by overlap resolution" in
    new Fixture(dualZoneTunerMpeInput) {
      // Given
      // Lower Zone master 0, members 1..7; Upper Zone master 15, members 8..14. Custom sensitivities on the
      // Upper Zone, which the MCM below does not address.
      sendPbsMsb(tuner, channel = 15, semitones = 12)
      sendPbsMsb(tuner, channel = 8, semitones = 24)
      tuner.zones.upper.masterPitchBendSensitivity shouldEqual PitchBendSensitivity(12)
      tuner.zones.upper.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)

      // When
      // An MCM on the Lower Zone forces overlap resolution to shrink the Upper Zone to 4 Members, so the Tuner
      // re-emits the Upper Zone's MCM — which resets that Zone's Pitch Bend Sensitivity at the receiver.
      sendMcm(tuner, channel = 0, memberCount = 10)

      // Then - The model mirrors the reset the message it emitted performs.
      tuner.zones.upper.memberCount shouldEqual 4
      tuner.zones.upper.masterPitchBendSensitivity shouldEqual MpeZone.DefaultMasterPitchBendSensitivity
      tuner.zones.upper.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
    }

  it should "keep the Pitch Bend Sensitivity of a Zone the reconfiguration leaves alone" in
    new Fixture(dualZoneTunerMpeInput) {
      // Given
      sendPbsMsb(tuner, channel = 8, semitones = 24)
      // When
      // Shrinking the Lower Zone to 4 Members leaves the Upper Zone's 7 untouched, so no MCM is emitted for it
      // and the receiver's sensitivity for it stands.
      sendMcm(tuner, channel = 0, memberCount = 4)
      // Then
      tuner.zones.upper.memberCount shouldEqual 7
      tuner.zones.upper.memberPitchBendSensitivity shouldEqual PitchBendSensitivity(24)
    }

  it should "re-emit the Pitch Bend of a retained note against the sensitivity the MCM reset" in
    new Fixture(mpeTunerMpeInput, Some(quarterCommaMeantone)) {
      // Given
      // Member PBS narrowed to ±24 semitones, then E4 sounding on output Member Channel 2 at its -14-cent offset.
      sendPbsMsb(tuner, channel = 1, semitones = 24)
      private val noteOutput = noteOn(2, E4)
      extractNoteOns(noteOutput).head.channel shouldEqual 2
      extractPitchBends(noteOutput) shouldEqual Seq(
        PitchBendScMidiMessage(2, rawPitchBend(-14.0, PitchBendSensitivity(24))))

      // When
      // An MCM shrinks the Lower Zone to 7 Members. Channel 2 is retained, and the MCM resets the Zone's Pitch
      // Bend Sensitivity to the ±48-semitone default at the receiver.
      private val output = sendMcm(tuner, channel = 0, memberCount = 7)

      // Then
      // The retained channel is retuned, so its Pitch Bend still means -14 cents under the reset sensitivity.
      tuner.zones.lower.memberPitchBendSensitivity shouldEqual MpeZone.DefaultMemberPitchBendSensitivity
      extractPitchBends(output) shouldEqual Seq(PitchBendScMidiMessage(2, rawPitchBend(-14.0)))
    }

  it should "drop the co-residents of a note the MCM's Pitch Bend Sensitivity reset reclassifies as high-bend" in
    new Fixture(tuner3MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // At ±2 semitones the threshold is 2048 raw, so C4 and C5 coexist on output channel 1 with bends below it.
      sendPbsMsb(tuner, channel = 1, semitones = 2)
      noteOn(1, C4)
      noteOn(2, E4)
      noteOn(3, C3)
      noteOn(2, C5)
      pitchBendValue(1, 500)
      pitchBendValue(2, 700)

      // When
      // An MCM that changes no Zone boundary still resets the Zone's Pitch Bend Sensitivity to ±48 semitones,
      // lowering the threshold to 85 raw and carrying both notes on channel 1 past it.
      private val output = sendMcm(tuner, channel = 0, memberCount = 3)

      // Then
      // The latest-onset note survives, and its co-resident's Note Off follows the MCM rather than preceding it:
      // the MCM's own reset is what caused the reclassification, unlike the reconfiguration's own drops.
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(1, C4))
      private val messages = extractScMidiMessages(output)
      messages.indexOf(NoteOffScMidiMessage(1, C4)) should be >
        messages.indexOf(CcScMidiMessage(0, ScMidiCc.DataEntryMsb, 3))
    }
```

Then extend four existing cases in the same block, which now see the retuning of retained channels:

1. `"stop the notes of channels leaving MPE control when an MCM shrinks a Zone"` (~line 2990) — append to `// Then`:

```scala
      // The retained channel is retuned against the Zone's reset Pitch Bend Sensitivity.
      extractPitchBends(output).map(_.channel) shouldEqual Seq(keptChannel)
```

2. `"keep the notes of channels untouched by the reconfiguration sounding and tunable"` (~line 3012) — capture the MCM
   output and assert on it:

```scala
      // When
      private val mcmOutput = sendMcm(tuner, channel = 0, memberCount = 7)
      // Then
      // The retained channel is retuned by the MCM itself, the reset sensitivity re-encoding its Pitch Bend.
      extractPitchBends(mcmOutput).map(_.channel) shouldEqual Seq(2)
```

   (keep the rest of the existing `// Then` block unchanged).

3. `"drop a note whose input channel leaves MPE control even when its output channel is retained"` (~line 3026) —
   append to `// Then`:

```scala
      // Nothing is left occupied, so the retuning pass emits nothing.
      extractPitchBends(mcmOutput) shouldBe empty
```

4. `"keep an Upper Zone note when an MCM enables the Lower Zone, leaving the Upper Zone untouched"` (~line 3047) —
   append to `// Then`:

```scala
      // The Upper Zone's retained channel is retuned all the same: the pass runs on both Zones' allocators.
      extractPitchBends(mcmOutput).map(_.channel) shouldEqual Seq(outChannel)
```

- [ ] **Step 6: Run them to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL — the sensitivity of the shrunk Zone is still 24, no Pitch Bend is emitted for retained channels, and
no reclassification drop occurs.

- [ ] **Step 7: Reset the other Zone's sensitivity and run the pass on the MCM path**

In `MpeTuner.processMcm`, replace the "Forward MCM for the other zone" block:

```scala
    // Forward MCM for the other zone only if it was changed by overlap resolution
    val otherZoneAfter = if (channel == 0) upperZone else lowerZone
    if (otherZoneAfter != otherZoneBefore) {
      val otherZoneType = if (channel == 0) MpeZoneType.Upper else MpeZoneType.Lower
      // The MCM about to be emitted resets that Zone's Pitch Bend Sensitivity at the receiver (MPE spec Section
      // 2.4), so the model mirrors it. `MpeZones.update` preserves the sensitivity of a Zone that overlap
      // resolution shrank, which is right at construction and when `applyPbsUpdate` stores one; the reset belongs
      // to the decision to emit, and only this method makes it. Re-applying the Zone cannot disturb the one just
      // configured: `zonesAfter` is already non-overlapping, so `wouldOverlap` is false and no shrink retriggers.
      val resetOtherZone = otherZoneAfter.withDefaultPitchBendSensitivities
      _zones = _zones.update(resetOtherZone)
      logger.info(s"$otherZoneType zone adjusted by overlap resolution: $resetOtherZone")
      emitMcmSequence(buffer, resetOtherZone)
    }

    // Reclassify each Zone's retained notes against its Pitch Bend Sensitivity and retune the channels that kept
    // them: the receiver has just reset its own sensitivity for every Zone whose MCM went out, so a retained
    // channel's Pitch Bend would otherwise be read against the wrong range, and the threshold has moved under its
    // notes. Lower before Upper, as `tune()` already orders them. An allocator built fresh by `createAllocator`
    // already holds the right threshold and holds no notes, so its call emits nothing and needs no condition.
    Seq(lowerAllocator, upperAllocator).flatten.foreach(applyExpressionPitchBendThreshold(buffer, _))

    // Switch to MPE input mode
    _inputMode = MpeInputMode.Mpe
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeZone.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeZoneTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "[#253] Reset Pitch Bend Sensitivity and retune retained channels on an MCM

An MCM resets the Pitch Bend Sensitivity of every Zone whose MCM the Tuner
emits, the Zone shrunk by overlap resolution included, so the model no longer
encodes against a sensitivity the receiver has dropped. Channels the
reconfiguration retained are reclassified against the new threshold and
retuned, with the reclassification drops emitted after the MCM.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Documentation

The behaviour being dropped was never specified in the paper (Design §9), so only two sections change. The paper edits
build on the uncommitted Section 4.2 split already in the working tree — read the section before editing and do not
revert it.

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md`
- Modify: `docs/architecture/tuner/README.md`

- [ ] **Step 1: Extend the paper's "Divergence on a Shared Channel" (Section 6.2.1)**

Insert this paragraph after the existing second paragraph (the one beginning "Several notes on a shared channel may
acquire a High Expression Pitch Bend from the *same* Pitch Bend message"), and before Section 6.2.2:

```markdown
Several notes may also cross the threshold with no Pitch Bend message arriving at all. A change to the Member Channel
Pitch Bend Sensitivity — including the reset an MCM performs (Section 4.2) — reinterprets every held bend at once: `t`
is an absolute pitch deviation (Section 5.5), and a held Pitch Bend value is reinterpreted rather than rescaled when
the range changes, so widening the range raises the deviation every held bend represents and can carry several notes
past `t` simultaneously. Unlike the same-input-channel case, the notes crossing together may carry *different* bends,
having arrived on different input channels. The resolution is unchanged: the Tuner retains the note with the latest
onset and drops the rest of the channel, leaving exactly one active note and restoring invariant 2 of Section 6.3. The
justification differs, there being no single bending gesture to protect — only that the latest-onset note is the one
the performer is most likely still shaping.
```

Leave the opening of Section 6 ("Dropping arises in two circumstances") as it stands: a Pitch Bend Sensitivity change
is a new trigger for an existing circumstance, not a third circumstance.

- [ ] **Step 2: Extend the paper's "Zones" (Section 4.2)**

Insert this paragraph immediately after the paragraph that ends "Channels of a Zone untouched by the reconfiguration
keep their notes and state." — that is, between it and the "A note counts as affected…" paragraph the uncommitted edit
introduced:

```markdown
The Pitch Bend Sensitivity reset has a different scope from the rest of that state. It applies to each Zone whose MCM
the Tuner emits — the addressed Zone, and the other Zone when overlap resolution changed it — rather than to the set
of channels entering or leaving MPE control, because that is the scope the emitted MCM has at the receiver [1, §2.4].
A channel the reconfiguration left untouched therefore keeps its notes and state while the range its Pitch Bend is
read against changes underneath them, with two consequences. Its Pitch Bend is re-emitted, encoded against the new
sensitivity, so the notes it carries keep sounding at the pitch they had. And its notes are reclassified against the
High Expression Pitch Bend threshold, which is a deviation in cents (Section 5.5) and so moves with the sensitivity;
the notes this reclassification drops are dropped *after* the MCM, the MCM's own reset being what caused it, unlike
the reconfiguration's own drops, whose Note Offs precede it.
```

- [ ] **Step 3: Update the `tuner` architecture README**

In `docs/architecture/tuner/README.md`, delete this bullet from the "Subject to change" list:

```markdown
- `MpeTuner` seeds a new note's Expression Pitch Bend by re-deriving cents from the input channel's raw Pitch Bend
  under the Zone's current member Pitch Bend Sensitivity, so after a member PBS change that the raw value predates, the
  seeded cents disagree with the cents retained for already-active notes (TODO #253).
```

In the `MpeTuner` paragraph of "Key types", extend the `MpeChannelAllocator` sentence. Replace:

```markdown
  `MpeChannelAllocator` owns both note→channel allocation and the per-note *Expression Value* model — `MpeNoteIdentity`,
  reference counting, the per-channel aggregate and its retention, and the change reporting `MpeTuner` emits from.
```

with:

```markdown
  `MpeChannelAllocator` owns both note→channel allocation and the per-note *Expression Value* model — `MpeNoteIdentity`,
  reference counting, the per-channel aggregate and its retention, and the change reporting `MpeTuner` emits from.
  Expression Pitch Bend is held in raw signed 14-bit units, exactly as received, and is reinterpreted rather than
  rescaled when the Member Channel Pitch Bend Sensitivity changes; the allocator classifies a High Expression Pitch
  Bend against a raw threshold `MpeTuner` injects through the constructor and re-injects through
  `setExpressionPitchBendThreshold`, which re-applies the divergence rule as part of the assignment. `MpeTuner` is
  therefore the only component that knows either cents or `PitchBendSensitivity`.
```

- [ ] **Step 4: Verify no other doc still describes the old behaviour**

Run: `rtk proxy grep -rn "253\|pitchBendCents" docs/ tuner/src/`
Expected: no hits in `docs/` and none in `tuner/src/`. If `TODO #253` still appears anywhere, remove it.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md docs/architecture/tuner/README.md
git commit -m "[#253] Document the raw Expression Pitch Bend and the MCM sensitivity reset

The paper's divergence rule now covers notes crossing the threshold because the
threshold moved, and its Zones section records that the Pitch Bend Sensitivity
reset is scoped to each Zone whose MCM is emitted rather than to the affected
channels.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Final checks

The four checks `CLAUDE.md` requires when an implementation is done. Nothing here changes behaviour; if a check fails,
fix it under the same red/green discipline and re-run all of them.

**Files:**
- Modify (only if a check demands it): the `tuner` sources and tests already touched.

- [ ] **Step 1: Module tests**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow its policy. Check the five files this change touched:
`MpeExpression.scala`, `MpeChannelState.scala`, `MpeChannelAllocator.scala`, `MpeZone.scala`, `MpeTuner.scala`.

Expected: the `tuner` module stays at or above its `coverageSettings(stmt = 80, branch = 80)` floor and does not drop
below whatever it measured before this change. If a branch introduced here is uncovered, the likely gaps are
`UnreachableExpressionPitchBendThreshold` (covered by the degenerate-range test in Task 2), the
`otherZoneAfter != otherZoneBefore` reset branch (covered by both Task 3 Zone tests), and the empty-result path of
`emitThresholdUpdateResult` (covered by the "drops nothing" test). Add a test rather than lowering the floor.

- [ ] **Step 3: Full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS across every module. `MpeExpression` and `MpeChannelAllocator` are `private[tuner]`, so no other module
can be affected — but `format` references `MpeTuner`, `MpeZone*` and `MpeInputMode`, and `MpeZone` gained a member, so
run it.

- [ ] **Step 4: Walk the acceptance criteria**

Open Design §10 and check each of its fourteen boxes against the working tree. Every one maps to a step above; if any
cannot be ticked, the gap is a missing step in this plan, not an optional item.

- [ ] **Step 5: Open the pull request**

Invoke the `contributing` skill and open a PR against `main` for issue #253, prefixed `[#253]`.

---

## Notes for the executor

- **`reapplyDivergenceRule` iterates a snapshot.** `noteChannels.keys.toSeq` is materialized strictly before
  `updateExpressionValues` groups by channel, and a drop only removes identities from the channel being processed, so
  the traversal cannot be invalidated mid-pass. Do not "optimize" it into a lazy view.
- **Ordering inside `processMcm` is load-bearing.** `stopNotesOn` must stay first, while the old Zone structure holds;
  the other Zone's sensitivity reset must happen before the reclassification pass, so that pass reads the reset value
  through `currentZone`; and the pass must precede `_inputMode = MpeInputMode.Mpe` only incidentally — it reads
  `_zones`, not the mode.
- **`applyPbsUpdate` already updated `_zones` before the pass runs**, so `currentZone(alloc)` inside
  `applyExpressionPitchBendThreshold` sees the new sensitivity. Do not reorder.
- **Golden values used above**, all at the sensitivities named: 25 cents at ±48 is raw 43; 293 cents at ±48 is raw 500;
  −14 cents is raw −24 at ±48 and −48 at ±24; the threshold is raw 85 at ±48, 171 at ±24, 2048 at ±2, and the
  unreachable 8192 at any range of 50 cents or less. Recompute rather than guess if a test needs a new one.
- **Design §7's equivalence claim** — that a single-note channel emits exactly what it emitted before — is a property
  of the arithmetic, not something a test pins directly. The existing `MpeTunerTest` cases that assert emitted Pitch
  Bend values on single-note channels are what would catch a regression in it, which is why none of them are rewritten.

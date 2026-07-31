# Polyphonic Expression for the MPE Tuner — Implementation Plan (Cycle 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date**: 2026-07-31
- **Issue**: [#154](https://github.com/calinburloiu/microtonalist/issues/154)
- **Design (the spec)**: [`2026-07-30-poly-expression-design.md`](2026-07-30-poly-expression-design.md)
- **Source of truth**: [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md)
- **Base commit**: `a076e43` (working tree of branch `doc/mpe-tuner-poly-expr-code-design`)

**Goal:** Implement the paper's per-note Expression Value model in the MPE Tuner — Note Identity, reference counting,
three-dimension aggregation on output Member Channels, fan-out of input-channel control updates, and the emission
rules of §7.5 — closing gaps P1–P5, N1, N2, N5, B1, C1 and C2.

**Architecture:** `MpeChannelAllocator` becomes input-channel-aware and owns the whole Expression Value model: its
`ChannelState` is keyed by `NoteIdentity(inputChannel, midiNote)`, each identity carries a reference count and its own
`MpeExpression`, and each channel carries an aggregate (the average over its active identities, retained when the
channel empties). Every mutating method reports which of the channel's three Expression Values changed, so `MpeTuner`
— which stays the only component aware of the input mode — decides what to emit and in which order. `MpeTuner` loses
`channelNoteMap`, `trackNote`, `untrackNote`, `outputChannelsFor` and `forwardToMemberChannel`; note bookkeeping moves
to the allocator and Master Channel notes are recovered from `ScMidiChannelStateTracker`.

**Tech Stack:** Scala 3, sbt 1 driven through `sbtn` on the shared BSP server, Metals MCP for compiling and code
intelligence, ScalaTest (`AnyFlatSpec` + `Matchers`), `sc-midi` message model.

**Throughout this document, `§` refers to a section of the MPE Tuner paper.** Sections of the design document are
referred to by name.

---

## Global Constraints

- **Session warm-up**, once before the first task: check the dev stack with `bin/microtonalist-dev-stack status`
  (exit 0 = running; otherwise follow [`docs/agents/dev-stack.md`](../../../docs/agents/dev-stack.md)), then warm the
  Metals index with `mcp__metals__compile-full` so SemanticDB is populated for symbol resolution and find-usages.
- **Compiling:** use the Metals MCP — `mcp__metals__compile-module` with `module = "tuner"`, or
  `mcp__metals__compile-full` for the whole project. Fall back to `sbtn "tuner/Test/compile"` only when the Metals MCP
  is unavailable.
- **Testing:** Metals cannot run tests with this BSP setup, so route every sbt invocation through `sbtn` and always
  append the ScalaTest reporter flags:
    - this module: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
    - one suite: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"`
    - whole project: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`

  Do **not** run coverage tasks while iterating on a task: coverage is verified once, in Task 10, through the
  `scoverage-inspector` skill, which carries the policy and the thresholds.
- **Code intelligence:** prefer the Metals MCP (`mcp__metals__inspect`, `get-usages`, `glob-search`, `get-docs`,
  `get-source`) over `grep`/`rg` for anything symbol-shaped — inspecting a type, finding its usages, checking that a
  symbol has no remaining references. Symbol tools need a `fileInFocus`; for this plan use
  `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`. Plain text searches (a word in a
  comment, a marker) stay with the `Grep` tool.
- **Language/build:** Scala 3, sbt 1. Line length: 120 columns (match the surrounding files).
- **Conventions:** production code follows
  [`coding-conventions.md`](../../../docs/development/coding-conventions.md)
  — brace syntax, `enum` over sealed traits, no `case class` for mutable state, no `new` when instantiating, no
  `return` (use `boundary.break`), `_`-prefixed backing fields, `// TODO #<issue>` — and every public identifier gets a
  ScalaDoc. Tests follow [`docs/development/test-conventions.md`](../../../docs/development/test-conventions.md):
  `AnyFlatSpec` + `Matchers`, `behavior of` grouping, `// Given` / `// When` / `// Then` comments, fixtures instead of
  duplicated setup, and no `if`s. `MpeTunerTest`'s own ScalaDoc governs which `behavior of` block and subgroup a test
  belongs to and takes precedence over the general guidance; every snippet below states its placement. The
  `it should "…" in new Fixture { … }` idiom keeps its `new` — a template body requires it, and both suites are
  written that way throughout; the "avoid `new`" convention applies to ordinary instantiation.
- **License headers:** never write the Apache header by hand in a `.scala` file — `.githooks/pre-commit` adds it. The
  `Read` hook hides it without renumbering, so every line number in this plan is a real one.
- **No git worktrees.** Switch branches in place with `git switch`; this repo's BSP/Metals/sbt stack is shared.
- **TDD, strictly red/green/refactor:**
    - **Red.** Write the failing tests first. The failure must be an *assertion* failure, never a compile error: when
      the tests need an API that does not exist yet, add the thinnest possible stub (`???` bodies, no logic) until the
      module compiles, then run the suite and confirm it fails for the intended reason. Each task states its expected
      red state.
    - **Green.** Write only enough production code to pass, no more.
    - **Refactor.** With the suite green, improve structure and naming; never mix a refactor with a behavioral change,
      and never commit red production code.
- **Commits:** frequent, one per task step group, message prefix `[#154] `, and every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- **Files under `issues/`:** only those explicitly named by a task may be read or written — the design and this plan in
  Task 1, `code-prompt.md` and the new cycle-2 prompt in Task 12. Do not load any other file from that directory: the
  rest are unrevised drafts and stale reports.
- **Terminology:** the code says *Expression Pitch Bend* (not "expressive"), *Expression Value*, *Note Identity*,
  matching §1.3 and §5.1.
- **Scope fence:** cycle 1 is the Expression Value model only. Do **not** touch RPN/NRPN routing, message
  filtering/discarding, out-of-zone handling, MCM reset scope, or MIDI Mode messages — gaps P7, C3, C4, C5, C6, N4,
  I1, I2, I3 belong to cycle 2.

## Deviations from the design document

The design's *Testing* section lists twelve ignored tests and says eleven activate "unchanged". The file actually holds
**fifteen** `ignore` declarations (the design's table counts its four-row high-bend truth table as one entry), five of
them need edits, and five currently-green tests must be rewritten. This is a correction of the design's bookkeeping
about tests, not of any behavior it specifies — the design's model wins, and these are its consequences.

| Test | Why it cannot activate unchanged |
|---|---|
| `MpeTunerTest.scala:1052` | Expects `-17` cents from an input bend of `-16.67`, but a Pitch Bend unit at ±48 semitones is ≈0.586 cents, so the value quantizes to `-16.41` and rounds to `-16`. The test is retargeted at `-20.0`, which quantizes to `-19.92` and rounds to `-20`. |
| `:1470` | Sends `pressure = Some(16)` where it means `slide = Some(16)`; its expected `(48 + 16) / 2` is only reachable with the slide. |
| `:1497`, `:1513`, `:1530` | Their *first* assertion block ignores the co-resident `D5` note that `DistributeFixture` places on `output1Channel`. Under §7.1 averaging the value on that channel is the average of two notes, not the updated note's raw value. Their *second* block is already correct. |

The suite's `epsilon` for tolerant `Double` equality (`MpeTunerTest.scala:73`) also has to grow from `2e-1` to `6e-1`.
Every emitted Pitch Bend is quantized to a 14-bit value, one unit being ≈0.586 cents at the default Member Channel
Pitch Bend Sensitivity of ±48 semitones, and averaging two quantized inputs puts the result up to half a unit from
the arithmetic expectation. A tolerance below one unit therefore makes cents assertions depend on quantization luck —
`:1497` and `:1421` both land 0.24–0.25 cents from their expected values with an exact implementation. Loosening a
tolerance cannot break a passing `shouldEqual`; Task 2 checks that no test asserts *in*equality between cents values.

| Currently green test | Why it must change |
|---|---|
| `:352` "clear internal state after reset" (MPE block) | Asserts a Channel Pressure and a CC #74 message that §7.5's emission optimization now omits (both already hold their default). Also constructs a Non-MPE tuner inside the MPE block. |
| `:588` "output Pitch Bend, CC #74, Channel Pressure, then Note On" (Non-MPE) | C1: CC #74 never reaches a Member Channel in Non-MPE Input Mode, and Channel Pressure 0 is unchanged so it is omitted. |
| `:658` "initialize member channel CC #74 to default 64…" | C1: the message is no longer emitted at all. |
| `:672` "initialize member channel Channel Pressure to default 0…" | Unchanged value ⇒ omitted (§7.1/§7.5). |
| `:798` "output Pitch Bend, CC #74, Channel Pressure, then Note On" (MPE) | With a default input channel all three dimensions are unchanged, so only Pitch Bend is emitted; the §7.5 ordering must be exercised with non-default input state. |

## File Structure

| File | Responsibility after this plan |
|---|---|
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala` | Note Identity, reference counting, per-note Expression Values, per-channel aggregation and retention, allocation/dropping, the three update methods and the divergence rule. Input-mode-unaware. |
| `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` | Input-mode decisions, seeding from `ScMidiChannelStateTracker`, message emission and ordering, Master Channel forwarding, the Non-MPE-with-both-Zones warning. |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala` | Allocator-level unit tests. |
| `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` | End-to-end message-stream tests, organized per its own ScalaDoc. |
| `docs/architecture/tuner/mpe-tuner-paper.md` | §6.2.1 gains the simultaneous-high-bend note. |
| `docs/architecture/tuner/README.md` | "Subject to change" TODO #154 bullet refreshed. |

## Task Map

**Phase 1 — code.** Tasks 1–11. Execution **stops** after Task 11 (cycle-1 PR opened) for the author's review and merge.

| Task | Deliverable |
|---|---|
| 1 | Design + plan docs merged to `main`; `feature/mpe-tuner-poly-expr` branched from it; architecture explored. **Checkpoint.** |
| 2 | `MpeChannelAllocator` rewritten (identity, reference counts, aggregation, updates, divergence); `MpeTuner` adapted mechanically; whole suite green. |
| 3 | `MpeTuner` state ownership: `channelNoteMap` removed; Note On/Off, `stopAllNotes`, Poly Pressure and CC #74 / Channel Pressure go through the allocator. |
| 4 | Note On: seeding from the tracker, §7.5 ordering and emission optimization, C1. |
| 5 | Note Off: recomputation, §7.5 inverted ordering, §7.4 Channel Pressure reset, §5.1 rule 4 discard. |
| 6 | Duplicate Note On / reference counting end to end (§9.6 Parts 1 and 2) and N5. |
| 7 | Non-MPE-with-both-Zones warning. |
| 8 | Paper §6.2.1 amendment and `docs/architecture/tuner/README.md` TODO refresh. |
| 9 | Worked examples §9.3 and §9.5 as end-to-end tests. |
| 10 | Final checks: module tests, coverage, full test suite, documentation — one task each. |
| 11 | Cycle-1 pull request. |

**Phase 2 — hand-off.** Task 12, executed **only after** the cycle-1 PR is merged into `main`.

---

## Task 1: Land the design and plan documents on `main`

**Files:**
- Modify: `issues/00154-mpe-tuner-poly-expr/code/2026-07-30-poly-expression-design.md` (already modified in the working
  tree — commit as is)
- Create (already written): `issues/00154-mpe-tuner-poly-expr/code/2026-07-31-poly-expression-plan.md`

**Interfaces:**
- Produces: branch `feature/mpe-tuner-poly-expr`, cut from a `main` that contains the design and this plan.

- [ ] **Step 1: Verify the working tree holds only the two documents**

```bash
git status --short
```
Expected: only `issues/00154-mpe-tuner-poly-expr/code/2026-07-30-poly-expression-design.md` (modified) and
`issues/00154-mpe-tuner-poly-expr/code/2026-07-31-poly-expression-plan.md` (untracked). If anything else appears, stop
and ask the author.

- [ ] **Step 2: Commit both documents on the current `doc/` branch**

```bash
git add issues/00154-mpe-tuner-poly-expr/code/2026-07-30-poly-expression-design.md \
        issues/00154-mpe-tuner-poly-expr/code/2026-07-31-poly-expression-plan.md
git commit -m "$(cat <<'EOF'
[#154] Add cycle-1 implementation plan for MPE Tuner polyphonic expression

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Open the documentation pull request**

Invoke the `contributing` skill, then:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 154 \
  "Design and cycle-1 plan for MPE Tuner polyphonic expression"
```

The branch prefix `doc/` supplies the label; the milestone is inherited from issue #154.

- [ ] **Step 4: CHECKPOINT — stop and wait for the author to review and merge this PR**

Do not proceed to Step 5 until the author confirms the PR is merged into `main`.

- [ ] **Step 5: Branch the feature branch off the refreshed `main`**

```bash
git switch main
git pull
git log --oneline -1        # must show the merge of the doc PR
git switch -c feature/mpe-tuner-poly-expr
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"   # baseline: green, with 15 ignored tests
```
Expected: all tests pass; ScalaTest reports 15 ignored.

- [ ] **Step 6: Explore the architecture before any code is written**

Create a task that reads, and reports back on, the documents relevant to this work — the module whose code changes and
its immediate collaborators:

- [`docs/architecture/tuner/README.md`](../../../docs/architecture/tuner/README.md) — the module's architecture, and
  the "Subject to change" entry Task 8 refreshes.
- [`docs/architecture/tuner/mpe-spec.md`](../../../docs/architecture/tuner/mpe-spec.md) — the MPE tuning topic doc.
- [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md) — the source of
  truth for every behavior in this plan; §5.1, §6.2, §6.3, §7.1–7.5 and the worked examples of §9 are the sections the
  tasks below cite.
- `docs/architecture/sc-midi/README.md` — the collaborator supplying the message model and
  `ScMidiChannelStateTracker`, which Task 4 seeds Expression Values from.

The always-loaded overviews (`module-overview.md`, `domain-concepts.md`, `data-flow.md`) are already in context and
need no task of their own.

---

## Task 2: `MpeChannelAllocator` — Note Identity, reference counting, aggregation, updates

This is the core rewrite. `MpeTuner` is adapted only as far as the new allocator API forces, so that the module keeps
compiling and the whole suite stays green; its own behavior changes land in Tasks 3–6.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala` (whole file)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:221-235,257-273,275-310,635-659`
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala` (whole file)
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala:1497,1758,1802`

**Interfaces:**
- Produces (public allocator API used by every later task):
  ```scala
  case class NoteIdentity(inputChannel: Int, midiNote: MidiNote)
  case class MpeExpressionUpdate(pitchBendCents: Option[Double] = None, pressure: Option[Int] = None,
                                 slide: Option[Int] = None)
  object MpeExpressionUpdate { val Unchanged: MpeExpressionUpdate }
  case class ChannelExpressionUpdate(channel: Int, update: MpeExpressionUpdate)
  case class DroppedNote(noteIdentity: NoteIdentity, referenceCount: Int)
  case class DroppedNotes(channel: Int, notes: Seq[DroppedNote], group: ChannelGroup)
  case class AllocationResult(channel: Int, update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                              droppedNotes: Option[DroppedNotes] = None, isDuplicate: Boolean = false)
  case class ReleaseResult(channel: Int, update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                           pressureWasReset: Boolean = false)
  case class ExpressionUpdateResult(channelUpdates: Seq[ChannelExpressionUpdate] = Nil,
                                    droppedNotes: Seq[DroppedNotes] = Nil)
  case class ImmutableMpeExpression(pitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                                    pressure: Int = MpeExpression.DefaultPressure,
                                    slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

  class MpeChannelAllocator(zone: MpeZoneStructure) {
    def zoneType: MpeZoneType
    def allocate(noteIdentity: NoteIdentity, expression: Option[MpeExpression] = None,
                 preferredChannel: Option[Int] = None): AllocationResult
    def release(noteIdentity: NoteIdentity, resetPressureOnEmpty: Boolean = false): Option[ReleaseResult]
    def updateExpressionPitchBend(inputChannel: Int, pitchBendCents: Double): ExpressionUpdateResult
    def updatePressure(inputChannel: Int, pressure: Int): ExpressionUpdateResult
    def updatePressure(noteIdentity: NoteIdentity, pressure: Int): ExpressionUpdateResult
    def updateSlide(inputChannel: Int, slide: Int): ExpressionUpdateResult
    def reset(): Unit
    def channelOf(noteIdentity: NoteIdentity): Option[Int]
    def channelExpression(channel: Int): MpeExpression
    def expressionFor(noteIdentity: NoteIdentity): MpeExpression
    def referenceCountOf(noteIdentity: NoteIdentity): Int
    def activeNotes(channel: Int): Set[NoteIdentity]
    def activeAllocations: Seq[(NoteIdentity, Int)]
    def channelPitchClass(channel: Int): Option[PitchClass]
    def activeChannelCount: Int
    def isChannelOccupied(channel: Int): Boolean
    def channelGroupOf(channel: Int): Option[ChannelGroup]
  }
  ```

### Step group A — migrate the allocator test suite to the new API (red)

- [ ] **Step 1: Add the test mixins and helpers to `MpeChannelAllocatorTest`**

Change the class declaration and add the helpers right after the `highPitchBendCents` / `lowPitchBendCents` values:

```scala
import org.scalatest.OptionValues

class MpeChannelAllocatorTest extends AnyFlatSpec with Matchers with OptionValues {
```

```scala
  /**
   * Note-centric shorthands for the call sites this suite was originally written against. Each note is
   * allocated as the identity `(inputChannel, midiNote)`; the input channel defaults to 0 and is passed
   * explicitly only where a test later addresses that note through an update method.
   */
  extension (alloc: MpeChannelAllocator) {
    private def allocateNote(midiNote: MidiNote,
                             expressionPitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                             preferredChannel: Option[Int] = None,
                             inputChannel: Int = 0): AllocationResult =
      alloc.allocate(NoteIdentity(inputChannel, midiNote),
        Some(ImmutableMpeExpression(expressionPitchBendCents)), preferredChannel)

    private def releaseNote(midiNote: MidiNote, inputChannel: Int = 0): Option[ReleaseResult] =
      alloc.release(NoteIdentity(inputChannel, midiNote))

    private def activeMidiNotes(channel: Int): Set[MidiNote] = alloc.activeNotes(channel).map(_.midiNote)
  }
```

Replace the `assertDroppedNotes` helper with:

```scala
  private def assertDroppedNotes(droppedNotes: Option[DroppedNotes], expectedNotes: Seq[MidiNote]): Unit = {
    droppedNotes should not be empty
    droppedNotes.get.notes.map(_.noteIdentity.midiNote) should contain theSameElementsAs expectedNotes
  }
```

- [ ] **Step 2: Mechanically migrate every existing call site**

Apply these four substitutions across the file (~50 sites), leaving every assertion's *meaning* intact:

| Old | New |
|---|---|
| `alloc.allocate(N)` | `alloc.allocateNote(N)` |
| `alloc.allocate(N, expressivePitchBendCents = X)` | `alloc.allocateNote(N, X)` |
| `alloc.allocate(N, preferredChannel = Some(C))` | `alloc.allocateNote(N, preferredChannel = Some(C))` |
| `alloc.release(N, ch)` | `alloc.releaseNote(N)` |
| `alloc.activeNotes(ch) should contain theSameElementsAs Set(N, …)` | `alloc.activeMidiNotes(ch) should contain theSameElementsAs Set(N, …)` |
| `alloc.activeNotes(ch).size` | `alloc.activeNotes(ch).size` (unchanged) |

The five `updateExpressivePitchBend` call sites need the bent note on an input channel of its own, because the update
is now addressed by *input* channel. Rewrite them as follows.

`"prefer channel without high expressive pitch bend when sharing"` (was line 384):

```scala
  it should "prefer channel without high expression pitch bend when sharing" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val r1 = alloc.allocateNote(C4, inputChannel = 1)
    val r2 = alloc.allocateNote(C5, inputChannel = 2)
    // Both notes are pitch class C on distinct channels; r1 then develops a high bend.
    r1.channel should not equal r2.channel
    alloc.channelPitchClass(r1.channel) shouldBe Some(PitchClass.C)
    alloc.channelPitchClass(r2.channel) shouldBe Some(PitchClass.C)
    alloc.updateExpressionPitchBend(1, highPitchBendCents)
    // When
    // The third C must share; it should avoid the high-bend r1 and share r2.
    val r3 = alloc.allocateNote(C3, inputChannel = 3)
    // Then
    r3.channel shouldBe r2.channel
  }
```

`"free the channel without a high expressive pitch bend among freeing candidates"` (was line 539):

```scala
  it should "free the channel without a high expression pitch bend among freeing candidates" in {
    // Given
    val alloc = allocator4 // PCG=2, EG=2, channels 1..4
    alloc.allocateNote(C4, inputChannel = 1) // lowest (boundary)
    alloc.allocateNote(E4, inputChannel = 2) // candidate, will get a high bend
    alloc.allocateNote(G4, inputChannel = 3) // candidate, no bend
    alloc.allocateNote(B4, inputChannel = 4) // highest (boundary)
    alloc.updateExpressionPitchBend(2, highPitchBendCents) // E4's channel: high bend
    // When
    val result = alloc.allocateNote(A4, inputChannel = 5) // new pitch class -> free a channel
    // Then
    // Criterion (a): avoid freeing the high-bend channel (E4); free the no-bend channel (G4).
    assertDroppedNotes(result.droppedNotes, Seq(G4))
  }
```

`"drop other notes when a note on a shared channel develops high expressive pitch bend"` (was line 567),
`"not drop notes when expressive pitch bend is below threshold"` (was 584) and `"ensure a note with high expressive
pitch bend is always sole note on its channel"` (was 647) follow the same shape — allocate C4/C5/C3/C6 on input
channels 1/2/3/4 and address the update to input channel 4 (C6's), which is the note that shares:

```scala
  it should "drop other notes when a note on a shared channel develops a high expression pitch bend" in {
    // Given
    val alloc = allocator3 // PCG=1, EG=2
    alloc.allocateNote(C4, inputChannel = 1)
    alloc.allocateNote(C5, inputChannel = 2)
    alloc.allocateNote(C3, inputChannel = 3)
    // All channels have C. Add another C to share.
    val r4 = alloc.allocateNote(C6, inputChannel = 4)
    val sharedChannel = r4.channel
    // When
    val result = alloc.updateExpressionPitchBend(4, highPitchBendCents)
    // Then
    result.droppedNotes should not be empty
    alloc.activeMidiNotes(sharedChannel) should contain theSameElementsAs Set(C6)
  }

  it should "not drop notes when the expression pitch bend is below the threshold" in {
    // Given
    val alloc = allocator3
    alloc.allocateNote(C4, inputChannel = 1)
    alloc.allocateNote(C5, inputChannel = 2)
    alloc.allocateNote(C3, inputChannel = 3)
    alloc.allocateNote(C6, inputChannel = 4)
    // When
    val result = alloc.updateExpressionPitchBend(4, lowPitchBendCents)
    // Then
    result.droppedNotes shouldBe empty
  }
```

Rename `"…expressive pitch bend…"` to `"…expression pitch bend…"` in every test name touched, and rename the
`behavior of "MpeChannelAllocator - Free a channel - High Expressive Pitch Bend"` heading to
`"MpeChannelAllocator - Free a channel - High Expression Pitch Bend"`.

- [ ] **Step 3: Add the new allocator behavior tests (red)**

Append these three `behavior of` blocks at the end of the file.

```scala
  behavior of "MpeChannelAllocator - Reference counting"

  it should "bypass allocation and report a duplicate for a Note On of an already active identity" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val r1 = alloc.allocate(identity)
    // When
    val r2 = alloc.allocate(identity)
    // Then
    r2.channel shouldBe r1.channel
    r2.isDuplicate shouldBe true
    r2.droppedNotes shouldBe empty
    r2.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.activeNotes(r1.channel) should contain theSameElementsAs Set(identity)
    alloc.referenceCountOf(identity) shouldBe 2
    alloc.activeChannelCount shouldBe 1
  }

  it should "deallocate a note only when its reference count reaches zero" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val channel = alloc.allocate(identity).channel
    alloc.allocate(identity)
    // When
    val first = alloc.release(identity)
    // Then
    first.value.channel shouldBe channel
    alloc.referenceCountOf(identity) shouldBe 1
    alloc.isChannelOccupied(channel) shouldBe true
    alloc.channelOf(identity) shouldBe Some(channel)
    // When
    val second = alloc.release(identity)
    // Then
    second.value.channel shouldBe channel
    alloc.referenceCountOf(identity) shouldBe 0
    alloc.isChannelOccupied(channel) shouldBe false
    alloc.channelOf(identity) shouldBe None
  }

  it should "return None when releasing an identity that holds no active count" in {
    // Given
    val alloc = allocator15
    // When / Then
    alloc.release(NoteIdentity(1, C4)) shouldBe None
  }

  it should "override the Expression Values of a duplicate Note On when they are given" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.allocate(identity, Some(ImmutableMpeExpression(20.0, 64, 96)))
    // Then
    result.isDuplicate shouldBe true
    result.update shouldBe MpeExpressionUpdate(Some(20.0), Some(64), Some(96))
    alloc.channelExpression(channel).pitchBendCents shouldBe 20.0
  }

  it should "leave the Expression Values of a duplicate Note On untouched when none are given" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.allocate(identity)
    // Then
    result.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.channelExpression(channel).pressure shouldBe 32
  }

  it should "count two identities sharing a note number as two active notes" in {
    // Given
    // Three C notes from three input channels occupy the three channels of the zone; a fourth shares the
    // oldest, which then holds two identities of the same note number.
    val alloc = allocator3 // PCG=1, EG=2, channels 1..3
    val r1 = alloc.allocate(NoteIdentity(1, C4))
    val r2 = alloc.allocate(NoteIdentity(2, C4))
    val r3 = alloc.allocate(NoteIdentity(3, C4))
    Set(r1.channel, r2.channel, r3.channel) should have size 3
    val r4 = alloc.allocate(NoteIdentity(4, C4))
    r4.channel shouldBe r1.channel
    alloc.activeNotes(r1.channel) should contain theSameElementsAs
      Set(NoteIdentity(1, C4), NoteIdentity(4, C4))
    // When
    // A fifth C must share; criterion (b) prefers the channel with the fewest active identities, which
    // requires counting the two same-numbered identities on r1's channel as two.
    val r5 = alloc.allocate(NoteIdentity(5, C4))
    // Then
    r5.channel shouldBe r2.channel
  }

  behavior of "MpeChannelAllocator - Expression Value aggregation"

  it should "average the Expression Values of the notes active on a channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = alloc.allocate(NoteIdentity(1, C4), Some(ImmutableMpeExpression(10.0, 32, 48)))
    alloc.allocate(NoteIdentity(2, C5))
    // When
    // Both groups are full and the pitch class is already present, so the third C shares the oldest channel.
    val shared = alloc.allocate(NoteIdentity(3, C3), Some(ImmutableMpeExpression(-20.0, 96, 96)))
    // Then
    shared.channel shouldBe first.channel
    val expression = alloc.channelExpression(shared.channel)
    expression.pitchBendCents shouldBe -5.0
    expression.pressure shouldBe 64
    expression.slide shouldBe 72
    shared.update shouldBe MpeExpressionUpdate(Some(-5.0), Some(64), Some(72))
  }

  it should "retain the last Expression Values when the channel becomes unoccupied" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.release(identity)
    // Then
    result.value.update shouldBe MpeExpressionUpdate.Unchanged
    alloc.isChannelOccupied(channel) shouldBe false
    val retained = alloc.channelExpression(channel)
    retained.pitchBendCents shouldBe 10.0
    retained.pressure shouldBe 32
    retained.slide shouldBe 48
  }

  it should "zero the retained Channel Pressure when the last note is released with resetPressureOnEmpty" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    val channel = alloc.allocate(identity, Some(ImmutableMpeExpression(10.0, 32, 48))).channel
    // When
    val result = alloc.release(identity, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe true
    result.update.pressure shouldBe Some(0)
    alloc.channelExpression(channel).pressure shouldBe 0
    // The other two dimensions are retained.
    alloc.channelExpression(channel).pitchBendCents shouldBe 10.0
    alloc.channelExpression(channel).slide shouldBe 48
  }

  it should "not report a pressure reset when other notes remain on the channel" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = NoteIdentity(1, C4)
    val second = NoteIdentity(2, C5)
    val third = NoteIdentity(3, C3)
    val channel = alloc.allocate(first, Some(ImmutableMpeExpression(pressure = 80))).channel
    alloc.allocate(second)
    alloc.allocate(third, Some(ImmutableMpeExpression(pressure = 20))).channel shouldBe channel
    // When
    val result = alloc.release(first, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe false
    result.update.pressure shouldBe Some(20)
    alloc.channelExpression(channel).pressure shouldBe 20
  }

  it should "not report a pressure reset when the retained Channel Pressure is already zero" in {
    // Given
    val alloc = allocator15
    val identity = NoteIdentity(1, C4)
    alloc.allocate(identity)
    // When
    val result = alloc.release(identity, resetPressureOnEmpty = true).value
    // Then
    result.pressureWasReset shouldBe false
    result.update shouldBe MpeExpressionUpdate.Unchanged
  }

  behavior of "MpeChannelAllocator - Expression Value updates"

  it should "fan an Expression Pitch Bend update out to every channel holding a note of the input channel" in {
    // Given
    val alloc = allocator15
    val cChannel = alloc.allocate(NoteIdentity(1, C4)).channel
    val eChannel = alloc.allocate(NoteIdentity(1, E4)).channel
    val otherChannel = alloc.allocate(NoteIdentity(2, G4)).channel
    // When
    val result = alloc.updateExpressionPitchBend(1, 30.0)
    // Then
    result.droppedNotes shouldBe empty
    result.channelUpdates should contain theSameElementsAs Seq(
      ChannelExpressionUpdate(cChannel, MpeExpressionUpdate(pitchBendCents = Some(30.0))),
      ChannelExpressionUpdate(eChannel, MpeExpressionUpdate(pitchBendCents = Some(30.0)))
    )
    alloc.channelExpression(otherChannel).pitchBendCents shouldBe 0.0
  }

  it should "report no update for a channel whose average is unchanged" in {
    // Given
    val alloc = allocator15
    alloc.allocate(NoteIdentity(1, C4), Some(ImmutableMpeExpression(30.0)))
    // When
    val result = alloc.updateExpressionPitchBend(1, 30.0)
    // Then
    result.channelUpdates shouldBe empty
  }

  it should "ignore a Polyphonic Key Pressure update addressed to an inactive identity" in {
    // Given
    val alloc = allocator15
    alloc.allocate(NoteIdentity(1, C4))
    // When / Then
    alloc.updatePressure(NoteIdentity(1, D4), 80) shouldBe ExpressionUpdateResult()
  }

  it should "update a single identity's Channel Pressure contribution" in {
    // Given
    val alloc = allocator2 // PCG=1, EG=1
    val first = NoteIdentity(1, C4)
    val channel = alloc.allocate(first).channel
    alloc.allocate(NoteIdentity(2, C5))
    alloc.allocate(NoteIdentity(3, C3)).channel shouldBe channel
    // When
    val result = alloc.updatePressure(first, 80)
    // Then
    result.channelUpdates shouldEqual Seq(
      ChannelExpressionUpdate(channel, MpeExpressionUpdate(pressure = Some(40))))
  }

  it should "keep the most recently sounded note when several notes on a channel acquire a high bend at once" in {
    // Given
    // Two identities from the same input channel end up sharing a channel.
    val alloc = allocator2 // PCG=1, EG=1
    val first = NoteIdentity(1, C4)
    val second = NoteIdentity(1, C5)
    val channel = alloc.allocate(first).channel
    alloc.allocate(NoteIdentity(2, D4))
    alloc.allocate(second).channel shouldBe channel
    // When
    // One Pitch Bend message gives both of them a High Expression Pitch Bend.
    val result = alloc.updateExpressionPitchBend(1, 100.0)
    // Then
    result.droppedNotes should have size 1
    result.droppedNotes.head.channel shouldBe channel
    result.droppedNotes.head.notes.map(_.noteIdentity) shouldEqual Seq(first)
    alloc.activeNotes(channel) should contain theSameElementsAs Set(second)
    alloc.channelOf(first) shouldBe None
    result.channelUpdates shouldEqual Seq(
      ChannelExpressionUpdate(channel, MpeExpressionUpdate(pitchBendCents = Some(100.0))))
  }

  it should "report the reference count of each dropped note" in {
    // Given
    val alloc = allocator1 // a single member channel
    val identity = NoteIdentity(1, C4)
    alloc.allocate(identity)
    alloc.allocate(identity)
    // When
    val result = alloc.allocate(NoteIdentity(2, E4))
    // Then
    result.droppedNotes.value.notes shouldEqual Seq(DroppedNote(identity, 2))
    alloc.channelOf(identity) shouldBe None
  }
```

### Step group B — stub the API, confirm the red state, then implement

- [ ] **Step 4: Stub the new API so the module compiles**

The tests written in Step group A cannot compile against the current allocator, and a compile error is not an
acceptable red state. Add the thinnest possible stub — declarations only, no logic:

1. In `MpeChannelAllocator.scala`, declare the value types listed under **Interfaces** above exactly as their
   signatures appear there. `NoteIdentity`, `MpeExpressionUpdate` and its companion, `ChannelExpressionUpdate`,
   `DroppedNote`, `ReleaseResult` and `ExpressionUpdateResult` are new; `DroppedNotes` (line 77) and
   `AllocationResult` (line 90) change shape; `ImmutableMpeExpression` (line 57) already exists as a `private case
   class` and only loses its `private`. They are pure data; their final ScalaDoc arrives with the implementation in
   Step 6.
2. Give `MpeChannelAllocator` the new and changed public methods from the same list with `???` bodies, keeping the
   existing private machinery in place for now.
3. Apply the mechanical `MpeTuner` edits below, which are the minimum the new signatures force. `channelNoteMap` and
   the emission rules stay as they are; Tasks 3–6 change them.

`processMemberNoteOn` (line 221) — build an identity and keep passing no Expression Values:

```scala
        val result = alloc.allocate(NoteIdentity(inputChannel, midiNote), preferredChannel = preferredChannel)
```

`processNoteOff` (line 265):

```scala
        allocator.foreach(_.release(NoteIdentity(inputChannel, midiNote)))
```

`processPitchBend`'s MPE member-channel branch (lines 289–300) — the allocator now fans out by itself:

```scala
        getAllocatorForInput(inputChannel).foreach { alloc =>
          val pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
            pitchBendValue, currentZone(alloc).memberPitchBendSensitivity)
          val result = alloc.updateExpressionPitchBend(inputChannel, pitchBendCents)
          result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, "expression pitch bend too high"))
          result.channelUpdates.foreach { channelUpdate =>
            emitTuningPitchBend(buffer, channelUpdate.channel, alloc)
          }
        }
```

`computeOutputPitchBend` (lines 635–648) — read the aggregate instead of averaging per note here:

```scala
  private def computeOutputPitchBend(channel: Int, alloc: MpeChannelAllocator, zone: MpeZone,
                                     tuningOffsetCents: Double): Int = {
    val totalCents = tuningOffsetCents + alloc.channelExpression(channel).pitchBendCents
    val pbs = zone.memberPitchBendSensitivity
    val clampedCents = clampValue(totalCents, -pbs.totalCents, pbs.totalCents)
    PitchBendScMidiMessage.convertCentsToValue(clampedCents, pbs)
  }
```

`emitDroppedNoteOffs` (lines 653–659) — one Note Off per forwarded Note On, at the neutral release velocity 64:

```scala
  /**
   * Emits Note Off messages for dropped notes: one per Note On forwarded for each note, at the neutral
   * release velocity 64 that a note ended by the Tuner's own decision receives.
   */
  private def emitDroppedNoteOffs(buffer: mutable.Buffer[MidiMessage], droppedNotes: DroppedNotes,
                                  reason: String): Unit = {
    logger.trace(s"Dropping notes ${droppedNotes.notes.map(_.noteIdentity.midiNote)} " +
      s"on channel ${droppedNotes.channel} ($reason)")
    for {
      droppedNote <- droppedNotes.notes
      _ <- 1 to droppedNote.referenceCount
    } {
      buffer += NoteOffScMidiMessage(droppedNotes.channel, droppedNote.noteIdentity.midiNote).asJava
    }
  }
```

Then compile with `mcp__metals__compile-module` (`module = "tuner"`), falling back to `sbtn "tuner/Test/compile"`.
Expected: the module and its tests compile.

- [ ] **Step 5: Run the allocator suite to confirm the red state**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: FAIL with `scala.NotImplementedError` from the stubbed methods — assertion-level failures, not compile
errors. `MpeTunerTest` is red for the same reason while the stubs stand — it goes through the allocator for every note
— and returns to green as the implementation replaces the stubs, which Step 15 verifies for the whole module. Nothing
is committed until then.

- [ ] **Step 6: Rewrite the value types and the `MpeExpression` ScalaDoc**

Every line number in this task is the one at the base commit; Step 4's stub has already shifted them, so locate the
declarations by name (`mcp__metals__inspect`) rather than by line from here on.

Replace the top of `MpeChannelAllocator.scala` (from the `MpeExpression` trait ScalaDoc through the
`AllocationResult` declaration, lines 25–90 at the base commit) with:

```scala
/**
 * Holds the MPE '''Expression Values''' of a note, or the aggregated Expression Values of an output MPE
 * Member Channel.
 *
 * A note's Expression Values are the performer-controlled values of the three MPE control dimensions:
 * its Expression Pitch Bend, its Channel Pressure and its CC #74 (Timbre / Slide). The Tuning Pitch Bend
 * is ''not'' an Expression Value — it belongs to the tuning domain rather than to the expression domain,
 * and is added to the Expression Pitch Bend only when the Pitch Bend emitted on a Member Channel is
 * computed.
 */
trait MpeExpression {
  /**
   * Expression Pitch Bend in cents, excluding any tuning offset. 0.0 means no bend.
   * Stored in cents so that the value is independent of the current Pitch Bend Sensitivity.
   */
  def pitchBendCents: Double

  /** Channel pressure (aftertouch) value. Ranges from 0 to 127. */
  def pressure: Int

  /** MIDI CC #74 (Timbre / Slide) value. Ranges from 0 to 127; 64 is the centre. */
  def slide: Int
}

object MpeExpression {
  /** Default Expression Pitch Bend value in cents (no bend). */
  val DefaultPitchBendCents: Double = 0.0

  /** Default channel pressure value (no pressure). */
  val DefaultPressure: Int = 0

  /** Default slide (CC #74) value (centre position). */
  val DefaultSlide: Int = 64
}

private class MutableMpeExpression(var pitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                                   var pressure: Int = MpeExpression.DefaultPressure,
                                   var slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

/**
 * Immutable [[MpeExpression]], used to hand Expression Values to [[MpeChannelAllocator]] and to snapshot a
 * channel's aggregate.
 */
case class ImmutableMpeExpression(pitchBendCents: Double = MpeExpression.DefaultPitchBendCents,
                                  pressure: Int = MpeExpression.DefaultPressure,
                                  slide: Int = MpeExpression.DefaultSlide) extends MpeExpression

/**
 * A note together with its origin: the pair (input channel, note number).
 *
 * The input channel belongs in a note's identity because it is the carrier of per-note information in both
 * input modes — of a note's Expression Values in MPE Input Mode, and of the Polyphonic Key Pressure
 * addressed to a note in Non-MPE Input Mode — so two notes with the same note number arriving on different
 * input channels are independent notes.
 */
case class NoteIdentity(inputChannel: Int, midiNote: MidiNote)

/**
 * Which of an output Member Channel's aggregated Expression Values changed as the result of an operation.
 *
 * `None` means the dimension is unchanged and needs no message on the output channel; `Some(value)` means
 * it changed to `value`.
 */
case class MpeExpressionUpdate(pitchBendCents: Option[Double] = None,
                               pressure: Option[Int] = None,
                               slide: Option[Int] = None)

object MpeExpressionUpdate {
  /** No Expression Value changed. */
  val Unchanged: MpeExpressionUpdate = MpeExpressionUpdate()
}

/** An [[MpeExpressionUpdate]] addressed to a specific output Member Channel. */
case class ChannelExpressionUpdate(channel: Int, update: MpeExpressionUpdate)

/**
 * A dropped note together with the number of Note Off messages owed for it: one per Note On that was
 * forwarded for it, which is the reference count it held at the moment of the drop.
 */
case class DroppedNote(noteIdentity: NoteIdentity, referenceCount: Int)

/**
 * Describes the notes that were removed from a channel as a side-effect of an allocation or of an
 * Expression Pitch Bend update.
 *
 * Notes can be dropped in two situations:
 *  - '''Channel exhaustion''': all Member Channels are occupied and a new note requires a free channel.
 *    The allocator evicts the least-important occupied channel, dropping all its notes.
 *  - '''High Expression Pitch Bend''': a note on a shared channel develops a pitch bend large enough to
 *    interfere with the intonation of the other notes on that channel, or a new note is assigned to such a
 *    channel. All co-resident notes are dropped so the bending note can occupy the channel exclusively.
 *
 * @param channel The 0-indexed MIDI channel from which notes were dropped.
 * @param notes   The dropped notes, oldest onset first, each with the reference count it held.
 * @param group   The [[ChannelGroup]] that `channel` belonged to at the time of the drop.
 */
case class DroppedNotes(channel: Int,
                        notes: Seq[DroppedNote],
                        group: ChannelGroup)

/**
 * Result of a channel allocation operation.
 *
 * @param channel      The 0-indexed MIDI channel assigned to the note.
 * @param update       The Expression Values of `channel` that changed as a result of the allocation.
 * @param droppedNotes Any notes that were dropped as a result of this allocation.
 * @param isDuplicate  `true` when the Note On raised an already active identity's reference count, so the
 *                     allocation algorithm was bypassed.
 */
case class AllocationResult(channel: Int,
                            update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                            droppedNotes: Option[DroppedNotes] = None,
                            isDuplicate: Boolean = false)

/**
 * Result of releasing a note.
 *
 * @param channel          The 0-indexed MIDI channel the released note was bound to.
 * @param update           The Expression Values of `channel` that changed as a result of the release.
 * @param pressureWasReset `true` when the release emptied the channel, the caller asked for the Channel
 *                         Pressure reset and the reset actually changed the retained value. The new value
 *                         is carried by `update.pressure` and must be emitted ''before'' the Note Off — the
 *                         sole exception to the Note Off message ordering.
 */
case class ReleaseResult(channel: Int,
                         update: MpeExpressionUpdate = MpeExpressionUpdate.Unchanged,
                         pressureWasReset: Boolean = false)

/**
 * Result of an Expression Value update received on an input channel and fanned out to every output Member
 * Channel holding one of that input channel's notes.
 *
 * @param channelUpdates One entry per affected output channel whose aggregate actually changed, ordered by
 *                       the earliest onset among the notes updated on it.
 * @param droppedNotes   Notes dropped by the divergence rule; always empty for pressure and slide updates.
 */
case class ExpressionUpdateResult(channelUpdates: Seq[ChannelExpressionUpdate] = Nil,
                                  droppedNotes: Seq[DroppedNotes] = Nil)
```

Delete the two `TODO #154` comments that stood at lines 61 and 81–82.

- [ ] **Step 7: Rewrite `NoteState` and `ChannelState`**

Replace the whole `private class ChannelState` (lines 92–208) with:

```scala
/**
 * Per-note state on an output Member Channel: the note's own Expression Values, its reference count and
 * the logical time of the Note On that allocated it.
 */
private class NoteState(val expression: MutableMpeExpression,
                        var referenceCount: Int,
                        var onsetTime: Long)

/**
 * Holds all mutable runtime state for a single MPE Member Channel within the allocator.
 *
 * A channel is considered ''occupied'' while it has at least one active Note Identity (i.e. a Note On has
 * been received but the matching Note Off has not yet arrived). Once it becomes unoccupied its pitch-class
 * and group assignments are cleared and the channel is eligible for reuse, but it '''retains''' its
 * aggregated Expression Values: averaging is defined only while at least one note is active, and the
 * retained values are what let the caller omit control messages whose value would not change.
 *
 * @param channel The 0-indexed MIDI channel number this state object represents.
 */
private class ChannelState(val channel: Int) {
  private val _notes: mutable.HashMap[NoteIdentity, NoteState] = mutable.HashMap.empty
  private val _expression: MutableMpeExpression = MutableMpeExpression()
  private var _pitchClass: Option[PitchClass] = None
  private var _group: Option[ChannelGroup] = None
  private var _lastNoteOnTime: Long = 0L
  private var _lastNoteOffTime: Long = 0L

  /** An immutable snapshot of the Note Identities currently active on this channel. */
  def noteIdentities: Set[NoteIdentity] = _notes.keySet.toSet

  /** The number of distinct active Note Identities, whatever their reference counts. */
  def noteCount: Int = _notes.size

  /** The channel's aggregated Expression Values, retained while the channel is unoccupied. */
  def expression: MpeExpression = _expression

  /** The mutable Expression Values of an active note on this channel. */
  def expressionFor(noteIdentity: NoteIdentity): MutableMpeExpression = _notes(noteIdentity).expression

  /** The reference count of an active identity, or 0 when it is not active on this channel. */
  def referenceCountOf(noteIdentity: NoteIdentity): Int =
    _notes.get(noteIdentity).map(_.referenceCount).getOrElse(0)

  /** The logical timestamp of the Note On that allocated an active identity. */
  def onsetTimeOf(noteIdentity: NoteIdentity): Long = _notes(noteIdentity).onsetTime

  /**
   * The pitch class shared by all active notes on this channel, or `None` when the channel is
   * unoccupied. All notes on a single channel are required to belong to the same pitch class so
   * that one tuning offset can serve all of them.
   */
  def pitchClass: Option[PitchClass] = _pitchClass

  /**
   * The [[ChannelGroup]] this channel is currently assigned to, or `None` when the channel is
   * unoccupied.
   */
  def group: Option[ChannelGroup] = _group

  /**
   * The logical timestamp of the most recent Note On event processed on this channel.
   * Zero when the channel is unoccupied (never received a note, or all notes have been released).
   */
  def lastNoteOnTime: Long = _lastNoteOnTime

  /**
   * The logical timestamp of the most recent Note Off event processed on this channel.
   * Zero when the channel has never had a note released.
   */
  def lastNoteOffTime: Long = _lastNoteOffTime

  /** `true` if this channel has at least one active note; `false` if it is free for allocation. */
  def isOccupied: Boolean = _notes.nonEmpty

  /**
   * Adds a note to this channel with a reference count of 1, updating pitch class, group, and onset time
   * accordingly. Pitch class and group are only set when the channel transitions from unoccupied to
   * occupied. When the channel is already occupied, the `targetGroup` must match the existing group.
   *
   * @param noteIdentity The note being added.
   * @param expression   The initial Expression Values of the note.
   * @param time         The logical timestamp of the onset.
   * @param targetGroup  The channel group; must match the existing group when the channel is already
   *                     occupied.
   */
  def addNote(noteIdentity: NoteIdentity,
              expression: MutableMpeExpression,
              time: Long,
              targetGroup: ChannelGroup): Unit = {
    if (_notes.isEmpty) {
      _pitchClass = Some(noteIdentity.midiNote.pitchClass)
      _group = Some(targetGroup)
    } else {
      require(_group.contains(targetGroup),
        s"targetGroup $targetGroup does not match existing group ${_group.orNull} on channel $channel")
    }
    _notes(noteIdentity) = NoteState(expression, referenceCount = 1, onsetTime = time)
    _lastNoteOnTime = time
  }

  /**
   * Increments the reference count of an already active identity, for a duplicate Note On. Nothing else
   * changes: the identity keeps its onset time, and the channel keeps its own timestamps, because no
   * allocation occurs.
   */
  def incrementReferenceCount(noteIdentity: NoteIdentity): Unit = {
    _notes(noteIdentity).referenceCount += 1
  }

  /**
   * Decrements the reference count of an active identity, removing it when the count reaches 0.
   *
   * @return `true` when the identity was removed, i.e. the count reached 0.
   */
  def decrementReferenceCount(noteIdentity: NoteIdentity, time: Long): Boolean = {
    val noteState = _notes(noteIdentity)
    noteState.referenceCount -= 1
    if (noteState.referenceCount <= 0) {
      removeNote(noteIdentity, time)
      true
    } else {
      false
    }
  }

  /**
   * Removes a note from this channel whatever its reference count, updating note-off time accordingly.
   * Clears pitch class, group, and onset time when the channel becomes unoccupied. The aggregated
   * Expression Values are left untouched; the caller recomputes them.
   *
   * @param noteIdentity The note to remove.
   * @param time         The logical timestamp of the removal.
   */
  def removeNote(noteIdentity: NoteIdentity, time: Long): Unit = {
    if (_notes.remove(noteIdentity).isDefined) {
      _lastNoteOffTime = time
      if (_notes.isEmpty) {
        _pitchClass = None
        _group = None
        _lastNoteOnTime = 0L
      }
    }
  }

  /**
   * Recomputes the channel's aggregated Expression Values as the average of its active notes' values, one
   * term per Note Identity whatever its reference count. The two integer dimensions are averaged in
   * `Double` and rounded half up.
   *
   * When the channel is unoccupied the aggregate is '''left untouched''': averaging is defined only while
   * at least one note is active, and the retained values give every dimension a defined value at all times.
   */
  def recomputeExpression(): Unit = {
    if (_notes.nonEmpty) {
      val noteStates = _notes.values
      val count = _notes.size
      _expression.pitchBendCents = noteStates.map(_.expression.pitchBendCents).sum / count
      _expression.pressure = Math.round(noteStates.map(_.expression.pressure).sum.toDouble / count).toInt
      _expression.slide = Math.round(noteStates.map(_.expression.slide).sum.toDouble / count).toInt
    }
  }

  /** Returns the retained Channel Pressure to its default of 0. */
  def resetPressure(): Unit = {
    _expression.pressure = MpeExpression.DefaultPressure
  }

  /** Resets all channel state, clearing notes, aggregated Expression Values and all timestamps. */
  def reset(): Unit = {
    _notes.clear()
    _expression.pitchBendCents = MpeExpression.DefaultPitchBendCents
    _expression.pressure = MpeExpression.DefaultPressure
    _expression.slide = MpeExpression.DefaultSlide
    _pitchClass = None
    _group = None
    _lastNoteOnTime = 0L
    _lastNoteOffTime = 0L
  }
}
```

- [ ] **Step 8: Rewrite the allocator body — bindings, `allocate`, `release`**

In `class MpeChannelAllocator`, add the binding map next to `channelStates` and replace `allocate` and `release`:

```scala
  /** The output Member Channel each active Note Identity is bound to. */
  private val noteChannels: mutable.HashMap[NoteIdentity, Int] = mutable.HashMap.empty
```

```scala
  /**
   * Allocates an output Member Channel for a note, or increments the reference count of an already active
   * one.
   *
   * @param noteIdentity     The note to allocate a channel for.
   * @param expression       The note's initial Expression Values, or `None` to use the defaults of
   *                         [[MpeExpression]]. On a duplicate Note On, `Some` overrides the note's current
   *                         Expression Values and `None` leaves them untouched.
   * @param preferredChannel An optional preferred output channel, applied by tie-break criterion (e). It is
   *                         a separate parameter rather than `noteIdentity.inputChannel` because the
   *                         preference is input-mode-dependent and this class is unaware of the input mode.
   * @return the assigned channel, the Expression Values that changed on it, and any notes dropped.
   */
  def allocate(noteIdentity: NoteIdentity,
               expression: Option[MpeExpression] = None,
               preferredChannel: Option[Int] = None): AllocationResult =
    noteChannels.get(noteIdentity) match {
      case Some(channel) => allocateDuplicate(channelStates(channel), noteIdentity, expression)
      case None => allocateFresh(noteIdentity, expression, preferredChannel)
    }

  /**
   * Handles a Note On for an already active identity: the reference count is incremented, the allocation
   * algorithm is bypassed and the note stays a single term in its channel's averages. The recomputation is
   * performed rather than assumed, so that a missed update surfaces as an emitted message instead of
   * silence.
   */
  private def allocateDuplicate(state: ChannelState,
                                noteIdentity: NoteIdentity,
                                expression: Option[MpeExpression]): AllocationResult = {
    val before = snapshotOf(state)
    state.incrementReferenceCount(noteIdentity)
    expression.foreach { newExpression =>
      val noteExpression = state.expressionFor(noteIdentity)
      noteExpression.pitchBendCents = newExpression.pitchBendCents
      noteExpression.pressure = newExpression.pressure
      noteExpression.slide = newExpression.slide
    }
    state.recomputeExpression()
    AllocationResult(state.channel, diff(before, state.expression), isDuplicate = true)
  }

  private def allocateFresh(noteIdentity: NoteIdentity,
                            expression: Option[MpeExpression],
                            preferredChannel: Option[Int]): AllocationResult = boundary {
    val pc = noteIdentity.midiNote.pitchClass
    val time = nextTime()

    // Step 1: Check Pitch Class Group availability
    val pitchClassInPCG = pitchClassGroupChannels.exists(_.pitchClass.contains(pc))
    if (!pitchClassInPCG && pitchClassGroupCount < zone.pitchClassGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, noteIdentity, expression, time, ChannelGroup.PitchClass))
    }

    // Step 2: Try Expression Group
    if (expressionGroupCount < zone.expressionGroupSize) {
      val target = bestCandidate(unoccupiedChannels.map(channelStates), preferredChannel)
      boundary.break(doAllocate(target, noteIdentity, expression, time, ChannelGroup.Expression))
    }

    // Step 3: Try sharing with the same pitch class
    val samePcChannels = channelStates.values.filter { s =>
      s.isOccupied && s.pitchClass.contains(pc)
    }.toSeq
    if (samePcChannels.nonEmpty) {
      // The candidates are all occupied, so the input-channel preference (criterion (e)) does not apply
      // and degenerates to the lowest channel number (see the paper's "Allocation Algorithm" section),
      // exactly as for Step 4's freeChannel; pass None rather than preferredChannel.
      val target = bestCandidate(samePcChannels, None)
      boundary.break(doAllocate(target, noteIdentity, expression, time, target.group.get))
    }

    // Step 4: No channel with the same pitch class and all channels occupied -> free a channel
    val dropped = freeChannel(time)
    doAllocate(channelStates(dropped.channel), noteIdentity, expression, time, dropped.group)
      .copy(droppedNotes = Some(dropped))
  }

  /**
   * Releases one Note On of a note. Deallocation — removal from the channel, from the identity → channel
   * binding, and the accompanying recomputation — happens only on the transition to a reference count of 0;
   * a decrement that leaves the count at 1 or above changes no average, because the identity remains a
   * single term in it.
   *
   * @param noteIdentity         The note to release.
   * @param resetPressureOnEmpty When `true` and this release empties the channel, the channel's retained
   *                             Channel Pressure is zeroed instead of retained.
   * @return `None` when the identity holds no active count, which is the signal to discard the Note Off;
   *         otherwise the resolved channel, the Expression Values that changed on it, and whether the
   *         Channel Pressure reset was applied.
   */
  def release(noteIdentity: NoteIdentity, resetPressureOnEmpty: Boolean = false): Option[ReleaseResult] =
    noteChannels.get(noteIdentity).map { channel =>
      val state = channelStates(channel)
      val before = snapshotOf(state)
      val deallocated = state.decrementReferenceCount(noteIdentity, nextTime())
      if (deallocated) {
        noteChannels.remove(noteIdentity)
        state.recomputeExpression()
      }

      val pressureWasReset = deallocated && !state.isOccupied && resetPressureOnEmpty &&
        state.expression.pressure != MpeExpression.DefaultPressure
      if (pressureWasReset) state.resetPressure()

      ReleaseResult(channel, diff(before, state.expression), pressureWasReset)
    }
```

- [ ] **Step 9: Implement the three update methods and the divergence rule**

Replace `updateExpressivePitchBend` (lines 293–324, including the `TODO #154` above it) with:

```scala
  /**
   * Applies an Expression Pitch Bend received on an input channel to every note active on it, wherever the
   * pitch-class invariant has placed those notes, and applies the divergence rule to each affected output
   * channel.
   *
   * @param inputChannel   The input channel the Pitch Bend arrived on.
   * @param pitchBendCents The new Expression Pitch Bend in cents.
   * @return the output channels whose aggregate changed and any notes dropped by the divergence rule.
   */
  def updateExpressionPitchBend(inputChannel: Int, pitchBendCents: Double): ExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel),
      noteExpression => noteExpression.pitchBendCents = pitchBendCents,
      applyDivergenceRule)

  /**
   * Applies a Channel Pressure received on an input channel to every note active on it.
   */
  def updatePressure(inputChannel: Int, pressure: Int): ExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel), noteExpression => noteExpression.pressure = pressure)

  /**
   * Applies a pressure value addressed to a single note, as a Polyphonic Key Pressure is. An identity that
   * is not active yields an empty result, which is how a Polyphonic Key Pressure addressed to a note for
   * which no Note On was issued on that input channel is ignored.
   */
  def updatePressure(noteIdentity: NoteIdentity, pressure: Int): ExpressionUpdateResult =
    updateExpressionValues(Seq(noteIdentity), noteExpression => noteExpression.pressure = pressure)

  /**
   * Applies a CC #74 (Timbre / Slide) value received on an input channel to every note active on it.
   */
  def updateSlide(inputChannel: Int, slide: Int): ExpressionUpdateResult =
    updateExpressionValues(identitiesOn(inputChannel), noteExpression => noteExpression.slide = slide)
```

Add these private members after `freeChannel`:

```scala
  private def identitiesOn(inputChannel: Int): Seq[NoteIdentity] =
    noteChannels.keys.filter(_.inputChannel == inputChannel).toSeq

  /**
   * Writes a new contribution into each of the given notes, applies an optional per-channel rule, then
   * recomputes each affected channel's aggregate and reports only the channels whose value actually
   * changed.
   *
   * Channels are reported in the order of the earliest onset among the notes updated on them, so the
   * output follows the order in which the performer sounded the notes rather than an incidental map order.
   *
   * @param noteIdentities The notes whose contribution changes; inactive ones are ignored.
   * @param write          Writes the new value into a note's Expression Values.
   * @param afterWrite     Applied to each affected channel after the writes and before the recomputation.
   */
  private def updateExpressionValues(noteIdentities: Seq[NoteIdentity],
                                     write: MutableMpeExpression => Unit,
                                     afterWrite: ChannelState => Option[DroppedNotes] = _ => None)
  : ExpressionUpdateResult = {
    val identitiesByChannel = noteIdentities
      .flatMap(noteIdentity => noteChannels.get(noteIdentity).map(channel => (channel, noteIdentity)))
      .groupMap(_._1)(_._2)
      .toSeq
      .sortBy { case (channel, identities) =>
        (identities.map(channelStates(channel).onsetTimeOf).min, channel)
      }

    val channelUpdates = Seq.newBuilder[ChannelExpressionUpdate]
    val droppedNotes = Seq.newBuilder[DroppedNotes]
    for ((channel, identities) <- identitiesByChannel) {
      val state = channelStates(channel)
      val before = snapshotOf(state)
      identities.foreach(noteIdentity => write(state.expressionFor(noteIdentity)))
      afterWrite(state).foreach(droppedNotes += _)
      state.recomputeExpression()
      val update = diff(before, state.expression)
      if (update != MpeExpressionUpdate.Unchanged) {
        channelUpdates += ChannelExpressionUpdate(channel, update)
      }
    }

    ExpressionUpdateResult(channelUpdates.result(), droppedNotes.result())
  }

  /**
   * Applies the divergence rule to a channel whose notes have just received a new Expression Pitch Bend:
   * when the channel holds more than one note and at least one of them now has a High Expression Pitch
   * Bend, the high-bend note with the greatest onset time — the most recently sounded — survives and every
   * other note on the channel is dropped.
   *
   * The single-high-bend case is the paper's rule as written. Several notes can acquire a high bend at once
   * only when they share an input channel, the Pitch Bend being a channel message that belongs to all of
   * them; retaining the most recently sounded preserves the performer's gesture on one voice, and leaving
   * exactly one note restores the invariant that a high-bend note is the sole note on its channel.
   */
  private def applyDivergenceRule(state: ChannelState): Option[DroppedNotes] = {
    val identities = state.noteIdentities
    val highBendIdentities = identities.filter { noteIdentity =>
      isHighExpressionPitchBend(state.expressionFor(noteIdentity).pitchBendCents)
    }

    if (identities.sizeIs > 1 && highBendIdentities.nonEmpty) {
      val survivor = highBendIdentities.maxBy(state.onsetTimeOf)
      Some(dropIdentities(state, (identities - survivor).toSeq, nextTime()))
    } else {
      None
    }
  }

  /**
   * Drops the given notes from a channel, clearing their channel bindings so that a Note Off the performer
   * sends for them afterwards is discarded.
   *
   * @return the dropped notes, oldest onset first, each with the reference count it held.
   */
  private def dropIdentities(state: ChannelState,
                             noteIdentities: Seq[NoteIdentity],
                             time: Long): DroppedNotes = {
    val group = state.group.get
    val dropped = noteIdentities
      .sortBy(state.onsetTimeOf)
      .map(noteIdentity => DroppedNote(noteIdentity, state.referenceCountOf(noteIdentity)))
    dropped.foreach { droppedNote =>
      state.removeNote(droppedNote.noteIdentity, time)
      noteChannels.remove(droppedNote.noteIdentity)
    }

    DroppedNotes(state.channel, dropped, group)
  }

  private def snapshotOf(state: ChannelState): MpeExpression =
    ImmutableMpeExpression(state.expression.pitchBendCents, state.expression.pressure, state.expression.slide)

  private def diff(before: MpeExpression, after: MpeExpression): MpeExpressionUpdate =
    MpeExpressionUpdate(
      pitchBendCents = Option.when(after.pitchBendCents != before.pitchBendCents)(after.pitchBendCents),
      pressure = Option.when(after.pressure != before.pressure)(after.pressure),
      slide = Option.when(after.slide != before.slide)(after.slide))
```

- [ ] **Step 10: Update `doAllocate`, `dropExistingNotesForHighBend`, `freeChannel`, `bestCandidate`, `reset` and the accessors**

```scala
  def reset(): Unit = {
    channelStates.values.foreach(_.reset())
    noteChannels.clear()
    _time = 0L
  }

  // State inspection accessors

  /** The output Member Channel bound to an active note, or `None` when it holds no active count. */
  def channelOf(noteIdentity: NoteIdentity): Option[Int] = noteChannels.get(noteIdentity)

  /** The aggregated Expression Values of a channel, retained while the channel is unoccupied. */
  def channelExpression(channel: Int): MpeExpression = channelStates(channel).expression

  /** The Note Identities currently active on a channel. */
  def activeNotes(channel: Int): Set[NoteIdentity] = channelStates(channel).noteIdentities

  /** Every active note with the output Member Channel it is bound to, ordered by channel. */
  def activeAllocations: Seq[(NoteIdentity, Int)] = noteChannels.toSeq.sortBy(_._2)

  /** The read-only Expression Values of an active note. */
  def expressionFor(noteIdentity: NoteIdentity): MpeExpression =
    channelStates(noteChannels(noteIdentity)).expressionFor(noteIdentity)

  /** The reference count of a note, or 0 when it holds no active count. */
  def referenceCountOf(noteIdentity: NoteIdentity): Int =
    noteChannels.get(noteIdentity).map(channelStates(_).referenceCountOf(noteIdentity)).getOrElse(0)
```

`doAllocate`, `dropExistingNotesForHighBend` and `freeChannel` become:

```scala
  private def doAllocate(state: ChannelState,
                         noteIdentity: NoteIdentity,
                         expression: Option[MpeExpression],
                         time: Long,
                         targetGroup: ChannelGroup): AllocationResult = {
    val before = snapshotOf(state)
    val existingIdentities = state.noteIdentities
    val noteExpression = MutableMpeExpression(
      expression.map(_.pitchBendCents).getOrElse(MpeExpression.DefaultPitchBendCents),
      expression.map(_.pressure).getOrElse(MpeExpression.DefaultPressure),
      expression.map(_.slide).getOrElse(MpeExpression.DefaultSlide))

    state.addNote(noteIdentity, noteExpression, time, targetGroup)
    noteChannels(noteIdentity) = state.channel
    val dropped = dropExistingNotesForHighBend(state, existingIdentities, noteExpression.pitchBendCents, time)
    state.recomputeExpression()

    AllocationResult(state.channel, diff(before, state.expression), dropped)
  }

  /**
   * Drops the existing notes on a channel when a High Expression Pitch Bend means they can no longer
   * coexist with the newly added note: either the new note has a high bend, or the channel already held a
   * note with one.
   */
  private def dropExistingNotesForHighBend(state: ChannelState,
                                           existingIdentities: Set[NoteIdentity],
                                           newPitchBendCents: Double,
                                           time: Long): Option[DroppedNotes] = {
    if (existingIdentities.isEmpty) {
      None
    } else {
      val existingHighBend = existingIdentities.exists { noteIdentity =>
        isHighExpressionPitchBend(state.expressionFor(noteIdentity).pitchBendCents)
      }
      val newHighBend = isHighExpressionPitchBend(newPitchBendCents)
      if (existingHighBend || newHighBend) {
        Some(dropIdentities(state, existingIdentities.toSeq, time))
      } else {
        None
      }
    }
  }

  private def freeChannel(time: Long): DroppedNotes = {
    val occupied = occupiedChannelStates
    assert(occupied.nonEmpty)

    val target =
      if (occupied.sizeIs == 1) {
        // Only one candidate: free it regardless of register.
        occupied.head
      } else {
        val (lowest, highest) = lowestAndHighestNotes(occupied)
        val nonBoundary = occupied.filterNot { s =>
          s.noteIdentities.exists(n => n.midiNote.number == lowest.number || n.midiNote.number == highest.number)
        }
        if (nonBoundary.nonEmpty) {
          bestCandidate(nonBoundary, None)
        } else {
          // Every occupied channel is a boundary channel (extremes on different channels): free the
          // channel holding the lower (bass) note, retaining the upper melodic note.
          bestCandidate(occupied.filter(_.noteIdentities.exists(_.midiNote.number == lowest.number)), None)
        }
      }

    dropIdentities(target, target.noteIdentities.toSeq, time)
  }
```

`lowestAndHighestNotes` iterates identities:

```scala
    val notes = states.iterator.flatMap(_.noteIdentities.iterator.map(_.midiNote))
```

`bestCandidate` uses the identity count and the renamed predicate:

```scala
    candidates.minBy { s =>
      (hasHighExpressionPitchBend(s),                       // (a) no high bend (false < true)
        s.noteCount,                                        // (b) fewest active Note Identities
        s.lastNoteOnTime,                                   // (c) oldest onset
        s.lastNoteOffTime,                                  // (d) oldest last Note Off
        if (preferredChannel.contains(s.channel)) 0 else 1, // (e) prefer the input channel
        s.channel)                                          // (e) then the lowest channel number
    }
```

In the companion object rename both predicates and update the `ExpressionPitchBendThreshold` ScalaDoc wording from
"expressive" to "Expression":

```scala
  private def isHighExpressionPitchBend(pitchBendCents: Double): Boolean =
    Math.abs(pitchBendCents) > ExpressionPitchBendThreshold

  private def hasHighExpressionPitchBend(state: ChannelState): Boolean =
    state.noteIdentities.exists(n => isHighExpressionPitchBend(state.expressionFor(n).pitchBendCents))
```

Also update the `ChannelGroup.Expression` ScalaDoc phrase "different expressive pitch bends" →
"different Expression Pitch Bends".

- [ ] **Step 11: Run the allocator tests**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeChannelAllocatorTest -- -oNCXEHLOPQRMWS"
```
Expected: PASS, all tests green. Every `???` stub from Step 4 is now gone; if any remains, the suite is not yet
covering it and the missing behavior needs its own failing test first.

### Step group C — activate the three tuner tests this task unlocks

- [ ] **Step 12: Widen the cents tolerance to one Pitch Bend unit, then activate and correct `:1497`**

First check that no test asserts inequality between cents values, so that loosening the tolerance cannot weaken an
existing assertion:

Use the `Grep` tool with the pattern `not equal|should not be` over
`tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`, output mode `content` with line
numbers.

Expected: only channel numbers and `MidiNote`s — no `cents` comparison. If a cents inequality shows up, keep the
tolerance and convert that assertion to a MIDI-value comparison instead.

Then, at `MpeTunerTest.scala:73`:

```scala
  // One Pitch Bend unit is ≈0.586 cents at the default Member Channel Pitch Bend Sensitivity of ±48
  // semitones, and an average over quantized per-note values lands up to half a unit from the arithmetic
  // expectation, so the tolerance is one unit. Assertions that need finer resolution compare MIDI values.
  private val epsilon: Double = 6e-1
```

Now replace `ignore` with `it` on `:1497` and correct the first assertion block: `DistributeFixture` places `D5` (from
input channel 3) on `output1Channel` alongside `D4`, so that channel emits the average of the two notes.

```scala
  it should "distribute the pitch bend values of the input channel" in new DistributeFixture {
    // When
    private val pitchBends1 = extractPitchBends(pitchBend(1, 10.0))
    private val pitchBends3 = extractPitchBends(pitchBend(3, 30.0))

    // Then
    // Input channel 1 feeds output channels 1 and 2. Output channel 1 also holds D5, which arrived on
    // input channel 3 and still carries no bend, so its Expression Pitch Bend is the average of the two.
    pitchBends1.map(_.channel) shouldEqual Seq(output1Channel, output2Channel)
    pitchBends1.head.cents shouldEqual (quarterCommaMeantone.d + (10.0 + 0.0) / 2)
    pitchBends1(1).cents shouldEqual (quarterCommaMeantone.e + 10.0)

    pitchBends3.map(_.channel) shouldEqual Seq(output3Channel, output4Channel, output1Channel)
    pitchBends3.head.cents shouldEqual (quarterCommaMeantone.f + 30.0)
    pitchBends3(1).cents shouldEqual (quarterCommaMeantone.g + 30.0)
    pitchBends3(2).cents shouldEqual (quarterCommaMeantone.d + (10.0 + 30.0) / 2)
  }
```

- [ ] **Step 13: Activate `:1758` "drop other notes on a shared channel when one note develops a high expression pitch bend"**

Replace `ignore` with `it`. Fix the copy-paste on the line that reads
`private val e2OutputChannel = extractNoteOns(e1Output).head.channel` to use `e2Output`. No other change.

- [ ] **Step 14: Rewrite and activate `:1802` under the divergence rule adopted by the design**

```scala
  it should "keep the most recently sounded note on a shared channel with a common input channel when a high " +
    "expression pitch bend is received on it" in
    new Fixture(tuner3MpeInput) {
      // Given
      // tuner3 in MPE input: PCG=1, EG=2. Input channels are 1..3.
      // Share E4 + E5 on the same output channel by sending E5 on E4's input channel.
      private val outE4 = noteOn(1, E4)
      private val sharedChannel = extractNoteOns(outE4).head.channel
      noteOn(2, G4)
      noteOn(3, C4)
      noteOn(1, E5)

      // When
      // One Pitch Bend message gives both notes a High Expression Pitch Bend (> 50 cents), so the
      // divergence rule keeps the most recently sounded (E5) and drops the other.
      private val output = pitchBend(1, 100.0)
      // Then
      private val noteOffs = extractNoteOffs(output).map(n => (n.channel, n.midiNote))
      noteOffs should contain theSameElementsAs Seq((sharedChannel, E4))
    }
```

- [ ] **Step 15: Run the whole module and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS, with 12 ignored tests remaining.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocator.scala \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Give MpeChannelAllocator Note Identities and Expression Values

Key the allocator's channel state by Note Identity, add reference counting,
per-note Expression Values and per-channel aggregation with retention, and
replace the last-added-note assumption with a fan-out from the input channel.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `MpeTuner` — move note bookkeeping to the allocator

Removes `channelNoteMap` and everything built on it. Note On/Off resolve through the allocator's bindings, Master
Channel notes are recovered from `ScMidiChannelStateTracker`, and CC #74 / Channel Pressure / Polyphonic Key Pressure
go through the allocator's update methods. This closes B1 and the mechanical halves of P3 and P5.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:77-83,193-273,512-545,547-600,635-673`
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala:1513,1530`

**Interfaces:**
- Consumes: `alloc.release(noteIdentity, resetPressureOnEmpty)`, `alloc.updatePressure`, `alloc.updateSlide`,
  `alloc.activeAllocations`, `alloc.channelOf` from Task 2.
- Produces: `MpeTuner` private helpers `emitExpressionUpdate(buffer, channel, update, alloc)` and
  `emitOutputPitchBend(buffer, channel, alloc)` (the renamed `emitTuningPitchBend`), used by Tasks 4–6.

- [ ] **Step 1: Activate and correct `:1513` "distribute the channel pressure values of the input channel" (red)**

```scala
  it should "distribute the channel pressure values of the input channel" in new DistributeFixture {
    // When
    private val channelPressures1 = extractChannelPressures(pressure(1, 10))
    private val channelPressures3 = extractChannelPressures(pressure(3, 30))

    // Then
    // Output channel 1 also holds D5, which arrived on input channel 3 and still carries pressure 0, so
    // the channel emits the average of the two notes.
    channelPressures1 should contain theSameElementsAs Seq(
      ChannelPressureScMidiMessage(output1Channel, (10 + 0) / 2),
      ChannelPressureScMidiMessage(output2Channel, 10)
    )
    channelPressures3 should contain theSameElementsAs Seq(
      ChannelPressureScMidiMessage(output3Channel, 30),
      ChannelPressureScMidiMessage(output4Channel, 30),
      ChannelPressureScMidiMessage(output1Channel, (10 + 30) / 2)
    )
  }
```

- [ ] **Step 2: Activate and correct `:1530` "distribute the slide values of the input channel" (red)**

```scala
  it should "distribute the slide values of the input channel" in new DistributeFixture {
    // When
    private val slides1 = extractSlides(slide(1, 10))
    private val slides3 = extractSlides(slide(3, 30))

    // Then
    // Output channel 1 also holds D5, which arrived on input channel 3 and still carries the default CC #74
    // of 64, so the channel emits the average of the two notes.
    slides1 should contain theSameElementsAs Seq(
      CcScMidiMessage(output1Channel, ScMidiCc.MpeSlide, (10 + 64) / 2),
      CcScMidiMessage(output2Channel, ScMidiCc.MpeSlide, 10)
    )
    slides3 should contain theSameElementsAs Seq(
      CcScMidiMessage(output3Channel, ScMidiCc.MpeSlide, 30),
      CcScMidiMessage(output4Channel, ScMidiCc.MpeSlide, 30),
      CcScMidiMessage(output1Channel, ScMidiCc.MpeSlide, (10 + 30) / 2)
    )
  }
```

- [ ] **Step 3: Add the B1 test — a dropped note's later Note Off is discarded (red)**

Place it in `behavior of "MpeTuner - process() - Note Dropping - MPE Input"`, in the
`// ---- Channel exhaustion dropping (mirrors Non-MPE) ----` subgroup, after the existing dropping cases.

```scala
  it should "discard the Note Off of a note the Tuner has dropped" in new Fixture(tuner3MpeInput) {
    // Given
    // PCG=1, EG=2: C4, E4 and G4 fill the three Member Channels; A4 then forces a channel to be freed and
    // the middle note E4 is the only non-boundary candidate.
    noteOn(1, C4)
    private val e4Output = noteOn(2, E4)
    private val e4Channel = extractNoteOns(e4Output).head.channel
    noteOn(3, G4)
    private val dropOutput = noteOn(1, A4)
    extractNoteOffs(dropOutput) shouldEqual Seq(NoteOffScMidiMessage(e4Channel, E4))

    // When
    // The performer eventually releases the note the Tuner had already dropped.
    private val output = noteOff(2, E4)

    // Then
    // No second Note Off downstream: the Tuner has already discharged this note's obligation.
    extractNoteOffs(output) shouldBe empty
    // And no stale binding steers a later expressive update at the dropped note's former channel.
    extractPitchBends(pitchBend(2, 20.0)) shouldBe empty
  }
```

- [ ] **Step 4: Run to confirm the red state**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: FAIL — the two distribution tests fail because CC #74 and Channel Pressure are still fanned out raw by
`forwardToMemberChannel`, and the dropped-note test fails with a duplicate Note Off emitted from the stale
`channelNoteMap` entry.

- [ ] **Step 5: Delete `channelNoteMap` and its helpers**

Remove from `MpeTuner.scala`:
- the `channelNoteMap` field and its comment (lines 77–83),
- `trackNote` (524–526), `untrackNote` (528–534), `outputChannelsFor` (536–545),
- `forwardToMemberChannel` (588–600),
- `getAllocatorForOutput` (740–748) — no caller remains,
- the `channelNoteMap.clear()` line in `resetState` (line 144).

- [ ] **Step 6: Rewrite the Note On and Note Off paths**

```scala
  private def processNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage): Unit = {
    if (_inputMode == MpeInputMode.Mpe && isMasterChannel(msg.channel)) {
      // Master Channel notes are forwarded as-is: no allocator, no tuning offset, no control
      // dimension setup. They play in 12-EDO (modulated only by Master Pitch Bend) because
      // applying a per-pitch-class tuning offset on the Master Channel would affect every
      // note in the Zone. `tracker` keeps them, which is all stopAllNotes needs.
      buffer += msg.asJava
    } else {
      processMemberNoteOn(buffer, msg)
    }
  }
```

```scala
  private def processNoteOff(buffer: mutable.Buffer[MidiMessage], msg: NoteOffScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    if (_inputMode == MpeInputMode.Mpe && isMasterChannel(inputChannel)) {
      buffer += msg.asJava
    } else {
      getAllocatorForInput(inputChannel).foreach { alloc =>
        // A `None` result means the identity holds no active count — chiefly after the Tuner dropped the
        // note itself, having already emitted its Note Offs — so the message is discarded.
        alloc.release(NoteIdentity(inputChannel, midiNote)).foreach { result =>
          buffer += NoteOffScMidiMessage(result.channel, midiNote, velocity).asJava
        }
      }
    }
  }
```

The `$COVERAGE-OFF$` / `$COVERAGE-ON$` markers and the `logger.warn` they wrapped disappear with the old body: the
discard is now a specified path exercised by tests, and the markers are dead under Scala 3 in any case.

- [ ] **Step 7: Route CC #74, Channel Pressure and Polyphonic Key Pressure through the allocator**

Add the shared emission helper next to `emitDroppedNoteOffs`, and rename `emitTuningPitchBend` to
`emitOutputPitchBend` (it emits Tuning Pitch Bend + Expression Pitch Bend), updating its two call sites in
`updateTuningOnZone` and `processPitchBend`:

```scala
  /**
   * Emits the control dimension messages for the Expression Values that changed on an output Member
   * Channel, in the relative order Pitch Bend, CC #74, Channel Pressure.
   */
  private def emitExpressionUpdate(buffer: mutable.Buffer[MidiMessage], channel: Int,
                                   update: MpeExpressionUpdate, alloc: MpeChannelAllocator): Unit = {
    if (update.pitchBendCents.isDefined) emitOutputPitchBend(buffer, channel, alloc)
    update.slide.foreach { value => buffer += CcScMidiMessage(channel, ScMidiCc.MpeSlide, value).asJava }
    update.pressure.foreach { value => buffer += ChannelPressureScMidiMessage(channel, value).asJava }
  }

  /**
   * Applies an Expression Value update received on an input Member Channel: emits the Note Offs of any
   * notes the update dropped first, then the recomputed Expression Values of each affected output channel.
   */
  private def emitExpressionUpdateResult(buffer: mutable.Buffer[MidiMessage], result: ExpressionUpdateResult,
                                         alloc: MpeChannelAllocator,
                                         dropReason: String = "expression pitch bend too high"): Unit = {
    result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, dropReason))
    result.channelUpdates.foreach { channelUpdate =>
      emitExpressionUpdate(buffer, channelUpdate.channel, channelUpdate.update, alloc)
    }
  }
```

Use it from `processPitchBend`'s MPE member-channel branch (replacing the loop written in Task 2, Step 4):

```scala
        getAllocatorForInput(inputChannel).foreach { alloc =>
          val pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
            pitchBendValue, currentZone(alloc).memberPitchBendSensitivity)
          emitExpressionUpdateResult(buffer, alloc.updateExpressionPitchBend(inputChannel, pitchBendCents),
            alloc, "expression pitch bend too high")
        }
```

`processChannelPressure` and the CC #74 case of `processCc`:

```scala
  private def processChannelPressure(buffer: mutable.Buffer[MidiMessage],
                                     msg: ChannelPressureScMidiMessage): Unit = {
    if (inputMode == MpeInputMode.Mpe) {
      // Per-note pressure in MPE input: it belongs to every note active on the input channel, wherever the
      // pitch-class invariant placed them. A Master Channel carries no allocated note, so nothing is
      // emitted for one; forwarding Master Channel Channel Pressure as a Zone-level control is TODO #154.
      getAllocatorForInput(msg.channel).foreach { alloc =>
        emitExpressionUpdateResult(buffer, alloc.updatePressure(msg.channel, msg.value), alloc)
      }
    } else {
      // Non-MPE input: Channel Pressure applies to all notes on the input channel. Route to the
      // Zone's Master Channel as Zone-level pressure.
      forwardOnZoneMasterChannel(buffer, msg)
    }
  }
```

```scala
      // CC #74 (MPE Slide / timbre): in MPE mode it is a per-note Expression Value of the notes active on
      // the input channel; in non-MPE mode it is a Zone-level control on the Master Channel, and never
      // reaches a Member Channel.
      case ScMidiCc.MpeSlide =>
        if (inputMode == MpeInputMode.Mpe) {
          getAllocatorForInput(inputChannel).foreach { alloc =>
            emitExpressionUpdateResult(buffer, alloc.updateSlide(inputChannel, ccValue), alloc)
          }
        } else {
          forwardOnZoneMasterChannel(buffer, msg)
        }
```

`processPolyPressure`'s Non-MPE branch:

```scala
    } else {
      // Non-MPE input: convert Polyphonic Key Pressure to Channel Pressure on the allocated Member
      // Channel, since MPE forbids Polyphonic Key Pressure on Member Channels. The value is the addressed
      // note's own Expression Value and is averaged with those of the other notes on its output channel.
      getAllocatorForInput(inputChannel).foreach { alloc =>
        emitExpressionUpdateResult(buffer,
          alloc.updatePressure(NoteIdentity(inputChannel, midiNote), pressure), alloc)
      }
    }
```

- [ ] **Step 8: Rewrite `stopAllNotes`**

```scala
  /**
   * Emits a Note Off for every note the Tuner currently considers active: the allocators' own bindings for
   * Member Channel notes, and — in MPE Input Mode — the Master Channel notes the tracker holds, which are
   * forwarded on the channel they arrived on.
   */
  private def stopAllNotes(buffer: mutable.Buffer[MidiMessage]): Unit = {
    for {
      alloc <- Seq(lowerAllocator, upperAllocator).flatten
      (noteIdentity, outChannel) <- alloc.activeAllocations
    } {
      buffer += NoteOffScMidiMessage(outChannel, noteIdentity.midiNote).asJava
    }

    if (_inputMode == MpeInputMode.Mpe) {
      for {
        zone <- Seq(lowerZone, upperZone) if zone.isEnabled
        midiNote <- tracker.activeNotes(zone.masterChannel)
      } {
        buffer += NoteOffScMidiMessage(zone.masterChannel, midiNote).asJava
      }
    }
  }
```

- [ ] **Step 9: Run the module and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS, with 10 ignored tests remaining.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Move MpeTuner note bookkeeping into the channel allocator

Remove channelNoteMap and route Note On/Off, CC #74, Channel Pressure and
Polyphonic Key Pressure through the allocator, so dropped notes leave no stale
binding and control updates reach the notes they belong to.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Note On — seeding, message ordering and the emission optimization

Closes P2 and C1, and makes §6.2.2 reachable. Seven ignored tests activate here.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:209-255`
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala:352,520,588,658,672,798,1052,1421,1445,1470,1687,1704,1721,1738,2315`

**Interfaces:**
- Consumes: `AllocationResult.{channel, update, droppedNotes, isDuplicate}`, `ImmutableMpeExpression`.
- Produces: `MpeTuner.inputExpressionOf(inputChannel, zone): MpeExpression`.

- [ ] **Step 1: Activate the ignored tests that seeding unlocks (red)**

Replace `ignore` with `it`, leaving the bodies untouched, at lines 520, 1421, 1445, 1687, 1704, 1721, 1738 and 2315.

- [ ] **Step 2: Fix and activate the two ignored tests whose expectations are wrong (red)**

`:1052` — an input bend of `-16.67` cents quantizes to `-16.41` and rounds to `-16`, not to the `-17` the test
expects. Retarget it at a value that survives the roundtrip:

```scala
  it should "seed Member Channel Pitch Bend from the per-input-channel value at Note On" in
    new Fixture(tuner7MpeInput) {
      // When
      private val output = noteOn(mpeInputChannel, C4, pbCents = Some(-20.0))
      // Then
      private val noteChannel = extractNoteOns(output).head.channel
      extractPitchBendsWithCents(output) should contain((noteChannel, -20))
    }
```

`:1470` — the second Note On must carry a *slide*, not a pressure; the expected `(48 + 16) / 2` is the slide average:

```scala
      // When: 1 PCG free, but cannot be used for E => new E2 will share channel with E1
      private var output = noteOn(2, E2, slide = Some(16))
```

Replace `ignore` with `it` on both.

- [ ] **Step 3: Rewrite the five green tests that the emission optimization changes (red)**

`:588` — Non-MPE. CC #74 never reaches a Member Channel and Channel Pressure 0 is unchanged, so only the Pitch Bend
precedes the Note On:

```scala
  it should "output Pitch Bend, then Note On for single Note On" in
    new Fixture(initialTuning = Some(quarterCommaMeantone)) {
      // When
      private val output = noteOn(nonMpeInputChannel, C4, 100)
      // Then
      private val msgs = extractScMidiMessages(output)
      private val noteChannel = extractNoteOns(output).head.channel

      // Pitch Bend carries the tuning offset; CC #74 never appears on a Member Channel in this mode and
      // Channel Pressure already holds its default, so both are omitted.
      msgs should contain inOrder(
        PitchBendScMidiMessage(noteChannel, 0),
        NoteOnScMidiMessage(noteChannel, C4, 100)
      )
      extractSlides(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
    }
```

`:658` — becomes the C1 test named by the design, covering the Note On, Poly Pressure and Note Off paths:

```scala
  it should "never send CC #74 on a Member Channel" in new Fixture {
    // Given
    // The sender's CC #74 is redirected to the Master Channel, never seeding a Member Channel.
    slide(nonMpeInputChannel, 120)
    // When
    private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
    private val polyPressureOutput = tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
    private val noteOffOutput = noteOff(nonMpeInputChannel, C4)
    // Then
    extractSlides(noteOnOutput) shouldBe empty
    extractSlides(polyPressureOutput) shouldBe empty
    extractSlides(noteOffOutput) shouldBe empty
  }
```

`:672` — the redirected Channel Pressure must not reach the Member Channel, and the unchanged default is omitted:

```scala
  it should "not send Channel Pressure on a Member Channel at Note On" in new Fixture {
    // Given
    // The sender's Channel Pressure is redirected to the Master Channel.
    pressure(nonMpeInputChannel, 100)
    // When
    private val output = noteOn(nonMpeInputChannel, C4)
    // Then
    // The Member Channel's Channel Pressure already holds its default of 0, so no message is needed.
    extractChannelPressures(output) shouldBe empty
  }
```

`:798` — MPE. Exercise the §7.5 ordering with input state that actually changes all three dimensions:

```scala
  it should "output Pitch Bend, CC #74, Channel Pressure, then Note On for single Note On" in
    new Fixture(tuner7MpeInput, initialTuning = Some(quarterCommaMeantone)) {
      // When
      // The input channel carries a bend, a pressure and a CC #74 that all differ from the output
      // channel's retained defaults, so all three setup messages are emitted.
      private val output = noteOn(mpeInputChannel, C4, 100,
        pbCents = Some(20.0), pressure = Some(90), slide = Some(100))
      // Then
      private val msgs = extractScMidiMessages(output)
      private val noteChannel = extractNoteOns(output).head.channel
      private val pitchBend = extractPitchBends(output).head

      msgs should contain inOrder(
        pitchBend,
        CcScMidiMessage(noteChannel, ScMidiCc.MpeSlide, 100),
        ChannelPressureScMidiMessage(noteChannel, 90),
        NoteOnScMidiMessage(noteChannel, C4, 100)
      )
      // C has a 0.0 cents offset in quarter-comma meantone, so the Pitch Bend is the expression component.
      pitchBend.channel shouldBe noteChannel
      pitchBend.cents shouldEqual 20.0
    }
```

`:352` — the MPE-block reset test asserts messages the optimization now omits, and was constructing a Non-MPE tuner:

```scala
  it should "clear internal state after reset" in
    new Fixture(mpeTunerMpeInput, initialTuning = Some(quarterCommaMeantone)) {
      // Given
      // Play a note carrying expression, and leave a CC #74 on another input channel.
      noteOn(1, C4, pbCents = Some(50.0), pressure = Some(32))
      slide(2, 64)

      // When
      // Reset should clear everything
      tuner.reset()

      // Then
      // tune() with no active notes should produce no pitch bend messages
      private var output = tuner.tune(pythagoreanTuning)
      extractPitchBends(output) shouldBe empty

      // The retained Expression Values are back to their defaults: the note carries no expression bend,
      // and neither Channel Pressure nor CC #74 is emitted because both already hold their default.
      output = noteOn(1, C4)
      extractPitchBendsWithCents(output) should contain((1, 0))
      extractChannelPressures(output) shouldBe empty
      extractSlides(output) shouldBe empty

      private val output2 = noteOn(2, D4)
      extractSlides(output2) shouldBe empty
    }
```

- [ ] **Step 4: Run to confirm the red state**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: FAIL — the activated tests fail because the note's Expression Values are still the defaults, and the
rewritten stream tests fail because CC #74 and Channel Pressure are still emitted unconditionally.

- [ ] **Step 5: Implement seeding and the new Note On emission rules**

Replace `processMemberNoteOn` (lines 209–255) with:

```scala
  private def processMemberNoteOn(buffer: mutable.Buffer[MidiMessage], msg: NoteOnScMidiMessage): Unit = {
    val inputChannel = msg.channel
    val midiNote = msg.midiNote
    val velocity = msg.velocity

    getAllocatorForInput(inputChannel) match {
      case Some(alloc) =>
        val zone = currentZone(alloc)
        // In MPE Input Mode the note's Expression Values are initialized from the state remembered for its
        // input Member Channel; in Non-MPE Input Mode there are none to take, and the allocator's defaults
        // apply — which is also what keeps CC #74 off the Member Channel in that mode.
        val expression = Option.when(_inputMode == MpeInputMode.Mpe)(inputExpressionOf(inputChannel, zone))
        val preferredChannel = Option.when(
          _inputMode == MpeInputMode.Mpe && zone.memberChannels.contains(inputChannel))(inputChannel)

        val result = alloc.allocate(NoteIdentity(inputChannel, midiNote), expression, preferredChannel)
        val outChannel = result.channel

        // Dropped notes are released before every message emitted for the new note: emitting the setup
        // messages first would retune the notes being dropped on their way out.
        result.droppedNotes.foreach(emitDroppedNoteOffs(buffer, _, "allocation overflow on new Note On"))

        // Pitch Bend, CC #74, Channel Pressure, then the Note On. Pitch Bend is emitted unconditionally on
        // a fresh allocation: what goes on the wire is Tuning Pitch Bend + Expression Pitch Bend, and the
        // tuning half is invisible to the allocator — a channel that was unoccupied retains the bend of a
        // note of a different pitch class and has missed every tune() that ran while it was empty. On a
        // duplicate Note On the channel was occupied by this very identity throughout, so the tuning half
        // is current by construction and Pitch Bend follows the same "only when changed" rule as the rest.
        if (!result.isDuplicate || result.update.pitchBendCents.isDefined) {
          emitOutputPitchBend(buffer, outChannel, alloc)
        }
        result.update.slide.foreach { value =>
          buffer += CcScMidiMessage(outChannel, ScMidiCc.MpeSlide, value).asJava
        }
        result.update.pressure.foreach { value =>
          buffer += ChannelPressureScMidiMessage(outChannel, value).asJava
        }

        buffer += NoteOnScMidiMessage(outChannel, midiNote, velocity).asJava

      case None =>
        // No allocator for this channel, forward as-is
        buffer += NoteOnScMidiMessage(inputChannel, midiNote, velocity).asJava
    }
  }

  /**
   * The Expression Values a note arriving on an input Member Channel starts with, taken from the control
   * state remembered for that channel — the state-tracking obligation the MPE Specification places on
   * receivers, so that a Pitch Bend, CC #74 or Channel Pressure sent before the Note On is not lost.
   */
  private def inputExpressionOf(inputChannel: Int, zone: MpeZone): MpeExpression = ImmutableMpeExpression(
    pitchBendCents = PitchBendScMidiMessage.convertValueToCents(
      tracker.pitchBend(inputChannel), zone.memberPitchBendSensitivity),
    pressure = tracker.channelPressure(inputChannel),
    slide = tracker.cc(inputChannel, ScMidiCc.MpeSlide))
```

- [ ] **Step 6: Run the module and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS, with 0 ignored tests remaining.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Seed Note On Expression Values and emit only what changed

Initialize a note's Expression Values from its input Member Channel's remembered
control state, and emit CC #74 and Channel Pressure only when the output
channel's average moves — which also stops CC #74 reaching a Member Channel in
Non-MPE Input Mode.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Note Off — recomputation, ordering and the Channel Pressure reset

Closes P4 and C2.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` (`processNoteOff`)
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` (new tests in
  `process() - Basic - MPE Input` → `// ---- Note Off behavior ----` and
  `process() - Basic - Non-MPE Input` → `// ---- Note Off behavior ----`)

**Interfaces:**
- Consumes: `ReleaseResult.{channel, update, pressureWasReset}` from Task 2.

- [ ] **Step 1: Write the MPE recomputation and ordering tests (red)**

Add to `behavior of "MpeTuner - process() - Basic - MPE Input"`, subgroup `// ---- Note Off behavior ----`:

```scala
  it should "emit the Expression Values recomputed over the remaining notes after the Note Off" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // PCG=2, EG=2: E1 takes a Pitch Class Group channel, E3 and E4 fill the Expression Group, and E2
      // shares E1's channel (criterion (c): the oldest onset).
      private val e1Output = noteOn(1, E1, pbCents = Some(10.0), pressure = Some(32), slide = Some(48))
      private val sharedChannel = extractNoteOns(e1Output).head.channel
      noteOn(3, E3)
      noteOn(4, E4)
      noteOn(2, E2, pbCents = Some(30.0), pressure = Some(96), slide = Some(96))

      // When
      private val output = noteOff(1, E1)

      // Then
      // The Note Off is emitted first, then the values recomputed over E2 alone, in the order
      // Pitch Bend, CC #74, Channel Pressure.
      extractScMidiMessages(output).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
        case cc: CcScMidiMessage if cc.number == ScMidiCc.MpeSlide => "slide"
        case _: ChannelPressureScMidiMessage => "pressure"
      } shouldEqual Seq("noteOff", "pitchBend", "slide", "pressure")

      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(sharedChannel, E1))
      extractPitchBends(output).head.cents shouldEqual (quarterCommaMeantone.e + 30.0)
      extractSlides(output) shouldEqual Seq(CcScMidiMessage(sharedChannel, ScMidiCc.MpeSlide, 96))
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(sharedChannel, 96))
    }

  it should "emit the Note Off alone when the released note was the last on its channel" in
    new Fixture(tuner7MpeInput, Some(quarterCommaMeantone)) {
      // Given
      private val noteOnOutput = noteOn(1, E4, pbCents = Some(30.0), pressure = Some(96), slide = Some(96))
      private val channel = extractNoteOns(noteOnOutput).head.channel
      // When
      private val output = noteOff(1, E4)
      // Then
      // Averaging no longer applies and the channel retains its latest Expression Values, so none of the
      // three changes and none is emitted. In MPE Input Mode the Tuner emits no Channel Pressure reset of
      // its own either: that dimension passes through from the sender.
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractPitchBends(output) shouldBe empty
      extractSlides(output) shouldBe empty
      extractChannelPressures(output) shouldBe empty
    }
```

- [ ] **Step 2: Write the Non-MPE Channel Pressure reset tests (red)**

Add to `behavior of "MpeTuner - process() - Basic - Non-MPE Input"`, subgroup `// ---- Note Off behavior ----`:

```scala
  it should "reset Channel Pressure before the Note Off when the released note was the last on its channel" in
    new Fixture(tuner7) {
      // Given
      private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
      private val channel = extractNoteOns(noteOnOutput).head.channel
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
      // When
      private val output = noteOff(nonMpeInputChannel, C4)
      // Then
      // In this mode the Tuner is the controller that synthesized the Channel Pressure, so it zeroes it
      // itself — the one control message emitted before the Note Off.
      extractScMidiMessages(output).collect {
        case _: ChannelPressureScMidiMessage => "pressure"
        case _: NoteOffScMidiMessage => "noteOff"
      } shouldEqual Seq("pressure", "noteOff")
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(channel, 0))
    }

  it should "emit the reduced Channel Pressure average after the Note Off when other notes remain" in
    new Fixture(tuner2) {
      // Given
      // PCG=1, EG=1: C4 and C5 take the two channels and C3 shares C4's, the oldest by onset.
      private val out1 = noteOn(nonMpeInputChannel, C4)
      private val sharedChannel = extractNoteOns(out1).head.channel
      noteOn(nonMpeInputChannel, C5)
      extractNoteOns(noteOn(nonMpeInputChannel, C3)).head.channel shouldBe sharedChannel
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C4, 80).asJava)
      tuner.process(PolyPressureScMidiMessage(nonMpeInputChannel, C3, 20).asJava)
      // When
      private val output = noteOff(nonMpeInputChannel, C4)
      // Then
      // The channel keeps a note, so the withdrawal reduces the average rather than zeroing it, and the
      // recomputed value follows the Note Off.
      extractScMidiMessages(output).collect {
        case _: ChannelPressureScMidiMessage => "pressure"
        case _: NoteOffScMidiMessage => "noteOff"
      } shouldEqual Seq("noteOff", "pressure")
      extractChannelPressures(output) shouldEqual Seq(ChannelPressureScMidiMessage(sharedChannel, 20))
    }

  it should "not emit a Channel Pressure reset when the channel already holds the default" in
    new Fixture(tuner7) {
      // Given
      private val noteOnOutput = noteOn(nonMpeInputChannel, C4)
      extractNoteOns(noteOnOutput).head.channel
      // When
      // No Polyphonic Key Pressure ever arrived, so the retained value is already 0.
      private val output = noteOff(nonMpeInputChannel, C4)
      // Then
      extractChannelPressures(output) shouldBe empty
    }
```

- [ ] **Step 3: Run to confirm the red state**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: FAIL — nothing is emitted after a Note Off, and no Channel Pressure reset is emitted before one.

- [ ] **Step 4: Implement the Note Off emission rules**

Replace the member-channel branch of `processNoteOff` written in Task 3:

```scala
      getAllocatorForInput(inputChannel).foreach { alloc =>
        // The Channel Pressure reset applies in Non-MPE Input Mode only: there the Tuner is the controller
        // that synthesized the value, whereas in MPE Input Mode the dimension passes through from the
        // sender and a conforming sender's own pre-release reset reaches the output as an ordinary update.
        val resetPressureOnEmpty = _inputMode == MpeInputMode.NonMpe

        // A `None` result means the identity holds no active count — chiefly after the Tuner dropped the
        // note itself, having already emitted its Note Offs — so the message is discarded.
        alloc.release(NoteIdentity(inputChannel, midiNote), resetPressureOnEmpty).foreach { result =>
          val outChannel = result.channel

          // The reset is the sole control message emitted before the Note Off; every other recomputed
          // value follows it, so that the released note's control state is final at the moment of release.
          if (result.pressureWasReset) {
            result.update.pressure.foreach { value =>
              buffer += ChannelPressureScMidiMessage(outChannel, value).asJava
            }
          }

          buffer += NoteOffScMidiMessage(outChannel, midiNote, velocity).asJava

          if (result.update.pitchBendCents.isDefined) emitOutputPitchBend(buffer, outChannel, alloc)
          result.update.slide.foreach { value =>
            buffer += CcScMidiMessage(outChannel, ScMidiCc.MpeSlide, value).asJava
          }
          if (!result.pressureWasReset) {
            result.update.pressure.foreach { value =>
              buffer += ChannelPressureScMidiMessage(outChannel, value).asJava
            }
          }
        }
      }
```

- [ ] **Step 5: Run the module and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Emit recomputed Expression Values after a Note Off

Withdraw the released note from its channel's averages and emit what changed
after the Note Off, with the Non-MPE Channel Pressure reset as the one message
that precedes it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Duplicate Note On and reference counting, end to end

Closes N1 and N2 at the `MpeTuner` level, tracing worked example §9.6.

**Files:**
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
  (`process() - Basic - MPE Input` → `// ---- Paper worked examples ----`)

**Interfaces:**
- Consumes: the duplicate handling implemented in Tasks 2 and 4. No production code should need to change; if a test
  fails, fix the production code rather than the expectation.

Like Task 9's, both tests trace a paper walkthrough and are annotated with its numbered steps rather than a single
`Given` / `When` / `Then` triple. They are the end-to-end cover for behavior already specified and implemented in
Tasks 2 and 4, so there is no red step of their own.

- [ ] **Step 1: Write the §9.6 Part 1 test (same input channel)**

```scala
  it should "reproduce paper section \"Duplicate Note On messages\" part 1 — the same input channel" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // 1. Note On E4 on input Channel 1: the reference count goes 0 -> 1, so allocation runs and the
      //    tuning Pitch Bend precedes the Note On.
      private val out1 = noteOn(1, E4)
      private val channel = extractNoteOns(out1).head.channel
      extractPitchBends(out1).head.cents shouldEqual quarterCommaMeantone.e

      // 2. Channel Pressure 80 on input Channel 1: the channel holds one identity, so its average is 80.
      private val out2 = pressure(1, 80)
      extractChannelPressures(out2) shouldEqual Seq(ChannelPressureScMidiMessage(channel, 80))

      // 3. A second Note On for E4 on input Channel 1, the first still active: the identity is unchanged,
      //    so the count goes 1 -> 2, allocation is bypassed, and overriding the note's Expression Values
      //    with the input channel's current state moves no average — the Note On is emitted alone.
      private val out3 = noteOn(1, E4)
      extractNoteOns(out3) shouldEqual Seq(NoteOnScMidiMessage(channel, E4))
      extractScMidiMessages(out3) should have size 1

      // 4. Note Off E4: the count goes 2 -> 1; the identity stays active and stays in the channel's
      //    averages, so nothing follows the Note Off.
      private val out4 = noteOff(1, E4)
      extractNoteOffs(out4) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractScMidiMessages(out4) should have size 1

      // 5. Note Off E4: the count goes 1 -> 0 and the identity leaves the averages, emptying the channel;
      //    retention leaves all three values unchanged, so the Note Off is again emitted alone.
      private val out5 = noteOff(1, E4)
      extractNoteOffs(out5) shouldEqual Seq(NoteOffScMidiMessage(channel, E4))
      extractScMidiMessages(out5) should have size 1

      // Two Note Ons entered and two were forwarded, two Note Offs entered and two were forwarded.
      // A third Note Off finds no count and is discarded.
      extractNoteOffs(noteOff(1, E4)) shouldBe empty
    }
```

- [ ] **Step 2: Write the §9.6 Part 2 test (different input channels)**

```scala
  it should "reproduce paper section \"Duplicate Note On messages\" part 2 — different input channels" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // PCG=2, EG=2. Input Channel 1 carries an Expression Pitch Bend of +10 cents and input Channel 2
      // one of −20 cents; neither channel has an active note yet, so nothing is emitted for them.
      extractPitchBends(pitchBend(1, 10.0)) shouldBe empty
      extractPitchBends(pitchBend(2, -20.0)) shouldBe empty

      // 1. Note On E4 on input Channel 1 -> identity (1, E4), Step 1 assigns output Channel 1.
      private val out1 = noteOn(1, E4)
      private val chE = extractNoteOns(out1).head.channel
      chE shouldBe 1
      extractPitchBends(out1).head.cents shouldEqual (quarterCommaMeantone.e + 10.0)

      // 2. Note On G4 on input Channel 1 -> identity (1, G4): the same input channel, a different note
      //    number and hence a different identity, filling the Pitch Class Group.
      private val out2 = noteOn(1, G4)
      private val chG = extractNoteOns(out2).head.channel
      chG should not be chE
      extractPitchBends(out2).head.cents shouldEqual (quarterCommaMeantone.g + 10.0)

      // 3. C4 and A4 fill the Expression Group; all four Member Channels are now occupied.
      noteOn(3, C4)
      noteOn(4, A4)

      // 4. Note On E4 on input Channel 2 -> identity (2, E4), distinct from (1, E4). Steps 1 and 2 fail,
      //    so Step 3 assigns the channel already holding pitch class E, and its Expression Pitch Bend
      //    becomes the average of the two identities.
      private val out4 = noteOn(2, E4)
      extractNoteOns(out4).head.channel shouldBe chE
      extractPitchBends(out4).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 - 20.0) / 2)

      // The fan-out that accompanies this fan-in: a Pitch Bend on input Channel 1 reaches both output
      // channels its notes were placed on, and only its own note's contribution moves on the shared one.
      private val bendOutput = pitchBend(1, 20.0)
      private val bends = extractPitchBends(bendOutput).map(pb => pb.channel -> pb.cents).toMap
      bends.keySet shouldEqual Set(chE, chG)
      bends(chE) shouldEqual (quarterCommaMeantone.e + (20.0 - 20.0) / 2)
      bends(chG) shouldEqual (quarterCommaMeantone.g + 20.0)

      // Both reference counts remain 1: no merging occurred, so each identity is released by its own
      // Note Off and both are forwarded on the shared channel.
      extractNoteOffs(noteOff(1, E4)) shouldEqual Seq(NoteOffScMidiMessage(chE, E4))
      extractNoteOffs(noteOff(2, E4)) shouldEqual Seq(NoteOffScMidiMessage(chE, E4))
    }
```

- [ ] **Step 3: Run the tests**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: PASS. If either test fails, the defect is in the duplicate handling of `MpeChannelAllocator.allocate` or in
the Note On emission rules — fix the production code, then rerun.

- [ ] **Step 4: Commit**

```bash
git add tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Cover duplicate Note On handling with the paper's worked example

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Warn when Non-MPE Input Mode is configured with both Zones enabled

Resolves the standing `TODO #154` at `MpeTuner.scala:67-68`.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala:67-88,105-115`
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
  (`process() - Basic - Non-MPE Input` → `// ---- Channel allocation across pitch classes ----`)

- [ ] **Step 1: Write the routing test that documents what the warning is about**

The log line itself is not observable through the Tuner's API, so the test pins the behavior that makes the
configuration wasteful — every note goes to the Lower Zone — rather than the message. This is the one task with no red
state: a `logger.warn` has no assertable contract, and the guarding test passes before the warning exists. Add nothing
beyond the warning here — any behavioral change would need its own failing test first.

```scala
  it should "route notes to the Lower Zone only when both Zones are enabled" in new Fixture(dualZoneTuner) {
    // Non-MPE input is routed exclusively to the Lower Zone, so the Upper Zone's Member Channels (8..14)
    // are unreachable and wasted — the configuration the Tuner warns about at construction and on reset().
    private val out1 = noteOn(nonMpeInputChannel, C4)
    private val out2 = noteOn(nonMpeInputChannel, E4)
    // Then
    Seq(out1, out2).flatMap(extractNoteOns).map(_.channel).foreach { channel =>
      channel should (be >= 1 and be <= 7)
    }
  }
```

- [ ] **Step 2: Run it**

```bash
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MpeTunerTest -- -oNCXEHLOPQRMWS"
```
Expected: PASS — this behavior already holds; the test guards it while the warning is added.

- [ ] **Step 3: Implement the warning**

Delete the `TODO #154` comment at lines 67–68 and add the check. Place the call after the `tracker` field so that
every field it reads is initialized:

```scala
  warnOnNonMpeInputWithBothZones()
```

```scala
  /**
   * Warns when the Tuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is
   * routed exclusively to the Lower Zone, so the Upper Zone is unreachable and its Member Channels are
   * wasted. Logged at construction and again on `reset()`, where the initial configuration is re-applied.
   */
  private def warnOnNonMpeInputWithBothZones(): Unit = {
    if (_inputMode == MpeInputMode.NonMpe && lowerZone.isEnabled && upperZone.isEnabled) {
      logger.warn("MpeTuner is configured in Non-MPE Input Mode with both Zones enabled: non-MPE input is " +
        "routed exclusively to the Lower Zone, so the Upper Zone's Member Channels are unreachable. " +
        s"Consider disabling the Upper Zone or switching to MPE Input Mode. Zones: ${_zones}")
    }
  }
```

Call it from `reset()`, after the configuration has been re-applied:

```scala
  override def reset(): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    // Emit Note Off for every active note before switching input mode / zone layout,
    // so downstream receivers are never left with hanging notes (MPE spec Section 2.1.4).
    stopAllNotes(buffer)
    _zones = initialZones
    _inputMode = initialInputMode
    resetState()
    warnOnNonMpeInputWithBothZones()
    buffer ++= configurationMessages()
    buffer.toSeq
  }
```

- [ ] **Step 4: Run the module and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS.

```bash
git add tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Warn about Non-MPE Input Mode with both Zones enabled

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Documentation — the §6.2.1 amendment and the architecture TODO

**Files:**
- Modify: `docs/architecture/tuner/mpe-tuner-paper.md` (§6.2.1, after the existing paragraph at lines 780–784)
- Modify: `docs/architecture/tuner/README.md` ("Subject to change", the TODO #154 bullet)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` (one new `TODO #154`)

- [ ] **Step 1: Amend §6.2.1 of the paper**

Append this paragraph to `#### 6.2.1 Divergence on a Shared Channel`, after the existing rationale sentence:

```markdown
Several notes on a shared channel may acquire a High Expression Pitch Bend from the *same* message, since a Pitch Bend
is a channel message that belongs to every note active on the input channel it arrives on (Section 7.2). The rule above
then has no single bending note to protect. In that case the Tuner retains the most recently sounded of them — the one
whose Note On is latest — and drops all the others. Retaining one preserves the performer's gesture on a voice rather
than silencing the channel, and leaving exactly one active note restores invariant 2 of Section 6.3, which the incoming
message had broken.
```

Make no other change to the paper: it is the source of truth, and every other behavior implemented in this cycle is
already in it.

- [ ] **Step 2: Refresh the architecture README bullet**

In `docs/architecture/tuner/README.md`, under "Subject to change", replace:

```markdown
- `MpeTuner` warns/forbids are still TODO for the non-MPE-input-with-both-zones case, and per-note MPE expression is not
  yet updated continuously (TODO #154).
```

with:

```markdown
- `MpeTuner`'s MIDI message routing and filtering does not yet conform to the paper: RPN/NRPN sequences, the scope of
  the state reset on Zone reconfiguration, messages arriving outside every Zone or at the wrong level, and the MIDI
  Mode messages 124–127 are still open (TODO #154). The per-note Expression Value model itself — averaging, fan-out,
  reference counting and the Note On/Note Off emission rules — is implemented.
```

- [ ] **Step 3: Leave the matching code marker**

The "Subject to change" entries are signalled in the code, so add a single `TODO #154` above
`resolveZoneMasterChannel` in `MpeTuner.scala` covering the deferred work:

```scala
  // TODO #154 Message routing and filtering conformance: a channel outside every Zone must have its
  //  messages discarded rather than passed through, a Zone-level message arriving on an input Member
  //  Channel must be discarded, and the MIDI Mode messages 124-127 must never be forwarded.
```

- [ ] **Step 4: Verify and commit**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS (documentation only; the run guards against an accidental edit).

```bash
git add docs/architecture/tuner/mpe-tuner-paper.md docs/architecture/tuner/README.md \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala
git commit -m "$(cat <<'EOF'
[#154] Record the simultaneous high-bend case in paper section 6.2.1

Also refresh the tuner architecture doc's TODO #154 bullet to the routing and
filtering work that remains.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Worked examples §9.3 and §9.5

**Files:**
- Modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala`
  (`process() - Expression - MPE Input` → new `// ---- Paper worked examples ----` subgroup at the end of the block,
  and `process() - Note Dropping - MPE Input` → the same, at the end of that block)

These two tests trace a paper walkthrough step by step, so their bodies are annotated with the paper's numbered steps
instead of a single `Given` / `When` / `Then` triple — the numbered comments carry the same information at the
granularity the walkthrough has. Both are pure test additions: if either fails, fix the production code.

- [ ] **Step 1: Write the §9.3 end-to-end test**

Add at the end of `behavior of "MpeTuner - process() - Expression - MPE Input"`, under a new
`// ---- Paper worked examples ----` subgroup:

```scala
  it should "reproduce paper section \"Averaging Expression Values\"" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // 1. E1 arrives on input Channel 1, which carries Pitch Bend +10 cents, Channel Pressure 32 and
      //    CC #74 48 — remembered from before the note and used to initialize its Expression Values.
      //    Step 1 assigns output Channel 1.
      private val out1 = noteOn(1, E1, pbCents = Some(10.0), pressure = Some(32), slide = Some(48))
      private val ch = extractNoteOns(out1).head.channel
      ch shouldBe 1
      extractPitchBends(out1).head.cents shouldEqual (quarterCommaMeantone.e + 10.0)
      extractSlides(out1) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, 48))
      extractChannelPressures(out1) shouldEqual Seq(ChannelPressureScMidiMessage(ch, 32))

      // 2. E3 and E4 arrive on input Channels 3 and 4, both at default expression: pitch class E is
      //    already in the Pitch Class Group, so Step 2 places them in the Expression Group, which is now
      //    at full capacity. Each emits only its tuning Pitch Bend.
      private val out2 = noteOn(3, E3)
      private val out3 = noteOn(4, E4)
      extractPitchBends(out2).head.cents shouldEqual quarterCommaMeantone.e
      extractSlides(out2) shouldBe empty
      extractChannelPressures(out2) shouldBe empty
      extractPitchBends(out3).head.cents shouldEqual quarterCommaMeantone.e

      // 3. E2 arrives on input Channel 2 carrying Pitch Bend −20 cents, Channel Pressure 96 and CC #74 96.
      //    Both groups are unavailable for it, so Step 3 shares the oldest E channel and all three
      //    Expression Values become averages.
      private val out4 = noteOn(2, E2, pbCents = Some(-20.0), pressure = Some(96), slide = Some(96))
      extractNoteOns(out4).head.channel shouldBe ch
      extractPitchBends(out4).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 - 20.0) / 2)
      extractSlides(out4) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, (48 + 96) / 2))
      extractChannelPressures(out4) shouldEqual Seq(ChannelPressureScMidiMessage(ch, (32 + 96) / 2))

      // 4. The performer bends E2 to +30 cents: the channel's Expression Pitch Bend becomes +20 — the
      //    half-amplitude attenuation of a shared channel — and no note is dropped, the threshold applying
      //    to a note's own bend.
      private val out5 = pitchBend(2, 30.0)
      extractPitchBends(out5) should have size 1
      extractPitchBends(out5).head.cents shouldEqual (quarterCommaMeantone.e + (10.0 + 30.0) / 2)
      extractNoteOffs(out5) shouldBe empty

      // 5. Note Off for E1: the Note Off is emitted first and the values recomputed without it follow.
      //    The Channel Pressure becomes the surviving note's own value rather than 0: in MPE Input Mode
      //    the dimension passes through from the sender.
      private val out6 = noteOff(1, E1)
      extractScMidiMessages(out6).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
        case cc: CcScMidiMessage if cc.number == ScMidiCc.MpeSlide => "slide"
        case _: ChannelPressureScMidiMessage => "pressure"
      } shouldEqual Seq("noteOff", "pitchBend", "slide", "pressure")
      extractPitchBends(out6).head.cents shouldEqual (quarterCommaMeantone.e + 30.0)
      extractSlides(out6) shouldEqual Seq(CcScMidiMessage(ch, ScMidiCc.MpeSlide, 96))
      extractChannelPressures(out6) shouldEqual Seq(ChannelPressureScMidiMessage(ch, 96))

      // 6. Note Off for E2, the channel's last active note: removal empties the channel, so averaging no
      //    longer applies and retention fixes what it keeps. None of the three values changes, so the
      //    Note Off is emitted alone — the Channel Pressure in particular is not zeroed.
      private val out7 = noteOff(2, E2)
      extractNoteOffs(out7) shouldEqual Seq(NoteOffScMidiMessage(ch, E2))
      extractPitchBends(out7) shouldBe empty
      extractSlides(out7) shouldBe empty
      extractChannelPressures(out7) shouldBe empty
    }
```

- [ ] **Step 2: Write the §9.5 end-to-end test**

Add at the end of `behavior of "MpeTuner - process() - Note Dropping - MPE Input"`, under a new
`// ---- Paper worked examples ----` subgroup:

```scala
  it should "reproduce paper section \"Note dropping under High Expression Pitch Bend\"" in
    new Fixture(tuner4MpeInput, Some(quarterCommaMeantone)) {
      // Given
      // The state reached at step 4 of "Averaging Expression Values": E1 (input Channel 1, +10 cents) and
      // E2 (input Channel 2, +30 cents) share an output channel, averaging to +20.
      private val out1 = noteOn(1, E1, pbCents = Some(10.0))
      private val ch = extractNoteOns(out1).head.channel
      noteOn(3, E3)
      noteOn(4, E4)
      extractNoteOns(noteOn(2, E2, pbCents = Some(30.0))).head.channel shouldBe ch

      // When
      // The performer sends Pitch Bend +101 cents on input Channel 1: the value belongs to E1, which
      // thereby acquires a High Expression Pitch Bend.
      private val output = pitchBend(1, 101.0)

      // Then
      // E1 shares its channel, so the divergence rule drops E2 — whose own bend is well below the
      // threshold. The Note Off comes first, carrying the neutral release velocity 64 that any note ended
      // by the Tuner's decision receives, and the recomputed Pitch Bend follows: emitting it first would
      // sweep E2 to E1's bend on its way out.
      extractScMidiMessages(output).collect {
        case _: NoteOffScMidiMessage => "noteOff"
        case _: PitchBendScMidiMessage => "pitchBend"
      } shouldEqual Seq("noteOff", "pitchBend")
      extractNoteOffs(output) shouldEqual Seq(NoteOffScMidiMessage(ch, E2, 64))
      extractPitchBends(output) should have size 1
      extractPitchBends(output).head.channel shouldBe ch
      extractPitchBends(output).head.cents shouldEqual (quarterCommaMeantone.e + 101.0)
    }
```

- [ ] **Step 3: Run the whole module**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS, 0 ignored.

- [ ] **Step 4: Commit**

```bash
git add tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
git commit -m "$(cat <<'EOF'
[#154] Trace the paper's averaging and divergence worked examples end to end

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Final checks

The implementation is complete here, so run the four checks the coding workflow requires — **module tests**,
**coverage**, **full test suite** and **documentation** — creating a task for each. Steps 1–4 below are those four
tasks; Step 5 is this cycle's own inspection checklist. Report anything left unmet in the PR body of Task 11 rather
than silently skipping it.

**Files:**
- Possibly modify: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeChannelAllocatorTest.scala`,
  `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala` (tests added for coverage)
- Possibly modify: the two main files' ScalaDocs and `docs/architecture/tuner/*` (documentation check)

- [ ] **Step 1: Module tests**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS, 0 ignored. `tuner` is the only module whose sources changed.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow it: it carries the coverage policy — the per-module thresholds, the
rule that a module's floor never decreases, and the 80% target for new files — and drives the
`scoverage-inspector` MCP server that checks report freshness, rebuilds when stale and parses the XML.

Apply it to the two files this cycle rewrote, `MpeChannelAllocator.scala` and `MpeTuner.scala`, and to the `tuner`
module as a whole. Where a gap shows up, close it the TDD way — a failing test first, then the code — and note that a
gap in a *new* branch usually means a specified behavior has no test at all, not that a test is merely missing for an
existing one. Do not lower a threshold in `build.sbt` to make the check pass.

- [ ] **Step 3: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: PASS across every module. Also run `mcp__metals__compile-full`; `MpeChannelAllocator`'s and `MpeTuner`'s only
consumers are inside `tuner`, so nothing else should need changes — if another module fails, stop and report it.

- [ ] **Step 4: Documentation**

- Every public identifier added or changed in `MpeChannelAllocator.scala` and `MpeTuner.scala` carries a ScalaDoc.
  The snippets in Tasks 2–7 include them; verify none was dropped while implementing.
- [`docs/architecture/tuner/README.md`](../../../docs/architecture/tuner/README.md) matches the code after Task 8's
  edit — in particular no surviving mention of `channelNoteMap`, `forwardToMemberChannel` or of per-note expression
  being unimplemented.
- [`docs/architecture/tuner/mpe-spec.md`](../../../docs/architecture/tuner/mpe-spec.md) does not describe behavior this
  cycle changed (Expression Value averaging, the Note On/Note Off message order, CC #74 on Member Channels); update it
  where it does.
- [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md) carries the
  §6.2.1 amendment from Task 8 and nothing else — it is the source of truth, not a record of the implementation.
- Agent artifacts (`CLAUDE.md` / `AGENTS.md`, `docs/agents/*`) need no change for this work; state so explicitly if
  the check finds otherwise.

- [ ] **Step 5: Verify the cycle's checklist by inspection**

- `MpeChannelAllocator` never references `MpeInputMode` — check with `mcp__metals__get-usages` on `MpeInputMode`
  (`fileInFocus` the `MpeTuner.scala` path) and confirm no usage lands in `MpeChannelAllocator.scala`.
- `channelNoteMap`, `trackNote`, `untrackNote`, `outputChannelsFor`, `forwardToMemberChannel` and
  `getAllocatorForOutput` are gone from `MpeTuner.scala` — `mcp__metals__inspect` finds no such members, and no caller
  survives elsewhere.
- No occurrence of "expressive" remains in either main file or in the two test files (`Grep` tool, case-insensitive).
- No `$COVERAGE-OFF$` / `$COVERAGE-ON$` marker remains in `MpeTuner.scala` (`Grep` tool). These markers are dead under
  Scala 3 anyway; the way to exclude a file is `coverageExcludedFiles` in `build.sbt`, which this cycle does not need.
- The only remaining `TODO #154` in the two main files is the routing/filtering one added in Task 8, and every TODO
  carries an issue number.

- [ ] **Step 6: Commit whatever the checks changed**

```bash
git status --short
```
If Steps 2–4 produced changes, commit them; otherwise this step is a no-op.

```bash
git commit -am "$(cat <<'EOF'
[#154] Close coverage and documentation gaps found in final checks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Open the cycle-1 pull request

**Files:** none — repository operations only.

- [ ] **Step 1: Confirm the branch is clean and green**

```bash
git status --short
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```
Expected: no uncommitted changes; tests pass with 0 ignored. Task 10 already ran the full suite and the coverage
check, so this is only a guard against an uncommitted edit.

- [ ] **Step 2: Open the pull request**

Invoke the `contributing` skill, then:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 154 \
  "Implement polyphonic expression for the MPE Tuner (cycle 1)" \
  "$(cat <<'EOF'
Implements the per-note Expression Value model of the MPE Tuner paper, cycle 1 of the work described in
`issues/00154-mpe-tuner-poly-expr/code/2026-07-30-poly-expression-design.md`.

- `MpeChannelAllocator` is now input-channel-aware: its channel state is keyed by Note Identity
  (input channel, note number), each identity carries a reference count and its own Expression Values, and each
  output Member Channel carries their average, retained when the channel empties.
- Expression Pitch Bend, Channel Pressure and CC #74 received on an input Member Channel fan out to every note
  active on it and only the output channels whose average actually moves receive a message.
- Note On seeds a note's Expression Values from its input channel's remembered control state; Note Off emits the
  values recomputed over the remaining notes, with the Non-MPE Channel Pressure reset preceding it.
- Gaps closed: P1, P2, P3, P4, P5, N1, N2, N5, B1, C1, C2, plus the standing TODO about Non-MPE input with both
  Zones enabled.
- Paper section 6.2.1 gains a note on several notes acquiring a High Expression Pitch Bend from one message.

Message routing and filtering conformance (P7, C3, C4, C5, C6, N4, I1, I2, I3) is cycle 2 and gets its own issue.
EOF
)"
```

The branch prefix `feature/` supplies the label; the milestone is inherited from issue #154.

- [ ] **Step 3: CHECKPOINT — Phase 1 ends here**

Report the PR URL and stop. Do not start Phase 2 until the author confirms the PR is merged into `main`.

---

# Phase 2 — hand-off (only after the cycle-1 PR is merged)

## Task 12: Cycle-2 prompt and follow-up issue

Deferring this until after the merge is the point: a prompt written before it would cite line numbers and describe
code that no longer exists.

**Files:**
- Read: `issues/00154-mpe-tuner-poly-expr/code/code-prompt.md` (the cycle-1 prompt, the source to filter)
- Create: `issues/00154-mpe-tuner-poly-expr/code/<YYYY-MM-DD>-routing-conformance-prompt.md`

- [ ] **Step 1: Confirm the merge and start from the merged tree**

```bash
git switch main
git pull
git log --oneline -5
```
Expected: the cycle-1 PR's merge commit is present. If it is not, stop — Phase 2 must not run before the merge.

- [ ] **Step 2: Create the cycle-2 branch**

```bash
git switch -c doc/mpe-tuner-routing-conformance-prompt
```

- [ ] **Step 3: Write the cycle-2 prompt as a filtered derivative of `code-prompt.md`**

Keep, re-deriving every line number and every statement about the current state of the code against the merged
`main`:

- §2.2(d), §2.2(e) and §2.2(f) of the prompt (message handling tables and RPN/NRPN atomicity).
- Gaps **P7** (RPN Null closing a forwarded Pitch Bend Sensitivity sequence), **C3** (scope of state reset on MCM
  reconfiguration), **C4** (all Zones deactivated), **C5** (Master Channel CC #74 and Channel Pressure forwarding),
  **C6** (uninterpreted RPN/NRPN traffic), **N4** (MIDI Mode messages 124–127), **I1** (active Tuning reset on
  incoming MCM), **I2** (out-of-zone notes and controls), **I3** (Zone-level messages on an input Member Channel).

Drop entirely: §1.1, §1.2, §2.1, §2.2(a)–(c) and gaps P1, P2, P3, P4, P5, N1, N2, N5, B1, C1, C2 — cycle 1 closed
them.

For every retained gap, rewrite the "current state of the implementation" paragraphs from scratch against the merged
code. In particular these statements from `code-prompt.md` are now stale and must not be carried over verbatim:

| Stale claim | Why |
|---|---|
| C3: "`stopAllNotes` (line 375) iterates all of `channelNoteMap`" | `channelNoteMap` no longer exists; `stopAllNotes` iterates the allocators' `activeAllocations` and the tracker's Master Channel notes. |
| C4: "`processMemberNoteOn`'s `case None` branch (lines 251-253)" | The method was rewritten; re-derive the line numbers and quote the current branch. |
| C5: "CC #74 and Channel Pressure go through `forwardToMemberChannel`" | That method was deleted; both now go through the allocator's update methods, which yield nothing for a Master Channel. |
| I2/I3: line references into `processCc`, `resolveZoneMasterChannel`, `findZoneForChannel` | Re-derive all of them; the file has shifted. |
| P7: "`applyPbsUpdate` (lines 485-487)" | Re-derive. |

Verify each citation before writing it — `mcp__metals__inspect` / `mcp__metals__get-usages` for a symbol and its
call sites, the `Grep` tool for plain text — and state the base commit SHA of merged `main` at the top of
the prompt, as `2026-07-30-poly-expression-design.md` does.

- [ ] **Step 4: Create the cycle-2 issue as a sub-issue of #154**

Invoke the `contributing` skill, then:

```bash
.claude/skills/contributing/scripts/microtonalist-gh issue \
  "Make MPE Tuner MIDI message routing and filtering conform to the paper" \
  "$(cat <<'EOF'
Cycle 2 of #154. Cycle 1 implemented the polyphonic expression model; what remains is message routing and
filtering conformance:

- Zone-level messages arriving on an input Member Channel must be discarded (I3).
- Messages received on a channel outside every enabled Zone must be discarded, including the degenerate case in
  which no Zone is enabled at all (I2, C4).
- Master Channel CC #74 and Channel Pressure must be forwarded as Zone-level controls (C5).
- Uninterpreted RPN/NRPN traffic must be routed per the paper, and an invalid MCM ignored in its entirety (C6).
- A forwarded Pitch Bend Sensitivity sequence must be closed with an RPN Null (P7).
- A Zone reconfiguration must reset state only for the channels entering or leaving MPE control, and must not
  discard the active Tuning (C3, I1).
- The MIDI Mode messages 124-127 must be discarded in both input modes (N4).

The prompt for this work is `issues/00154-mpe-tuner-poly-expr/code/<YYYY-MM-DD>-routing-conformance-prompt.md`.
EOF
)" --label feature --milestone MPE --wip
```

Then link it under #154 as a sub-issue (the same relationship #237 has), using the GitHub MCP
`sub_issue_write` tool with the parent `154` and the new issue number.

- [ ] **Step 5: Point the prompt at its issue, commit and open the PR**

Update the prompt's header to reference the new issue number, then:

```bash
git add issues/00154-mpe-tuner-poly-expr/code/<YYYY-MM-DD>-routing-conformance-prompt.md
git commit -m "$(cat <<'EOF'
[#154/#<child>] Add prompt for MPE Tuner routing and filtering conformance

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
.claude/skills/contributing/scripts/microtonalist-gh pr 154/<child> \
  "Prompt for MPE Tuner message routing and filtering conformance"
```

- [ ] **Step 6: Report**

Report the issue URL, the PR URL and the prompt's path. Phase 2, and this plan, end here.

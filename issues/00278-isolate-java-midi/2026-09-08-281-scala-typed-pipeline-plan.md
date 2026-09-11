# Scala-Typed MIDI Pipeline — Implementation Plan (#281)

- **Date**: 2026-09-08
- **Revised**: 2026-09-09 on `384bfa4`, the top of `refactoring/280-midi-transmitter-family` — the review of PR
  [#287](https://github.com/calinburloiu/microtonalist/pull/287) changed D4's extension mechanism, and this plan
  follows. `MutableMidiTransmitter` now makes every modifier **and** the `receivers` setter `final` and offers a
  subclass two `protected` hooks instead: `withChangeGuard`, how a change is made atomic, and `setReceivers`, what
  happens on a change and the single point every modifier and `receivers_=` funnel through. `ConcurrentMidiTransmitter`
  overrides only `receivers` (read lock) and `withChangeGuard` (write lock). So `MidiProcessorTransmitter` overrides
  **`setReceivers`**, not `receivers_=`, and carries **no locking code of its own**: the change guard has already
  taken the write lock whatever the entry point — a direct `receivers = …` assignment included — and the hook may
  read `receivers` re-entrantly to compare sequences, a lock downgrade that a `ReentrantReadWriteLock` permits. The
  old caveat that a setter override had to take the lock itself is gone. Task 3 is the only task whose code changes;
  Tasks 4 and 5 change only where they quote the mechanism. See the third revision entry of the design document. Note
  that the base commit named below predates this change: branch off the current top of
  `refactoring/280-midi-transmitter-family` and read the transmitter family there before starting Task 3.
- **Issue**: [#281](https://github.com/calinburloiu/microtonalist/issues/281) — "Carry MidiMsg through the MIDI
  pipeline and strip javax.sound.midi from tuner", sub-issue 3 of parent
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `0bd199e` — "[#278/#280] Pin the direct-assignment lock asymmetry and tidy the transmitter tests",
  the top of `refactoring/280-midi-transmitter-family` (PR [#287](https://github.com/calinburloiu/microtonalist/pull/287)).
  That branch stacks on `refactoring/279-drop-sc-prefix` (PR #286, #279) and `refactoring/isolate-java-midi` (PR #284,
  the design and the plans). The code described below is the code at that commit: the `Sc` prefix is gone, the message
  model is `MidiMsg` / `Midi1Msg` / `Midi2Msg`, `JavaMidiConverters` lives in `scmidi.javamidi`, and the
  `MidiTransmitter` family exists but nothing uses it yet.
- **Spec**: [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md) — the approved design
  for the whole of #278. The scope of this plan is **decisions D5, D6 and D7** and the **#281 row of Section 3**;
  Section 4 (testing) and Section 5 (documentation) apply where they mention the pipeline, the splitter, the boundary
  conversion and the `tuner` adaptation. No separate design document exists for #281. Two places where this plan
  refines the design are called out in [Design notes](#design-notes): the lock-ordering caveat of D6 and the
  `MidiSerialProcessor` unwiring on disconnect.
- **Branch stack (temporary workflow for #278)**: the sub-issues are developed as a stack of branches, not off `main`.
  Branch #281 off `refactoring/280-midi-transmitter-family` and target its PR at that branch, unless the lower stack
  has already merged (Task 1, Step 0 checks). Never merge, rebase, retarget or force-push the open PRs #284, #286 and
  #287 or their branches; they are being reviewed and merged to `main` bottom-up in parallel.
- **Verification while writing this plan**: the code below was written against the sources at the base commit but was
  **not compiled**; expect to fix small compile slips (an import, a name) rather than design deviations. Two things
  were verified by running them: (1) ScalaMock 7.5.5 stubs `MidiReceiver` with both the `Stubs` API (`stub[...]`,
  `.send.returns`, `.send.calls`) and the `MockFactory` API (`.send.when`, `.send.verify`), including calls that use
  the default `timeStamp = -1L`, so every test migration below can replace `stub[javax.sound.midi.Receiver]` by
  `stub[MidiReceiver]` one for one; (2) the coverage baseline at the base commit is `sc-midi` **72.11%** statements /
  **56.10%** branches and `tuner` **83.34%** / **82.03%** (floors: 67/52 and 80/80).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every stage of the MIDI pipeline — `MidiSplitter`, `MidiProcessor`, `MidiSerialProcessor`, the tuners
and tuning changers, `Track` — carry `MidiMsg` and `MidiReceiver`, convert to Java Sound exactly once inside
`MidiDeviceHandle`, delete `MultiTransmitter`, and leave `tuner` with no `javax.sound.midi` import.

**Architecture:** `MidiSplitter(transmitter: MidiTransmitter)` is a `MidiReceiver` that fans out to the transmitter's
receivers (D5). `MidiProcessor` keeps a closed-flag `receiver: MidiReceiver` that processes once and forwards to every
output receiver, and its output is `transmitter: MidiProcessorTransmitter`, a `ConcurrentMidiTransmitter` whose
`setReceivers` override runs the connect/disconnect protocol, which D4's change guard already runs under the write
lock (D6); `MidiSerialProcessor` wires
neighbours with `receivers = Seq(next.receiver)` and `clearReceivers()`. `MidiDeviceHandle` converts outbound with
`asJava` in its receiver (a `Midi2Msg` is dropped with a warning) and inbound with `asScala` into a
`MidiSplitter(ConcurrentMidiTransmitter())` (D7). `Tuner`, `TuningChanger` and their processors are typed on `MidiMsg`;
`Track` loses its output splitter and exposes the pipeline's `receiver` and `transmitter`.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`), ScalaMock
7.5.5 (`Stubs` API in `sc-midi` tests, `MockFactory` in `tuner` tests, matching each file's current style),
`java.util.concurrent` (`ReentrantReadWriteLock` via `ConcurrentMidiTransmitter`, `AtomicBoolean`), Metals MCP for
compilation, scoverage for coverage.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Modules**: production changes live in `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/` and
  `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/`; tests in the matching `src/test` trees. Nothing
  in `app`, `cli`, `ui`, `format` or `common` uses the types this plan changes (verified by search at the base commit),
  so those modules are not touched. The only files outside the two modules that change are the two architecture
  documents of Task 5.
- **Do not touch**: `MidiManager.scala`, `MidiDeviceId.scala`, `JavaMidiConverters.scala`, `MidiMsg.scala` beyond the
  `SysExMidiMsg` companion of Task 1, anything under `cli`. They are #282's scope (D8, D9).
- **Compile** with `mcp__metals__compile-module` (`module = "sc-midi"` / `"tuner"`), or `mcp__metals__compile-full`;
  fall back to `sbtn "sc-midi/compile"`, `sbtn "tuner/Test/compile"`.
- **Run tests** with `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"` and `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`.
  A single class: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiProcessorTest -- -oNCXEHLOPQRMWS"`.
- **TDD** per `CLAUDE.md`: the new behaviour of this issue — the splitter's fan-out, the processor receiver's fan-out,
  the D6 hook protocol, the serial processor's unwiring, the `SysExMidiMsg` constants, the `Track` wiring — is written
  red → green: the test first, the thinnest `???` stub so the suite compiles, a run that fails on an assertion or a
  `NotImplementedError` (never a compile error), then the implementation. The rest of the issue is a **type
  migration** (`MidiMessage` → `MidiMsg`, `Receiver` → `MidiReceiver`) with no behaviour change: those steps are
  refactors, done green-to-green with production code and tests migrated together, and the suite run after each. Never
  mix a refactor with a behavioural change in one commit; commit only green code.
- **Temporary bridges**: Task 2 leaves a conversion bridge in `TunerProcessor` and `TuningChangeProcessor`, and Task 3
  leaves two device adapters in `Track`, so that every commit compiles and is green while the migration proceeds
  bottom-up. Each bridge is deleted by the next task; none survives the PR. See [Why the tasks run in this
  order](#why-the-tasks-run-in-this-order).
- **Scala conventions** (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines, no
  `new` (Scala 3 universal `apply` covers Java classes too: `ReentrantReadWriteLock()`, `AtomicBoolean(false)`,
  `Thread(() => …)`; anonymous subclasses — `new MidiProcessor { … }` in tests — are the unavoidable
  exception), no `return`, ScalaDoc on every public identifier, `_receiver`/`_transmitter` as backing fields,
  `// TODO #<issue>` only with an issue number. `Msg` is the suffix of message *types* only; helpers keep the full
  word (`RpnMessages`, `PitchBendSensitivityMessages`, `extractMidiMessages`).
- **Test conventions** (`docs/development/test-conventions.md`): same package as the production class, class name
  suffixed `Test`, `behavior of` sections, `// Given` / `// When` / `// Then` comments, no `if` in tests, fixtures for
  repeated setup. Check a test class's own ScalaDoc first — `MpeTunerTest` documents its category / subgroup layout.
- **License headers**: `.scala` files are covered by the `addlicense` pre-commit hook — never write or edit a header
  by hand. `Read` skips the ~15-line header, so files appear to start at ~line 17 with real line numbers preserved.
- **Coverage**: floors are `sc-midi` 67% statements / 52% branches and `tuner` 80% / 80% (`build.sbt`); never lower
  them. Baseline at the base commit: `sc-midi` 72.11 / 56.10, `tuner` 83.34 / 82.03. New files target 80%;
  `MidiSplitter` and `MidiProcessor` should reach ~100%. `MidiDeviceHandle` is hardware-bound and stays uncovered
  (#177). Deleting `MultiTransmitter` removes ~30 fully covered statements, so the `sc-midi` percentage may dip a
  little; if it still clears the floors, that is fine. The user reports that `coverageCheck` can fail on this branch
  stack for pre-existing reasons unrelated to new work — report such a failure with the numbers, do not chase it.
- **Commits**: imperative subject prefixed `[#278/#281]`, e.g. `[#278/#281] Type Tuner and TuningChanger on MidiMsg`.
  The sub-issue notation is mandatory (contributing skill, "Sub-issues").
- **Branch and PR**: work on `refactoring/281-scala-typed-pipeline`, branched off
  `refactoring/280-midi-transmitter-family` (or off `main` if Step 0 of Task 1 finds the stack merged). Switch in
  place with `git switch`, no worktrees. Open the PR as a draft only when Task 5 is green, targeting the branch you
  branched from — the `contributing` skill's script has no base-branch option, so Task 5 runs its `--dry-run` and
  then the printed `gh` commands by hand with `--base`.
- **Reading this plan from the implementation branch**: the plan is committed on `refactoring/isolate-java-midi` (PR
  #284) *after* the upper branches of the stack were cut, so it is **not** on `refactoring/280-midi-transmitter-family`
  and will not be on the #281 branch either; the plans of #279 and #280 stay on #284 the same way. Read it from that
  branch without switching:
  `git show refactoring/isolate-java-midi:issues/00278-isolate-java-midi/2026-09-08-281-scala-typed-pipeline-plan.md`
  (use `origin/refactoring/isolate-java-midi` once it is pushed). Do not merge the plan branch into the upper branches
  to get the file, and do not copy it onto the #281 branch.

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `sc-midi/.../scmidi/message/MidiMsg.scala` | Modify (Task 1) | `SysExMidiMsg` gains a companion with `StatusByte` (`0xF0`) and `EndOfExclusiveByte` (`0xF7`). |
| `sc-midi/.../scmidi/PitchBendSensitivity.scala` | Modify (Task 2) | `PitchBendSensitivityMessages.create` returns `Seq[MidiMsg]`; no Java import. |
| `sc-midi/.../scmidi/MidiProcessor.scala` | Rewrite (Task 3) | `receiver: MidiProcessorReceiver` (a `MidiReceiver` with a closed flag; processes once, fans out); `transmitter: MidiProcessorTransmitter extends ConcurrentMidiTransmitter` running the D6 protocol in its `setReceivers` override; `process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]`. |
| `sc-midi/.../scmidi/MidiSerialProcessor.scala` | Rewrite (Task 3) | Constructor takes `initialOutputReceivers: Seq[MidiReceiver]`; wires neighbours with `receivers = Seq(next.receiver)`, unwires with `clearReceivers()`; `onDisconnect` unwires the last processor, `onConnect` wires it. |
| `sc-midi/.../scmidi/MidiSplitter.scala` | Rewrite (Task 4) | `class MidiSplitter(val transmitter: MidiTransmitter) extends MidiReceiver`: `send` fans out to `transmitter.receivers`; `close()` stops forwarding and leaves the transmitter alone. |
| `sc-midi/.../scmidi/MidiDeviceHandle.scala` | Modify (Task 4) | The only place messages cross to Java Sound: `receiver: MidiReceiver` converts with `asJava` (drops a `Midi2Msg` with a warning); inbound Java `Receiver` converts with `asScala` into a `MidiSplitter(ConcurrentMidiTransmitter())`; `transmitter: ConcurrentMidiTransmitter` replaces `multiTransmitter`. |
| `sc-midi/.../scmidi/MultiTransmitter.scala` | Delete (Task 4) | Superseded by the `MidiTransmitter` family. |
| `tuner/.../tuner/Tuner.scala`, `TuningChanger.scala` | Modify (Task 2) | `reset()`, `tune()`, `process()` and `decide()` typed on `MidiMsg`. |
| `tuner/.../tuner/MpeTuner.scala`, `MonophonicPitchBendTuner.scala`, `MtsTuner.scala`, `PedalTuningChanger.scala` | Modify (Task 2) | Lose every `asScala` / `asJava` and the `javax.sound.midi` imports; `MpeTuner.processShortMessage` merges into `process`. |
| `tuner/.../tuner/MtsMessageGenerator.scala` | Modify (Task 1) | Uses `SysExMidiMsg.StatusByte` / `EndOfExclusiveByte`. |
| `tuner/.../tuner/TunerProcessor.scala`, `TuningChangeProcessor.scala` | Modify (Tasks 2, 3) | Task 2: temporary conversion bridge to the `MidiMsg`-typed plugins. Task 3: typed on `MidiMsg` end to end; `TunerProcessor` sends to every receiver of its transmitter. |
| `tuner/.../tuner/Track.scala` | Modify (Tasks 3, 4) | No output splitter; the pipeline's transmitter fans out to the device receiver and to other tracks. `receiver: MidiReceiver`, `transmitter: ConcurrentMidiTransmitter`, `initMidiMessages: Seq[MidiMsg]`. Task 3 leaves two device adapters that Task 4 removes. |
| `tuner/.../tuner/TrackManager.scala` | Modify (Task 3) | Inter-track wiring through `transmitter.addReceiver`. |
| `tuner/.../tuner/TuningChange.scala` | Modify (Task 2) | ScalaDoc link no longer names `javax.sound.midi.MidiMessage`. |
| `sc-midi/src/test/.../scmidi/message/MidiMsgTest.scala` | Modify (Task 1) | Pins the two `SysExMidiMsg` constants. |
| `sc-midi/src/test/.../scmidi/PitchBendSensitivityTest.scala` | Modify (Task 2) | Compares `create`'s output directly, no `asScala`. |
| `sc-midi/src/test/.../scmidi/MidiProcessorTest.scala` | Rewrite (Task 3) | Fan-out of the receiver, the closed flag, every D6 hook transition, receivers visible to the hooks, hooks inside the write lock (modifier and direct-assignment paths). |
| `sc-midi/src/test/.../scmidi/MidiSerialProcessorTest.scala` | Modify (Task 3) | Migrated to `MidiReceiver` / `MidiMsg`; new cases for unwiring on clear, rewiring on replace, and fan-out to several outputs. |
| `sc-midi/src/test/.../scmidi/MidiSplitterTest.scala` | Rewrite (Task 4) | The splitter over each of the three transmitter implementations, the closed flag, not closing the transmitter. |
| `sc-midi/src/test/.../scmidi/MultiTransmitterTest.scala` | Delete (Task 4) | Goes with its class. |
| `tuner/src/test/.../tuner/MpeTunerTest.scala`, `MonophonicPitchBendTunerTest.scala`, `MtsTunerTest.scala`, `PedalTuningChangerTest.scala`, `MtsMessageGeneratorTest.scala` | Modify (Tasks 1, 2) | Build and inspect `MidiMsg` values directly; no converters, no `javax.sound.midi`. |
| `tuner/src/test/.../tuner/TunerProcessorTest.scala`, `TuningChangeProcessorTest.scala` | Modify (Tasks 2, 3) | Task 2: transitional (Scala-typed plugin stubs, Java-typed receiver). Task 3: final, on `stub[MidiReceiver]`. |
| `tuner/src/test/.../tuner/TrackTest.scala` | Create (Task 3) | Pins D6 at track level: a tuner's `reset()` messages reach a receiver added after construction; messages sent to the track's receiver come out of its transmitter. |
| `docs/architecture/sc-midi/README.md`, `docs/architecture/tuner/README.md` | Modify (Task 5) | Plumbing on `MidiMsg`, the boundary in the handle, no `MidiSplitter` in the track, `MultiTransmitter` gone, `tuner` free of Java Sound. |

## Design notes

### Why the tasks run in this order

Everything from `MidiProcessor.process` down to `Track` is coupled by types: changing the processor's signature breaks
`TunerProcessor`, which breaks nothing else only if `Tuner` has already changed, and `Track` must compile against
whatever the pipeline and the device handle expose at that moment. There is no split into independently green commits
without either one enormous commit or a few lines of throw-away conversion. This plan chooses the latter and orders
the tasks bottom-up by *plugin → processor → device*:

1. **Task 1** — `SysExMidiMsg` constants; independent.
2. **Task 2** — the plugin interfaces (`Tuner`, `TuningChanger`) and their four implementations on `MidiMsg`. This is
   the biggest mechanical diff (about 230 `asJava` / `asScala` sites in the two tuner tests) and reviews best on its
   own. `TunerProcessor` / `TuningChangeProcessor` get a five-line bridge (`message.asScala` in, `collect { case m:
   Midi1Msg => m.asJava }` out) so the Java-typed `MidiProcessor` still compiles.
3. **Task 3** — `MidiProcessor` / `MidiSerialProcessor` on `MidiMsg` (D6). The bridges of Task 2 disappear, because
   both sides are now Scala-typed. `Track` is rebuilt on the pipeline's transmitter; since the device handle is still
   Java-typed, it keeps two small adapters (Java receiver → `pipeline.receiver`; `MidiReceiver` → device receiver).
4. **Task 4** — `MidiSplitter` (D5), the handle boundary (D7), `MultiTransmitter` deleted, the `Track` adapters gone.
5. **Task 5** — final checks, documentation, PR.

### `MidiProcessorReceiver` still skips `process` when there is no output

Today `MidiProcessorReceiver.send` calls `process` only when a receiver is set. The rewrite keeps that: a processor
with no output receivers drops the message without processing it. This is a deliberate non-change — it is what keeps
a disconnected `TuningChangeProcessor` from triggering tuning changes and what `MidiSerialProcessorTest`'s "some
initial processors and no output receiver" case pins — and it is stated in the ScalaDoc so that a future reader does
not take it for an accident.

### `MidiSerialProcessor.onDisconnect` unwires the last processor

Today's `MidiProcessorTransmitter.setReceiver` calls `onConnect()` **even when the new receiver is `null`** — a
quirk — and `MidiSerialProcessor` silently relies on it: both hooks call `wireOutput()`, and the one that runs
*after* the swap is what propagates "no output" to the last processor in the chain. Under D6, `onConnect` fires only
for a non-empty set, so the serial processor would leave its last processor wired to stale receivers after a
`clearReceivers()`. Task 3 therefore makes `onDisconnect` call `unwireOutput()` (clear the last processor's
receivers, while the old outputs are still in place) and `onConnect` call `wireOutput()` (set the new ones). For a
replacement A → B the last processor sees `clearReceivers()` then `receivers = B`, so a `TunerProcessor` at the end
tunes A back to standard and resets B — the same messages, to the same receivers, as today. A test pins the unwiring.

### The D6 hooks run inside the transmitter's write lock: a lock-ordering caveat

D6 says the hooks "only send downstream (read-locking this transmitter re-entrantly), so no lock-ordering issue
arises". That holds for `TunerProcessor`, but not for `MidiSerialProcessor`, whose hooks take **its own** write lock
(`wireOutput` / `unwireOutput` are `withWriteLock` on the serial lock) while the transmitter's write lock is held; its
modifiers (`append`, `insert`, `processors_=`, …) take the locks in the opposite order (serial write lock, then
`transmitter.receivers` read lock inside `wireOutput`). Two threads mutating the *same* pipeline — one through
`pipeline.transmitter.addReceiver`, the other through `pipeline.append` — could therefore deadlock. Nothing does that
today: the only callers of either are `Track`'s constructor and `TrackManager.replaceAllTracks`, both on the business
thread, and the message path takes read locks only, which never deadlock. This plan keeps D6 as designed (the
atomicity it buys is real), records the caveat in the `MidiSerialProcessor` ScalaDoc, and leaves the resolution to
[#121](https://github.com/calinburloiu/microtonalist/issues/121), where a track owns one thread and `MidiProcessor`
can take a non-concurrent transmitter. The design document's D6 sentence should be amended with this caveat.

### The `Track`-level test passes `null` for `MidiManager`

Section 4 asks for a `Track`-level test that a tuner's `reset()` messages reach a receiver added after construction.
`Track` takes a `MidiManager`, which today is a concrete class whose constructor scans CoreMIDI4J, so it cannot be
stubbed or instantiated in a unit test. A `TrackSpec` with no device input and no device output never dereferences
the manager, so `TrackTest` passes `null` for it, with a `// TODO #282` to switch to a stub once `MidiManager` is a
trait. A concrete recording double is not needed: ScalaMock stubs `Tuner`, `TuningService` and `MidiReceiver`.

### Timestamps through `MidiSerialProcessor`

`MidiSerialProcessor.process` hands the message to its first processor with time-stamp `-1`, not the one it
received. That is today's behaviour, it is unrelated to this issue, and the type migration leaves it alone; the tests
this plan adds match the time-stamp with `*` where the chain is involved.

---

## Task 1: `SysExMidiMsg.StatusByte` / `EndOfExclusiveByte` and `MtsMessageGenerator`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala` (after `case class SysExMidiMsg`)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MtsMessageGenerator.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MtsMessageGeneratorTest.scala`

**Interfaces:**
- Consumes: `case class SysExMidiMsg(data: ArraySeq[Byte]) extends Midi1Msg` (existing).
- Produces: `object SysExMidiMsg { val StatusByte: Byte = 0xF0.toByte; val EndOfExclusiveByte: Byte = 0xF7.toByte }`.

- [ ] **Step 0: Create the branch**

```bash
git switch refactoring/280-midi-transmitter-family
git pull --ff-only
git log --oneline main..refactoring/280-midi-transmitter-family | head -3
```

If the last command prints commits, the stack has not merged: `git switch -c refactoring/281-scala-typed-pipeline`
and target the PR at `refactoring/280-midi-transmitter-family` in Task 5. If it prints nothing, the stack has merged:
`git switch main && git pull --ff-only && git switch -c refactoring/281-scala-typed-pipeline` and target `main`.

- [ ] **Step 1: Write the failing tests (red)**

In `MidiMsgTest.scala`, add a section before `behavior of "MidiMsg hierarchy"`:

```scala
  behavior of "SysExMidiMsg"

  it should "expose the System Exclusive status byte" in {
    // Then
    SysExMidiMsg.StatusByte shouldEqual 0xF0.toByte
  }

  it should "expose the End of Exclusive byte" in {
    // Then
    SysExMidiMsg.EndOfExclusiveByte shouldEqual 0xF7.toByte
  }
```

In `MtsMessageGeneratorTest.scala`, replace the body of `assertTuning` so that it reads the bytes from the message
itself and pins the framing bytes; drop the `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` line
and add `import org.calinburloiu.music.scmidi.message.SysExMidiMsg`:

```scala
  def assertTuning(messageGenerator: MtsMessageGenerator, expectedOffsets: Seq[Double]): Unit = {
    val sysExMessage = messageGenerator.generate(tuning)
    val data = sysExMessage.data.toArray
    data.head shouldEqual SysExMidiMsg.StatusByte
    data.last shouldEqual SysExMidiMsg.EndOfExclusiveByte
    val softTuning = new SoftTuning(data)
    val tuningValues = softTuning.getTuning

    for (i <- tuningValues.indices) {
      (tuningValues(i) - 100 * i) shouldEqual expectedOffsets(i % 12)
    }
  }
```

- [ ] **Step 2: Add the thinnest stub so the suite compiles**

In `MidiMsg.scala`, right after `case class SysExMidiMsg(data: ArraySeq[Byte]) extends Midi1Msg`:

```scala
object SysExMidiMsg {
  val StatusByte: Byte = ???

  val EndOfExclusiveByte: Byte = ???
}
```

- [ ] **Step 3: Run the two test classes to verify they fail for the right reason**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MtsMessageGeneratorTest -- -oNCXEHLOPQRMWS"
```

Expected: the whole `MidiMsgTest` class aborts with `NotImplementedError` (a `???` in an object initialiser fails the
first access), and `MtsMessageGeneratorTest` fails the same way. Not a compile error.

- [ ] **Step 4: Implement (green)**

```scala
object SysExMidiMsg {
  /** The status byte that opens every System Exclusive message on the wire (`0xF0`). */
  val StatusByte: Byte = 0xF0.toByte

  /** The End of Exclusive byte that closes every System Exclusive message on the wire (`0xF7`). */
  val EndOfExclusiveByte: Byte = 0xF7.toByte
}
```

In `MtsMessageGenerator.scala`, replace `import javax.sound.midi.{ShortMessage, SysexMessage}` with nothing (the
`SysExMidiMsg` import is already there), and use the constants:

```scala
  private val headerBytes: Array[Byte] = Array(
    SysExMidiMsg.StatusByte,
    realTimeByte,
    deviceId,
    HeaderByte_Mts,
    form
  )
```

```scala
    // # Footer
    buffer.put(SysExMidiMsg.EndOfExclusiveByte)
```

- [ ] **Step 5: Run the two test classes to verify they pass**

Same commands as Step 3. Expected: green. Also compile `tuner` with `mcp__metals__compile-module` (`module =
"tuner"`) to confirm `MtsMessageGenerator.scala` has no remaining Java Sound import.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MtsMessageGenerator.scala \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MtsMessageGeneratorTest.scala
git commit -m "[#278/#281] Add SysExMidiMsg.StatusByte and EndOfExclusiveByte; MtsMessageGenerator uses them"
```

---

## Task 2: `Tuner`, `TuningChanger` and their implementations on `MidiMsg`

A type migration: no behaviour changes, so there is no red step. Production code and tests move together and the
`tuner` and `sc-midi` suites must be green at the end. `TunerProcessor` and `TuningChangeProcessor` keep the
Java-typed `MidiProcessor` API for one more task through a small bridge.

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Tuner.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChanger.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChange.scala` (one ScalaDoc link)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MtsTuner.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/PedalTuningChanger.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessor.scala` (bridge)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessor.scala` (bridge)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/{MtsTunerTest,PedalTuningChangerTest,MonophonicPitchBendTunerTest,MpeTunerTest,TunerProcessorTest,TuningChangeProcessorTest}.scala`

**Interfaces:**
- Consumes: `MidiMsg`, `Midi1Msg`, `ChannelMidiMsg.mapChannel`, `SystemResetMidiMsg`, the `asJava` / `asScala`
  extensions (bridge only).
- Produces, for Task 3:
  - `trait Tuner { def reset(): Seq[MidiMsg] = Seq.empty; def tune(tuning: Tuning): Seq[MidiMsg];
    def process(message: MidiMsg): Seq[MidiMsg] }`
  - `abstract class TuningChanger { def decide(message: MidiMsg): TuningChange }`
  - `PitchBendSensitivityMessages.create(channel: Int, pitchBendSensitivity: PitchBendSensitivity): Seq[MidiMsg]`

- [ ] **Step 1: `PitchBendSensitivityMessages.create` returns `Seq[MidiMsg]`**

In `PitchBendSensitivity.scala`, delete `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` and
`import javax.sound.midi.MidiMessage`; change the `message` import to
`import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, MidiRequirements}`; and make `create`:

```scala
  def create(channel: Int, pitchBendSensitivity: PitchBendSensitivity): Seq[MidiMsg] = {
    RpnMessages.select(channel, RpnMessages.PitchBendSensitivitySelector) ++
      Seq(
        CcMidiMsg(channel, MidiCc.DataEntryMsb, pitchBendSensitivity.semitones),
        CcMidiMsg(channel, MidiCc.DataEntryLsb, pitchBendSensitivity.cents)) ++
      // Leaving the channel with no parameter selected — the Null RPN on the wire — prevents a later stray Data
      // Entry from changing this parameter.
      RpnMessages.select(channel, RpnSelector.None)
  }
```

Update its ScalaDoc `@return` to "the MIDI messages that configure the pitch bend sensitivity". In
`PitchBendSensitivityTest.scala`, delete the `JavaMidiConverters` import and change
`PitchBendSensitivityMessages.create(channel = 5, pbs).map(_.asScala)` to
`PitchBendSensitivityMessages.create(channel = 5, pbs)`. `sc-midi` no longer compiles `tuner`, so run
`sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"` now: green. (`tuner` is red until Step 4; do not commit yet.)

- [ ] **Step 2: The two plugin interfaces**

`Tuner.scala`: replace `import javax.sound.midi.MidiMessage` with
`import org.calinburloiu.music.scmidi.message.MidiMsg` and change the three signatures, keeping every ScalaDoc
sentence as it is:

```scala
  def reset(): Seq[MidiMsg] = Seq.empty

  def tune(tuning: Tuning): Seq[MidiMsg]

  def process(message: MidiMsg): Seq[MidiMsg]
```

`TuningChanger.scala`: same import swap; `def decide(message: MidiMsg): TuningChange`.

`TuningChange.scala`, ScalaDoc of `MayTriggerTuningChange`: replace
`[[org.calinburloiu.music.microtonalist.tuner.TuningChanger#decide(javax.sound.midi.MidiMessage)]]` with
`[[TuningChanger.decide]]`.

- [ ] **Step 3: The four implementations**

`MtsTuner.scala`: replace the `JavaMidiConverters` and `javax.sound.midi.MidiMessage` imports with
`import org.calinburloiu.music.scmidi.message.MidiMsg`; the abstract class body becomes:

```scala
  override def tune(tuning: Tuning): Seq[MidiMsg] = Seq(mtsMessageGenerator.generate(tuning))

  override def process(message: MidiMsg): Seq[MidiMsg] = if (thru) Seq(message) else Seq.empty
```

`PedalTuningChanger.scala`: replace the `JavaMidiConverters` and `javax.sound.midi.MidiMessage` imports with
`import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg}`; `decide` becomes
`override def decide(message: MidiMsg): TuningChange = message match {` with an unchanged body.

`MonophonicPitchBendTuner.scala`:

1. Delete `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` and
   `import javax.sound.midi.{MidiMessage, ShortMessage}` (`MidiMsg` comes from the existing `message.*` import).
2. Replace every `Seq[MidiMessage]` with `Seq[MidiMsg]` and every `mutable.Buffer[MidiMessage]` with
   `mutable.Buffer[MidiMsg]` (`reset`, `_init`, `tune`, `process`, `applyPitchBendSensitivityMsb/Lsb`, `applyNoteOn`,
   `turnNoteOn`, `applyNoteOff`, `turnNoteOff`, `interruptPedals`, `applyPitchBend(buffer)`).
3. Delete every `.asJava` (nine sites: `applyNoteOn`, `applyNoteOff` ×2, `interruptPedals` ×3, and `applyPitchBend`).
4. `process` loses its `asScala` line; `scMessage` becomes the parameter:

```scala
  override def process(message: MidiMsg): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    val forwardMessage = () => message match {
      case channelMessage: ChannelMidiMsg => channelMessage.mapChannel(_ => outputChannel)
      case _ => message
    }

    // `turnNoteOn` / `turnNoteOff` need to know which notes were held down *before* this message.
    // Capture the pre-message state once, then update the tracker so all other reads (CC values,
    // RPN selector, Channel Pressure, etc.) see fresh state during the rest of the handling.
    val prevNotes = tracker.orderedActiveNotes(trackedChannel)
    val prevLastNote = prevNotes.lastOption.getOrElse(_lastSingleNote)
    sendToTracker(message)

    message match {
      // … the existing cases, unchanged …
    }

    buffer.toSeq
  }
```

   `sendToTracker(scMessage: MidiMsg)` keeps its body; rename its parameter to `message`.
5. `applyPitchBend()` returns the typed message; update its ScalaDoc (`ShortMessage` → `PitchBendMidiMsg`):

```scala
  private def applyPitchBend(): Option[PitchBendMidiMsg] = {
    // Only send the pitch bend value if it changed since the last call
    if (_unsentPitchBend) {
      _unsentPitchBend = false

      Some(PitchBendMidiMsg(outputChannel, currPitchBend))
    } else {
      None
    }
  }
```

`MpeTuner.scala`:

1. Delete `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` and
   `import javax.sound.midi.{MidiMessage, ShortMessage}`.
2. Replace every `Seq[MidiMessage]` with `Seq[MidiMsg]` and every `mutable.Buffer[MidiMessage]` with
   `mutable.Buffer[MidiMsg]`.
3. Delete every `.asJava` and `.map(_.asJava)` (eleven sites: the two in `processShortMessage`, `processNoteOn`,
   `processNoteOff`, `stopNotesOn` ×2, the dropped-note Note Off, `emitSlide`, `emitPressure`, `emitPitchBend`, and
   the PBS sequence `buffer ++= (sequence ++ RpnMessages.select(…))`).
4. Replace `process` and `processShortMessage` by a single `process`. Today a non-`ShortMessage` (SysEx, meta)
   bypasses the tracker and passes through; the tracker ignores non-channel messages (`case _ =>` in
   `MidiChannelStateTracker.send`), so sending it everything is equivalent:

```scala
  override def process(message: MidiMsg): Seq[MidiMsg] = {
    val buffer = mutable.Buffer[MidiMsg]()
    // A Note On with velocity 0 is a Note Off per the MIDI Specification. Normalizing it here, ahead of both the
    // tracker and the router, keeps every downstream decision — routing included — reading a single note-off shape.
    val normalizedMessage = message match {
      case msg: NoteOnMidiMsg if msg.velocity == NoteOnMidiMsg.NoteOffVelocity =>
        NoteOffMidiMsg(msg.channel, msg.midiNote)
      case other => other
    }
    tracker.send(normalizedMessage)

    normalizedMessage match {
      case msg: ChannelMidiMsg =>
        // … the existing ChannelMidiMsg branch, with `buffer += msg.mapChannel(_ => channel)` and
        // `buffer ++= messages` in place of the `.asJava` forms …
      case _ =>
        // System Exclusive, System Common, System Real-Time and meta messages affect the whole system and pass
        // through.
        buffer += normalizedMessage
        // A System Reset returns every receiving channel to its power-up state, parameter selection included.
        if (normalizedMessage == SystemResetMidiMsg) outputRpnSelectors.clear()
    }

    buffer.toSeq
  }
```

5. In the ScalaDoc of `outputRpnSelectors`, "the System Reset case in `processShortMessage`" becomes "the System
   Reset case in `process`".

- [ ] **Step 4: The temporary bridge in the two processors**

`TunerProcessor.scala` keeps `import javax.sound.midi.MidiMessage` and adds
`import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` and
`import org.calinburloiu.music.scmidi.message.{Midi1Msg, MidiMsg}`:

```scala
  def tune(tuning: Tuning): Unit = {
    val tuningMessages = tuner.tune(tuning)
    sendToReceiver(toJava(tuningMessages), -1)
  }

  override def process(message: MidiMessage, timeStamp: Long): Seq[MidiMessage] =
    toJava(tuner.process(message.asScala))

  override protected def onConnect(): Unit = {
    super.onConnect()

    val initMessages = tuner.reset()
    sendToReceiver(toJava(initMessages), -1)

    logger.info(s"Connected the processor for tuner $tuner.")
  }

  // Bridge to the Java-typed MidiProcessor, removed when MidiProcessor carries MidiMsg (#281).
  private def toJava(messages: Seq[MidiMsg]): Seq[MidiMessage] =
    messages.collect { case message: Midi1Msg => message.asJava }
```

`TuningChangeProcessor.scala` adds the same two imports (`JavaMidiConverters.*` and `MidiMsg`) and changes one call
and one signature; the forwarded message stays the original Java object:

```scala
    val (tuningChange, effectiveTuningChanger) = TuningChangeProcessor.computeTuningChange(
      message.asScala, tuningChangers.toList)
```

```scala
  private def computeTuningChange(message: MidiMsg,
                                  tuningChangers: List[TuningChanger]): (TuningChange, Option[TuningChanger]) = {
```

Compile `tuner` with `mcp__metals__compile-module` (`module = "tuner"`): production code must compile before the tests
are migrated.

- [ ] **Step 5: Migrate the tuner tests**

`MtsTunerTest.scala` (whole class body; the four `MtsOctave…Tuner` cases at the end stay as they are):

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.tuner.*
import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOnMidiMsg, SysExMidiMsg}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq

class MtsTunerTest extends AnyFlatSpec with Matchers with MockFactory {

  abstract class Fixture(thru: Boolean = MtsTuner.DefaultThru) {
    val mtsMessageGenerator: MtsMessageGenerator = stub[MtsMessageGenerator]("MtsMessageGenerator")
    val sysExMessage: SysExMidiMsg = SysExMidiMsg(
      ArraySeq.unsafeWrapArray(Array(0xF0, 0x7E, 0x7F, 0x08, 0xF7).map(_.toByte)))
    val tuner: MtsTuner = new MtsTuner(mtsMessageGenerator, thru) {
      override val typeName: String = "test"
    }

    mtsMessageGenerator.generate.when(*).returns(sysExMessage)
  }

  "MtsTuner#tune" should "return the generated SysEx MTS message" in new Fixture {
    // When
    val result: Seq[MidiMsg] = tuner.tune(TestTunings.justCMaj)
    // Then
    mtsMessageGenerator.generate.verify(TestTunings.justCMaj).once()
    result shouldEqual Seq(sysExMessage)
  }

  "MtsTuner#process" should "return the received MIDI message if thru is true" in new Fixture(thru = true) {
    // Given
    val message: MidiMsg = NoteOnMidiMsg(0, MidiNote.A4)
    // Then
    tuner.process(message) shouldEqual Seq(message)
  }

  it should "return nothing if thru is false" in new Fixture(thru = false) {
    // Given
    val message: MidiMsg = NoteOnMidiMsg(0, MidiNote.A4)
    // Then
    tuner.process(message) shouldBe empty
  }
```

`PedalTuningChangerTest.scala`: delete the `JavaMidiConverters` and `javax.sound.midi.ShortMessage` imports; the two
helpers that build `CcMidiMsg(1, cc, value).asJava.asInstanceOf[ShortMessage]` return `CcMidiMsg(1, cc, value)` and
declare `CcMidiMsg` as their result type; `NoteOnMidiMsg(1, MidiNote.C4, 64).asJava` loses `.asJava`;
`tuningChanger.decide(sysExMessage.asJava)` becomes `tuningChanger.decide(sysExMessage)`.

`MonophonicPitchBendTunerTest.scala`:

1. Delete the `JavaMidiConverters` and `javax.sound.midi.{MidiMessage, ShortMessage}` imports.
2. `Seq[MidiMessage]` → `Seq[MidiMsg]`; `mutable.Buffer[MidiMessage]` → `mutable.Buffer[MidiMsg]`.
3. Delete every `.asJava` (77 sites) and every `.asScala` (11 sites: `output.toSeq.map(_.asScala)` → `output.toSeq`,
   `midiMessages.map(_.asScala).collect` → `midiMessages.collect`, `output.head.asScala` → `output.head`,
   `output(n).asScala` → `output(n)`).
4. Replace the Java-only helper by its typed equivalent — the tuner emits channel messages only, so the collected sets
   are the same:

```scala
    def channelMessageOutput: Seq[ChannelMidiMsg] = output.toSeq.collect {
      case channelMessage: ChannelMidiMsg => channelMessage
    }
```

   and its four uses: `shortMessageOutput.map(_.getChannel).forall(_ == outputChannel)` →
   `channelMessageOutput.map(_.channel).forall(_ == outputChannel)`; the three `shortMessageOutput should have size n`
   → `channelMessageOutput should have size n`.

`MpeTunerTest.scala` (read its class ScalaDoc first; the layout does not change):

1. Delete the `JavaMidiConverters` and `javax.sound.midi.{MidiMessage, ShortMessage}` imports.
2. `Seq[MidiMessage]` → `Seq[MidiMsg]` everywhere (43 sites, including `private var output: Seq[MidiMsg]`).
3. Delete every `.asJava` (147 sites) and every `.map(_.asScala)` (two sites outside the helpers:
   `output.map(_.asScala).collect { case m: ProgramChangeMidiMsg => m }` → `output.collect { … }` and
   `output.map(_.asScala) shouldEqual Seq(ProgramChangeMidiMsg(masterChannel, 6))` → `output shouldEqual …`).
4. Replace the helper block (`extractShortMessages` through `extractMidiMessages`) by:

```scala
  private def extractPitchBends(output: Seq[MidiMsg]): Seq[PitchBendMidiMsg] =
    output.collect { case m: PitchBendMidiMsg => m }

  private def extractPitchBendsWithCents(output: Seq[MidiMsg]): Seq[(Int, Int)] =
    extractPitchBends(output).map(msg => (msg.channel, msg.cents.round.toInt))

  private def extractNoteOns(output: Seq[MidiMsg]): Seq[NoteOnMidiMsg] =
    output.collect { case m: NoteOnMidiMsg => m }.filter(_.velocity > 0)

  private def extractNoteOffs(output: Seq[MidiMsg]): Seq[NoteOffMidiMsg] =
    output.collect {
      case NoteOffMidiMsg(ch, note, velocity) => NoteOffMidiMsg(ch, note, velocity)
      case NoteOnMidiMsg(ch, note, 0) => NoteOffMidiMsg(ch, note)
    }

  private def extractCc(output: Seq[MidiMsg]): Seq[CcMidiMsg] =
    output.collect { case m: CcMidiMsg => m }

  private def extractChannelPressures(output: Seq[MidiMsg]): Seq[ChannelPressureMidiMsg] =
    output.collect { case m: ChannelPressureMidiMsg => m }

  private def extractPolyPressures(output: Seq[MidiMsg]): Seq[PolyPressureMidiMsg] =
    output.collect { case m: PolyPressureMidiMsg => m }

  private def extractSlides(output: Seq[MidiMsg]): Seq[CcMidiMsg] =
    extractCc(output).filter(_.number == MidiCc.MpeSlide)
```

   `extractShortMessages` was unused and is gone; `extractMidiMessages` was `output.map(_.asScala)` and is gone —
   replace each `extractMidiMessages(x)` by `x` (about 20 sites).

Run `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"` — `TunerProcessorTest` and `TuningChangeProcessorTest` still fail to
compile; fix them in Step 6 and run again.

- [ ] **Step 6: Transitional `TunerProcessorTest` and `TuningChangeProcessorTest`**

Both are rewritten again in Task 3; this version only keeps them honest against the bridge. `TunerProcessorTest`:
the plugin stub works on `MidiMsg`, the receiver is still Java, and the bridge produces *new* Java objects (Java
`MidiMessage` has no structural equality), so the receiver is verified through `where`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiMsg, NoteOnMidiMsg, PitchBendMidiMsg}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.midi.{MidiMessage, Receiver}

class TunerProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, 67, 0)

  val tuneMessage1: MidiMsg = PitchBendMidiMsg(0, 100)
  val tuneMessage2: MidiMsg = PitchBendMidiMsg(0, 0)

  val processMessage1: MidiMsg = NoteOnMidiMsg(0, MidiNote(60), 64)
  val processMessage2: MidiMsg = PitchBendMidiMsg(0, 101)

  abstract class Fixture(shouldConnect: Boolean = true) {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(TestTunings.justCMaj).returns(Seq(tuneMessage1))
    tuner.tune.when(Tuning.Standard).returns(Seq(tuneMessage2))
    tuner.process.when(processMessage1).returns(Seq(processMessage1, processMessage2))

    val receiver: Receiver = stub[Receiver]
    val processor: TunerProcessor = TunerProcessor(tuner)

    if (shouldConnect) {
      processor.transmitter.receiver = Some(receiver)
    }

    /** Matches a Java message forwarded by the bridge against the typed message it was built from. */
    def sent(expected: MidiMsg, expectedTimeStamp: Long): (MidiMessage, Long) => Boolean =
      (message, timeStamp) => message.asScala == expected && timeStamp == expectedTimeStamp
  }

  "onConnect" should "send init message after connecting" in new Fixture(shouldConnect = false) {
    // When
    processor.transmitter.receiver = Some(receiver)
    // Then
    receiver.send.verify(where(sent(initMessage, -1))).once()
  }

  it should "not send init message before connecting" in new Fixture(shouldConnect = false) {
    receiver.send.verify(*, *).never()
  }

  "tune" should "send the tune messages returned by the tuner" in new Fixture {
    // When
    processor.tune(TestTunings.justCMaj)
    // Then
    tuner.tune.verify(TestTunings.justCMaj).once()
    receiver.send.verify(where(sent(tuneMessage1, -1))).once()
  }

  "send" should "send the message processed by the tuner" in new Fixture {
    // Given
    val timeStamp: Long = 3L
    // When
    processor.receiver.send(processMessage1.asJava, timeStamp)
    // Then
    tuner.process.verify(processMessage1).once()
    receiver.send.verify(where(sent(processMessage1, timeStamp))).once()
    receiver.send.verify(where(sent(processMessage2, timeStamp))).once()
  }

  "onDisconnect" should "reset tuning to 12-EDO and the internal state of the tuner" in new Fixture {
    // When
    processor.close()
    // Then
    tuner.tune.verify(Tuning.Standard).once()
    receiver.send.verify(where(sent(tuneMessage2, -1))).once()
  }
}
```

`TuningChangeProcessorTest`: the changers decide on typed messages, while the processor still receives and forwards
the same Java objects, so only the stub arguments change. Keep the file as it is except: add
`import org.calinburloiu.music.scmidi.message.MidiMsg` (the `JavaMidiConverters` import stays for now); declare the
four messages typed and derive their Java forms:

```scala
  val noteTriggerMessage: MidiMsg = NoteOnMidiMsg(1, MidiNote.C4, 64)
  val ccTriggerMessage: MidiMsg = CcMidiMsg(1, MidiCc.SostenutoPedal, 32)
  val nonTriggerMessage1: MidiMsg = CcMidiMsg(1, MidiCc.ModulationMsb, 96)
  val nonTriggerMessage2: MidiMsg = NoteOnMidiMsg(1, MidiNote.B4, 16)

  val noteTriggerMidiMessage: MidiMessage = noteTriggerMessage.asJava
  val ccTriggerMidiMessage: MidiMessage = ccTriggerMessage.asJava
  val nonTriggerMidiMessage1: MidiMessage = nonTriggerMessage1.asJava
  val nonTriggerMidiMessage2: MidiMessage = nonTriggerMessage2.asJava
```

and stub the changers on the typed values: `noteTuningChangerStub.decide.when(noteTriggerMessage)` (both lines) and
`ccTuningChangerStub.decide.when(ccTriggerMessage)` (both lines). Everything else — `processor.process(…Midi
Message…)`, `processor.receiver.send(…)`, `receiverStub.send.verify(…)` — keeps the Java values.

- [ ] **Step 7: Run both module suites to verify they pass**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. Then confirm that no tuner plugin still imports Java Sound:

```bash
grep -ln 'javax.sound.midi\|JavaMidiConverters' tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/*.scala
```

Expected: exactly `Track.scala`, `TunerProcessor.scala`, `TuningChangeProcessor.scala` (the bridges, removed by
Tasks 3 and 4).

- [ ] **Step 8: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/PitchBendSensitivityTest.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner
git commit -m "[#278/#281] Type Tuner, TuningChanger and their implementations on MidiMsg"
```

---

## Task 3: `MidiProcessor` and `MidiSerialProcessor` on `MidiMsg` (D6), the processors and `Track` on top

**Files:**
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala`
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSerialProcessor.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessor.scala` (bridge removed)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessor.scala` (bridge removed)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiProcessorTest.scala` (rewrite)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiSerialProcessorTest.scala` (migrate)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessorTest.scala` (final)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessorTest.scala` (final)
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala` (create)

**Interfaces:**
- Consumes: `ConcurrentMidiTransmitter` (the `protected def setReceivers` hook it inherits from
  `MutableMidiTransmitter`, plus `receivers`, `receivers_=` and the modifiers — but neither its lock nor
  `withWriteLock`, since the inherited change guard takes the write lock for it), `MidiReceiver`, `MidiMsg`, the
  Task 2 plugin signatures, the still-Java-typed `MidiDeviceHandle.receiver: Receiver` and
  `MidiDeviceHandle.multiTransmitter` (adapted in `Track` until Task 4).
- Produces, for Task 4 and for `tuner`:
  - `trait MidiProcessor extends AutoCloseable { def receiver: MidiProcessorReceiver; def transmitter:
    MidiProcessorTransmitter; protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg];
    protected def onConnect(): Unit; protected def onDisconnect(): Unit }` with
    `class MidiProcessorReceiver extends MidiReceiver { def isClosed: Boolean }` and
    `class MidiProcessorTransmitter extends ConcurrentMidiTransmitter`
  - `class MidiSerialProcessor(initialProcessors: Seq[MidiProcessor], initialOutputReceivers: Seq[MidiReceiver] =
    Seq.empty)` — the rest of its API unchanged
  - `Track.receiver: MidiReceiver`, `Track.transmitter: ConcurrentMidiTransmitter`,
    `Track(spec, midiManager, tuningService, initMidiMessages: Seq[MidiMsg] = Seq.empty)`

- [ ] **Step 1: Write the new `MidiProcessorTest` (red)**

Replace the whole file:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOffMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

class MidiProcessorTest extends AnyFlatSpec with Matchers with Stubs {

  /** Records what it processes and the order of its hook calls; forwards every message unchanged. */
  class RecordingMidiProcessor extends MidiProcessor {
    val processedMessages: mutable.ListBuffer[(MidiMsg, Long)] = mutable.ListBuffer()
    val hookCalls: mutable.ListBuffer[String] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = {
      processedMessages += ((message, timeStamp))
      Seq(message)
    }

    override protected def onConnect(): Unit = {
      hookCalls += "connect"
    }

    override protected def onDisconnect(): Unit = {
      hookCalls += "disconnect"
    }

    override def close(): Unit = {}
  }

  /** Snapshots the transmitter's receivers as seen from inside each hook. */
  class SnapshottingMidiProcessor extends MidiProcessor {
    var receiversOnDisconnect: Seq[MidiReceiver] = Seq.empty
    var receiversOnConnect: Seq[MidiReceiver] = Seq.empty

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(): Unit = {
      receiversOnConnect = transmitter.receivers
    }

    override protected def onDisconnect(): Unit = {
      receiversOnDisconnect = transmitter.receivers
    }

    override def close(): Unit = {}
  }

  /**
   * From inside each hook, tries to read the transmitter's receivers on another thread and records whether that
   * read completed while the hook was still running. It completes at once unless the hook holds the write lock.
   */
  class LockProbingMidiProcessor extends MidiProcessor {
    val readerTimeoutMillis: Long = 200L
    val readCompletedDuringHook: mutable.ListBuffer[Boolean] = mutable.ListBuffer()

    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message)

    override protected def onConnect(): Unit = probe()

    override protected def onDisconnect(): Unit = probe()

    override def close(): Unit = {}

    private def probe(): Unit = {
      val completed = AtomicBoolean(false)
      val reader = Thread(() => {
        transmitter.receivers
        completed.set(true)
      })
      reader.setDaemon(true)
      reader.start()
      reader.join(readerTimeoutMillis)
      readCompletedDuringHook += completed.get
    }
  }

  trait Fixture {
    val processor: RecordingMidiProcessor = RecordingMidiProcessor()

    val message: MidiMsg = NoteOnMidiMsg(1, 60, 100)
    val timeStamp: Long = 123L

    val receiver1: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiver2: Stub[MidiReceiver] = stub[MidiReceiver]
    Seq(receiver1, receiver2).foreach(_.send.returns(_ => ()))
  }

  behavior of "receiver"

  it should "process a message once and forward the result to every receiver of the transmitter" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.processedMessages.toSeq shouldEqual Seq((message, timeStamp))
    receiver1.send.calls shouldEqual Seq((message, timeStamp))
    receiver2.send.calls shouldEqual Seq((message, timeStamp))
  }

  it should "forward every message a processor returns, in order, with the input time-stamp" in new Fixture {
    // Given
    val noteOff: MidiMsg = NoteOffMidiMsg(1, 60, 0)
    val echoingProcessor: MidiProcessor = new MidiProcessor {
      override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = Seq(message, noteOff)

      override def close(): Unit = {}
    }
    echoingProcessor.transmitter.addReceiver(receiver1)

    // When
    echoingProcessor.receiver.send(message, timeStamp)

    // Then
    receiver1.send.calls shouldEqual Seq((message, timeStamp), (noteOff, timeStamp))
  }

  it should "not process a message while the transmitter has no receivers" in new Fixture {
    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.processedMessages shouldBe empty
  }

  it should "not process or forward messages once closed" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)
    processor.receiver.close()

    // When
    processor.receiver.send(message, timeStamp)

    // Then
    processor.receiver.isClosed shouldBe true
    processor.processedMessages shouldBe empty
    receiver1.send.times shouldEqual 0
  }

  behavior of "transmitter"

  it should "call onConnect when the first receiver is added" in new Fixture {
    // When
    processor.transmitter.addReceiver(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect")
  }

  it should "call onDisconnect, then onConnect, when the receivers are replaced" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1)

    // When
    processor.transmitter.receivers = Seq(receiver2)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect", "connect")
  }

  it should "call onDisconnect, then onConnect, when a receiver is added to a connected processor" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)

    // When
    processor.transmitter.addReceiver(receiver2)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect", "connect")
    processor.transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "call only onDisconnect when the last receiver is removed" in new Fixture {
    // Given
    processor.transmitter.addReceiver(receiver1)

    // When
    processor.transmitter.removeReceiver(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect")
  }

  it should "call only onDisconnect when the receivers are cleared" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1, receiver2)

    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect", "disconnect")
  }

  it should "call no hook when the same receivers are set again" in new Fixture {
    // Given
    processor.transmitter.receivers = Seq(receiver1)

    // When
    processor.transmitter.receivers = Seq(receiver1)

    // Then
    processor.hookCalls.toSeq shouldEqual Seq("connect")
  }

  it should "call no hook when an empty transmitter is cleared" in new Fixture {
    // When
    processor.transmitter.clearReceivers()

    // Then
    processor.hookCalls shouldBe empty
  }

  it should "still expose the old receivers during onDisconnect and the new ones during onConnect" in new Fixture {
    // Given
    val snapshotting: SnapshottingMidiProcessor = SnapshottingMidiProcessor()
    snapshotting.transmitter.receivers = Seq(receiver1)

    // When
    snapshotting.transmitter.receivers = Seq(receiver2)

    // Then
    snapshotting.receiversOnDisconnect shouldEqual Seq(receiver1)
    snapshotting.receiversOnConnect shouldEqual Seq(receiver2)
  }

  it should "run the hooks while holding the write lock, through a modifier and through a direct assignment" in
    new Fixture {
      // Given
      val probing: LockProbingMidiProcessor = LockProbingMidiProcessor()

      // When
      probing.transmitter.addReceiver(receiver1)
      probing.transmitter.receivers = Seq(receiver2)

      // Then: connect; disconnect, connect
      probing.readCompletedDuringHook.toSeq shouldEqual Seq(false, false, false)
    }
}
```

- [ ] **Step 2: Rewrite `MidiProcessor` with a `???` protocol and migrate its consumers so the suite compiles**

`MidiProcessor.scala`, whole file (the `setReceivers` body is the stub; everything else is final):

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * MIDI interceptor that can change the MIDI messages that pass through it.
 *
 * Its input is the [[receiver]] and its output the [[transmitter]]. A subclass implements [[process]] to filter,
 * change or generate messages: every message the receiver gets is processed once and each resulting message is
 * forwarded to every receiver of the transmitter, in order. A processor whose transmitter has no receivers is not
 * processing: such a message is dropped without reaching [[process]].
 *
 * The processor is ''connected'' while its transmitter has at least one receiver. Whenever the receiver sequence
 * changes:
 *
 *   1. if the current sequence is non-empty, [[onDisconnect]] is called, with the old receivers still in place;
 *   1. the sequence is replaced;
 *   1. if the new sequence is non-empty, [[onConnect]] is called.
 *
 * Setting the same sequence again does nothing. The hooks run inside the transmitter's write lock, so a message
 * arriving on another thread cannot interleave with the messages the hooks emit. A hook may send downstream through
 * `transmitter.receivers` (the lock is reentrant); it must not wait for another thread.
 */
trait MidiProcessor extends AutoCloseable {

  private val _receiver: MidiProcessorReceiver = MidiProcessorReceiver()

  private val _transmitter: MidiProcessorTransmitter = MidiProcessorTransmitter()

  /**
   * The [[MidiReceiver]] of a [[MidiProcessor]]: processes every incoming message and forwards the results to every
   * receiver of the [[transmitter]]. Once closed, it ignores everything.
   */
  class MidiProcessorReceiver private[scmidi] extends MidiReceiver {

    @volatile private var _isClosed: Boolean = false

    override def send(message: MidiMsg, timeStamp: Long): Unit = if (!_isClosed) {
      val outputReceivers = transmitter.receivers
      if (outputReceivers.nonEmpty) {
        for (outputMessage <- process(message, timeStamp); outputReceiver <- outputReceivers) {
          outputReceiver.send(outputMessage, timeStamp)
        }
      }
    }

    override def close(): Unit = {
      _isClosed = true
    }

    /** @return whether [[close]] was called; a closed receiver drops every message. */
    def isClosed: Boolean = _isClosed
  }

  /**
   * The [[ConcurrentMidiTransmitter]] of a [[MidiProcessor]]: overrides the `setReceivers` hook so that the connect /
   * disconnect protocol described on [[MidiProcessor]] runs around every change of its receivers, whether made
   * through a modifier or by assignment. The hook is always called inside the transmitter's write lock, so it needs
   * no locking of its own.
   */
  class MidiProcessorTransmitter private[scmidi] extends ConcurrentMidiTransmitter() {

    override protected def setReceivers(newReceivers: Seq[MidiReceiver]): Unit = ???
  }

  /**
   * @return the receiver that takes the messages to be processed and forwarded to the output.
   */
  def receiver: MidiProcessorReceiver = _receiver

  /**
   * @return the transmitter that forwards the processed messages to its receivers.
   */
  def transmitter: MidiProcessorTransmitter = _transmitter

  /**
   * Processes a MIDI message and returns the resulting sequence of MIDI messages.
   *
   * The input messages can be filtered, modified or used to generate new MIDI output messages.
   *
   * @param message   The MIDI message to process.
   * @param timeStamp The time-stamp of the MIDI message.
   * @return A sequence of processed MIDI messages.
   */
  protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]

  /**
   * Callback called after the transmitter's receivers changed to a non-empty sequence, to let the processor configure
   * the output it is now connected to.
   */
  protected def onConnect(): Unit = {}

  /**
   * Callback called before the transmitter's receivers change away from a non-empty sequence, to let the processor
   * leave the output it was connected to in a consistent state.
   *
   * The processor can't know what was the exact state of the output device before connecting the processor to it.
   * Leaving it in a consistent state means setting the parameters (CCs, RPNs, NRPNs etc.) that were altered by the
   * processor to some convenient/default values.
   */
  protected def onDisconnect(): Unit = {}
}
```

`MidiSerialProcessor.scala`, whole file (mechanical, except the two hooks — see [Design notes](#design-notes)):

```scala
package org.calinburloiu.music.scmidi

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.message.MidiMsg

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}

/**
 * A [[MidiProcessor]] that connects a sequence of [[MidiProcessor]]s in a chain ending with the receivers of its own
 * [[transmitter]].
 *
 * {{{
 *   MidiProcessor -> MidiProcessor -> ... -> MidiProcessor -> transmitter.receivers
 * }}}
 *
 * Every mutation of the chain rewires the neighbours; the last processor's transmitter always carries this
 * processor's output receivers, so a change of those (see [[onConnect]] / [[onDisconnect]]) propagates to it.
 *
 * Lock ordering: the two hooks take this processor's lock while the transmitter's write lock is held, whereas the
 * chain modifiers take this processor's lock first and read the transmitter inside it. Mutating the chain and the
 * output receivers of the same instance from two threads at once could therefore deadlock; today both happen on the
 * business thread only. #121 gives each track one thread and removes the concern.
 *
 * @param initialProcessors      The [[MidiProcessor]]s to execute in sequence.
 * @param initialOutputReceivers The receivers of the [[transmitter]] at construction.
 */
class MidiSerialProcessor(initialProcessors: Seq[MidiProcessor],
                          initialOutputReceivers: Seq[MidiReceiver] = Seq.empty)
  extends MidiProcessor, Locking, StrictLogging {
  private implicit val lock: ReadWriteLock = ReentrantReadWriteLock()

  private var _processors: Seq[MidiProcessor] = initialProcessors

  transmitter.receivers = initialOutputReceivers
  wireAll()

  /**
   * Retrieves the sequence of MIDI processors that are chained.
   *
   * @return A sequence of MIDI processors.
   */
  def processors: Seq[MidiProcessor] = withReadLock {
    _processors
  }

  /**
   * Sets the sequence of MIDI processors that are chained.
   *
   * @param processors A sequence of MIDI processors to be set.
   */
  def processors_=(processors: Seq[MidiProcessor]): Unit = withWriteLock {
    _processors.foreach(_.transmitter.clearReceivers())

    _processors = processors

    wireAll()
  }

  /**
   * Inserts a MIDI processor at the specified index in the chain of processors.
   *
   * @param index     The position at which the processor should be inserted.
   * @param processor The MIDI processor to be inserted.
   */
  def insert(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors.patch(index, Seq(processor), 0)

    wireProcessor(index)
  }

  /**
   * Appends a MIDI processor to the end of the processors chain.
   *
   * @param processor The MIDI processor to be appended.
   */
  def append(processor: MidiProcessor): Unit = withWriteLock {
    _processors = _processors :+ processor

    wireProcessor(size - 1)
  }

  /**
   * Updates the MIDI processor at the specified index in the sequence of chained processors.
   *
   * @param index     The 0-based position of the processor to be updated.
   * @param processor The new MIDI processor to replace the existing one at the specified index.
   */
  def update(index: Int, processor: MidiProcessor): Unit = withWriteLock {
    val oldProcessor = processors(index)
    oldProcessor.transmitter.clearReceivers()

    _processors = _processors.updated(index, processor)

    wireProcessor(index)
  }

  /**
   * Removes the specified MIDI processor from the sequence of chained processors if it exists.
   *
   * @param processor The MIDI processor to be removed.
   */
  def remove(processor: MidiProcessor): Unit = withWriteLock {
    val index = _processors.indexOf(processor)
    if (index != -1) removeAt(index)
  }

  /**
   * Removes the MIDI processor at the specified index from the sequence of chained processors.
   *
   * @param index The 0-based position of the processor to be removed.
   */
  def removeAt(index: Int): Unit = withWriteLock {
    if (0 <= index && index < size) {
      val processor = processors(index)

      _processors = _processors.patch(index, Seq.empty, 1)

      wireProcessorToPrevious(index)
      // Note that after the remove the size is smaller with 1, that's why we check against size, not size - 1
      if (index == size) wireOutput()

      processor.transmitter.clearReceivers()
    } else if (index < 0) {
      throw IllegalArgumentException(s"index should be non-negative, but was $index")
    }
  }

  /**
   * Clears all MIDI processors in the chain and disconnects their transmitters.
   */
  def clear(): Unit = withWriteLock {
    _processors.foreach(_.transmitter.clearReceivers())

    _processors = Seq.empty
  }

  /**
   * @return the number of MIDI processors in the chain.
   */
  def size: Int = processors.size

  override def close(): Unit = {
    logger.info(s"Closing ${this.getClass.getCanonicalName}...")
    _processors.foreach(_.transmitter.clearReceivers())
  }

  protected override def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = {
    // If there is at least one processor, then messages will flow through processors towards the output receivers due
    // to the way they are wired, so there is no need to return anything. But if processors is empty, we return the
    // input such that forwarding to the output receivers is handled by MidiProcessor#MidiProcessorReceiver.
    processors.headOption match {
      case Some(firstProcessor) =>
        firstProcessor.receiver.send(message, -1)

        Seq.empty
      case None =>
        Seq(message)
    }
  }

  /** Wires the new output receivers to the last processor of the chain. */
  override protected def onConnect(): Unit = wireOutput()

  /** Unwires the old output receivers from the last processor of the chain, while they are still in place. */
  override protected def onDisconnect(): Unit = unwireOutput()

  /**
   * Wires a processor at the specified index to neighboring processors or the MIDI output as needed.
   *
   * @param index The index of the processor to wire. Must be between 0 and size - 1.
   */
  private def wireProcessor(_index: Int): Unit = withWriteLock {
    require(0 <= _index, s"index should be positive")
    val index = _index.min(size - 1)

    if (index > 0) {
      wireProcessorToPrevious(index)
    }

    val nextIndex = index + 1
    if (nextIndex == size) {
      wireOutput()
    } else {
      wireProcessorToPrevious(nextIndex)
    }
  }

  /**
   * Wires all MIDI processors in the chain together sequentially, ensuring correct data flow
   * between adjacent processors and from the last processor to the output.
   */
  private def wireAll(): Unit = withWriteLock {
    for (i <- 1 until size) {
      wireProcessorToPrevious(i)
    }

    wireOutput()
  }

  /**
   * Wires the current processor at the specified index to the previous processor in the chain,
   * enabling data flow between them.
   *
   * @param index The index of the processor to be connected to its predecessor. Must be between 1 and size - 1.
   */
  private def wireProcessorToPrevious(_index: Int): Unit = withWriteLock {
    require(1 <= _index, s"index should be greater or equal to 1")
    val index = _index.min(size - 1)

    processors(index - 1).transmitter.receivers = Seq(processors(index).receiver)
  }

  /**
   * Wires the output receivers of this processor's transmitter to the transmitter of the last MIDI processor in the
   * chain, ensuring proper data flow from the processors to the MIDI output.
   */
  private def wireOutput(): Unit = withWriteLock {
    if (size > 0) {
      processors.last.transmitter.receivers = transmitter.receivers
    }
  }

  /**
   * Disconnects the last MIDI processor in the chain from the output receivers.
   */
  private def unwireOutput(): Unit = withWriteLock {
    if (size > 0) {
      processors.last.transmitter.clearReceivers()
    }
  }
}
```

`TunerProcessor.scala` — final form of the class body (imports: drop `JavaMidiConverters.*` and
`javax.sound.midi.MidiMessage`; keep `import org.calinburloiu.music.scmidi.message.MidiMsg`):

```scala
@NotThreadSafe
class TunerProcessor(tuner: Tuner) extends MidiProcessor with StrictLogging {

  /**
   * Tunes the output instrument using the specified tuning.
   * The method generates the corresponding MIDI messages, if any, for the given tuning
   * and sends them to every receiver of the transmitter.
   *
   * @param tuning The instance that contains the tuning information,
   *               including the offset in cents for each of the 12 pitch classes.
   */
  def tune(tuning: Tuning): Unit = {
    val tuningMessages = tuner.tune(tuning)
    sendToReceivers(tuningMessages, -1)
  }

  override def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = tuner.process(message)

  override protected def onConnect(): Unit = {
    super.onConnect()

    val initMessages = tuner.reset()
    sendToReceivers(initMessages, -1)

    logger.info(s"Connected the processor for tuner $tuner.")
  }

  override protected def onDisconnect(): Unit = {
    super.onDisconnect()

    tuneToStandard()

    logger.info(s"Disconnected the processor for tuner $tuner.")
  }

  override def close(): Unit = {
    logger.info(s"Closing the processor for tuner $tuner...")
    tuneToStandard()
  }

  private def sendToReceivers(messages: Seq[MidiMsg], timeStamp: Long): Unit = {
    // TODO #97 Handle the try differently
    try {
      for (message <- messages; outputReceiver <- transmitter.receivers) {
        outputReceiver.send(message, timeStamp)
      }
    } catch {
      case e: IllegalStateException => throw TunerException(e)
    }
  }

  /**
   * Reset the output instrument to the standard tuning.
   */
  private def tuneToStandard(): Unit = tune(Tuning.Standard)
}
```

`TuningChangeProcessor.scala`: drop the `JavaMidiConverters.*` and `javax.sound.midi.MidiMessage` imports;
`override def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]` calls
`computeTuningChange(message, tuningChangers.toList)` (no `asScala`); the rest is unchanged.

`Track.scala` — transitional form. The pipeline is Scala-typed, the device handle is not yet (Task 4), so two
adapters bridge them; everything else is final:

```scala
package org.calinburloiu.music.microtonalist.tuner

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}
import org.calinburloiu.music.scmidi.{ConcurrentMidiTransmitter, MidiDeviceHandle, MidiManager, MidiReceiver}
import org.calinburloiu.music.scmidi.MidiSerialProcessor

import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiMessage, Receiver}

/**
 * MIDI route for tuning an output device.
 *
 * The output device receiver is an initial receiver of the pipeline, so the pipeline connects — and a tuner sends its
 * `reset()` messages to the device — as soon as the track is built; receivers added later through [[transmitter]]
 * (other tracks) reconnect it.
 *
 * @param tuningChangeProcessor Interceptor used for detecting MIDI messages that change the tuning.
 */
@ThreadSafe
class Track(val spec: TrackSpec,
            midiManager: MidiManager,
            tuningService: TuningService,
            initMidiMessages: Seq[MidiMsg] = Seq.empty) extends Runnable, AutoCloseable, StrictLogging {

  private val inputDeviceHandle: Option[MidiDeviceHandle] = spec.input.collect {
    case DeviceTrackInputSpec(midiDeviceId, _) => midiManager.openInput(midiDeviceId)
  }
  private val tuningChangeProcessor: Option[TuningChangeProcessor] = if (spec.tuningChangers.nonEmpty) {
    Some(TuningChangeProcessor(spec.tuningChangers, tuningService))
  } else {
    None
  }
  private val tunerProcessor: Option[TunerProcessor] = spec.tuner.map { tuner => TunerProcessor(tuner) }
  private val outputDeviceHandle: Option[MidiDeviceHandle] = spec.output.collect {
    case DeviceTrackOutputSpec(midiDeviceId, _) => midiManager.openOutput(midiDeviceId)
  }

  // Adapter to the still Java-typed device handle; removed by #281 once MidiDeviceHandle exposes a MidiReceiver.
  private val outputDeviceReceiver: Option[MidiReceiver] = outputDeviceHandle.map { handle =>
    new MidiReceiver {
      override def send(message: MidiMsg, timeStamp: Long): Unit = message match {
        case midi1Message: Midi1Msg => handle.receiver.send(midi1Message.asJava, timeStamp)
        case _: Midi2Msg =>
      }

      override def close(): Unit = {}
    }
  }

  private val pipeline: MidiSerialProcessor = MidiSerialProcessor(
    Seq(tuningChangeProcessor, tunerProcessor).flatten, outputDeviceReceiver.toSeq)

  // Adapter from the still Java-typed device handle; removed by #281 once MidiDeviceHandle exposes a transmitter.
  private val inputDeviceReceiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = receiver.send(message.asScala, timeStamp)

    override def close(): Unit = {}
  }
  inputDeviceHandle.foreach(_.multiTransmitter.addReceiver(inputDeviceReceiver))

  sendInitMidiMessages()

  def id: TrackSpec.Id = spec.id

  // TODO #121 Implement Track#run
  override def run(): Unit = {
    logger.warn("Track#run is not yet implemented!")
  }

  /**
   * @return the receiver every MIDI message of this track enters through.
   */
  def receiver: MidiReceiver = pipeline.receiver

  /**
   * @return the transmitter this track's output goes out through: the output device receiver and the receivers of
   *         the tracks fed by this one.
   */
  def transmitter: ConcurrentMidiTransmitter = pipeline.transmitter

  override def close(): Unit = {
    logger.info(s"Closing track $id...")

    logger.info(s"Switching back to 12-EDO for track $id...")
    tune(Tuning.Standard)

    inputDeviceHandle.foreach(_.close())
    outputDeviceHandle.foreach(_.close())
  }

  /**
   * Tunes the output instrument using the provided tuning.
   *
   * @param tuning The tuning to be applied.
   */
  def tune(tuning: Tuning): Unit = {
    tunerProcessor.foreach(_.tune(tuning))
  }

  private def sendInitMidiMessages(): Unit = {
    for (message <- initMidiMessages) {
      pipeline.receiver.send(message, -1)
    }
  }
}

object Track {
  /**
   * Default MIDI output channel to be used if not other is provided in a context where a channel number is required.
   *
   * Note that the channel number is 0-based internally, although the `.tracks` files and the UI may expose it as
   * 1-based.
   */
  val DefaultOutputChannel: Int = 0
}
```

`TrackManager.scala`, the two inter-track wiring lines:

```scala
          fromTrack.transmitter.addReceiver(currTrack.receiver)
```

```scala
          currTrack.transmitter.addReceiver(toTrack.receiver)
```

`MidiSerialProcessorTest.scala`, mechanical migration:

1. Imports: delete `org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*` and
   `javax.sound.midi.{MidiMessage, Receiver}`; import `org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOnMidiMsg}`.
2. `TestMidiProcessor.process`:

```scala
    override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = message match {
      case NoteOnMidiMsg(channel, midiNote, velocity) =>
        val newVelocity = Math.min(factor * velocity, 127)
        processedVelocities += Tuple2(factor, newVelocity)

        Seq(NoteOnMidiMsg(channel, midiNote, newVelocity))
      case _ => Seq(message)
    }
```

3. The fixture's receiver and `send`:

```scala
    val outputReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
    outputReceiver.send.returns {
      case (msg, ts) => msg match {
        case NoteOnMidiMsg(_, _, velocity) => outputVelocities += velocity
        case _ =>
      }
    }

    val midiSerialProcessor: MidiSerialProcessor

    def send(velocity: Int): Unit = {
      if (shouldSetOutputReceiverOnSend) midiSerialProcessor.transmitter.receivers = Seq(outputReceiver)

      midiSerialProcessor.receiver.send(NoteOnMidiMsg(0, MidiNote.C4, velocity), 123L)
    }
```

4. Everywhere: `new MidiSerialProcessor(x, Some(outputReceiver))` → `MidiSerialProcessor(x, Seq(outputReceiver))`;
   `new MidiSerialProcessor(x, None)` → `MidiSerialProcessor(x, Seq.empty)`;
   `.transmitter.receiver = Some(outputReceiver)` → `.transmitter.receivers = Seq(outputReceiver)`;
   `.transmitter.receiver shouldBe empty` / `should not be empty` / `should be(empty)` →
   `.transmitter.receivers …` with the same matcher; `.asJava` deleted on the two direct `receiver.send` calls.

`TunerProcessorTest.scala`, final form (whole file):

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.{MidiNote, MidiReceiver}
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiMsg, NoteOnMidiMsg, PitchBendMidiMsg}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TunerProcessorTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, 67, 0)

  val tuneMessage1: MidiMsg = PitchBendMidiMsg(0, 100)
  val tuneMessage2: MidiMsg = PitchBendMidiMsg(0, 0)

  val processMessage1: MidiMsg = NoteOnMidiMsg(0, MidiNote(60), 64)
  val processMessage2: MidiMsg = PitchBendMidiMsg(0, 101)

  abstract class Fixture(shouldConnect: Boolean = true) {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(TestTunings.justCMaj).returns(Seq(tuneMessage1))
    tuner.tune.when(Tuning.Standard).returns(Seq(tuneMessage2))
    tuner.process.when(processMessage1).returns(Seq(processMessage1, processMessage2))

    val receiver: MidiReceiver = stub[MidiReceiver]
    val processor: TunerProcessor = TunerProcessor(tuner)

    if (shouldConnect) {
      processor.transmitter.addReceiver(receiver)
    }
  }

  "onConnect" should "send init message after connecting" in new Fixture(shouldConnect = false) {
    // When
    processor.transmitter.addReceiver(receiver)
    // Then
    receiver.send.verify(initMessage, -1L).once()
  }

  it should "not send init message before connecting" in new Fixture(shouldConnect = false) {
    receiver.send.verify(*, *).never()
  }

  it should "send init message to every receiver" in new Fixture(shouldConnect = false) {
    // Given
    val anotherReceiver: MidiReceiver = stub[MidiReceiver]
    // When
    processor.transmitter.receivers = Seq(receiver, anotherReceiver)
    // Then
    receiver.send.verify(initMessage, -1L).once()
    anotherReceiver.send.verify(initMessage, -1L).once()
  }

  "tune" should "send the tune messages returned by the tuner" in new Fixture {
    // When
    processor.tune(TestTunings.justCMaj)
    // Then
    tuner.tune.verify(TestTunings.justCMaj).once()
    receiver.send.verify(tuneMessage1, -1L).once()
  }

  "send" should "send the message processed by the tuner" in new Fixture {
    // Given
    val timeStamp: Long = 3L
    // When
    processor.receiver.send(processMessage1, timeStamp)
    // Then
    tuner.process.verify(processMessage1).once()
    receiver.send.verify(processMessage1, timeStamp).once()
    receiver.send.verify(processMessage2, timeStamp).once()
  }

  "onDisconnect" should "reset tuning to 12-EDO and the internal state of the tuner" in new Fixture {
    // When
    processor.close()
    // Then
    tuner.tune.verify(Tuning.Standard).once()
    receiver.send.verify(tuneMessage2, -1L).once()
  }
}
```

`TuningChangeProcessorTest.scala`, final form: delete the `JavaMidiConverters.*` and
`javax.sound.midi.{MidiMessage, Receiver}` imports and the four `…MidiMessage: MidiMessage = ….asJava` values;
import `org.calinburloiu.music.scmidi.{MidiNote, MidiReceiver}`; every `processor.process(xMidiMessage, n)`,
`processor.receiver.send(xMidiMessage, n)` and `receiverStub.send.verify(xMidiMessage, …)` uses the typed value
(`noteTriggerMessage`, `ccTriggerMessage`, `nonTriggerMessage1`, `nonTriggerMessage2`); the fixture ends with:

```scala
    val receiverStub: MidiReceiver = stub[MidiReceiver]
    processor.transmitter.addReceiver(receiverStub)
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiProcessorTest -- -oNCXEHLOPQRMWS"
```

Expected: everything compiles; every `MidiProcessorTest` case that touches the transmitter fails with
`NotImplementedError`, and so does every `MidiSerialProcessorTest` case (the constructor assigns the receivers).
`tuner` need not be run yet.

- [ ] **Step 4: Implement the D6 protocol (green)**

Replace the `???` in `MidiProcessorTransmitter`:

```scala
    // No locking here: MutableMidiTransmitter routes every modifier, and a direct `receivers = …` assignment, through
    // withChangeGuard, which ConcurrentMidiTransmitter implements as the write lock. Reading `receivers` takes the
    // read lock while the write lock is held — a downgrade, which a ReentrantReadWriteLock permits.
    override protected def setReceivers(newReceivers: Seq[MidiReceiver]): Unit = {
      val currentReceivers = receivers
      if (currentReceivers != newReceivers) {
        if (currentReceivers.nonEmpty) {
          onDisconnect()
        }
        super.setReceivers(newReceivers)
        if (newReceivers.nonEmpty) {
          onConnect()
        }
      }
    }
```

- [ ] **Step 5: Run the `sc-midi` suite to verify it passes**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green, `MidiProcessorTest` and `MidiSerialProcessorTest` included.

- [ ] **Step 6: Add the `MidiSerialProcessor` cases the protocol change needs (red, then green)**

Append to `MidiSerialProcessorTest`, in a new `behavior of "transmitter"` section placed after the `"constructor"`
section:

```scala
  behavior of "transmitter"

  it should "unwire the last processor when its receivers are cleared" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
      Seq(processor2x, processor3x), Seq(outputReceiver))
    processor3x.transmitter.receivers shouldEqual Seq(outputReceiver)

    // When
    midiSerialProcessor.transmitter.clearReceivers()

    // Then
    processor3x.transmitter.receivers shouldBe empty
    processor2x.transmitter.receivers shouldEqual Seq(processor3x.receiver)
  }

  it should "rewire the last processor when its receivers are replaced" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
      Seq(processor2x, processor3x), Seq(outputReceiver))
    val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
    anotherReceiver.send.returns(_ => ())

    // When
    midiSerialProcessor.transmitter.receivers = Seq(anotherReceiver)

    // Then
    processor3x.transmitter.receivers shouldEqual Seq(anotherReceiver)
  }

  it should "fan the output out to every receiver" in new Fixture {
    // Given
    override val midiSerialProcessor: MidiSerialProcessor = MidiSerialProcessor(
      Seq(processor2x), Seq(outputReceiver))
    val anotherReceiver: Stub[MidiReceiver] = stub[MidiReceiver]
    anotherReceiver.send.returns(_ => ())
    midiSerialProcessor.transmitter.addReceiver(anotherReceiver)

    // When
    send(1)

    // Then
    outputVelocities should contain theSameElementsAs Seq(2)
    anotherReceiver.send.calls shouldEqual Seq((NoteOnMidiMsg(0, MidiNote.C4, 2), 123L))
  }
```

Run the class; the first case fails only if `onDisconnect` does not unwire — to see it red, temporarily make
`onDisconnect` call `wireOutput()` (today's behaviour) and watch "unwire the last processor" fail; restore
`unwireOutput()` and run again: green.

- [ ] **Step 7: Run the `tuner` suite and the `TrackTest` red step**

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green (Task 2's transitional tests were replaced by the final ones above). Now create `TrackTest.scala`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.{MidiNote, MidiReceiver}
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, MidiCc, MidiMsg, NoteOnMidiMsg, PitchBendMidiMsg}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TrackTest extends AnyFlatSpec with Matchers with MockFactory {

  val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)
  val inputMessage: MidiMsg = NoteOnMidiMsg(0, MidiNote.C4, 64)
  val outputMessage: MidiMsg = PitchBendMidiMsg(0, 100)

  trait Fixture {
    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(*).returns(Seq.empty)
    tuner.process.when(inputMessage).returns(Seq(outputMessage))

    val tuningService: TuningService = stub[TuningService]
    val spec: TrackSpec = TrackSpec("track", "Track", tuner = Some(tuner))
    // The spec has no device input and no device output, so the track never touches the manager.
    // TODO #282 Pass a stub once MidiManager is a trait.
    val track: Track = Track(spec = spec, midiManager = null, tuningService = tuningService)

    val receiver: MidiReceiver = stub[MidiReceiver]
  }

  behavior of "transmitter"

  it should "deliver the tuner's reset messages to a receiver added after the track was built" in new Fixture {
    // Given
    receiver.send.verify(*, *).never()

    // When
    track.transmitter.addReceiver(receiver)

    // Then
    receiver.send.verify(initMessage, -1L).once()
  }

  it should "forward the tuner's output for a message sent to the track's receiver" in new Fixture {
    // Given
    track.transmitter.addReceiver(receiver)

    // When
    track.receiver.send(inputMessage, 7L)

    // Then
    receiver.send.verify(outputMessage, *).once()
  }
}
```

Run `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackTest -- -oNCXEHLOPQRMWS"`. Expected:
green already — the `Track` above was written for it. To confirm the test detects the D6 defect it pins, temporarily
make `MidiProcessorTransmitter.setReceivers` skip `onConnect()` and watch the first case fail, then restore it and run
again: green. (Before D6, the same wiring sent the reset messages to an empty splitter; the test would have failed.)

- [ ] **Step 8: Run both suites, then commit**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. Then two commits:

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala \
  sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSerialProcessor.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiProcessorTest.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiSerialProcessorTest.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessor.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessor.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessorTest.scala \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessorTest.scala
git commit -m "[#278/#281] Type MidiProcessor on MidiMsg with a ConcurrentMidiTransmitter running the connect protocol"
git add tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala
git commit -m "[#278/#281] Pin at track level that a tuner's reset messages reach a receiver added later"
```

---

## Task 4: `MidiSplitter` over `MidiTransmitter` (D5), the boundary in `MidiDeviceHandle` (D7), `MultiTransmitter` deleted

**Files:**
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSplitter.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala`
- Delete: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MultiTransmitter.scala`
- Delete: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MultiTransmitterTest.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala` (adapters removed)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiSplitterTest.scala` (rewrite)

**Interfaces:**
- Consumes: `MidiTransmitter`, `ImmutableMidiTransmitter`, `MutableMidiTransmitter`, `ConcurrentMidiTransmitter`,
  `MidiReceiver`, `Midi1Msg` / `Midi2Msg`, the `asJava` / `asScala` extensions (inside the handle only).
- Produces, for `Track` and for #282:
  - `class MidiSplitter(val transmitter: MidiTransmitter) extends MidiReceiver { def isClosed: Boolean }`
  - `MidiDeviceHandle.receiver: MidiReceiver`, `MidiDeviceHandle.transmitter: ConcurrentMidiTransmitter`
    (`multiTransmitter` is gone)

- [ ] **Step 1: Write the new `MidiSplitterTest` (red)**

Replace the whole file:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.{MidiMsg, NoteOffMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiSplitterTest extends AnyFlatSpec, Matchers, Stubs {

  /** A transmitter that records whether it was closed, to check that the splitter never closes what it is given. */
  class CloseRecordingTransmitter extends MutableMidiTransmitter {
    var isClosed: Boolean = false

    override def close(): Unit = {
      isClosed = true
    }
  }

  trait Fixture {
    val noteOn: MidiMsg = NoteOnMidiMsg(0, MidiNote.C4, 69)
    val noteOff: MidiMsg = NoteOffMidiMsg(0, MidiNote.C4, 63)

    val receiverStub1: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStub2: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStub3: Stub[MidiReceiver] = stub[MidiReceiver]
    val receiverStubs: Seq[Stub[MidiReceiver]] = Seq(receiverStub1, receiverStub2, receiverStub3)
    receiverStubs.foreach { receiverStub =>
      receiverStub.send.returns(_ => ())
    }
  }

  behavior of "constructor"

  it should "expose the transmitter it was given" in new Fixture {
    // Given
    val transmitter: MidiTransmitter = ImmutableMidiTransmitter(receiverStubs)

    // When
    val splitter: MidiSplitter = MidiSplitter(transmitter)

    // Then
    splitter.transmitter should be theSameInstanceAs transmitter
  }

  behavior of "send"

  it should "forward every message, with its time-stamp, to every receiver of an immutable transmitter" in
    new Fixture {
      // Given
      val splitter: MidiSplitter = MidiSplitter(ImmutableMidiTransmitter(receiverStubs))

      // When
      splitter.send(noteOn, 100L)
      splitter.send(noteOff, 120L)

      // Then
      for (receiverStub <- receiverStubs) {
        receiverStub.send.calls shouldEqual Seq((noteOn, 100L), (noteOff, 120L))
      }
    }

  it should "forward to receivers added to a mutable transmitter after construction" in new Fixture {
    // Given
    val transmitter: MutableMidiTransmitter = MutableMidiTransmitter()
    val splitter: MidiSplitter = MidiSplitter(transmitter)
    splitter.send(noteOn, 100L)

    // When
    transmitter.addReceivers(Seq(receiverStub1, receiverStub2))
    splitter.send(noteOff, 120L)

    // Then
    receiverStub1.send.calls shouldEqual Seq((noteOff, 120L))
    receiverStub2.send.calls shouldEqual Seq((noteOff, 120L))
    receiverStub3.send.times shouldEqual 0
  }

  it should "forward to receivers added to a concurrent transmitter after construction" in new Fixture {
    // Given
    val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter(Seq(receiverStub1))
    val splitter: MidiSplitter = MidiSplitter(transmitter)

    // When
    transmitter.addReceiver(receiverStub2)
    transmitter.removeReceiver(receiverStub1)
    splitter.send(noteOn, 100L)

    // Then
    receiverStub1.send.times shouldEqual 0
    receiverStub2.send.calls shouldEqual Seq((noteOn, 100L))
  }

  it should "do nothing when the transmitter has no receivers" in new Fixture {
    // Given
    val splitter: MidiSplitter = MidiSplitter(MutableMidiTransmitter())

    // When / Then
    noException should be thrownBy splitter.send(noteOn, 100L)
  }

  it should "forward nothing once closed" in new Fixture {
    // Given
    val splitter: MidiSplitter = MidiSplitter(ImmutableMidiTransmitter(Seq(receiverStub1)))
    splitter.close()

    // When
    splitter.send(noteOn, 100L)

    // Then
    splitter.isClosed shouldBe true
    receiverStub1.send.times shouldEqual 0
  }

  behavior of "close"

  it should "not close the transmitter it was given" in new Fixture {
    // Given
    val transmitter: CloseRecordingTransmitter = CloseRecordingTransmitter()
    val splitter: MidiSplitter = MidiSplitter(transmitter)

    // When
    splitter.close()

    // Then
    transmitter.isClosed shouldBe false
  }
}
```

- [ ] **Step 2: Add the thinnest `MidiSplitter` and migrate its two users so the suite compiles**

`MidiSplitter.scala`, whole file with `???` bodies:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.MidiMsg

/**
 * A [[MidiReceiver]] that forwards every message it gets to every receiver of a [[MidiTransmitter]].
 *
 * The caller chooses the transmitter and, with it, whether and how the receivers can change: an
 * [[ImmutableMidiTransmitter]] for a fixed fan-out, a [[MutableMidiTransmitter]] on a single thread, a
 * [[ConcurrentMidiTransmitter]] when receivers are added from other threads. The splitter does not own the
 * transmitter and never closes it.
 *
 * @param transmitter the transmitter whose receivers get every message.
 */
class MidiSplitter(val transmitter: MidiTransmitter) extends MidiReceiver {

  @volatile private var _isClosed: Boolean = false

  override def send(message: MidiMsg, timeStamp: Long): Unit = ???

  /** Stops forwarding. The transmitter is left untouched, since the splitter does not own it. */
  override def close(): Unit = ???

  /** @return whether [[close]] was called; a closed splitter drops every message. */
  def isClosed: Boolean = _isClosed
}
```

`MidiDeviceHandle.scala` — the boundary (D7). Imports: keep `javax.sound.midi.*` and the `JavaMidiConverters.*`
import; add `import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}`. Replace the receiver /
splitter fields and the `HandleReceiver` class:

```scala
  private lazy val _receiver: HandleReceiver = HandleReceiver()
  private lazy val _transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  private lazy val splitter: MidiSplitter = MidiSplitter(_transmitter)

  /** Inbound boundary: the Java receiver handed to the device's transmitter converts and fans out. */
  private lazy val inboundReceiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = splitter.send(message.asScala, timeStamp)

    override def close(): Unit = {}
  }

  /** Outbound boundary: converts to Java Sound and sends to the open device. */
  private class HandleReceiver extends MidiReceiver {
    override def send(message: MidiMsg, timeStamp: Long): Unit = message match {
      case midi1Message: Midi1Msg =>
        for (midiDevice <- _device if midiDevice.isOpen; deviceReceiver <- Option(midiDevice.getReceiver)) {
          deviceReceiver.send(midi1Message.asJava, timeStamp)
        }
      case midi2Message: Midi2Msg =>
        logger.warn(s"Dropping $midi2Message sent to device $id: Java Sound devices speak MIDI 1.0 only.")
    }

    override def close(): Unit = {}
  }
```

Replace the two public accessors:

```scala
  /**
   * Retrieves the receiver of the device, which can be used to send MIDI messages to it. A message sent while the
   * device is not open is dropped; a [[Midi2Msg]] is always dropped, with a warning.
   *
   * @return The MIDI receiver instance.
   */
  def receiver: MidiReceiver = _receiver

  /**
   * Retrieves the transmitter of the device, which can be used to subscribe to the MIDI messages it sends. Receivers
   * may be added before the device is connected or open; they start getting messages when it is.
   *
   * @return The transmitter instance.
   */
  def transmitter: ConcurrentMidiTransmitter = _transmitter
```

In `doOpen`, `dev.getTransmitter.setReceiver(splitter.receiver)` becomes
`dev.getTransmitter.setReceiver(inboundReceiver)`. In the class ScalaDoc, the sentence "An instance exposes a
`[[Receiver]]` and a `[[MultiTransmitter]]` via `[[receiver]]` and `[[multiTransmitter]]` accessors" becomes "An
instance exposes a [[MidiReceiver]] and a [[ConcurrentMidiTransmitter]] via [[receiver]] and [[transmitter]]; they
are the only place where messages are converted to and from Java Sound".

`Track.scala` — final form: delete the `JavaMidiConverters.*`, `javax.sound.midi.{MidiMessage, Receiver}` and
`{Midi1Msg, Midi2Msg, MidiMsg}` imports (keep `import org.calinburloiu.music.scmidi.message.MidiMsg`), delete the
`outputDeviceReceiver` and `inputDeviceReceiver` adapters, and wire the handles directly:

```scala
  private val pipeline: MidiSerialProcessor = MidiSerialProcessor(
    Seq(tuningChangeProcessor, tunerProcessor).flatten, outputDeviceHandle.map(_.receiver).toSeq)

  inputDeviceHandle.foreach(_.transmitter.addReceiver(receiver))

  sendInitMidiMessages()
```

Delete `MultiTransmitter` and its test:

```bash
git rm sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MultiTransmitter.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MultiTransmitterTest.scala
```

Compile both modules (`mcp__metals__compile-module` for `sc-midi`, then `tuner`); `grep -rn 'MultiTransmitter\|
multiTransmitter' sc-midi/src tuner/src` must print nothing.

- [ ] **Step 3: Run `MidiSplitterTest` to verify it fails for the right reason**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiSplitterTest -- -oNCXEHLOPQRMWS"
```

Expected: the `constructor` case passes; every `send` and `close` case fails with `NotImplementedError`.

- [ ] **Step 4: Implement `MidiSplitter` (green)**

```scala
  override def send(message: MidiMsg, timeStamp: Long): Unit = if (!_isClosed) {
    transmitter.receivers.foreach(_.send(message, timeStamp))
  }

  /** Stops forwarding. The transmitter is left untouched, since the splitter does not own it. */
  override def close(): Unit = {
    _isClosed = true
  }
```

- [ ] **Step 5: Run both suites to verify they pass**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. Then the two greps that define "done" for this issue:

```bash
grep -rn 'javax.sound.midi' tuner/src
grep -rln 'javax.sound.midi' sc-midi/src/main
```

Expected: the first prints nothing. The second prints exactly `javamidi/JavaMidiConverters.scala`,
`MidiDeviceHandle.scala`, `MidiDeviceId.scala`, `MidiManager.scala` (all #282's) and `message/MidiMsg.scala`,
`MidiReceiver.scala`, `MidiTransmitter.scala` (ScalaDoc links only, no import).

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiSplitter.scala \
  sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiSplitterTest.scala \
  tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala
git commit -m "[#278/#281] Convert to Java Sound once in MidiDeviceHandle; drop MultiTransmitter"
```

(The two `git rm` of Step 2 are already staged.)

---

## Task 5: Final checks, documentation and the pull request

**Files:**
- Modify: `docs/architecture/sc-midi/README.md`
- Modify: `docs/architecture/tuner/README.md`
- Verify: every file changed in Tasks 1–4; `docs/architecture/module-overview.md` and `data-flow.md` (no change
  expected — at the base commit neither mentions `javax.sound.midi`, `MultiTransmitter` or `MidiSplitter`)

**Interfaces:** none new.

- [ ] **Step 1: Module tests**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow it for `sc-midi` and `tuner`. Check `MidiSplitter`,
`MidiProcessor` (both inner classes), `MidiSerialProcessor`, `TunerProcessor`, `TuningChangeProcessor` and `Track`
individually; the first two should be at ~100%, the others at or above their previous level. The module totals must
stay at or above the floors (67/52, 80/80); the baseline is 72.11/56.10 and 83.34/82.03. `MidiDeviceHandle` stays
uncovered (#177). If a line is uncovered, add the missing case to the corresponding test class (Given/When/Then, in
the existing `behavior of` block) rather than excluding the file. If `coverageCheck` fails on a floor while the
numbers above are met, or fails for a file this issue did not touch, report it with the numbers and move on — the
user knows this branch stack carries a pre-existing coverage failure.

- [ ] **Step 3: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. `app`, `cli`, `ui` and `format` compile against the new `tuner` API without changes (they do not
use the types this issue changed); a failure there is pre-existing or environmental — report it, do not "fix" it in
this PR.

- [ ] **Step 4: Update the `sc-midi` architecture document**

In `docs/architecture/sc-midi/README.md`:

1. "Responsibility", the sentence starting "`sc-midi` is not the only Microtonalist module that touches
   `javax.sound.midi` directly" — replace the whole sentence (up to "enumerate devices.") with:

```markdown
`sc-midi` is almost the only Microtonalist module that touches `javax.sound.midi` directly: `tuner` no longer imports
it at all (#281), and `cli` still reads `MidiDevice.Info` to enumerate devices until #282. Inside `sc-midi`, messages
cross to and from Java Sound in exactly one place, `MidiDeviceHandle`; everything upstream of it carries `MidiMsg`.
```

2. "Device handling", `MidiDeviceHandle` paragraph — replace "Callers **send** to an output via `handle.receiver` and
   **subscribe** to an input via `handle.multiTransmitter`; both are wired through an internal `MidiSplitter`, so
   they survive disconnect/reconnect without re-wiring." with:

```markdown
Callers **send** to an output via `handle.receiver: MidiReceiver` and **subscribe** to an input via
`handle.transmitter: ConcurrentMidiTransmitter`; both survive disconnect/reconnect without re-wiring. The handle is
the **Java Sound boundary**: its receiver converts each `Midi1Msg` with `asJava` and sends it to the open device (a
`Midi2Msg` is dropped with a warning, since Java Sound speaks MIDI 1.0 only), and the Java `Receiver` it hands to the
device's transmitter converts with `asScala` into an internal `MidiSplitter(ConcurrentMidiTransmitter())`.
```

3. "MIDI plumbing (receivers, transmitters, processors)" — replace the `MidiSplitter`, `MultiTransmitter`,
   `MidiTransmitter`, `MidiProcessor` and `MidiSerialProcessor` bullets with:

```markdown
- **`MidiReceiver`** — an `AutoCloseable` counterpart of `javax.sound.midi.Receiver` that consumes `MidiMsg`
  directly; every stage of the pipeline is one.
- **`MidiTransmitter`** — the read-only, `AutoCloseable` transmitter of the Scala API: a single
  `receivers: Seq[MidiReceiver]` member. The trait *itself* declares no state, no locking and no implementation, but
  locking is each implementation's business, so a reference typed as `MidiTransmitter` may well hold a
  `ConcurrentMidiTransmitter` that takes a lock on every read. Three implementations, all with a no-op `close()`:
  `ImmutableMidiTransmitter` (a case class whose `withReceiver`/`withReceivers`/`withoutReceiver`/`withoutReceivers`
  return new instances), `MutableMidiTransmitter` (`@NotThreadSafe`; every modifier and the `receivers` setter are
  `final` and offer a subclass two `protected` hooks instead — `setReceivers`, which every change funnels through,
  and `withChangeGuard`, which wraps each change together with the read of the current receivers that computes it)
  and `ConcurrentMidiTransmitter` (`@ThreadSafe`; overrides only those two points — `receivers` under the read lock,
  `withChangeGuard` under the write lock of a `ReentrantReadWriteLock` via `Locking` — so a subclass overriding
  `setReceivers` runs inside the write lock whatever the entry point, a direct `receivers = …` assignment included,
  and may read `receivers` re-entrantly, a downgrade the lock permits).
- **`MidiSplitter(transmitter: MidiTransmitter)`** — a `MidiReceiver` that fans every message out to the receivers
  of the transmitter it is given; the caller picks the transmitter implementation, and the splitter never closes it.
  `MidiDeviceHandle` uses one over a `ConcurrentMidiTransmitter` to broadcast a device's stream.
- **`MidiProcessor`** — a MIDI interceptor that can filter, modify, or synthesise messages as they pass through.
  Subclasses implement `process(message: MidiMsg, timeStamp): Seq[MidiMsg]`; its `receiver` processes each message
  once and forwards the results to every receiver of its `transmitter`, a `MidiProcessorTransmitter` (a
  `ConcurrentMidiTransmitter`) that calls `onDisconnect()` before and `onConnect()` after every change of its receiver
  set — from or to a non-empty set respectively, and never for an unchanged set — inside its write lock, so that the
  reset/initialisation messages the hooks emit cannot interleave with traffic. **This is the abstraction `tuner`
  extends** to tune the MIDI stream. A processor with no output receivers drops messages without processing them.
- **`MidiSerialProcessor`** — a `MidiProcessor` that chains a mutable, thread-safe sequence of `MidiProcessor`s end
  to end, rewiring the chain automatically on every mutation (`receivers = Seq(next.receiver)` between neighbours,
  its own output receivers on the last one) and forwarding input straight to the output when empty. Its hooks take
  its own lock inside the transmitter's, so a chain mutation and an output-receiver change of the same instance must
  not race from two threads (they do not today; #121 removes the concern).
```

4. "How MIDI devices are opened, enumerated, and used", step 4 — replace with:

```markdown
4. Use the handle: send `MidiMsg` values via `handle.receiver` (outputs), subscribe `MidiReceiver`s via
   `handle.transmitter.addReceiver` (inputs). The wiring survives disconnect/reconnect cycles.
```

5. "Message conversion model" — replace the last sentence ("Within `sc-midi`, message code prefers …") with:

```markdown
Conversion happens exactly once per message, in `MidiDeviceHandle`: outbound in its receiver, inbound in the Java
receiver it registers on the device. Everything upstream — the splitter, the processors, the `tuner` pipeline —
carries `MidiMsg`, so no processor converts on entry or exit.
```

6. "Notes / subject to change" — replace the last bullet (starting "The `Sc` prefix is gone (#279)") with:

```markdown
- The `Sc` prefix is gone (#279), the `MidiTransmitter` family replaced `MultiTransmitter` (#280, #281) and the
  pipeline carries `MidiMsg` end to end (#281); #282 turns `MidiManager` / `MidiDeviceHandle` into traits with a
  `JavaMidiManager` / `JavaMidiDeviceHandle` implementation under `javamidi` — see `issues/00278-isolate-java-midi/`.
```

- [ ] **Step 5: Update the `tuner` architecture document**

In `docs/architecture/tuner/README.md`:

1. "The processor pipeline." — replace the paragraph with:

```markdown
**The processor pipeline.** Each `Tuner`/`TuningChanger` is wrapped in a `MidiProcessor` (from `sc-midi`) so it can be
chained; both plugins and both processors are typed on `MidiMsg`, so no conversion to Java Sound happens in this
module. `TuningChangeProcessor` asks its `TuningChanger`s in order (first effective decision wins) and, on an effective
change, calls `TuningService.changeTuning`. `TunerProcessor` wraps a `Tuner`, forwarding `tune`/`process`, sending
`reset()` to every receiver of its transmitter on connect, and restoring 12-EDO on disconnect.
```

2. "Track pipeline" — replace the diagram and the second bullet with:

````markdown
```
input device ──▶ TuningChangeProcessor ──▶ TunerProcessor ──▶ pipeline transmitter ──▶ output device
  (MidiManager)   (TuningChanger plugins)   (Tuner plugin)     (Track.transmitter)      (MidiManager)
```
````

```markdown
- Input/output can be a MIDI device or another track (`FromTrackInputSpec` / `ToTrackOutputSpec`). The output device
  receiver is an initial receiver of the pipeline, so a tuner's `reset()` messages reach the device as soon as the
  track is built; `TrackManager` wires the inter-track connections afterwards with `transmitter.addReceiver`, which
  reconnects the pipeline (the tuner re-sends its reset messages to every receiver, harmlessly).
```

3. "Dependencies" — replace "`MidiSplitter`, message types, `MidiNote`, `PitchClass`, …" with
   "`MidiReceiver`/`ConcurrentMidiTransmitter`, the `MidiMsg` message model, `MidiNote`, `PitchClass`, …" and append
   the sentence: "The module imports nothing from `javax.sound.midi` (#281)."

- [ ] **Step 6: Review the ScalaDocs and the conventions**

Read `MidiProcessor.scala`, `MidiSerialProcessor.scala`, `MidiSplitter.scala`, `MidiDeviceHandle.scala`,
`Track.scala`, `TunerProcessor.scala`, `Tuner.scala` and `TuningChanger.scala` once more and check that every public
identifier has a ScalaDoc that no longer mentions Java types it does not use, that no line exceeds 120 columns, that no
`new` slipped into production code (the two anonymous `Receiver` / `MidiReceiver` instances in `MidiDeviceHandle` are
the exception), and that no bridge or adapter from Tasks 2–3 survived (`grep -rn 'toJava\|Adapter' tuner/src/main`
prints nothing). Compile once more with `mcp__metals__compile-full` and confirm there are no new warnings.

- [ ] **Step 7: Commit the documentation**

```bash
git add docs/architecture/sc-midi/README.md docs/architecture/tuner/README.md
git commit -m "[#278/#281] Document the MidiMsg-typed pipeline and the Java Sound boundary in the handle"
```

- [ ] **Step 8: Open the draft pull request against the stack**

The `contributing` skill's script targets `main`; this PR must target the branch chosen in Task 1, Step 0. Dry-run
first to get the resolved title, body, label and milestone:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 278/281 \
  "Carry MidiMsg through the MIDI pipeline and strip javax.sound.midi from tuner" \
  "Types \`MidiSplitter\`, \`MidiProcessor\`, \`MidiSerialProcessor\`, the tuners and \`Track\` on \`MidiMsg\` / \`MidiReceiver\`; \`MidiDeviceHandle\` converts to Java Sound once (a \`Midi2Msg\` is dropped with a warning); \`MultiTransmitter\` is deleted; \`tuner\` imports nothing from \`javax.sound.midi\`. Design: decisions D5–D7 of \`issues/00278-isolate-java-midi/2026-09-07-isolate-java-midi-design.md\`; plan: \`issues/00278-isolate-java-midi/2026-09-08-281-scala-typed-pipeline-plan.md\` (both on PR #284). Stacked on #287." \
  --dry-run
```

Then run the printed commands by hand, adding `--base` to the `gh pr create` line (replace the base if Step 0 chose
`main`), and the project step with the URL `gh pr create` prints:

```bash
git push -u origin HEAD
gh pr create --draft --base refactoring/280-midi-transmitter-family \
  --title '[#278/#281] Carry MidiMsg through the MIDI pipeline and strip javax.sound.midi from tuner' \
  --body "$(printf '%s\n\nResolves #281' '<the body above>')" \
  --label refactoring --assignee @me --milestone sc-midi
gh project item-add 1 --owner calinburloiu --url <PR_URL>
```

Report the PR URL. Do not merge, rebase or retarget any PR of the stack.

---

## Self-review against the design

- **D5 — `MidiSplitter(transmitter: MidiTransmitter) extends MidiReceiver`**, `send` fans out to
  `transmitter.receivers`, the caller picks the implementation, the splitter does not close it, the separate
  `receiver` field is gone: Task 4 (tested over all three implementations).
- **D6 — `MidiProcessor`**: `receiver: MidiReceiver` with a closed flag that processes then forwards to every output
  receiver; `transmitter: MidiProcessorTransmitter extends ConcurrentMidiTransmitter` whose `setReceivers` override
  runs the four-step protocol, reached from every modifier and from a direct assignment inside the write lock that
  D4's change guard takes;
  `process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]`: Task 3. Its consequences: `MidiSerialProcessor` wires
  with `receivers = Seq(next.receiver)` / `clearReceivers()` and takes `initialOutputReceivers: Seq[MidiReceiver]`
  (Task 3); `Track` has no output splitter and `Track.transmitter` is the pipeline's (Tasks 3–4); adding a receiver to
  a connected processor re-fires the hooks (pinned in `MidiProcessorTest`); the design's observation about `reset()`
  messages reaching the device is pinned by `TrackTest` (Task 3). Two refinements are recorded in
  [Design notes](#design-notes): the serial processor's `onDisconnect` unwires, and the lock-ordering caveat.
- **D7 — conversion once, at the device boundary**: outbound `asJava` in the handle's receiver with a `Midi2Msg`
  dropped and logged, inbound `asScala` into `MidiSplitter(ConcurrentMidiTransmitter())`: Task 4. Every tuner and
  `PedalTuningChanger` lose `asScala`/`asJava`, `PitchBendSensitivityMessages.create` returns `Seq[MidiMsg]`: Task 2.
  `MtsMessageGenerator` uses `SysExMidiMsg.StatusByte` / `EndOfExclusiveByte`: Task 1.
- **Section 3, row 3**: D5, D6, D7; boundary conversion in `MidiDeviceHandle`; `MultiTransmitter` deleted (Task 4);
  `tuner` adapted (Tasks 2–3); `tuner` free of `javax.sound.midi` (verified by grep in Task 4, Step 5).
- **Section 4**: `MidiSplitter` over each transmitter implementation (Task 4); `MidiProcessorTransmitter` hooks on
  every kind of set change — empty → non-empty, non-empty → different non-empty, non-empty → empty, same set
  (Task 3);
  the `Track`-level test for D6 (Task 3, with `null` for the manager, see the design note); every `sc-midi` and
  `tuner` test that stubbed a Java `Receiver` or built messages with `asJava` moved to `MidiReceiver` and plain
  `MidiMsg` values (Tasks 2–4); coverage floors (Task 5).
- **Section 5**: ScalaDocs on every changed public type and member (Tasks 1–4, reviewed in Task 5); the `sc-midi`
  README's plumbing, handle and conversion sections, and the `tuner` README's pipeline, track diagram and
  dependencies (Task 5); `module-overview.md` / `data-flow.md` checked — nothing to narrow.
- **Placeholder scan**: every code step carries its code or an exhaustive substitution list; the two red steps that
  cannot fail naturally (Task 3, Step 6 and Step 7) say how to see the test red.
- **Type consistency**: `receivers_=(newReceivers)`, `setReceivers(newReceivers)`, `addReceiver(receiver)`,
  `clearReceivers()`,
  `MidiSerialProcessor(initialProcessors, initialOutputReceivers)`, `MidiSplitter(transmitter)`,
  `MidiDeviceHandle.receiver` / `.transmitter`, `Track.receiver` / `.transmitter`, `TunerProcessor.sendToReceivers`,
  `SysExMidiMsg.StatusByte` / `EndOfExclusiveByte`, `PitchBendSensitivityMessages.create(channel,
  pitchBendSensitivity): Seq[MidiMsg]` are spelled the same in every task.

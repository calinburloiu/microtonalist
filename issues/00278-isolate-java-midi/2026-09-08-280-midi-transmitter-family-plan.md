# `MidiTransmitter` Family — Implementation Plan (#280)

- **Date**: 2026-09-08
- **Superseded in part**: 2026-09-09 on `384bfa4`, the top of `refactoring/280-midi-transmitter-family` — **this
  document is a record of the steps that were executed; its body is deliberately left as it was written.** The D4
  shape it implements has since been superseded: the review of PR
  [#287](https://github.com/calinburloiu/microtonalist/pull/287) replaced the public virtual setter `receivers_=` as
  the extension point with two `protected` hooks, `withChangeGuard` and `setReceivers`, and made every modifier and
  the setter `final`. Do not copy the verbatim `MutableMidiTransmitter` / `ConcurrentMidiTransmitter` code below into
  new work; read the third revision entry and D4 of
  [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md), and the code on the branch,
  instead.
- **Issue**: [#280](https://github.com/calinburloiu/microtonalist/issues/280) — "Add the MidiTransmitter family:
  immutable, mutable and concurrent implementations", sub-issue 2 of parent
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `fd8f6d6c289239d6127ac91278d38e55d66c7b38` — "Start v1.5.0-SNAPSHOT" (`main`). The documentation
  branch `refactoring/isolate-java-midi` (PR [#284](https://github.com/calinburloiu/microtonalist/pull/284)) that
  carries this plan adds no code on top of it, so the code described below is the code at that commit.
- **Spec**: [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md) — the approved design
  for the whole of #278. The scope of this plan is **decision D4** and the **#280 row of Section 3**; Section 4
  (testing) and Section 5 (documentation) apply where they mention the transmitters. No separate design document
  exists for #280.
- **Relation to #279**: #280 is independent of #279 (the `Sc` → `Midi` renames). At the time of writing #279 has
  **not** merged, so this plan uses the current names `ScMidiReceiver` and `ScMidiMessage`. If #279 has merged when
  you execute this plan, substitute `MidiReceiver` for `ScMidiReceiver`, `MidiMsg` for `ScMidiMessage`, and
  `NoOpMidiReceiver` for the test helper `NoOpScMidiReceiver` throughout. If #280 merges first, #279's mechanical
  rename picks up the files created here like any other.
- **Verification of the code in this plan**: the final production code and the test code of Tasks 1–3 were extracted
  from this document and compiled and run with `scala-cli` (Scala 3.6.3, ScalaTest 3.2.19, stand-ins for
  `ScMidiReceiver` and `ScMidiMessage`, the repository's `Locking`) while the plan was written: 37 tests, green on
  three consecutive runs. With the locks removed from `ConcurrentMidiTransmitter`, the lock-probe test and the
  lost-update test both fail, so the concurrency tests detect what they claim to. The stubs of the red steps were not
  run; their signatures are identical to the final code.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the read-only `MidiTransmitter` trait and its three implementations — `ImmutableMidiTransmitter`,
`MutableMidiTransmitter`, `ConcurrentMidiTransmitter` — typed on the Scala receiver, fully unit-tested (including a
concurrency test), without touching `MultiTransmitter` or any of its users.

**Architecture:** `MidiTransmitter` is a pure interface (`receivers: Seq[ScMidiReceiver]` plus the inherited
`AutoCloseable.close()`), with no locks and no implementation. `ImmutableMidiTransmitter` is a case class whose
`with…`/`without…` methods return new instances. `MutableMidiTransmitter` holds a private `_receivers` field; every
modifier (`addReceiver`, `addReceivers`, `removeReceiver`, `clearReceivers`) reads the field directly and writes only
through the public setter `receivers_=`, so a subclass that overrides the setter intercepts every mutation by virtual
dispatch (this is what D6's `MidiProcessorTransmitter` relies on in #281). `ConcurrentMidiTransmitter` extends the
mutable class and overrides every accessor and modifier under a `ReentrantReadWriteLock` via the existing `Locking`
mixin, so that a modifier's read-modify-write is atomic and `receivers_=` is always reached while the write lock is
held. Nothing in the module uses the new types yet; #281 rewires `MidiSplitter`, `MidiProcessor` and
`MidiDeviceHandle` onto them and deletes `MultiTransmitter`.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`, shared
behaviour functions), `java.util.concurrent` (`ReentrantReadWriteLock`, `CountDownLatch`), `javax.annotation.concurrent`
(`@ThreadSafe` / `@NotThreadSafe`, already on the `sc-midi` classpath through `jsr305`), Metals MCP for compilation,
scoverage for coverage.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Module**: all production changes live in `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/`; all test
  changes in `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/`. The only file outside the module that changes is
  `docs/architecture/sc-midi/README.md` (Task 4).
- **Do not touch**: `MultiTransmitter.scala`, `MultiTransmitterTest.scala`, `MidiSplitter.scala`,
  `MidiProcessor.scala`, `MidiSerialProcessor.scala`, `MidiDeviceHandle.scala`, anything in `tuner`. They are
  rewired and cleaned up by #281 (Design §3, row 3).
- **Compile** with `mcp__metals__compile-module` (`module = "sc-midi"`); fall back to `sbtn "sc-midi/compile"` /
  `sbtn "sc-midi/Test/compile"`.
- **Run tests** with `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`. A single class:
  `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ImmutableMidiTransmitterTest -- -oNCXEHLOPQRMWS"`.
- **Strict TDD** per `CLAUDE.md`: red → green → refactor. A red step must fail on an *assertion* or a
  `NotImplementedError`, never on a compile error, so each red step below names the thinnest production stub (`???`
  bodies) that makes the suite compile. Never mix a refactor with a behavioural change; commit only green code.
- **Scala conventions** (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines, no
  `new` (Scala 3 universal `apply` covers Java classes too: `ReentrantReadWriteLock()`, `CountDownLatch(1)`,
  `Thread(() => …)`), no `return`, ScalaDoc on every public identifier, `_receivers` as the backing field of the
  `receivers` accessor.
- **Test conventions** (`docs/development/test-conventions.md`): same package as the production class, class name
  suffixed `Test`, `behavior of` sections, `// Given` / `// When` / `// Then` comments, no `if` in tests, fixtures for
  repeated setup.
- **License headers**: `.scala` files are covered by the `addlicense` pre-commit hook — never write or edit a header
  by hand. `Read` skips the ~15-line header, so files appear to start at ~line 17 with real line numbers preserved.
- **Coverage floor**: `sc-midi` is at `coverageSettings(stmt = 67, branch = 52)` in `build.sbt`. Never lower it. The
  three new production files are tiny and fully exercised, so each should reach ~100%; the target for a new file is
  80%. Verify with the `scoverage-inspector` skill (Task 4).
- **Commits**: imperative subject prefixed `[#278/#280]`, e.g. `[#278/#280] Add MidiTransmitter and
  ImmutableMidiTransmitter`. The sub-issue notation is mandatory in commit messages (contributing skill, "Sub-issues").
- **Branch and PR**: work on a new branch off `main` named `refactoring/midi-transmitter-family` (label prefix per the
  contributing skill; switch in place with `git switch`, no worktrees). Do not open the PR until Task 4 is green; open
  it as a draft with the `contributing` skill's script, issue-spec `278/280`.
- **Reading this plan from the implementation branch**: the plan is committed on `refactoring/isolate-java-midi` (PR
  #284), not on `main`. If it is not present on your branch, read it with
  `git show origin/refactoring/isolate-java-midi:issues/00278-isolate-java-midi/2026-09-08-280-midi-transmitter-family-plan.md`.

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiTransmitter.scala` | Create | The read-only `AutoCloseable` transmitter interface: `receivers: Seq[ScMidiReceiver]`. No state, no locks. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitter.scala` | Create | Case class implementation; `withReceiver` / `withReceivers` / `withoutReceiver` / `withoutReceivers` return new instances. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitter.scala` | Create | `@NotThreadSafe` single-thread implementation; `_receivers` backing field; every modifier funnels through `receivers_=`. |
| `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala` | Create | `@ThreadSafe` subclass of the mutable one; every accessor/modifier overridden under a `ReentrantReadWriteLock` (`protected`, so #281's subclass can lock too). |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/NoOpScMidiReceiver.scala` | Create | Test double: a `ScMidiReceiver` that ignores everything. Each instance is a distinct identity, which is all the transmitter tests need. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitterTest.scala` | Create | Covers the four `with…`/`without…` methods, immutability of the original, `close()` as a no-op, case-class equality. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterBehaviors.scala` | Create | ScalaTest shared behaviours: the single-thread contract of `MutableMidiTransmitter`, run once against each of the two mutable classes. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterTest.scala` | Create | Runs the shared behaviours on `MutableMidiTransmitter`; pins that every modifier goes through `receivers_=` and that the constructor does not. |
| `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala` | Create | Runs the shared behaviours on `ConcurrentMidiTransmitter`; pins that modifiers reach `receivers_=` under the write lock; the multi-thread lost-update test. |
| `docs/architecture/sc-midi/README.md` | Modify | "MIDI plumbing" gains the `MidiTransmitter` family; `MultiTransmitter` is marked as superseded (deleted by #281). |

### Why the test double is a class and not a ScalaMock stub

The existing `MultiTransmitterTest` uses `stub[javax.sound.midi.Receiver]`. The new tests never call the receivers —
they only compare them by identity and count them — so a two-line concrete `ScMidiReceiver` is simpler, has no
ScalaMock macro surface to worry about for a trait whose `send` has a default argument, and can be instantiated by the
thousand in the concurrency test without cost. The helper lives in the `sc-midi` test tree because only `sc-midi`
tests need it; if `tuner` needs one later, move it to `common-test-utils` per the test conventions.

### Why the constructor stores `initialReceivers` directly instead of calling `receivers_=`

`ConcurrentMidiTransmitter` overrides `receivers_=` to take the write lock, and the lock is a `val` of the subclass. A
call to the overridable setter from the base-class constructor would therefore run the override **before the lock
exists** (`NullPointerException`). The same problem appears again in #281, where `MidiProcessorTransmitter` overrides
the setter to fire `onConnect()`: a setter call from the constructor would fire the hook on a half-built object. So
`MutableMidiTransmitter`'s constructor initialises `_receivers` directly, and a test in Task 2 and Task 3 pins that the
setter is *not* called during construction.

### Notes for #281 (recorded here so the next plan does not rediscover them)

- The lock of `ConcurrentMidiTransmitter` is `protected implicit val lock: ReentrantReadWriteLock`. A subclass that
  overrides `receivers_=` (D6's `MidiProcessorTransmitter`) is reached **inside** the write lock when the change comes
  through a modifier (`addReceiver`, `addReceivers`, `removeReceiver`, `clearReceivers`), because each modifier
  override takes the write lock before calling `super`, which funnels to the virtual `receivers_=`. A **direct**
  `transmitter.receivers = …` call reaches the subclass override *before* `ConcurrentMidiTransmitter.receivers_=` takes
  the lock, so the override must wrap its own body in `withWriteLock` (the lock is reentrant, so the nested acquisition
  in `super.receivers_=` is fine). Task 3's lock-probe test pins the modifier path only.
- `removeReceiver` and `withoutReceiver` remove **every** occurrence equal to the argument (`filterNot(_ == receiver)`),
  as `MultiTransmitter.removeReceiver` does today; duplicates are allowed by `addReceiver`/`withReceiver`, as today.

---

## Task 1: `MidiTransmitter` trait and `ImmutableMidiTransmitter`

**Files:**
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiTransmitter.scala`
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitter.scala`
- Create: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/NoOpScMidiReceiver.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitterTest.scala`

**Interfaces:**
- Consumes: `org.calinburloiu.music.scmidi.ScMidiReceiver` (trait with `send(message: ScMidiMessage, timeStamp: Long
  = -1L): Unit` and `close(): Unit`) and `org.calinburloiu.music.scmidi.message.ScMidiMessage`, both existing.
- Produces, for Tasks 2–4 and for #281:
  - `trait MidiTransmitter extends AutoCloseable { def receivers: Seq[ScMidiReceiver]; def close(): Unit }`
  - `case class ImmutableMidiTransmitter(receivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter` with
    `withReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter`,
    `withReceivers(newReceivers: Seq[ScMidiReceiver]): ImmutableMidiTransmitter`,
    `withoutReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter`,
    `withoutReceivers(receiversToRemove: Seq[ScMidiReceiver]): ImmutableMidiTransmitter`, `close(): Unit` (no-op)
  - Test helper `class NoOpScMidiReceiver extends ScMidiReceiver` (test scope only)

- [ ] **Step 0: Create the branch**

```bash
git switch main
git pull --ff-only
git switch -c refactoring/midi-transmitter-family
```

- [ ] **Step 1: Write the test double**

Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/NoOpScMidiReceiver.scala` (no license header — the
pre-commit hook adds it):

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.message.ScMidiMessage

/**
 * A [[ScMidiReceiver]] that ignores every message. Transmitter tests only need receivers that are distinct by
 * identity, so each instance stands for one distinct receiver and nothing is ever sent to it.
 */
class NoOpScMidiReceiver extends ScMidiReceiver {
  override def send(message: ScMidiMessage, timeStamp: Long): Unit = {}

  override def close(): Unit = {}
}
```

- [ ] **Step 2: Write the failing tests for `ImmutableMidiTransmitter` (red)**

Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitterTest.scala`:

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImmutableMidiTransmitterTest extends AnyFlatSpec with Matchers {

  trait Fixture {
    val receiver1: ScMidiReceiver = NoOpScMidiReceiver()
    val receiver2: ScMidiReceiver = NoOpScMidiReceiver()
    val receiver3: ScMidiReceiver = NoOpScMidiReceiver()

    val transmitter: ImmutableMidiTransmitter = ImmutableMidiTransmitter(Seq(receiver1, receiver2))
  }

  behavior of "constructor"

  it should "default to no receivers" in {
    // When
    val transmitter = ImmutableMidiTransmitter()

    // Then
    transmitter.receivers shouldBe empty
  }

  it should "expose the receivers it was given, in order" in new Fixture {
    // Then
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  behavior of "withReceiver"

  it should "return a new transmitter with the receiver appended and leave the original unchanged" in new Fixture {
    // When
    val result = transmitter.withReceiver(receiver3)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "allow the same receiver to be added twice" in new Fixture {
    // When
    val result = transmitter.withReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver1)
  }

  behavior of "withReceivers"

  it should "return a new transmitter with all the receivers appended, in order" in new Fixture {
    // Given
    val transmitterWithOne = ImmutableMidiTransmitter(Seq(receiver1))

    // When
    val result = transmitterWithOne.withReceivers(Seq(receiver2, receiver3))

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    transmitterWithOne.receivers shouldEqual Seq(receiver1)
  }

  behavior of "withoutReceiver"

  it should "return a new transmitter without the receiver and leave the original unchanged" in new Fixture {
    // When
    val result = transmitter.withoutReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver2)
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  it should "remove every occurrence of the receiver" in new Fixture {
    // Given
    val transmitterWithDuplicate = ImmutableMidiTransmitter(Seq(receiver1, receiver2, receiver1))

    // When
    val result = transmitterWithDuplicate.withoutReceiver(receiver1)

    // Then
    result.receivers shouldEqual Seq(receiver2)
  }

  it should "return an equal transmitter when the receiver is absent" in new Fixture {
    // When
    val result = transmitter.withoutReceiver(receiver3)

    // Then
    result.receivers shouldEqual Seq(receiver1, receiver2)
  }

  behavior of "withoutReceivers"

  it should "return a new transmitter without any of the given receivers" in new Fixture {
    // Given
    val transmitterWithThree = ImmutableMidiTransmitter(Seq(receiver1, receiver2, receiver3))

    // When
    val result = transmitterWithThree.withoutReceivers(Seq(receiver1, receiver3))

    // Then
    result.receivers shouldEqual Seq(receiver2)
    transmitterWithThree.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
  }

  behavior of "close"

  it should "be a no-op that keeps the receivers" in new Fixture {
    // When
    transmitter.close()

    // Then
    transmitter.receivers shouldEqual Seq(receiver1, receiver2)
  }

  behavior of "equality"

  it should "hold between two transmitters with the same receivers" in new Fixture {
    // When
    val other = ImmutableMidiTransmitter(Seq(receiver1, receiver2))

    // Then
    other shouldEqual transmitter
    other.withReceiver(receiver3) should not equal transmitter
  }
}
```

- [ ] **Step 3: Add the thinnest stubs so the suite compiles**

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiTransmitter.scala` in its **final** form — a pure
interface has no logic to stub:

```scala
package org.calinburloiu.music.scmidi

/**
 * Source of MIDI messages that fans out to a sequence of [[ScMidiReceiver]]s.
 *
 * This is the read-only view: it exposes the current receivers and nothing else, so a consumer that only forwards
 * messages (e.g. a splitter) does not depend on how, or whether, the sequence can change. Implementations decide the
 * mutation policy:
 *
 *   - [[ImmutableMidiTransmitter]] — a value; changes produce new instances.
 *   - [[MutableMidiTransmitter]] — in-place changes, for a single thread.
 *   - [[ConcurrentMidiTransmitter]] — in-place changes from any thread.
 *
 * It is `AutoCloseable` so that an implementation that holds a resource (a native endpoint, a thread) has a release
 * hook; the three implementations above hold none and implement [[close]] as a no-op. A consumer that is merely
 * handed a transmitter does not own it and must not close it.
 *
 * @see [[javax.sound.midi.Transmitter]], which allows a single receiver only.
 */
trait MidiTransmitter extends AutoCloseable {
  /**
   * The receivers messages are currently forwarded to.
   *
   * @return an immutable snapshot; later changes to the transmitter do not affect a sequence already returned.
   */
  def receivers: Seq[ScMidiReceiver]

  /**
   * Releases any resources held by this transmitter. Implementations that hold none make this a no-op.
   */
  override def close(): Unit
}
```

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitter.scala` as a **stub**:

```scala
package org.calinburloiu.music.scmidi

case class ImmutableMidiTransmitter(receivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter {
  def withReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter = ???

  def withReceivers(newReceivers: Seq[ScMidiReceiver]): ImmutableMidiTransmitter = ???

  def withoutReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter = ???

  def withoutReceivers(receiversToRemove: Seq[ScMidiReceiver]): ImmutableMidiTransmitter = ???

  override def close(): Unit = ???
}
```

- [ ] **Step 4: Run the test class to verify it fails for the right reason**

Compile with `mcp__metals__compile-module` (`module = "sc-midi"`), then:

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ImmutableMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: the two `constructor` tests pass (a case class field needs no logic); every other test **fails with
`NotImplementedError`**. No compile errors.

- [ ] **Step 5: Implement `ImmutableMidiTransmitter` (green)**

Replace the stub body with the final implementation, ScalaDoc included:

```scala
package org.calinburloiu.music.scmidi

/**
 * A [[MidiTransmitter]] that is a value: every change returns a new instance and leaves this one untouched.
 *
 * Suited to configuration that is built once and then only read, and to pipelines owned by a single thread that
 * prefer to swap a whole transmitter rather than mutate one.
 *
 * @param receivers the receivers messages are forwarded to, in order; defaults to none.
 */
case class ImmutableMidiTransmitter(receivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter {

  /**
   * Returns a copy with `receiver` appended. The same receiver may appear more than once.
   *
   * @param receiver the receiver to append.
   * @return a new transmitter; this one is unchanged.
   */
  def withReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter = copy(receivers = receivers :+ receiver)

  /**
   * Returns a copy with `newReceivers` appended, in order.
   *
   * @param newReceivers the receivers to append.
   * @return a new transmitter; this one is unchanged.
   */
  def withReceivers(newReceivers: Seq[ScMidiReceiver]): ImmutableMidiTransmitter =
    copy(receivers = receivers :++ newReceivers)

  /**
   * Returns a copy without any occurrence of `receiver`. Returns an equal transmitter when it is absent.
   *
   * @param receiver the receiver to remove.
   * @return a new transmitter; this one is unchanged.
   */
  def withoutReceiver(receiver: ScMidiReceiver): ImmutableMidiTransmitter =
    copy(receivers = receivers.filterNot(_ == receiver))

  /**
   * Returns a copy without any occurrence of any of `receiversToRemove`.
   *
   * @param receiversToRemove the receivers to remove.
   * @return a new transmitter; this one is unchanged.
   */
  def withoutReceivers(receiversToRemove: Seq[ScMidiReceiver]): ImmutableMidiTransmitter =
    copy(receivers = receivers.filterNot(receiversToRemove.contains))

  /** No-op: an immutable transmitter holds no resources. */
  override def close(): Unit = {}
}
```

- [ ] **Step 6: Run the test class to verify it passes**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ImmutableMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiTransmitter.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitter.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/NoOpScMidiReceiver.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ImmutableMidiTransmitterTest.scala
git commit -m "[#278/#280] Add MidiTransmitter and ImmutableMidiTransmitter"
```

The pre-commit hook adds the license headers; if it rewrites the files and aborts the commit, `git add` them again and
repeat the commit.

---

## Task 2: `MutableMidiTransmitter` and the shared single-thread behaviours

**Files:**
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitter.scala`
- Create: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterBehaviors.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterTest.scala`

**Interfaces:**
- Consumes: `MidiTransmitter`, `NoOpScMidiReceiver` from Task 1.
- Produces, for Task 3 and for #281:
  - `class MutableMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter`
    (annotated `@NotThreadSafe`) with `receivers: Seq[ScMidiReceiver]`, `receivers_=(newReceivers:
    Seq[ScMidiReceiver]): Unit`, `addReceiver(receiver: ScMidiReceiver): Unit`, `addReceivers(newReceivers:
    Seq[ScMidiReceiver]): Unit`, `removeReceiver(receiver: ScMidiReceiver): Unit`, `clearReceivers(): Unit`,
    `close(): Unit` (no-op). Contract: every modifier reads the private field and writes **only** via the virtual
    `receivers_=`; the constructor writes the field directly.
  - Test-scope `trait MutableMidiTransmitterBehaviors { this: AnyFlatSpec & Matchers => def
    mutableMidiTransmitter(newTransmitter: Seq[ScMidiReceiver] => MutableMidiTransmitter): Unit }`.

- [ ] **Step 1: Write the shared behaviours (red)**

ScalaTest's shared-behaviour idiom (`it should behave like …`) lets the same cases run against both mutable classes
without duplicating them. Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterBehaviors.scala`:

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Shared behaviours for the single-thread contract of [[MutableMidiTransmitter]], to be run by the test class of
 * each implementation (the class itself and [[ConcurrentMidiTransmitter]]) via `it should behave like`.
 */
trait MutableMidiTransmitterBehaviors {
  this: AnyFlatSpec & Matchers =>

  /**
   * Runs the single-thread contract against transmitters built by `newTransmitter`.
   *
   * @param newTransmitter factory taking the initial receivers.
   */
  def mutableMidiTransmitter(newTransmitter: Seq[ScMidiReceiver] => MutableMidiTransmitter): Unit = {
    trait Fixture {
      val receiver1: ScMidiReceiver = NoOpScMidiReceiver()
      val receiver2: ScMidiReceiver = NoOpScMidiReceiver()
      val receiver3: ScMidiReceiver = NoOpScMidiReceiver()

      val transmitter: MutableMidiTransmitter = newTransmitter(Seq.empty)
    }

    it should "default to no receivers" in new Fixture {
      // Then
      transmitter.receivers shouldBe empty
    }

    it should "expose the initial receivers it was constructed with, in order" in new Fixture {
      // When
      val initialised = newTransmitter(Seq(receiver1, receiver2))

      // Then
      initialised.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "replace all receivers when receivers is assigned" in new Fixture {
      // Given
      transmitter.addReceiver(receiver3)

      // When
      transmitter.receivers = Seq(receiver1, receiver2)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "return a snapshot that later changes do not affect" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1)
      val snapshot = transmitter.receivers

      // When
      transmitter.addReceiver(receiver2)

      // Then
      snapshot shouldEqual Seq(receiver1)
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "append a receiver with addReceiver, preserving order and allowing duplicates" in new Fixture {
      // When
      transmitter.addReceiver(receiver1)
      transmitter.addReceiver(receiver2)
      transmitter.addReceiver(receiver1)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2, receiver1)
    }

    it should "append a sequence of receivers with addReceivers, preserving order" in new Fixture {
      // Given
      transmitter.addReceiver(receiver1)

      // When
      transmitter.addReceivers(Seq(receiver2, receiver3))

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2, receiver3)
    }

    it should "remove every occurrence of a receiver with removeReceiver" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2, receiver1, receiver3)

      // When
      transmitter.removeReceiver(receiver1)

      // Then
      transmitter.receivers shouldEqual Seq(receiver2, receiver3)
    }

    it should "leave the receivers unchanged when removeReceiver is given an absent receiver" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2)

      // When
      transmitter.removeReceiver(receiver3)

      // Then
      transmitter.receivers shouldEqual Seq(receiver1, receiver2)
    }

    it should "remove all receivers with clearReceivers" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1, receiver2)

      // When
      transmitter.clearReceivers()

      // Then
      transmitter.receivers shouldBe empty
    }

    it should "keep its receivers when closed, close being a no-op" in new Fixture {
      // Given
      transmitter.receivers = Seq(receiver1)

      // When
      transmitter.close()

      // Then
      transmitter.receivers shouldEqual Seq(receiver1)
    }
  }
}
```

- [ ] **Step 2: Write the failing test class (red)**

Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterTest.scala`. The probing subclass
pins the funnel contract (#281's `MidiProcessorTransmitter` intercepts every mutation by overriding the setter) and
that the constructor bypasses the setter (see "Why the constructor stores `initialReceivers` directly" above).

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class MutableMidiTransmitterTest extends AnyFlatSpec with Matchers with MutableMidiTransmitterBehaviors {

  /**
   * Records every sequence passed to `receivers_=`, to prove each modifier funnels through the setter. Not `private`:
   * a fixture exposes a value of this type, and Scala rejects a non-private member whose type is a private class.
   */
  class SetterProbingTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty)
    extends MutableMidiTransmitter(initialReceivers) {

    val assignedSequences: mutable.ListBuffer[Seq[ScMidiReceiver]] = mutable.ListBuffer()

    override def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = {
      assignedSequences += newReceivers
      super.receivers_=(newReceivers)
    }
  }

  trait ProbeFixture {
    val receiver1: ScMidiReceiver = NoOpScMidiReceiver()
    val receiver2: ScMidiReceiver = NoOpScMidiReceiver()

    val probe: SetterProbingTransmitter = SetterProbingTransmitter()
  }

  behavior of "MutableMidiTransmitter"

  it should behave like mutableMidiTransmitter(initialReceivers => MutableMidiTransmitter(initialReceivers))

  it should "not call receivers_= from its constructor" in {
    // Given
    val receiver = NoOpScMidiReceiver()

    // When
    val probe = SetterProbingTransmitter(Seq(receiver))

    // Then
    probe.assignedSequences shouldBe empty
    probe.receivers shouldEqual Seq(receiver)
  }

  it should "funnel every modifier through receivers_= with the resulting sequence" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)
    probe.addReceivers(Seq(receiver2, receiver1))
    probe.removeReceiver(receiver1)
    probe.clearReceivers()

    // Then
    probe.assignedSequences.toSeq shouldEqual Seq(
      Seq(receiver1),
      Seq(receiver1, receiver2, receiver1),
      Seq(receiver2),
      Seq.empty,
    )
  }
}
```

- [ ] **Step 3: Add the thinnest stub so the suite compiles**

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitter.scala` as a stub. The getter and
constructor are the only parts with a body, because the red run must reach the assertions of the "constructor" tests:

```scala
package org.calinburloiu.music.scmidi

import javax.annotation.concurrent.NotThreadSafe

@NotThreadSafe
class MutableMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter {
  private var _receivers: Seq[ScMidiReceiver] = initialReceivers

  override def receivers: Seq[ScMidiReceiver] = _receivers

  def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = ???

  def addReceiver(receiver: ScMidiReceiver): Unit = ???

  def addReceivers(newReceivers: Seq[ScMidiReceiver]): Unit = ???

  def removeReceiver(receiver: ScMidiReceiver): Unit = ???

  def clearReceivers(): Unit = ???

  override def close(): Unit = ???
}
```

- [ ] **Step 4: Run the test class to verify it fails for the right reason**

Compile with `mcp__metals__compile-module` (`module = "sc-midi"`), then:

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MutableMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: "default to no receivers", "expose the initial receivers …" and "not call receivers_= from its constructor"
pass; every other test **fails with `NotImplementedError`**. No compile errors.

- [ ] **Step 5: Implement `MutableMidiTransmitter` (green)**

Replace the stub with the final implementation:

```scala
package org.calinburloiu.music.scmidi

import javax.annotation.concurrent.NotThreadSafe

/**
 * A [[MidiTransmitter]] whose receivers change in place, for use from a single thread.
 *
 * Every modifier — [[addReceiver]], [[addReceivers]], [[removeReceiver]], [[clearReceivers]] — computes the new
 * sequence from the current one and assigns it through [[receivers_=]] and nothing else, so a subclass that overrides
 * the setter observes every change through that one method (see [[ConcurrentMidiTransmitter]]). To keep the funnel
 * the only re-entry point, modifiers read the backing field directly rather than through the public getter. The
 * constructor stores `initialReceivers` directly, without calling the setter, so that a subclass override never runs
 * on a partially constructed object.
 *
 * Not thread-safe: use [[ConcurrentMidiTransmitter]] when several threads read or change the receivers.
 *
 * @param initialReceivers the receivers messages are forwarded to at construction; defaults to none.
 */
@NotThreadSafe
class MutableMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty) extends MidiTransmitter {
  private var _receivers: Seq[ScMidiReceiver] = initialReceivers

  override def receivers: Seq[ScMidiReceiver] = _receivers

  /**
   * Replaces all receivers. Every other modifier ends up here.
   *
   * @param newReceivers the receivers messages are forwarded to from now on, in order.
   */
  def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = {
    _receivers = newReceivers
  }

  /**
   * Appends a receiver. The same receiver may be added more than once.
   *
   * @param receiver the receiver to append.
   */
  def addReceiver(receiver: ScMidiReceiver): Unit = {
    receivers = _receivers :+ receiver
  }

  /**
   * Appends receivers, in order.
   *
   * @param newReceivers the receivers to append.
   */
  def addReceivers(newReceivers: Seq[ScMidiReceiver]): Unit = {
    receivers = _receivers :++ newReceivers
  }

  /**
   * Removes every occurrence of a receiver; does nothing when it is absent.
   *
   * @param receiver the receiver to remove.
   */
  def removeReceiver(receiver: ScMidiReceiver): Unit = {
    receivers = _receivers.filterNot(_ == receiver)
  }

  /** Removes all receivers. */
  def clearReceivers(): Unit = {
    receivers = Seq.empty
  }

  /** No-op: this transmitter holds no resources. */
  override def close(): Unit = {}
}
```

`receivers = …` inside the class is Scala's assignment syntax for the `receivers`/`receivers_=` pair and dispatches
virtually to `receivers_=`, which is what the funnel tests check.

- [ ] **Step 6: Run the test class to verify it passes**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MutableMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitter.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterBehaviors.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MutableMidiTransmitterTest.scala
git commit -m "[#278/#280] Add MutableMidiTransmitter with every modifier funnelling through receivers_="
```

---

## Task 3: `ConcurrentMidiTransmitter`

**Files:**
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala`

**Interfaces:**
- Consumes: `MutableMidiTransmitter` and `MutableMidiTransmitterBehaviors` from Task 2, `NoOpScMidiReceiver` from
  Task 1, `org.calinburloiu.music.microtonalist.common.concurrency.Locking` from the `common` module (`withReadLock` /
  `withWriteLock` taking an implicit `ReadWriteLock`).
- Produces, for #281:
  - `class ConcurrentMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty) extends
    MutableMidiTransmitter(initialReceivers) with Locking` (annotated `@ThreadSafe`), overriding `receivers` under the
    read lock and `receivers_=`, `addReceiver`, `addReceivers`, `removeReceiver`, `clearReceivers` under the write lock.
  - `protected implicit val lock: ReentrantReadWriteLock` — for a subclass that overrides `receivers_=` and needs to
    take the same lock (see "Notes for #281").

- [ ] **Step 1: Write the failing tests (red)**

Create `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala`. Three groups: the
shared single-thread contract; a lock probe proving each modifier reaches `receivers_=` while the current thread holds
the write lock (the property D6 relies on); and the multi-thread test. The multi-thread test targets the lost-update
race: without a write lock around the whole read-modify-write in `addReceiver`, two threads appending at once read the
same sequence and one append is dropped, so the final count comes out short.

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

class ConcurrentMidiTransmitterTest extends AnyFlatSpec with Matchers with MutableMidiTransmitterBehaviors {

  /**
   * Records, for every call of `receivers_=`, whether the calling thread held the write lock at that moment. A
   * subclass overriding the setter (as `MidiProcessorTransmitter` will) relies on this being `true` for every change
   * that arrives through a modifier. Not `private`: a fixture exposes a value of this type. The two `lock…` accessors
   * let a test look at the `protected` lock without reaching into it from outside the class.
   */
  class WriteLockProbingTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty)
    extends ConcurrentMidiTransmitter(initialReceivers) {

    val writeLockHeldOnSet: mutable.ListBuffer[Boolean] = mutable.ListBuffer()

    override def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = {
      writeLockHeldOnSet += lock.isWriteLockedByCurrentThread
      super.receivers_=(newReceivers)
    }

    def lockIsWriteLocked: Boolean = lock.isWriteLocked

    def lockReadLockCount: Int = lock.getReadLockCount
  }

  trait ProbeFixture {
    val receiver1: ScMidiReceiver = NoOpScMidiReceiver()
    val receiver2: ScMidiReceiver = NoOpScMidiReceiver()

    val probe: WriteLockProbingTransmitter = WriteLockProbingTransmitter()
  }

  /**
   * Fixture for the multi-thread test: `writerCount` writer threads each own `receiversPerWriter` distinct receivers,
   * add them all, then remove the first half; `readerCount` reader threads read snapshots in a loop meanwhile. All
   * threads start on one latch so that they overlap, and any exception thrown on a thread is collected and asserted on.
   */
  trait ConcurrencyFixture {
    val writerCount: Int = 8
    val receiversPerWriter: Int = 250
    val readerCount: Int = 4

    val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
    val receiversByWriter: Seq[Seq[ScMidiReceiver]] =
      Seq.fill(writerCount)(Seq.fill(receiversPerWriter)(NoOpScMidiReceiver()))
    val expectedFinalReceivers: Seq[ScMidiReceiver] = receiversByWriter.flatMap(_.drop(receiversPerWriter / 2))

    val start: CountDownLatch = CountDownLatch(1)
    val readersRunning: AtomicBoolean = AtomicBoolean(true)
    val failures: ConcurrentLinkedQueue[Throwable] = ConcurrentLinkedQueue()

    def startThread(body: => Unit): Thread = {
      // Typed as Runnable explicitly: the catch branch returns a Boolean, so an untyped lambda would not SAM-convert.
      val runnable: Runnable = () => {
        start.await()
        try {
          body
        } catch {
          case NonFatal(e) => failures.add(e)
        }
      }
      val thread = Thread(runnable)
      thread.start()
      thread
    }

    val writers: Seq[Thread] = receiversByWriter.map { ownReceivers =>
      startThread {
        ownReceivers.foreach(transmitter.addReceiver)
        ownReceivers.take(receiversPerWriter / 2).foreach(transmitter.removeReceiver)
      }
    }

    val readers: Seq[Thread] = Seq.fill(readerCount) {
      startThread {
        while (readersRunning.get()) {
          val snapshot = transmitter.receivers
          snapshot.size should be <= writerCount * receiversPerWriter
          snapshot.distinct.size shouldEqual snapshot.size
        }
      }
    }

    def runAll(): Unit = {
      start.countDown()
      writers.foreach(_.join(30000L))
      readersRunning.set(false)
      readers.foreach(_.join(30000L))
    }
  }

  behavior of "ConcurrentMidiTransmitter"

  it should behave like mutableMidiTransmitter(initialReceivers => ConcurrentMidiTransmitter(initialReceivers))

  it should "not call receivers_= from its constructor" in {
    // Given
    val receiver = NoOpScMidiReceiver()

    // When
    val probe = WriteLockProbingTransmitter(Seq(receiver))

    // Then
    probe.writeLockHeldOnSet shouldBe empty
    probe.receivers shouldEqual Seq(receiver)
  }

  it should "reach receivers_= from every modifier while holding the write lock" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)
    probe.addReceivers(Seq(receiver2))
    probe.removeReceiver(receiver1)
    probe.clearReceivers()

    // Then
    probe.writeLockHeldOnSet.toSeq shouldEqual Seq(true, true, true, true)
    probe.receivers shouldBe empty
  }

  it should "release the write lock after a modifier returns" in new ProbeFixture {
    // When
    probe.addReceiver(receiver1)

    // Then
    probe.lockIsWriteLocked shouldBe false
    probe.lockReadLockCount shouldEqual 0
  }

  it should "not lose updates when several threads add and remove receivers while others read" in
    new ConcurrencyFixture {
      // When
      runAll()

      // Then
      writers.map(_.isAlive) should contain only false
      readers.map(_.isAlive) should contain only false
      failures.asScala shouldBe empty
      // The size check comes first so that a lost update fails with a readable count, not a dump of 1000 receivers.
      transmitter.receivers should have size expectedFinalReceivers.size
      transmitter.receivers should contain theSameElementsAs expectedFinalReceivers
    }
}
```

- [ ] **Step 2: Add the thinnest stub so the suite compiles**

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala` as a stub. The lock
must exist (the probe reads it) and the getter must work (the constructor tests assert on it); every override that
carries logic is `???`:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
class ConcurrentMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty)
  extends MutableMidiTransmitter(initialReceivers), Locking {

  protected implicit val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

  override def receivers: Seq[ScMidiReceiver] = super.receivers

  override def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = ???

  override def addReceiver(receiver: ScMidiReceiver): Unit = ???

  override def addReceivers(newReceivers: Seq[ScMidiReceiver]): Unit = ???

  override def removeReceiver(receiver: ScMidiReceiver): Unit = ???

  override def clearReceivers(): Unit = ???
}
```

- [ ] **Step 3: Run the test class to verify it fails for the right reason**

Compile with `mcp__metals__compile-module` (`module = "sc-midi"`), then:

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ConcurrentMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: the two constructor tests pass; every other test **fails with `NotImplementedError`** — in the multi-thread
test the error is thrown on the writer threads, collected in `failures`, and surfaces at `failures.asScala shouldBe
empty`. No compile errors.

- [ ] **Step 4: Implement `ConcurrentMidiTransmitter` (green)**

Replace the stub with the final implementation:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.microtonalist.common.concurrency.Locking

import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.ThreadSafe

/**
 * A [[MutableMidiTransmitter]] that may be read and changed from any thread.
 *
 * Every accessor and modifier of the mutable class is overridden to run under a [[ReentrantReadWriteLock]]: reads
 * take the read lock, changes take the write lock for the whole read-modify-write, so concurrent `addReceiver`s never
 * lose an update. Because the mutable base funnels every modifier through `receivers_=`, and each modifier override
 * here already holds the write lock when it calls `super`, a subclass that overrides `receivers_=` is reached
 * **inside** the write lock for every change made through a modifier. A subclass override reached by a direct
 * `receivers = …` assignment runs before this class's own `receivers_=` takes the lock, so such an override must take
 * the write lock itself (the lock is reentrant and `protected` for that purpose).
 *
 * Extends the mutable class so that a caller which only needs "something it can add a receiver to" has one static
 * type, whatever the threading policy.
 *
 * @param initialReceivers the receivers messages are forwarded to at construction; defaults to none.
 */
@ThreadSafe
class ConcurrentMidiTransmitter(initialReceivers: Seq[ScMidiReceiver] = Seq.empty)
  extends MutableMidiTransmitter(initialReceivers), Locking {

  /** Guards the receivers. Reentrant, so an override of `receivers_=` may take it again. */
  protected implicit val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

  override def receivers: Seq[ScMidiReceiver] = withReadLock {
    super.receivers
  }

  override def receivers_=(newReceivers: Seq[ScMidiReceiver]): Unit = withWriteLock {
    super.receivers_=(newReceivers)
  }

  override def addReceiver(receiver: ScMidiReceiver): Unit = withWriteLock {
    super.addReceiver(receiver)
  }

  override def addReceivers(newReceivers: Seq[ScMidiReceiver]): Unit = withWriteLock {
    super.addReceivers(newReceivers)
  }

  override def removeReceiver(receiver: ScMidiReceiver): Unit = withWriteLock {
    super.removeReceiver(receiver)
  }

  override def clearReceivers(): Unit = withWriteLock {
    super.clearReceivers()
  }
}
```

`close()` is inherited: it touches no state, so there is nothing to lock. `Locking.withReadLock`/`withWriteLock` take
an implicit `ReadWriteLock`; the `ReentrantReadWriteLock` val satisfies it and additionally exposes
`isWriteLockedByCurrentThread` for the probe. Inside a modifier override the write lock is taken once here and once
more, reentrantly, when `super.addReceiver` reaches `receivers_=`; `ReentrantReadWriteLock` counts the holds and
releases on the outermost `unlock`.

- [ ] **Step 5: Run the test class to verify it passes**

```bash
sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.ConcurrentMidiTransmitterTest -- -oNCXEHLOPQRMWS"
```

Expected: all tests pass. Run the class **three times**; the multi-thread test must be green every time. If it is
flaky, the cause is in the implementation (a missing lock), not in the test: do not widen the timeouts or shrink the
counts.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitter.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ConcurrentMidiTransmitterTest.scala
git commit -m "[#278/#280] Add ConcurrentMidiTransmitter locking every accessor and modifier"
```

---

## Task 4: Final checks, documentation and the pull request

**Files:**
- Modify: `docs/architecture/sc-midi/README.md` (the "MIDI plumbing (receivers, transmitters, processors)" list and
  the "Notes / subject to change" list)
- Verify: every file created in Tasks 1–3

**Interfaces:** none new.

- [ ] **Step 1: Module tests**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green, `MultiTransmitterTest` and `MidiSplitterTest` included and unchanged.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow it for the `sc-midi` module. Check the three new production classes
individually (`ImmutableMidiTransmitter`, `MutableMidiTransmitter`, `ConcurrentMidiTransmitter`); each must be at or
above 80% statement coverage (expect ~100%), and the module totals must stay at or above the `67` / `52` floors. If a
line is uncovered, add the missing case to the corresponding test class in Tasks 1–3 style (Given/When/Then, in the
existing `behavior of` block) rather than excluding the file.

- [ ] **Step 3: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. Nothing outside `sc-midi` changed, so a failure elsewhere is pre-existing or environmental — report
it, do not "fix" it in this PR.

- [ ] **Step 4: Update the `sc-midi` architecture document**

In `docs/architecture/sc-midi/README.md`, section "MIDI plumbing (receivers, transmitters, processors)", replace the
`MultiTransmitter` bullet with the two bullets below (the first is the old bullet, marked as superseded):

```markdown
- **`MultiTransmitter`** — a thread-safe transmitter allowing **multiple** Java `Receiver`s, unlike Java's
  single-receiver `Transmitter`. Superseded by the `MidiTransmitter` family below; #281 rewires its users and deletes
  it.
- **`MidiTransmitter`** — the read-only, `AutoCloseable` transmitter of the Scala API: a single
  `receivers: Seq[ScMidiReceiver]` member, no locks. Three implementations, all with a no-op `close()`:
  `ImmutableMidiTransmitter` (a case class whose `withReceiver`/`withReceivers`/`withoutReceiver`/`withoutReceivers`
  return new instances), `MutableMidiTransmitter` (`@NotThreadSafe`; every modifier funnels through `receivers_=`, so
  a subclass overriding the setter intercepts every change) and `ConcurrentMidiTransmitter` (`@ThreadSafe`; the
  mutable one with every accessor and modifier under a `ReentrantReadWriteLock` via `Locking`, so a subclass override
  of `receivers_=` is reached inside the write lock for every change made through a modifier). Nothing uses them yet:
  #281 puts `MidiSplitter`, `MidiProcessor` and `MidiDeviceHandle` on top of them.
```

In "Notes / subject to change", append:

```markdown
- The module is being split into a pure Scala API and a `javamidi` implementation package under #278; until #281
  lands, `MultiTransmitter` (Java-typed) and the `MidiTransmitter` family (Scala-typed) coexist.
```

- [ ] **Step 5: Review the ScalaDocs**

Read the four production files once more and check that every public identifier — each class, `lock`, every method —
has a ScalaDoc, that no line exceeds 120 columns, and that no `new` slipped in. Compile once more with
`mcp__metals__compile-module` (`module = "sc-midi"`) and confirm there are no warnings from the new files.

- [ ] **Step 6: Commit the documentation**

```bash
git add docs/architecture/sc-midi/README.md
git commit -m "[#278/#280] Document the MidiTransmitter family in the sc-midi architecture doc"
```

- [ ] **Step 7: Open the draft pull request**

Use the `contributing` skill's script; it pushes the branch, applies the `[#278/#280]` title prefix, the
`refactoring` label, the `sc-midi` milestone inherited from #280, the project, the assignee, and the `Resolves #280`
line. Dry-run first, then create:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 278/280 \
  "Add the MidiTransmitter family: immutable, mutable and concurrent implementations" \
  "Adds the read-only \`MidiTransmitter\` trait and its \`ImmutableMidiTransmitter\`, \`MutableMidiTransmitter\` and \`ConcurrentMidiTransmitter\` implementations, typed on the Scala receiver, with unit tests including a multi-thread lost-update test. Design: decision D4 of \`issues/00278-isolate-java-midi/2026-09-07-isolate-java-midi-design.md\`; plan: \`issues/00278-isolate-java-midi/2026-09-08-280-midi-transmitter-family-plan.md\` (both on PR #284). \`MultiTransmitter\` and its users are untouched; #281 rewires them and deletes it." \
  --dry-run
```

If the output looks right, run the same command without `--dry-run`. Report the PR URL.

---

## Self-review against the design

- **D4 trait**: `MidiTransmitter extends AutoCloseable`, single `receivers` member, no locks, no implementation — Task 1.
- **D4 `ImmutableMidiTransmitter`**: case class, the four `with…`/`without…` methods returning new instances — Task 1.
- **D4 `MutableMidiTransmitter`**: `@NotThreadSafe`, `receivers_=`, `addReceiver`, `addReceivers`, `removeReceiver`,
  `clearReceivers`, every modifier funnelling through `receivers_=`, no other overridable method called from a
  modifier — Task 2 (pinned by the setter-probe test).
- **D4 `ConcurrentMidiTransmitter`**: `@ThreadSafe`, extends the mutable class, every method under a
  `ReentrantReadWriteLock` via `Locking` — Task 3 (pinned by the lock-probe test); `close()` inherited as a no-op.
- **`close()` no-op on all three; `MidiSplitter` unchanged** — Tasks 1–3, Global Constraints.
- **Section 3, row 2**: the four types and their tests, `MultiTransmitter` untouched — all tasks.
- **Section 4**: unit tests for the three implementations, the last with a concurrency test that mutates and reads
  from several threads — Tasks 1–3; coverage floors — Task 4.
- **Section 5**: ScalaDocs on every new public type and member — Tasks 1–3; `docs/architecture/sc-midi/README.md`
  gains the transmitter family (the full rewrite around the API/implementation split waits for #282) — Task 4.
- **Type consistency**: `receivers_=(newReceivers)`, `addReceiver(receiver)`, `addReceivers(newReceivers)`,
  `removeReceiver(receiver)`, `clearReceivers()`, `withoutReceivers(receiversToRemove)`, `NoOpScMidiReceiver()`,
  `MutableMidiTransmitterBehaviors.mutableMidiTransmitter(factory)` and `lock: ReentrantReadWriteLock` are spelled the
  same in every task.

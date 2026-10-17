# Decouple `sc-midi` from `businessync` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-10-16
- **Based on commit:** `3f74046` (`refactoring/sc-midi-javamidi`, step 2 implemented, PR #373 open, not merged)
- **Issue:** #376, step 6 of #366

**Goal:** Make `JavaMidiManager` publish its `MidiEvent`s through a `MidiEventPublisher` SAM trait of `sc-midi`
instead of a `Businessync`, so that neither `sc-midi`, `sc-midi-javamidi` nor `cli` depends on `businessync`.

**Architecture:** `Businessync.publish` first widens to `AnyRef`, so that the bus no longer needs `MidiEvent` to be a
`BusinessyncEvent`. Then `JavaMidiManager` takes a `MidiEventPublisher`: `app` passes a lambda that calls
`businessync.publish`, `cli` a no-op. Last, `MidiEvent` stops extending `BusinessyncEvent` and both MIDI modules drop
`businessync` from `build.sbt`. `RigManager` keeps subscribing to `MidiEvent` through Guava, unchanged.

**Tech Stack:** sbt 1, Scala 3, Guava `EventBus`, ScalaTest, ScalaMock, Markdown.

**Spec:** [`2026-10-16-design.md`](2026-10-16-design.md): *Module conventions* and step *6. Decouple `sc-midi` from
`businessync`*.

## Global Constraints

- The trait: `trait MidiEventPublisher { def publish(event: MidiEvent): Unit }`, in
  `midi/sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEventPublisher.scala`, package
  `org.calinburloiu.music.scmidi`. A trait rather than a function type, so that its ScalaDoc can say on which thread
  the events come; a lambda still converts to it.
- `JavaMidiManager`'s first parameter, in the private constructor and in `JavaMidiManager.apply`, becomes
  `eventPublisher: MidiEventPublisher`. Its other parameters and its behaviour don't change.
- `app` passes `event => businessync.publish(event)`, and `cli` passes `_ => ()` and builds no `Businessync`.
- `MidiEvent` stops extending `BusinessyncEvent`. `Businessync.publish` takes `event: AnyRef`, as Guava's
  `EventBus.post` does. `BusinessyncEvent` stays, as the base of `TuningEvent`, `RigEvent` and
  `ScaleLossyConversionEvent`, and so do the `BusinessyncEvent` bounds of `handle`, `handleOnUi`, `subscribe` and
  `subscribeOnUi`: #90 redesigns them.
- `scMidiModule` and `scMidiJavamidiModule` drop `businessyncModule` from `dependsOn`. No other module's `dependsOn`
  changes: `cli` never declared `businessync`, and every other module that imports it declares it or gets it through
  `tuner`, as before.
- Done when the projects with `businessync` on their `Compile` classpath are `app composition format rig tuner ui`
  (today also `cli sc-midi sc-midi-javamidi`).
- `businessync` stays unpublished (#90). Publishing `sc-midi` and its `common` dependency (`Locking`) are out of scope.
- Coverage floors don't change: `sc-midi` and `sc-midi-javamidi` stay at 80/80, `businessync` at 0/0 (#174), `app` at
  59/39 (#176), `cli` at 0/0 (#181). The step writes no tests to raise coverage.
- TDD: Task 2 and Task 3 are red/green; Task 4's red/green check is the classpath check.
- Every commit message starts with `[#366/#376]` and ends with the attribution lines that your session's instructions
  give.
- Don't edit `issues/` (other than checking off this plan) or `docs/agent-evals/`. `docs/release-notes.md` is out of
  scope.
- Docs follow `docs/development/doc-conventions.md`, with lines of at most 120 columns.
- Work in this clone, on `refactoring/midi-event-publisher`, stacked on `refactoring/sc-midi-javamidi`, with
  `git switch`: no git worktree, and never touch the processes of another clone (`microtonalist2`). Push only in
  Task 6, which opens the PR.
- Shell notes: the Bash tool runs zsh, which doesn't word-split an unquoted `$VAR`; rtk rewrites `grep` to `rg` and
  filters `git status`, `git diff` and `ps`, so use `grep -F` for literal text and `rtk proxy <cmd>` where the exact
  output matters.
- `$SCRATCH` is your session's scratchpad directory.

## Review Focus

1. **`app`'s publisher must forward to the bus that `RigManager` is registered with.** A no-op or another bus there
   compiles and passes every test, but `RigManager` then stops resetting a track's tuner when its output device
   reopens, and stops releasing its input when the input device goes away. No test reaches `MicrotonalistApp.run`, so
   Task 3, step 6 checks the line by reading it, and the PR's test plan (Task 6) asks the user to replug a device.
2. **A subscriber of `BusinessyncEvent` itself would silently stop receiving `MidiEvent`s**, since Guava dispatches by
   runtime type. Task 4, step 5 lists the `@Subscribe` handlers' parameter types; `RigManagerTest`, which publishes
   `MidiEvent`s through a real `Businessync`, pins the delivery.
3. **A module that got `businessync` or Guava only through `sc-midi`** fails to compile once the dependency goes.
   Task 4, step 4 compiles every module and runs the full suite.
4. **Docs and ScalaDoc that still put `sc-midi`'s events on the bus**, which no compiler checks. Task 5, step 5 greps
   `midi/` and the docs for `businessync` and the bus.
5. **`cli` with a no-op publisher.** No test loads CoreMIDI4J, so Task 6, step 3 runs `cli`'s fat JAR against the real
   devices and compares with Task 1's list.

---

### Task 1: Baseline

**Files:**
- Create (untracked): `$SCRATCH/check_md_links.py`

**Interfaces:**
- Produces: the link checker, run as `python3 $SCRATCH/check_md_links.py .` from the repository root; the baseline
  numbers that Tasks 2–6 compare against.

- [ ] **Step 1: Check the starting point**

```bash
git switch refactoring/midi-event-publisher
rtk proxy git status --short
git log --oneline -2
bin/mtlist-dev-stack status; echo "exit=$?"
```

Expected: no output from `git status`; the log shows this plan's commit on top of `3f74046`; `exit=0`. If the stack
isn't running, follow `docs/agents/dev-stack.md`. Then run `mcp__metals__compile-full`: success, with only the two
deprecation warnings on `TuningService.tunings` (in `TuningServiceTest` and `TuningListFrame`).

- [ ] **Step 2: Save the link checker and record the docs baseline**

```bash
awk '/^````python$/{f=1;next} /^````$/{if(f){exit}} f' \
  issues/00366-modularization/2026-10-16-367-grouping-folders-plan.md > "$SCRATCH/check_md_links.py"
wc -l < "$SCRATCH/check_md_links.py"
python3 "$SCRATCH/check_md_links.py" . | tail -1
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py | wc -l
```

Expected: `90`; `0 broken of 247 relative link(s) and import(s)`; `21` docs.

- [ ] **Step 3: Record the classpath check (red)**

```bash
sbtn "show Compile/dependencyClasspath" 2>&1 \
  | awk '/ \/ Compile \/ dependencyClasspath/ {project = $2} /businessync\/target-bsp/ {print project}' \
  | sort -u | tr '\n' ' '; echo
```

Expected: `app cli composition format rig sc-midi sc-midi-javamidi tuner ui`.

- [ ] **Step 4: Record the test counts**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "sc-midi-javamidi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: 333 tests in 17 suites; 161 tests in 4 suites; `11 modules, 1475 tests` and no other line.

- [ ] **Step 5: Record the devices that `cli` lists**

```bash
sbtn "show cli/assembly"
java -jar app/cli/target-bsp/scala-*/microtonalist-cli.jar midi-devices
```

Expected: `=== Input Devices ===` and `=== Output Devices ===`, each with the devices of this computer. Note the
device names: Task 6 compares against them.

---

### Task 2: `Businessync.publish` accepts any event

**Files:**
- Create: `base/businessync/src/test/scala/org/calinburloiu/businessync/BusinessyncTest.scala`
- Modify: `base/businessync/src/main/scala/org/calinburloiu/businessync/Businessync.scala` (`publish`)

**Interfaces:**
- Produces: `Businessync.publish(event: AnyRef): Unit`, which Task 4 needs once `MidiEvent` is no longer a
  `BusinessyncEvent`.

- [ ] **Step 1: Write the failing test**

`businessync` has no tests yet; this is its first suite. The contract is a type, so the test checks that the call
compiles, which ScalaTest checks when the test runs, not when the suite compiles:

```scala
class BusinessyncTest extends AnyWordSpec with Matchers {
  /** An event that isn't a [[BusinessyncEvent]], like the events of a library that doesn't know the bus. */
  private case class PlainEvent(value: Int)

  "a Businessync" should {
    "accept an event of any type" in {
      // Given
      val businessync = Businessync(EventBus())

      // When / Then
      "businessync.publish(PlainEvent(1))" should compile
    }
  }
}
```

If the snippet can't see the local `businessync` (a ScalaTest limitation of the `compile` matcher in Scala 3), make
`businessync` a member of the class instead, next to `PlainEvent`.

- [ ] **Step 2: Run it to see it fail**

Run: `sbtn "businessync/testOnly org.calinburloiu.businessync.BusinessyncTest -- -oNCXEHLOPQRMWS"`

Expected: the suite compiles and the test fails with `Expected no compiler error, but got the following type error`,
naming `PlainEvent` and the expected `BusinessyncEvent`.

- [ ] **Step 3: Widen `publish` to `event: AnyRef`**

Keep the body, `eventBus.post(event)`. Its ScalaDoc gains, before `TODO #90 Details`: "The event may be of any type:
the subscribers are matched by its runtime class and its supertypes, as Guava's `EventBus.post` does."

- [ ] **Step 4: Run it to see it pass**

Run the command of step 2. Expected: 1 test, passed. Then run `mcp__metals__compile-full`: success with only Task 1's
warnings; `handle(event: BusinessyncEvent)` still compiles, since it passes a `BusinessyncEvent` on.

- [ ] **Step 5: Commit**

```bash
git add base/businessync/src
git commit -m "[#366/#376] Let Businessync publish an event of any type" -m "<attribution lines>"
```

---

### Task 3: `JavaMidiManager` publishes through a `MidiEventPublisher`

**Files:**
- Create: `midi/sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEventPublisher.scala`
- Modify: `midi/sc-midi-javamidi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala`
  (constructor, `apply`, the two publishing calls in `refresh` and `withLockThenPublish`, their ScalaDoc)
- Modify: `midi/sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala` (class ScalaDoc)
- Modify: `app/app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala:89`
- Modify: `app/cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala`
- Test: `midi/sc-midi-javamidi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManagerTest.scala`

**Interfaces:**
- Produces: `trait MidiEventPublisher { def publish(event: MidiEvent): Unit }`;
  `JavaMidiManager.apply(eventPublisher: MidiEventPublisher, environment: JavaMidiEnvironment = CoreMidi4JEnvironment)`.

- [ ] **Step 1: Create the trait (stub)**

The trait has no body to stub, so write it with its final ScalaDoc:

```scala
/**
 * Receives the [[MidiEvent]]s that a [[MidiManager]] publishes, e.g. to forward them to an event bus.
 *
 * The manager calls [[publish]] synchronously, on the thread that made the change: the caller's for one of its
 * operations, a platform thread for a change in the environment. An implementation must therefore be thread-safe.
 */
trait MidiEventPublisher {

  /**
   * Publishes an event of the manager.
   *
   * @param event What changed in the MIDI environment or in one of the manager's devices.
   */
  def publish(event: MidiEvent): Unit
}
```

- [ ] **Step 2: Switch the test to a `MidiEventPublisher`**

In `JavaMidiManagerTest`'s `Fixture`, replace the `Businessync` stub:

```scala
/** What the publisher does with each published event, besides recording it; a test may replace it. */
var onPublish: MidiEvent => Unit = _ => ()

val eventPublisher: Stub[MidiEventPublisher] = stub[MidiEventPublisher]
eventPublisher.publish.returns(event => onPublish(event))

/** Creates the manager, which scans the devices plugged into [[environment]] so far. */
def newManager(): JavaMidiManager = JavaMidiManager(eventPublisher, environment)
```

Then rename the assertions and drop the `businessync` import:

```bash
f=midi/sc-midi-javamidi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManagerTest.scala
perl -pi -e 's/\bbusinessync\.publish\.calls\b/eventPublisher.publish.calls/g' "$f"
grep -nF -e businessync -e Businessync "$f"
```

Expected: the last command prints only the import line, which you delete. If a test assigns `onPublish` a function
that pattern-matches on a `BusinessyncEvent`, retype it to `MidiEvent`.

- [ ] **Step 3: Change `JavaMidiManager`'s parameter (stub)**

Change the first parameter of the private constructor and of `apply` to `eventPublisher: MidiEventPublisher`, drop
the `Businessync` import, and replace both `events.foreach(businessync.publish)` with `events.foreach(_ => ???)`.

- [ ] **Step 4: Run the test to see it fail**

Run: `sbtn "sc-midi-javamidi/testOnly * -- -oNCXEHLOPQRMWS"`

Expected: the module compiles (`app` and `cli` don't, which this command doesn't build); every test that expects
events fails with `scala.NotImplementedError` raised from `JavaMidiManager`, and those that expect none pass.

- [ ] **Step 5: Publish through `eventPublisher`**

Replace both `events.foreach(_ => ???)` with `events.foreach(eventPublisher.publish)`. Update the ScalaDoc that names
the bus or `businessync`:

- the class ScalaDoc: "because the bus delivers them synchronously to subscribers that may use the manager or send
  MIDI" becomes "because the publisher may deliver them synchronously to subscribers that use the manager or send
  MIDI";
- both `@param businessync Used for publishing [[MidiEvent]]s.` become `@param eventPublisher Receives the
  [[MidiEvent]]s, on the thread that made the change and outside the locks of the manager.`;
- `withLockThenPublish`: "a subscriber, which the bus calls on this thread" becomes "a subscriber, which the publisher
  may call on this thread".

In `MidiManager`'s class ScalaDoc, "publishes [[MidiEvent]]s about what it finds" becomes "publishes [[MidiEvent]]s
about what it finds through a [[MidiEventPublisher]]".

- [ ] **Step 6: Pass a publisher from the composition roots**

- `MicrotonalistApp.scala:89`: `val midiManager = JavaMidiManager(event => businessync.publish(event))`, with the
  same `businessync` that `RigModule` receives two lines below.
- `MicrotonalistToolApp.scala`: `val midiManager = JavaMidiManager(_ => ())`; delete the `EventBus` and `Businessync`
  imports. In `main`'s ScalaDoc, add after the first sentence of the second paragraph: "It needs none of the manager's
  events, so it publishes them nowhere."

- [ ] **Step 7: Run the tests to see them pass**

Run `mcp__metals__compile-full` (success, only Task 1's warnings), then:

```bash
sbtn "sc-midi-javamidi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "app/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: 161 tests in 4 suites; 333 tests in 17 suites; `cli` and `app` pass.

- [ ] **Step 8: Commit**

```bash
git add midi app
git commit -m "[#366/#376] Publish JavaMidiManager's events through a MidiEventPublisher" -m "<attribution lines>"
```

---

### Task 4: Drop `businessync` from the MIDI modules

**Files:**
- Modify: `build.sbt` (`scMidiModule`, `scMidiJavamidiModule`)
- Modify: `midi/sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEvent.scala`

**Interfaces:**
- Consumes: Task 2's `Businessync.publish(event: AnyRef)`, which `app`'s lambda and `RigManagerTest` call with a
  `MidiEvent`.
- Produces: `abstract sealed class MidiEvent`, with no supertype.

- [ ] **Step 1: Drop the dependencies**

Delete the `businessyncModule,` line from the `dependsOn` of `scMidiModule` and of `scMidiJavamidiModule`. Then run
`mcp__metals__import-build`, which reloads the build on the stack's sbt server too.

- [ ] **Step 2: See `sc-midi` fail to compile**

Run `mcp__metals__compile-module` with `module = "sc-midi"`. Expected: errors in `MidiEvent.scala` only, on the
`BusinessyncEvent` import and the `extends`. An error in another file is a use of `businessync` that Task 1 didn't
know: stop and report it.

- [ ] **Step 3: Make `MidiEvent` a root class**

Delete the import and the `extends BusinessyncEvent`: `abstract sealed class MidiEvent`. Its ScalaDoc doesn't mention
the bus and stays.

- [ ] **Step 4: Compile and run the classpath check (green)**

Run `mcp__metals__compile-full`: success, only Task 1's warnings. Then rerun Task 1, step 3's command.

Expected: `app composition format rig tuner ui`.

- [ ] **Step 5: Check the `@Subscribe` handlers**

```bash
rtk proxy grep -rn -A3 --include='*.scala' '@Subscribe' . | grep -F 'def ' | grep -v target
```

Expected: four handlers, whose parameters are `TuningIndexUpdatedEvent`, `TuningEvent`, `MidiEvent` and
`ScaleLossyConversionEvent`. A handler of `BusinessyncEvent`, `AnyRef` or `Object` would change which events it
receives: stop and report it.

- [ ] **Step 6: Run the tests of the modules involved, then the full suite**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "sc-midi-javamidi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "rig/testOnly org.calinburloiu.music.microtonalist.rig.RigManagerTest -- -oNCXEHLOPQRMWS"
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | awk '/Total number of tests run/ {n += $NF; m++} /FAILED|\[error\]/ {print} END {print m " modules, " n " tests"}'
```

Expected: 333 tests in 17 suites; 161 tests in 4 suites; `RigManagerTest` passes; `12 modules, 1476 tests` (Task 2
added `businessync`'s suite) and no other line.

- [ ] **Step 7: Commit**

```bash
git add build.sbt midi/sc-midi/src
git commit -m "[#366/#376] Drop sc-midi's and sc-midi-javamidi's businessync dependency" -m "<attribution lines>"
```

---

### Task 5: Docs

**Files:**
- Modify: `midi/sc-midi/docs/ARCHITECTURE.md` (*Responsibility*, *Device handling*)
- Modify: `midi/sc-midi-javamidi/docs/ARCHITECTURE.md` (`JavaMidiManager`'s key-type entry, *Subject to change*)
- Modify: `base/businessync/docs/ARCHITECTURE.md` (`BusinessyncEvent`'s key-type entry)
- Modify: `app/app/docs/ARCHITECTURE.md` (the `JavaMidiManager(businessync)` sentence, the *Dependencies* chains)
- Modify: `app/cli/docs/ARCHITECTURE.md` (*Responsibility*, `MicrotonalistToolApp`, *Dependencies*)
- Modify: `docs/architecture/module-overview.md` (the tree)

- [ ] **Step 1: Invoke the `architecture-docs` skill**

Every file of this task but the tree is an architecture document; follow the skill for them.

- [ ] **Step 2: Update the MIDI modules' docs**

- `sc-midi`: in *Responsibility*, the device traits' events no longer go "on the Businessync bus": a manager publishes
  them through a `MidiEventPublisher` that the composition root gives it, and `sc-midi` depends on no event bus.
  Drop the link to `businessync`'s doc. Under *Device handling*, add after `MidiEvent`: "**`MidiEventPublisher`** —
  where a manager publishes its `MidiEvent`s, synchronously on the thread that made the change; `app`'s forwards them
  to the Businessync bus."
- `sc-midi-javamidi`: `JavaMidiManager` "publishes its `MidiEvent`s through the `MidiEventPublisher` it is given",
  without the Businessync link. Delete the *Subject to change* bullet about `MidiEventPublisher` (#366).

- [ ] **Step 3: Update `businessync`'s and the composition roots' docs**

- `businessync`: `BusinessyncEvent` is the base class of the application's events (`TuningEvent`, `RigEvent`), not of
  every event posted; `publish` takes any object, so a library that doesn't know the bus, like `sc-midi`, publishes
  its own `MidiEvent`s, which `RigManager` subscribes to all the same.
- `app`: `app` constructs `JavaMidiManager` with a `MidiEventPublisher` that forwards its events to `businessync`. In
  the *Dependencies* chains, `sc-midi` no longer leads to `businessync`: `app → ui → tuner → sc-midi` and
  `tuner → businessync`, or the equivalent in the chains' style.
- `cli`: it depends on `sc-midi` and `sc-midi-javamidi` only, with no transitive `businessync`; `main` passes
  `JavaMidiManager` a publisher that drops the events. Delete the sentences about building a `Businessync` and
  pulling in `businessync` and Guava's `EventBus`.

- [ ] **Step 4: Update the module tree**

In `docs/architecture/module-overview.md`:

```
├── sc-midi-javamidi (Java Sound implementation of sc-midi, with CoreMIDI4J, depends on sc-midi)
├── sc-midi          (Scala-idiomatic MIDI API, no event bus)
```

- [ ] **Step 5: Check that nothing puts `sc-midi`'s events on the bus**

```bash
rtk proxy grep -rniE --exclude-dir=target --exclude-dir=target-bsp 'businessync|\bbus\b' midi
rtk proxy grep -rnF -e 'JavaMidiManager(businessync' -e 'sc-midi → businessync' -e 'sc-midi + businessync' \
  --include='*.md' . | grep -v -e '^./issues/' -e '^./docs/agent-evals/'
rtk proxy grep -rniF --exclude-dir=target --exclude-dir=target-bsp businessync app/cli
```

Expected: nothing from any of the three. Then skim the other Markdown hits of `businessync` outside `issues/` and
`docs/agent-evals/` (`rtk proxy grep -rnF -i businessync --include='*.md' .`) for a claim that this step made false;
`ui`'s "`businessync`, `common`, and `sc-midi` arrive transitively through" `tuner` stays true.

- [ ] **Step 6: Check the links and sizes**

```bash
python3 "$SCRATCH/check_md_links.py" . | tail -1
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py
```

Expected: `0 broken of N relative link(s) and import(s)`, with N at most 247 (the dropped Businessync links); 21 docs,
none over its size budget.

- [ ] **Step 7: Commit**

```bash
git add midi base app docs
git commit -m "[#366/#376] Document the MidiEventPublisher" -m "<attribution lines>"
```

---

### Task 6: Final checks and the PR

**Interfaces:**
- Consumes: Tasks 1–5.

- [ ] **Step 1: Coverage**

Invoke the `scoverage-inspector` skill and check `businessync`, `sc-midi`, `sc-midi-javamidi`, `app` and `cli`
against their floors (Global Constraints). Expected: all at or above them; `businessync` rises from 0, `sc-midi` and
`sc-midi-javamidi` stay above 80/80.

- [ ] **Step 2: Lint**

Run `sbtn fixLint` and repeat until it passes. If it changed files, rerun the full suite of Task 4, step 6 and commit
them as `[#366/#376] Apply the lint fixes`.

- [ ] **Step 3: Run `cli` against the real devices**

Rerun Task 1, step 5. Expected: the same devices as then.

- [ ] **Step 4: Open the PR, stacked on #373**

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 366/376 "Decouple sc-midi from businessync" "$(cat <<'EOF'
Step 6 of #366, stacked on #373 (step 2).

`JavaMidiManager` publishes its `MidiEvent`s through a `MidiEventPublisher` SAM trait of `sc-midi` instead of a
`Businessync`. `app` passes a lambda that calls `businessync.publish`, which now takes any event, and `cli` a no-op.
`MidiEvent` no longer extends `BusinessyncEvent`, so neither `sc-midi`, `sc-midi-javamidi` nor `cli` depends on
`businessync`.

## Test plan

- [x] `BusinessyncTest` pins that `publish` accepts any event; `JavaMidiManagerTest` runs over a stubbed
  `MidiEventPublisher`; `RigManagerTest` still receives `MidiEvent`s through a real `Businessync`.
- [x] Full suite and `sbtn lint` pass; coverage floors hold.
- [x] `cli midi-devices` lists the same devices.
- [ ] Manual: with the app running a rig on a device, unplug and replug the output device; the tuning is restored on
  it, which shows that `app` still forwards the device events to `RigManager`.
EOF
)"
gh pr edit --base refactoring/sc-midi-javamidi
gh pr view --json baseRefName,title,isDraft,url
```

Expected: `baseRefName` `refactoring/sc-midi-javamidi`, title
`[#366/#376] Decouple sc-midi from businessync`, `isDraft` true. The script opens the PR against `main`, so check
that the diff it shows after the edit has only this step's commits. Append the PR body's attribution lines from your
session's instructions before running it.

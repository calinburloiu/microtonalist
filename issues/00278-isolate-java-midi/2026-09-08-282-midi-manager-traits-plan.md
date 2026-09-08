# `MidiManager` and `MidiDeviceHandle` as Traits with a Java Sound Implementation — Implementation Plan (#282)

- **Date**: 2026-09-08
- **Issue**: [#282](https://github.com/calinburloiu/microtonalist/issues/282) — "Make MidiManager and MidiDeviceHandle
  traits with a JavaMidiManager implementation", sub-issue 4 of parent
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `25d841e` — "[#278/#281] Amend D6 with the lock-ordering caveat and the wiring-time behaviour",
  the top of `refactoring/281-scala-typed-pipeline` (#281; its PR was not yet open when this plan was written — find
  it with `gh pr list --head refactoring/281-scala-typed-pipeline`). That branch stacks on
  `refactoring/280-midi-transmitter-family` (PR #287, #280), `refactoring/279-drop-sc-prefix` (PR #286, #279) and
  `refactoring/isolate-java-midi` (PR #284, the design and the plans). The code described below is the code at that
  commit: the pipeline carries `MidiMsg` end to end, `MidiDeviceHandle` is the Java Sound boundary and exposes
  `receiver: MidiReceiver` / `transmitter: ConcurrentMidiTransmitter`, `tuner` imports nothing from
  `javax.sound.midi`, and `MidiManager` / `MidiDeviceHandle` are still concrete classes in `scmidi` that expose
  `MidiDevice.Info` and `MidiDevice`.
- **Spec**: [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md) — the approved design
  for the whole of #278. The scope of this plan is **decisions D8, D9 and D11**, **Section 8** (the defect to file,
  not fix) and the **#282 row of Section 3**; Section 4 (testing) and Section 5 (documentation) apply where they
  mention `MidiDeviceInfo`, `MidiDeviceId`, the device traits and the API/implementation split. No separate design
  document exists for #282. The places where this plan refines the design are called out in
  [Design notes](#design-notes); the D11 refinement (a `deviceOf` call instead of a `devices` list) was also applied
  to the design document in the same commit as this plan.
- **Branch stack (temporary workflow for #278)**: the sub-issues are developed as a stack of branches, not off `main`.
  Branch #282 off `refactoring/281-scala-typed-pipeline` and target its PR at that branch, unless the lower stack has
  already merged (Task 1, Step 0 checks). Never merge, rebase, retarget or force-push the open PRs of the stack or
  their branches; they are being reviewed and merged to `main` bottom-up in parallel.
- **The bug issue of Section 8**: it could not be created in the session that wrote this plan (the action was
  blocked), so Task 0 creates it — with the body given there verbatim — before any code is written. Its number is
  referred to as **`#<N>`** below: in the `// TODO` of Task 3 and in the documentation of Task 6. Write the number
  into this bullet once Task 0 has run.
- **Verification while writing this plan**: the code below was written against the sources at the base commit but was
  **not compiled**; expect to fix small compile slips (an import, a name) rather than design deviations. Verified by
  running or reading: (1) ScalaMock 7.5.5 already stubs `javax.sound.midi.MidiDevice` in `JavaMidiConvertersTest`
  with the `MockFactory` API (`(() => device.getMaxTransmitters).when().returns(…)`), so the converter tests below
  follow that style; `MidiDevice.Info`'s constructor is `protected`, so a four-line test subclass is needed;
  (2) CoreMIDI4J 1.6's `CoreMidiDeviceProvider` exposes `getMidiDeviceInfo(): Array[MidiDevice.Info]`,
  `addNotificationListener(CoreMidiNotification)` and `removeNotificationListener(CoreMidiNotification)`, and
  `CoreMidiNotification` is a single-method interface (`midiSystemUpdated()`), so a Scala lambda converts to it;
  (3) the coverage baseline at the base commit is `sc-midi` **69.16%** statements / **50.95%** branches (the branch
  figure is *already below* the 52% floor, a pre-existing failure the user knows about), `tuner` **84.85%** /
  **82.66%**, `cli` **0%** / **0%** (floors: 67/52, 80/80, 0/0).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `MidiManager` and `MidiDeviceHandle` into traits of the pure Scala API, with `MidiDeviceInfo` and
`MidiConnectionLimit` replacing `MidiDevice.Info` in every public signature, move today's implementation into
`javamidi` as `JavaMidiManager` / `JavaMidiDeviceHandle` behind a `JavaMidiEnvironment` seam, inject the manager into
`TunerModule` from `MicrotonalistApp`, and leave `javamidi` as the only package that imports `javax.sound.midi`.

**Architecture:** `MidiDeviceInfo(name, vendor, description, version, maxTransmitters, maxReceivers)` derives its
`id` and its `endpointType` from `MidiConnectionLimit` (`Unlimited` / `Limited(count)`);
`MidiDeviceId.correspondsToInfo` takes it. `trait MidiManager` keeps today's per-direction surface and `trait MidiDeviceHandle` keeps `id`, `info`,
the derived `isInputDevice` / `isOutputDevice` / `endpointType`, `state`, `isConnected`, `isOpen`, `open()`,
`close()`, `receiver`, `transmitter`, with `State` in its companion. `JavaMidiManager(businessync, environment)`
resolves each `MidiDevice` once per `refresh()` through `JavaMidiEnvironment.deviceOf`, builds its `MidiDeviceInfo`
with `JavaMidiConverters.asMidiDeviceInfo` and hands the resolved device to `JavaMidiDeviceHandle.onConnect`;
`CoreMidi4JEnvironment` is the production environment and the only file that calls the CoreMIDI4J / `MidiSystem`
statics. `TunerModule` receives a `MidiManager`; `MicrotonalistApp` and `MicrotonalistToolApp` instantiate
`JavaMidiManager`.

**Tech Stack:** Scala 3, sbt 1 (via `sbtn` on the BSP server), ScalaTest 3 (`AnyFlatSpec` + `Matchers`,
`TableDrivenPropertyChecks`), ScalaMock 7.5.5 (`MockFactory` API, matching the files touched), CoreMIDI4J 1.6
(inside `javamidi` only), Metals MCP for compilation, scoverage for coverage.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Modules**: production changes live in `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/` (API) and
  `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/` (implementation), plus one file each in `tuner`
  (`TunerModule.scala`), `app` (`MicrotonalistApp.scala`) and `cli` (`MicrotonalistToolApp.scala`); tests in the
  matching `src/test` trees. `ui`, `format`, `composition` and `common` do not use the types this plan changes
  (verified by search at the base commit: only `Track`, `TrackManager`, `TunerModule`, `TrackTest`, the app and the
  cli name `MidiManager` / `MidiDeviceHandle`, and `format` only builds `MidiDeviceId(name, vendor)`), so they are
  not touched. The only files outside those modules that change are the architecture documents of Task 6.
- **Do not touch**: `MidiMsg.scala`, `MidiCc.scala`, `MidiChannelStateTracker.scala` (they are #285's scope, D10);
  `MidiProcessor.scala`, `MidiSerialProcessor.scala`, `MidiSplitter.scala`, `Track.scala`, `TrackManager.scala`
  (finished by #281; `Track` and `TrackManager` compile unchanged against the trait because they already only name
  `MidiManager` / `MidiDeviceHandle` from `scmidi`).
- **Behaviour is preserved verbatim.** #278 is a refactoring. In particular the defect of Section 8 in
  `purgeDisconnectedDevices` is carried over unchanged with a `// TODO #<N>` (Task 3), and no test pins it.
- **Compile** with `mcp__metals__compile-module` (`module = "sc-midi"` / `"tuner"` / `"cli"` / `"app"`), or
  `mcp__metals__compile-full`; fall back to `sbtn "sc-midi/Test/compile"`, `sbtn "cli/Test/compile"`, and so on.
- **Run tests** with `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`, `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
  and `sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"`. A single class:
  `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiDeviceInfoTest -- -oNCXEHLOPQRMWS"`.
- **TDD** per `CLAUDE.md`: the new behaviour of this issue — `MidiConnectionLimit`, `MidiDeviceInfo`, the three
  converter builders, `MidiDeviceId.correspondsToInfo(MidiDeviceInfo)`, the trait's derived direction members, the
  `cli` printing through the trait — is written red → green: the test first, the thinnest `???` stub so the suite
  compiles, a run that fails on an assertion or a `NotImplementedError` (never a compile error), then the
  implementation. The rest of the issue is a **type migration and a move** (`MidiDevice.Info` → `MidiDeviceInfo`,
  class → trait + implementation, statics → seam) with no behaviour change: those steps are refactors, done
  green-to-green, and the suite run after each. Never mix a refactor with a behavioural change in one commit; commit
  only green code.
- **`JavaMidiManager` and `JavaMidiDeviceHandle` get no unit tests in this issue** (design, Section 4 and D11):
  they stay uncovered as today's classes are (#177); the seam is delegation only and needs no test. Do not budget for
  a `JavaMidiManager` suite; do not write a test that pins the Section 8 defect.
- **Scala conventions** (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines,
  Scala 3 `enum` for `MidiConnectionLimit`, no `new` in new or rewritten code (universal `apply` covers Java classes
  too: `ReentrantLock()`, `EventBus()`; the anonymous `Receiver` in the handle is the unavoidable exception; lines
  that this plan does not rewrite keep their existing `new`), no `return`, ScalaDoc on every public identifier,
  `_info` / `_device` / `_state` as backing fields, `// TODO #<issue>` only with an issue number. `Msg` is the
  suffix of message *types* only.
- **Test conventions** (`docs/development/test-conventions.md`): same package as the production class, class name
  suffixed `Test`, `behavior of` sections, `// Given` / `// When` / `// Then` comments, no `if` in tests, fixtures
  for repeated setup.
- **License headers**: `.scala` files are covered by the `addlicense` pre-commit hook — never write or edit a header
  by hand. `Read` skips the ~15-line header, so files appear to start at ~line 17 with real line numbers preserved.
  A file created with `git mv` keeps its header.
- **Coverage**: floors are `sc-midi` 67% statements / 52% branches, `tuner` 80% / 80%, `cli` 0% / 0% (`build.sbt`);
  never lower them. Baseline at the base commit: `sc-midi` 69.16 / 50.95, `tuner` 84.85 / 82.66, `cli` 0 / 0. New
  API files (`MidiConnectionLimit`, `MidiDeviceInfo`) target ~100%; `MidiDeviceId` gets its first tests;
  `JavaMidiManager`, `JavaMidiDeviceHandle` and `CoreMidi4JEnvironment` stay uncovered (#177). The `sc-midi` branch
  figure is already below its floor, so `coverageCheck` fails on this branch stack for a pre-existing reason — report
  it with the numbers, do not chase it.
- **Commits**: imperative subject prefixed `[#278/#282]`, e.g. `[#278/#282] Add MidiConnectionLimit and
  MidiDeviceInfo`. The sub-issue notation is mandatory (contributing skill, "Sub-issues").
- **Branch and PR**: work on `refactoring/282-midi-manager-traits`, branched off
  `refactoring/281-scala-typed-pipeline` (or off `main` if Step 0 of Task 1 finds the stack merged). Switch in place
  with `git switch`, no worktrees. Open the PR as a draft only when Task 6 is green, targeting the branch you branched
  from — the `contributing` skill's script has no base-branch option, so Task 6 runs its `--dry-run` and then the
  printed `gh` commands by hand with `--base`.
- **Reading this plan from the implementation branch**: the plan is committed on `refactoring/isolate-java-midi` (PR
  #284) *after* the upper branches of the stack were cut, so it is **not** on `refactoring/281-scala-typed-pipeline`
  and will not be on the #282 branch either. Read it from that branch without switching:
  `git show refactoring/isolate-java-midi:issues/00278-isolate-java-midi/2026-09-08-282-midi-manager-traits-plan.md`
  (use `origin/refactoring/isolate-java-midi` once it is pushed). Do not merge the plan branch into the upper branches
  to get the file, and do not copy it onto the #282 branch.

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `sc-midi/.../scmidi/MidiConnectionLimit.scala` | Create (Task 1) | `enum MidiConnectionLimit { case Unlimited; case Limited(count: Int) }` with `allowsConnections` and a `toString` that prints `unlimited` or the count. |
| `sc-midi/.../scmidi/MidiDeviceInfo.scala` | Create (Task 1) | `case class MidiDeviceInfo(name, vendor, description, version, maxTransmitters, maxReceivers)` with derived `id`, `isInputDevice`, `isOutputDevice`, `endpointType`. |
| `sc-midi/.../scmidi/javamidi/JavaMidiConverters.scala` | Modify (Tasks 2, 3) | Task 2 adds `connectionLimit(javaMaxConnections)`, `info.asMidiDeviceId`, `device.asMidiDeviceInfo`. Task 3 deletes the `MidiDevice` capability extensions `isInputDevice` / `isOutputDevice`. |
| `sc-midi/.../scmidi/MidiDeviceId.scala` | Modify (Task 3) | `correspondsToInfo(MidiDeviceInfo)`; the factory from `MidiDevice.Info` is gone; no Java import. |
| `sc-midi/.../scmidi/MidiDeviceHandle.scala` | Modify (Task 3), then rewrite (Task 4) | Task 3: still the concrete class, on `MidiDeviceInfo`, `onConnect(info, device)` takes the resolved device. Task 4: the API trait plus the `State` companion. |
| `sc-midi/.../scmidi/MidiManager.scala` | Modify (Task 3), then rewrite (Task 4) | Task 3: still concrete, resolves each device once in `refresh()`, endpoints keep `(MidiDeviceInfo, MidiDevice)` per id, `…DevicesInfo` return `MidiDeviceInfo`, the Section 8 `// TODO #<N>`. Task 4: the API trait. |
| `sc-midi/.../scmidi/javamidi/JavaMidiDeviceHandle.scala` | Create by `git mv` (Task 4) | Today's handle in `javamidi`, implementing the trait; `device: Option[MidiDevice]` on the concrete class only. |
| `sc-midi/.../scmidi/javamidi/JavaMidiManager.scala` | Create by `git mv` (Task 4), modify (Task 5) | Today's manager in `javamidi`, implementing the trait; Task 5 routes the four statics through `JavaMidiEnvironment`. |
| `sc-midi/.../scmidi/javamidi/JavaMidiEnvironment.scala` | Create (Task 5) | `trait JavaMidiEnvironment { deviceInfos; deviceOf(info); onEnvironmentChanged(listener) }` and `object CoreMidi4JEnvironment`, the only file calling `CoreMidiDeviceProvider` / `MidiSystem`. |
| `sc-midi/.../scmidi/MidiEvent.scala` | Modify (Task 4) | ScalaDoc no longer ties the events to a concrete manager or to CoreMIDI4J. |
| `tuner/.../tuner/TunerModule.scala` | Modify (Task 4) | Takes `midiManager: MidiManager`; no longer builds or closes it. |
| `app/.../MicrotonalistApp.scala` | Modify (Task 4) | Instantiates `JavaMidiManager`, passes it to `TunerModule`, closes it after the module in the shutdown hook. |
| `cli/.../cli/MicrotonalistToolApp.scala` | Modify (Tasks 3, 4) | Task 3: prints `MidiDeviceInfo` fields and limits, no `MidiSystem`. Task 4: `printMidiDevices(midiManager: MidiManager)`; `main` instantiates `JavaMidiManager`. |
| `sc-midi/src/test/.../scmidi/MidiConnectionLimitTest.scala` | Create (Task 1) | `allowsConnections` and `toString`. |
| `sc-midi/src/test/.../scmidi/MidiDeviceInfoTest.scala` | Create (Task 1) | Derived `id`; `endpointType` / `isInputDevice` / `isOutputDevice` over the limit combinations. |
| `sc-midi/src/test/.../scmidi/javamidi/JavaMidiConvertersTest.scala` | Modify (Tasks 2, 3) | Task 2: `connectionLimit`, `asMidiDeviceId`, `asMidiDeviceInfo`. Task 3: the `isInputDevice` / `isOutputDevice` sections are deleted with their extensions. |
| `sc-midi/src/test/.../scmidi/MidiDeviceIdTest.scala` | Create (Task 3) | `correspondsToInfo(MidiDeviceInfo)`, `sanitizedName`, `toString`. |
| `sc-midi/src/test/.../scmidi/MidiDeviceHandleTest.scala` | Create (Task 4) | The trait's derived `isInputDevice` / `isOutputDevice` / `endpointType` over a minimal handle. |
| `tuner/src/test/.../tuner/TrackTest.scala` | Modify (Task 4) | Passes `stub[MidiManager]` instead of `null`; the `TODO #282` goes. |
| `cli/src/test/.../cli/MicrotonalistToolAppTest.scala` | Create (Task 4) | `printMidiDevices` over a stubbed `MidiManager`, capturing the output. |
| `docs/architecture/sc-midi/README.md`, `docs/architecture/tuner/README.md`, `docs/architecture/module-overview.md` | Modify (Task 6) | The API/implementation split, the device traits and value types, the seam, the injection at the composition root. |

## Design notes

### Why the tasks run in this order

The device layer is coupled by one type: the moment `MidiDeviceHandle.info` returns `MidiDeviceInfo`, the manager's
endpoints, `…DevicesInfo`, `MidiDeviceId.correspondsToInfo` and the `cli` all change together. The plan therefore
first lands everything that has no caller, then makes that one coupled change in the concrete classes, then splits
the classes into trait + implementation, then adds the seam, so that every commit compiles and is green:

1. **Task 0** — file the Section 8 bug so that Task 3 can name it.
2. **Task 1** — `MidiConnectionLimit` and `MidiDeviceInfo`: new API value types, nothing uses them yet.
3. **Task 2** — the converter builders in `javamidi` that produce them from Java Sound: still nothing uses them.
4. **Task 3** — the coupled change: `MidiDeviceId`, the concrete handle and manager and the `cli` move to
   `MidiDeviceInfo`; the manager resolves each device once and hands it to the handle; the old `MidiDevice`
   capability extensions and the `MidiDevice.Info` factory are deleted. Reviews as "the device layer no longer
   exposes Java Sound values".
5. **Task 4** — the split: the API traits stay in `scmidi`, the classes move to `javamidi` with `git mv` (so that
   history and review follow the move), consumers pick the implementation at the composition root (D9). Reviews as
   "trait extraction and a move".
6. **Task 5** — D11: the four statics go behind `JavaMidiEnvironment`. Small and last, so it lands in the final file.
7. **Task 6** — final checks, documentation, PR.

### D11 refined: the environment resolves devices one at a time, so the manager keeps publishing failures

D11 drafts the seam as `devices: Seq[MidiDevice]`, "already resolved", and in the same breath requires that a device
which fails to resolve keeps producing today's `MidiDeviceFailedToConnectEvent`. The two cannot both hold: the
environment has no `Businessync`, so if it resolved internally it could only drop the device silently, and the event
stream would change. This plan therefore gives the seam two discovery calls that mirror the two statics one for one —
`deviceInfos: Seq[MidiDevice.Info]` (`CoreMidiDeviceProvider.getMidiDeviceInfo`) and
`deviceOf(info: MidiDevice.Info): MidiDevice` (`MidiSystem.getMidiDevice`, throwing as it does) — and keeps the
`try` / `catch` that today lives in `MidiDeviceHandle.onConnect` in `JavaMidiManager.refresh()`, which publishes the
event with the id derived from the `MidiDevice.Info`. Everything else D11 says stands: the manager resolves each
device once per refresh (D8 needs the device for the connection limits), hands it to the handle, and only one
production file calls the statics. A fake environment for the follow-up tests (#177) is a map from info to device.
The design document's D11 was amended to this shape in the same commit as this plan.

### Resolution moves from the handle to the manager

Today `refresh()` builds a throwaway `MidiDeviceHandle(info, businessync)` per Java info (whose constructor resolves
the device to learn its direction) and `openDevice` resolves it *again* through `onConnect(info)`. After Task 3 the
manager resolves once per refresh, keeps `(MidiDeviceInfo, MidiDevice)` per id in its endpoint, and
`onConnect(info: MidiDeviceInfo, device: MidiDevice)` can no longer fail. Two consequences, both accepted:

- The error log of a failed resolution no longer says `none device` (today's `endpointType` of a device-less handle):
  it says `Failed to connect to device "…"!`. The published event is the same.
- `openDevice` can no longer publish a `MidiDeviceFailedToConnectEvent` of its own, since it no longer resolves;
  today that could only happen if a device resolved at refresh time and failed to resolve a moment later.

Everything else in `onConnect` — the `require` on the id, `onDisconnect()` first, then the state transition, and
the `Open` state being left alone for an already open handle — is kept verbatim. That last point is the "related"
finding recorded in the Task 0 issue; it is not fixed here.

### The `MidiDevice.Info` factory becomes `asMidiDeviceId`

The issue says the factory `MidiDeviceId(info: MidiDevice.Info)` "moves to the Java side". Its only remaining use is
the failure path of `refresh()`: a device that does not resolve has no `MidiDeviceInfo`, so the event's id must come
from the Java info. It is therefore an extension in `JavaMidiConverters`, `info.asMidiDeviceId`, next to
`device.asMidiDeviceInfo`; both are named for what they produce rather than `asScala`, because a `MidiDevice.Info`
has two Scala counterparts.

### What is concrete in the `MidiDeviceHandle` trait

`isInputDevice`, `isOutputDevice` and `endpointType` derive from `info` (D8), so they are concrete in the trait and
`JavaMidiDeviceHandle` does not override them; `_info` and `_device` are assigned together, so `info.exists(…)` reads
the same as today's `_device.exists(…)`. `isConnected` and `isOpen` stay abstract: today they read through to the Java
device (`_device.isDefined`, `_device.exists(_.isOpen)`), which is exactly what lets them disagree with `state` in the
Section 8 defect, and this plan preserves that.

### `TunerModule` no longer closes the manager

An injected dependency is owned by whoever injects it. `TunerModule.close()` closes only the `TrackManager`;
`MicrotonalistApp`'s shutdown hook closes the `JavaMidiManager` right after `tunerModule.close()`, which is the same
order as today (`trackManager.close()` then `midiManager.close()`). The `cli` closes the manager it creates, as today.

### ScalaDoc may still name Java types; the acceptance check is on imports

`MidiReceiver`, `MidiTransmitter` and `MidiMsg` describe themselves as counterparts of `javax.sound.midi.Receiver`,
`Transmitter` and `MidiMessage` in ScalaDoc links. Those are documentation, not dependencies, and stay. The check that
"only `javamidi` imports `javax.sound.midi`" (issue text, D9) is therefore a grep on `import` lines, in Task 4.

### The `cli` gets a test through the trait

The `cli` has never had a test because `MidiManager` could not be instantiated without CoreMIDI4J. With the trait,
`printMidiDevices(midiManager)` is tested over a `stub[MidiManager]` by capturing `Console.out`; that is the one
`cli` production change beyond the type migration, and it pins the exact output the issue asks to keep.

---

## Task 0: File the Section 8 bug

**Files:** none in the repository.

**Interfaces:**
- Produces: the issue number `#<N>` used by Task 3 (`// TODO #<N>`) and Task 6 (documentation).

- [ ] **Step 1: Check that the bug is not already filed**

```bash
gh issue list --state open --search "MidiDeviceHandle unplugged orphan" --json number,title
gh issue list --state open --search "purgeDisconnectedDevices" --json number,title
```

Expected: no matching issue (when this plan was written, neither query matched; #131 "Allow hot plugging MIDI
devices" is the *feature* this bug blocks, not the bug). If one exists, use its number as `#<N>` and skip Step 2.

- [ ] **Step 2: Create the issue with the contributing skill's script**

Write the body to a scratch file first, then create the issue. Label `bugfix` (the branch prefix is `refactoring`, so
it must be explicit), milestone `sc-midi`, not `--wip` (it is follow-up work, not this session's).

````bash
cat > "$SCRATCHPAD/purge-bug.md" <<'EOF'
Found while designing decision D11 of #278 (see Section 8 of `issues/00278-isolate-java-midi/2026-09-07-isolate-java-midi-design.md` on PR #284). #282 carries the behaviour over to `JavaMidiManager` unchanged and leaves a `// TODO` at the ported call site; the fix belongs with the `JavaMidiManager` / `JavaMidiDeviceHandle` tests that D11's `JavaMidiEnvironment` seam makes possible (#177). Observed by reading the code, not verified at runtime.

## The defect

`MidiManager.MidiEndpoint.purgeDisconnectedDevices` handles an opened device that vanished from the environment like
this:

```scala
val device = openedDevicesMap.get(deviceId).device
device.foreach(_.close())
openedDevicesMap.remove(deviceId)
```

The `close()` is `javax.sound.midi.MidiDevice.close()`, not `MidiDeviceHandle.close()`, and `onDisconnect()` is never called on the handle. So when a device is unplugged while open:

- **The handle's accessors contradict each other.** Its `openRefCount` and `_state` are untouched: `state` keeps reporting `State.Open` while `isOpen`, which reads through to the Java device, reports `false`; `_device` / `_info` stay defined, so `isConnected` also stays `true`.
- **The handle is orphaned.** It is dropped from `openedDevicesMap`, so replugging the device makes `refresh()` build a *new* handle. The old one never reconnects, even though `WaitingToOpen` exists precisely so that a handle can survive a disconnect.
- **User-visible symptom.** `Track` retains the handle it got from `openInput` / `openOutput` for its whole lifetime, so unplugging and replugging a MIDI device mid-session silently kills that track until the application is restarted. This is the scenario #131 (hot plugging) wants to work.

There is a smaller race alongside it: `openedDevicesMap.get(deviceId)` re-reads the map after the `diff` that produced `deviceId`, so a concurrent `closeDevice` makes it return `null` and the `.device` call throws.

## Related, same neighbourhood

`MidiEndpoint.openDevice` calls `deviceHandle.onConnect(info)` on the handle it finds in `openedDevicesMap` *even when that handle is already open*. `onConnect` starts with `onDisconnect()`, which closes the Java device, and then leaves `_state` at `Open` because it only transitions from `Closed` or `WaitingToOpen`; the following `open()` just bumps the reference count. So opening the same device a second time (two tracks sharing an output, for instance) closes the Java device while the handle still reports `State.Open`. The fix and its tests overlap with the above, so it is recorded here rather than in a separate issue.

## Expected

- `purgeDisconnectedDevices` calls `onDisconnect()` on the handle and keeps it in the map (or in a "waiting" set) so that a replugged device reconnects to the same handle and its `Track` keeps working; `state`, `isOpen` and `isConnected` agree at all times.
- `openDevice` on an already connected handle does not close and re-resolve the device.
- The map read in the purge loop is race-free (iterate over the entries, or tolerate a `null`).
- Unit tests over a fake `JavaMidiEnvironment` pin all of the above.
EOF

.claude/skills/contributing/scripts/microtonalist-gh issue \
  "MidiManager orphans an open MidiDeviceHandle when its device is unplugged" \
  "$(cat "$SCRATCHPAD/purge-bug.md")" --label bugfix --milestone sc-midi
````

Record the printed issue number as `#<N>`; write it into the "The bug issue of Section 8" bullet at the top of this
plan (on `refactoring/isolate-java-midi`, in a `[#278/#282]` commit — not on the implementation branch). Do not
change anything else in the plan.

---

## Task 1: `MidiConnectionLimit` and `MidiDeviceInfo`

**Files:**
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiConnectionLimit.scala`
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceInfo.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiConnectionLimitTest.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceInfoTest.scala`

**Interfaces:**
- Consumes: `case class MidiDeviceId(name: String, vendor: String)`;
  `object MidiEndpointType { def apply(isInput: Boolean, isOutput: Boolean): MidiEndpointType }` (existing).
- Produces: `enum MidiConnectionLimit { case Unlimited; case Limited(count: Int); def allowsConnections: Boolean }`,
  printing as `unlimited` or the count;
  `case class MidiDeviceInfo(name: String, vendor: String, description: String, version: String,
  maxTransmitters: MidiConnectionLimit, maxReceivers: MidiConnectionLimit)` with `val id: MidiDeviceId`,
  `def isInputDevice: Boolean`, `def isOutputDevice: Boolean`, `def endpointType: MidiEndpointType`.

- [ ] **Step 0: Create the branch**

```bash
git switch refactoring/281-scala-typed-pipeline
git pull --ff-only
git log --oneline main..refactoring/281-scala-typed-pipeline | head -3
```

If the last command prints commits, the stack has not merged: `git switch -c refactoring/282-midi-manager-traits`
and target the PR at `refactoring/281-scala-typed-pipeline` in Task 6. If it prints nothing, the stack has merged:
`git switch main && git pull --ff-only && git switch -c refactoring/282-midi-manager-traits` and target `main`.

- [ ] **Step 1: Write the failing tests (red)**

`MidiConnectionLimitTest.scala`:

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiConnectionLimitTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  behavior of "allowsConnections"

  it should "be true for an unlimited or positive limit and false for zero" in {
    // Given
    val cases = Table[MidiConnectionLimit, Boolean](
      ("limit", "expected"),
      (MidiConnectionLimit.Unlimited, true),
      (MidiConnectionLimit.Limited(0), false),
      (MidiConnectionLimit.Limited(1), true),
      (MidiConnectionLimit.Limited(8), true)
    )

    forAll(cases) { (limit, expected) =>
      // When / Then
      limit.allowsConnections shouldBe expected
    }
  }

  behavior of "toString"

  it should "print unlimited or the count" in {
    // Given
    val cases = Table[MidiConnectionLimit, String](
      ("limit", "expected"),
      (MidiConnectionLimit.Unlimited, "unlimited"),
      (MidiConnectionLimit.Limited(0), "0"),
      (MidiConnectionLimit.Limited(8), "8")
    )

    forAll(cases) { (limit, expected) =>
      // When / Then
      limit.toString shouldEqual expected
    }
  }
}
```

`MidiDeviceInfoTest.scala`:

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiDeviceInfoTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private val info: MidiDeviceInfo = MidiDeviceInfo(
    name = "CoreMIDI4J - FP-90",
    vendor = "Roland",
    description = "Digital piano",
    version = "1.0",
    maxTransmitters = MidiConnectionLimit.Unlimited,
    maxReceivers = MidiConnectionLimit.Limited(1)
  )

  behavior of "id"

  it should "be derived from the name and vendor" in {
    // When / Then
    info.id shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  }

  behavior of "endpointType"

  it should "derive the directions from the connection limits" in {
    // Given
    val cases = Table[MidiConnectionLimit, MidiConnectionLimit, MidiEndpointType, Boolean, Boolean](
      ("maxTransmitters", "maxReceivers", "endpointType", "isInputDevice", "isOutputDevice"),
      (MidiConnectionLimit.Unlimited, MidiConnectionLimit.Limited(0), MidiEndpointType.Input, true, false),
      (MidiConnectionLimit.Limited(0), MidiConnectionLimit.Limited(1), MidiEndpointType.Output, false, true),
      (MidiConnectionLimit.Limited(2), MidiConnectionLimit.Unlimited, MidiEndpointType.InputOutput, true, true),
      (MidiConnectionLimit.Limited(0), MidiConnectionLimit.Limited(0), MidiEndpointType.None, false, false)
    )

    forAll(cases) { (maxTransmitters, maxReceivers, endpointType, isInputDevice, isOutputDevice) =>
      // When
      val deviceInfo = info.copy(maxTransmitters = maxTransmitters, maxReceivers = maxReceivers)

      // Then
      deviceInfo.endpointType shouldEqual endpointType
      deviceInfo.isInputDevice shouldBe isInputDevice
      deviceInfo.isOutputDevice shouldBe isOutputDevice
    }
  }
}
```

- [ ] **Step 2: Write the thinnest stubs so the tests compile**

`MidiConnectionLimit.scala` (no `toString` override yet, so that test fails on the assertion):

```scala
package org.calinburloiu.music.scmidi

enum MidiConnectionLimit {
  case Unlimited
  case Limited(count: Int)

  def allowsConnections: Boolean = ???
}
```

`MidiDeviceInfo.scala`:

```scala
package org.calinburloiu.music.scmidi

case class MidiDeviceInfo(name: String,
                          vendor: String,
                          description: String,
                          version: String,
                          maxTransmitters: MidiConnectionLimit,
                          maxReceivers: MidiConnectionLimit) {

  val id: MidiDeviceId = ???

  def isInputDevice: Boolean = ???

  def isOutputDevice: Boolean = ???

  def endpointType: MidiEndpointType = ???
}
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiConnectionLimitTest org.calinburloiu.music.scmidi.MidiDeviceInfoTest -- -oNCXEHLOPQRMWS"`

Expected: compiles; `allowsConnections` fails with `NotImplementedError`, `toString` fails with `"Unlimited" did not
equal "unlimited"`, both `MidiDeviceInfoTest` cases fail with `NotImplementedError` (the `val id` throws at
construction).

- [ ] **Step 4: Implement**

`MidiConnectionLimit.scala`:

```scala
package org.calinburloiu.music.scmidi

/**
 * How many transmitters or receivers a MIDI device can open at once, that is, how many consumers can subscribe to
 * the messages it sends or how many producers can send messages to it.
 *
 * Java Sound encodes "unlimited" as `-1`; the Java Sound implementation maps that to [[Unlimited]], so the API never
 * carries the sentinel. A [[Limited]] count is non-negative.
 */
enum MidiConnectionLimit {
  /** The device opens as many connections as requested. */
  case Unlimited

  /** The device opens at most `count` connections; `Limited(0)` means it cannot be used in that direction. */
  case Limited(count: Int)

  /**
   * @return whether at least one connection can be opened, that is, whether the device can be used in the
   *         direction this limit describes.
   */
  def allowsConnections: Boolean = this match {
    case Unlimited => true
    case Limited(count) => count > 0
  }

  /** Prints as `unlimited` or as the count, which is how the `cli` lists devices. */
  override def toString: String = this match {
    case Unlimited => "unlimited"
    case Limited(count) => count.toString
  }
}
```

`MidiDeviceInfo.scala`:

```scala
package org.calinburloiu.music.scmidi

/**
 * Information about a MIDI device, as reported by the platform.
 *
 * The [[id]] is derived from the name and vendor, and the directions the device can be used in derive from its
 * connection limits: a device that can open at least one transmitter is an input, one that can open at least one
 * receiver is an output. A physical device that works as both is listed by a [[MidiManager]] once per direction,
 * with the same [[id]].
 *
 * @param name            Name of the device.
 * @param vendor          Name of the company that supplies the device.
 * @param description     Description of the device.
 * @param version         Version of the device.
 * @param maxTransmitters How many transmitters the device can open, that is, how many consumers can subscribe to
 *                        the messages it sends.
 * @param maxReceivers    How many receivers the device can open, that is, how many producers can send messages to
 *                        it.
 */
case class MidiDeviceInfo(name: String,
                          vendor: String,
                          description: String,
                          version: String,
                          maxTransmitters: MidiConnectionLimit,
                          maxReceivers: MidiConnectionLimit) {

  /** Unique identifier of the device, derived from its name and vendor. */
  val id: MidiDeviceId = MidiDeviceId(name, vendor)

  /** Whether the device can be used as an input, that is, whether it can open at least one transmitter. */
  def isInputDevice: Boolean = maxTransmitters.allowsConnections

  /** Whether the device can be used as an output, that is, whether it can open at least one receiver. */
  def isOutputDevice: Boolean = maxReceivers.allowsConnections

  /** The directions in which the device can be used. */
  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiConnectionLimitTest org.calinburloiu.music.scmidi.MidiDeviceInfoTest -- -oNCXEHLOPQRMWS"`

Expected: PASS. Then `mcp__metals__compile-module` with `module = "sc-midi"`: no warnings.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiConnectionLimit.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceInfo.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiConnectionLimitTest.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceInfoTest.scala
git commit -m "[#278/#282] Add MidiConnectionLimit and MidiDeviceInfo to the sc-midi API"
```

---

## Task 2: The `MidiDeviceInfo` builders in `JavaMidiConverters`

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`

**Interfaces:**
- Consumes: `MidiConnectionLimit`, `MidiDeviceInfo`, `MidiDeviceId` (Task 1);
  `javax.sound.midi.MidiDevice` (`getDeviceInfo`, `getMaxTransmitters`, `getMaxReceivers`) and `MidiDevice.Info`
  (`getName`, `getVendor`, `getDescription`, `getVersion`).
- Produces, in `object JavaMidiConverters`: `def connectionLimit(javaMaxConnections: Int): MidiConnectionLimit`;
  `extension (info: MidiDevice.Info) def asMidiDeviceId: MidiDeviceId`;
  `extension (device: MidiDevice) def asMidiDeviceInfo: MidiDeviceInfo`.

- [ ] **Step 1: Write the failing tests (red)**

In `JavaMidiConvertersTest.scala`, change the first import line to
`import org.calinburloiu.music.scmidi.{MidiConnectionLimit, MidiDeviceId, MidiDeviceInfo, MidiNote}`, add this
helper class after `textBytes` (the `MidiDevice.Info` constructor is `protected`):

```scala
  /** `MidiDevice.Info` has a protected constructor; this is the four-line subclass tests need to build one. */
  private class TestDeviceInfo(name: String, vendor: String, description: String, version: String)
    extends MidiDevice.Info(name, vendor, description, version)
```

and add these sections right before `behavior of "JavaMidiConverters.isInputDevice"`:

```scala
  behavior of "JavaMidiConverters.connectionLimit"

  it should "map Java Sound's -1 to Unlimited and any other count to Limited" in {
    // Given
    val cases = Table[Int, MidiConnectionLimit](
      ("javaMaxConnections", "expected"),
      (-1, MidiConnectionLimit.Unlimited),
      (0, MidiConnectionLimit.Limited(0)),
      (1, MidiConnectionLimit.Limited(1)),
      (8, MidiConnectionLimit.Limited(8))
    )

    forAll(cases) { (javaMaxConnections, expected) =>
      // When / Then
      JavaMidiConverters.connectionLimit(javaMaxConnections) shouldEqual expected
    }
  }

  behavior of "JavaMidiConverters.asMidiDeviceId"

  it should "take the name and vendor of the Java Sound device info" in {
    // Given
    val javaInfo = TestDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0")

    // When / Then
    javaInfo.asMidiDeviceId shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  }

  behavior of "JavaMidiConverters.asMidiDeviceInfo"

  it should "copy the Java Sound device info fields and convert the connection limits" in {
    // Given
    val device = stub[MidiDevice]
    (() => device.getDeviceInfo).when().returns(TestDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0"))
    (() => device.getMaxTransmitters).when().returns(-1)
    (() => device.getMaxReceivers).when().returns(1)

    // When
    val info = device.asMidiDeviceInfo

    // Then
    info shouldEqual MidiDeviceInfo(
      name = "CoreMIDI4J - FP-90",
      vendor = "Roland",
      description = "Digital piano",
      version = "1.0",
      maxTransmitters = MidiConnectionLimit.Unlimited,
      maxReceivers = MidiConnectionLimit.Limited(1)
    )
    info.id shouldEqual MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  }
```

- [ ] **Step 2: Write the thinnest stubs so the tests compile**

In `JavaMidiConverters.scala`, change the import `import org.calinburloiu.music.scmidi.MidiNote` to
`import org.calinburloiu.music.scmidi.{MidiConnectionLimit, MidiDeviceId, MidiDeviceInfo, MidiNote}` and add, right
after the existing `extension (device: MidiDevice) { … }` block:

```scala
  def connectionLimit(javaMaxConnections: Int): MidiConnectionLimit = ???

  extension (info: MidiDevice.Info) {
    def asMidiDeviceId: MidiDeviceId = ???
  }

  extension (device: MidiDevice) {
    def asMidiDeviceInfo: MidiDeviceInfo = ???
  }
```

- [ ] **Step 3: Run the tests to verify they fail for the right reason**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`

Expected: compiles; the three new cases fail with `NotImplementedError`; every other case still passes.

- [ ] **Step 4: Implement**

Replace the three stubs with:

```scala
  /**
   * Converts a Java Sound connection count into a [[MidiConnectionLimit]]: `-1`, Java Sound's encoding of
   * "unlimited", becomes [[MidiConnectionLimit.Unlimited]]; any other count becomes [[MidiConnectionLimit.Limited]].
   *
   * @param javaMaxConnections the value of `MidiDevice.getMaxTransmitters` or `MidiDevice.getMaxReceivers`.
   */
  def connectionLimit(javaMaxConnections: Int): MidiConnectionLimit = {
    if (javaMaxConnections == -1) MidiConnectionLimit.Unlimited else MidiConnectionLimit.Limited(javaMaxConnections)
  }

  extension (info: MidiDevice.Info) {
    /** The [[MidiDeviceId]] of the device this Java Sound info describes: its name and vendor. */
    def asMidiDeviceId: MidiDeviceId = MidiDeviceId(info.getName, info.getVendor)
  }

  extension (device: MidiDevice) {
    /**
     * Builds the [[MidiDeviceInfo]] of this Java Sound device. It needs the device rather than its
     * `MidiDevice.Info` alone, because the connection limits come from `getMaxTransmitters` / `getMaxReceivers`.
     */
    def asMidiDeviceInfo: MidiDeviceInfo = {
      val info = device.getDeviceInfo
      MidiDeviceInfo(
        name = info.getName,
        vendor = info.getVendor,
        description = info.getDescription,
        version = info.getVersion,
        maxTransmitters = connectionLimit(device.getMaxTransmitters),
        maxReceivers = connectionLimit(device.getMaxReceivers)
      )
    }
  }
```

Also extend the object's ScalaDoc: replace the line "It also hosts the [[javax.sound.midi.MidiDevice]] capability
extensions `isInputDevice` / `isOutputDevice`." with "It also builds the device-level API values from Java Sound:
`device.asMidiDeviceInfo`, `info.asMidiDeviceId` and [[connectionLimit]]; and, until #282 removes them, hosts the
[[javax.sound.midi.MidiDevice]] capability extensions `isInputDevice` / `isOutputDevice`." (Task 3 shortens it
again.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala
git commit -m "[#278/#282] Build MidiDeviceInfo and MidiDeviceId from Java Sound in JavaMidiConverters"
```

---

## Task 3: The device layer on `MidiDeviceInfo`; the manager resolves each device once

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceId.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala`
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala` (delete the
  two `MidiDevice` capability extensions)
- Modify: `cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala`
- Test (create): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceIdTest.scala`
- Test (modify): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`
  (delete the `isInputDevice` / `isOutputDevice` sections)

**Interfaces:**
- Consumes: Tasks 1 and 2; the issue number `#<N>` of Task 0.
- Produces: `MidiDeviceId.correspondsToInfo(midiDeviceInfo: MidiDeviceInfo): Boolean`;
  `MidiDeviceHandle.info: Option[MidiDeviceInfo]`,
  `private[scmidi] def onConnect(info: MidiDeviceInfo, device: MidiDevice): Unit`;
  `MidiManager.inputDeviceInfoOf / outputDeviceInfoOf: Option[MidiDeviceInfo]`,
  `inputDevicesInfo / outputDevicesInfo: Seq[MidiDeviceInfo]`. Everything else on the two classes keeps its name and
  type; the `MidiDevice.Info` factory `MidiDeviceId(info)` and the `MidiDevice` extensions `isInputDevice` /
  `isOutputDevice` are gone.

- [ ] **Step 1: Write the failing test (red) — `MidiDeviceIdTest.scala`**

```scala
package org.calinburloiu.music.scmidi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MidiDeviceIdTest extends AnyFlatSpec with Matchers {

  private val info: MidiDeviceInfo = MidiDeviceInfo(
    name = "CoreMIDI4J - FP-90",
    vendor = "Roland",
    description = "Digital piano",
    version = "1.0",
    maxTransmitters = MidiConnectionLimit.Limited(0),
    maxReceivers = MidiConnectionLimit.Limited(1)
  )

  behavior of "correspondsToInfo"

  it should "be true for the info the id was derived from" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-90", "Roland").correspondsToInfo(info) shouldBe true
  }

  it should "be false for an info with another name or another vendor" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-30", "Roland").correspondsToInfo(info) shouldBe false
    MidiDeviceId("CoreMIDI4J - FP-90", "Yamaha").correspondsToInfo(info) shouldBe false
  }

  behavior of "sanitizedName"

  it should "strip the CoreMIDI4J prefix" in {
    // When / Then
    MidiDeviceId("CoreMIDI4J - FP-90", "Roland").sanitizedName shouldEqual "FP-90"
  }

  it should "leave a name without the prefix untouched" in {
    // When / Then
    MidiDeviceId("FP-90", "Roland").sanitizedName shouldEqual "FP-90"
  }

  behavior of "toString"

  it should "quote the name and append the vendor in parentheses" in {
    // When / Then
    MidiDeviceId("FP-90", "Roland").toString shouldEqual "\"FP-90\" (Roland)"
  }

  it should "omit the parentheses for a blank vendor" in {
    // When / Then
    MidiDeviceId("IAC 1", " ").toString shouldEqual "\"IAC 1\""
  }
}
```

- [ ] **Step 2: Add the overload as a stub so the test compiles**

In `MidiDeviceId.scala`, keep the existing `correspondsToInfo(midiDeviceInfo: MidiDevice.Info)` for the moment (the
handle still calls it) and add next to it:

```scala
  def correspondsToInfo(midiDeviceInfo: MidiDeviceInfo): Boolean = ???
```

- [ ] **Step 3: Run the test to verify it fails for the right reason**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiDeviceIdTest -- -oNCXEHLOPQRMWS"`

Expected: compiles; the two `correspondsToInfo` cases fail with `NotImplementedError`; the `sanitizedName` and
`toString` cases pass (they pin existing behaviour that had no test).

- [ ] **Step 4: Implement `correspondsToInfo(MidiDeviceInfo)` (green)**

```scala
  /**
   * Checks whether this identifier is the one derived from the given device information.
   *
   * @param midiDeviceInfo The MIDI device information to compare against.
   * @return True if this identifier matches the given device information, false otherwise.
   */
  def correspondsToInfo(midiDeviceInfo: MidiDeviceInfo): Boolean = this == midiDeviceInfo.id
```

Run the class again: PASS. Commit this half on its own, since the rest of the task is a refactor:

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceId.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceIdTest.scala
git commit -m "[#278/#282] Add MidiDeviceId.correspondsToInfo(MidiDeviceInfo) and the first MidiDeviceId tests"
```

- [ ] **Step 5: Migrate `MidiDeviceHandle` to `MidiDeviceInfo` and a resolved device (refactor)**

In `MidiDeviceHandle.scala`:

1. Replace `import javax.sound.midi.*` with `import javax.sound.midi.{MidiDevice, MidiMessage, Receiver}`.
2. In the class ScalaDoc, replace the sentence "Only when the device is connected the [[MidiDevice]], via [[device]]
   accessor, and the [[MidiDevice.Info]], via [[info]] accessor, become defined on the instance." with "Only while
   the device is connected are the [[MidiDevice]], via the [[device]] accessor, and the [[MidiDeviceInfo]], via the
   [[info]] accessor, defined on the instance; [[MidiManager]] resolves the device and hands it to [[onConnect]]."
3. Replace `@volatile private var _info: Option[MidiDevice.Info] = None` with
   `@volatile private var _info: Option[MidiDeviceInfo] = None`.
4. **Delete** the convenience constructor `private[scmidi] def this(info: MidiDevice.Info, businessync: Businessync)`
   together with its ScalaDoc (the manager no longer builds throwaway handles).
5. Replace the `info` accessor with:

```scala
  /**
   * Retrieves the information about the MIDI device.
   *
   * @return The MIDI device information while the device is connected; otherwise, None.
   */
  def info: Option[MidiDeviceInfo] = _info
```

6. Replace the bodies of `isInputDevice` and `isOutputDevice` (ScalaDocs unchanged):

```scala
  def isInputDevice: Boolean = _info.exists(_.isInputDevice)
```

```scala
  def isOutputDevice: Boolean = _info.exists(_.isOutputDevice)
```

7. Replace the whole `onConnect` method (ScalaDoc included) with:

```scala
  /**
   * Informs the instance that the device got connected to the system.
   *
   * @param info   Information about the connected MIDI device.
   * @param device The resolved Java Sound device, which [[MidiManager]] obtains once per environment scan.
   */
  private[scmidi] def onConnect(info: MidiDeviceInfo, device: MidiDevice): Unit = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"MidiDeviceHandle $id!")

    onDisconnect()

    _device = Some(device)
    _info = Some(info)

    if (_state == State.Closed) {
      _state = State.Connected
    } else if (_state == State.WaitingToOpen) {
      doOpen()
    }
  }
```

8. In `doOpen()`, replace `if (dev.isInputDevice) {` with `if (isInputDevice) {` (the handle's own accessor, from
   `_info`, which `onConnect` sets before it calls `doOpen()` and which is set whenever the state is `Connected`).

The `JavaMidiConverters.*` import stays: `asJava` / `asScala` are still used by the two boundary receivers.

- [ ] **Step 6: Rewrite `MidiManager` so that it resolves each device once (refactor)**

Replace the whole content of `MidiManager.scala` below the license header with the following. The public surface is
the same as today except that `…DeviceInfoOf` and `…DevicesInfo` return `MidiDeviceInfo`; the `MidiEndpoint` keeps
`(MidiDeviceInfo, MidiDevice)` per id instead of `MidiDevice.Info`; `purgeDisconnectedDevices` is carried over
verbatim under the Section 8 `// TODO` (replace `<N>` with the Task 0 number).

```scala
package org.calinburloiu.music.scmidi

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.{MidiDevice, MidiSystem, MidiUnavailableException}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Class that manages connections and gives information about MIDI devices.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[MidiDeviceHandle]]) instances for the same physical devices, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each [[refresh]] resolves every device once, builds its [[MidiDeviceInfo]] and keeps the resolved device so that
 * it can be handed to the [[MidiDeviceHandle]] when the device is opened.
 */
class MidiManager(businessync: Businessync) extends AutoCloseable with StrictLogging {

  import MidiManager.*

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Input, businessync)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Output, businessync)

  private val onMidiNotification: CoreMidiNotification = () => {
    logger.info("The MIDI environment has changed.")
    businessync.publish(MidiEnvironmentChangedEvent)
    refresh()
  }

  init()

  private def init(): Unit = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    CoreMidiDeviceProvider.addNotificationListener(onMidiNotification)
  }

  /**
   * Rescans the environment for MIDI device information and updates the class internal state.
   */
  def refresh(): Unit = {
    // Alternative to `javax.sound.midi.MidiSystem.getMidiDeviceInfo()` to make Java MIDI work on Mac.
    // This should also work on Windows.
    val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo

    val currentInputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    val currentOutputDevices: mutable.Buffer[ConnectedDevice] = mutable.Buffer()
    for (javaInfo <- deviceInfoArray; device <- resolveDevice(javaInfo)) {
      val connectedDevice = ConnectedDevice(device.asMidiDeviceInfo, device)

      if (connectedDevice.info.isInputDevice) {
        currentInputDevices += connectedDevice
      }
      if (connectedDevice.info.isOutputDevice) {
        currentOutputDevices += connectedDevice
      }
    }

    inputEndpoint.updateDevices(currentInputDevices)
    outputEndpoint.updateDevices(currentOutputDevices)

    inputEndpoint.purgeDisconnectedDevices()
    outputEndpoint.purgeDisconnectedDevices()
  }

  /**
   * Resolves the Java Sound device described by `javaInfo`, or `None` if it cannot be: a `MidiUnavailableException`
   * or an `IllegalArgumentException` drops the device silently; any other exception is logged and published as a
   * [[MidiDeviceFailedToConnectEvent]] before the device is dropped.
   */
  private def resolveDevice(javaInfo: MidiDevice.Info): Option[MidiDevice] = {
    try {
      Some(MidiSystem.getMidiDevice(javaInfo))
    } catch {
      case _: MidiUnavailableException => None
      case _: IllegalArgumentException => None
      case exception: Exception =>
        val id = javaInfo.asMidiDeviceId
        logger.error(s"Failed to connect to device $id!", exception)
        businessync.publish(MidiDeviceFailedToConnectEvent(id, exception))
        None
    }
  }

  override def close(): Unit = {
    logger.info(s"Closing MIDI connections...")
    inputEndpoint.close()
    outputEndpoint.close()
    logger.info(s"Finished closing MIDI connections.")

    CoreMidiDeviceProvider.removeNotificationListener(onMidiNotification)
  }

  def isInputAvailable(deviceId: MidiDeviceId): Boolean = inputEndpoint.isDeviceAvailable(deviceId)

  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = inputEndpoint.deviceInfoOf(deviceId)

  def inputDeviceIds: Seq[MidiDeviceId] = inputEndpoint.deviceIds

  def inputDevicesInfo: Seq[MidiDeviceInfo] = inputEndpoint.devicesInfo

  /**
   * Opens an input connection to a MIDI device based on its unique identifiers.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = inputEndpoint.openDevice(deviceId)

  /**
   * Tries to sequentially open a connection with the first input device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    inputEndpoint.openFirstAvailableDevice(deviceIds)

  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = inputEndpoint.deviceHandleOf(deviceId)

  def inputOpenedDevices: Seq[MidiDeviceHandle] = inputEndpoint.openedDevices

  def closeInput(deviceId: MidiDeviceId): Unit = inputEndpoint.closeDevice(deviceId)


  def isOutputAvailable(deviceId: MidiDeviceId): Boolean = outputEndpoint.isDeviceAvailable(deviceId)

  def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = outputEndpoint.deviceInfoOf(deviceId)

  def outputDeviceIds: Seq[MidiDeviceId] = outputEndpoint.deviceIds

  def outputDevicesInfo: Seq[MidiDeviceInfo] = outputEndpoint.devicesInfo

  /**
   * Opens an output connection to a MIDI device based on its unique identifiers.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = outputEndpoint.openDevice(deviceId)

  /**
   * Tries to sequentially open a connection with the first output device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] =
    outputEndpoint.openFirstAvailableDevice(deviceIds)

  def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = outputEndpoint.deviceHandleOf(deviceId)

  def outputOpenedDevices: Seq[MidiDeviceHandle] = outputEndpoint.openedDevices

  def closeOutput(deviceId: MidiDeviceId): Unit = outputEndpoint.closeDevice(deviceId)
}

object MidiManager {

  /** A device present in the environment: its API-level information and the resolved Java Sound device. */
  private case class ConnectedDevice(info: MidiDeviceInfo, device: MidiDevice) {
    def id: MidiDeviceId = info.id
  }

  /**
   * Helper class that manages either input or output MIDI devices. The reason for that is that the Java MIDI API
   * lists input and output devices separately, so the same physical device may appear twice but with the same
   * [[MidiDeviceId]].
   *
   * @param endpointType whether the devices managed are input or output devices.
   */
  private class MidiEndpoint(val endpointType: MidiEndpointType,
                             businessync: Businessync) extends AutoCloseable with StrictLogging {

    private val connectedDevices = ConcurrentHashMap[MidiDeviceId, ConnectedDevice]()
    private val openedDevicesMap = ConcurrentHashMap[MidiDeviceId, MidiDeviceHandle]()

    def updateDevices(devices: Iterable[ConnectedDevice]): Unit = {
      // New devices
      for (connectedDevice <- devices) {
        val id = connectedDevice.id
        var wasConnected = false
        connectedDevices.computeIfAbsent(id, _ => {
          wasConnected = true
          connectedDevice
        })

        if (wasConnected) {
          logDebugConnectedDevice(connectedDevice.info)
          businessync.publish(MidiDeviceConnectedEvent(id))
        }
      }

      // Removed devices
      val currentIds = devices.map(_.id).toSet

      for (previousId <- connectedDevices.keys.asScala if !currentIds.contains(previousId)) {
        connectedDevices.remove(previousId)
        logger.info(s"${endpointType.toString.capitalize} device $previousId was disconnected.")
        businessync.publish(MidiDeviceDisconnectedEvent(previousId))
      }
    }

    /** Remove devices that were previously opened, but now were disconnected. */
    def purgeDisconnectedDevices(): Unit = {
      val disconnectedDeviceIds = openedDevicesMap.keySet.asScala diff connectedDevices.keySet.asScala

      disconnectedDeviceIds.foreach { deviceId =>
        // TODO #<N> This closes the Java device behind the handle's back and drops the handle: its state contradicts
        //   isOpen and a replugged device never reconnects to it; the map re-read below also races with closeDevice.
        val device = openedDevicesMap.get(deviceId).device

        device.foreach(_.close())

        openedDevicesMap.remove(deviceId)

        logger.info(s"${endpointType.toString.capitalize} device $deviceId was closed.")
        businessync.publish(MidiDeviceClosedEvent(deviceId))
      }
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = connectedDevices.containsKey(deviceId)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] =
      Option(connectedDevices.get(deviceId)).map(_.info)

    def deviceIds: Seq[MidiDeviceId] = connectedDevices.keys.asScala.toSeq

    def devicesInfo: Seq[MidiDeviceInfo] = connectedDevices.values.asScala.map(_.info).toSeq

    def openDevice(deviceId: MidiDeviceId): MidiDeviceHandle = {
      val deviceHandle = openedDevicesMap.computeIfAbsent(deviceId, _ => MidiDeviceHandle(deviceId, businessync))

      Option(connectedDevices.get(deviceId)) match {
        case Some(connectedDevice) =>
          deviceHandle.onConnect(connectedDevice.info, connectedDevice.device)
          deviceHandle.open()
          logger.info(s"Successfully opened $endpointType device $deviceId.")
          businessync.publish(MidiDeviceOpenedEvent(deviceId))
        case None => logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not connected.")
      }

      deviceHandle
    }

    def openFirstAvailableDevice(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle] = {
      deviceIds.to(LazyList)
        .map { deviceId =>
          logger.info(s"Attempting to open $endpointType device $deviceId...")
          openDevice(deviceId)
        }
        .find(_.isOpen)
    }

    def deviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = Option(openedDevicesMap.get(deviceId))

    def openedDevices: Seq[MidiDeviceHandle] = openedDevicesMap.values.asScala.toSeq

    def closeDevice(deviceId: MidiDeviceId): Unit = {
      deviceHandleOf(deviceId) match {
        case Some(openedDevice) if openedDevice.isOpen =>
          logger.info(s"Closing $endpointType device $deviceId...")
          openedDevice.close()
          openedDevicesMap.remove(deviceId)
          logger.info(s"Successfully $endpointType closed device $deviceId.")
        case _ => // Do nothing
      }
    }

    override def close(): Unit = {
      openedDevicesMap.keys.asScala.foreach { deviceId =>
        closeDevice(deviceId)
      }
    }

    @inline
    private def logDebugConnectedDevice(info: MidiDeviceInfo): Unit = {
      logger.whenDebugEnabled {
        val (handlerType, maxHandlers) = if (endpointType == MidiEndpointType.Input) {
          ("transmitters", info.maxTransmitters)
        } else {
          ("receivers", info.maxReceivers)
        }

        logger.debug(s"${endpointType.toString.capitalize} device ${info.id} with $maxHandlers $handlerType was " +
          s"connected.")
      }
    }
  }
}
```

(`$maxHandlers` prints `unlimited` or the count through `MidiConnectionLimit.toString`, which is the same text the
old `-1` check produced.)

- [ ] **Step 7: Finish `MidiDeviceId` and delete the `MidiDevice` extensions (refactor)**

In `MidiDeviceId.scala`: delete `import javax.sound.midi.MidiDevice`, delete the `correspondsToInfo(midiDeviceInfo:
MidiDevice.Info)` overload and its ScalaDoc, delete `def apply(midiDeviceInfo: MidiDevice.Info): MidiDeviceId` from
the companion (only the private prefix constant remains there), and replace the class ScalaDoc's second paragraph
with:

```scala
 * Note that a physical device that works as both input and output has a single [[MidiDeviceId]] but is listed by a
 * [[MidiManager]] once per direction, with a [[MidiDeviceInfo]] and a [[MidiDeviceHandle]] for each.
```

In `JavaMidiConverters.scala`: delete the `extension (device: MidiDevice) { def isInputDevice … def isOutputDevice …
}` block (the one with the two capability helpers, *not* the `asMidiDeviceInfo` one) and shorten the object ScalaDoc
sentence added in Task 2 to "It also builds the device-level API values from Java Sound: `device.asMidiDeviceInfo`,
`info.asMidiDeviceId` and [[connectionLimit]]."

In `JavaMidiConvertersTest.scala`: delete the two sections `behavior of "JavaMidiConverters.isInputDevice"` and
`behavior of "JavaMidiConverters.isOutputDevice"` with their cases.

- [ ] **Step 8: Migrate the `cli` to `MidiDeviceInfo` (refactor)**

Replace the content of `MicrotonalistToolApp.scala` below the license header with:

```scala
package org.calinburloiu.music.microtonalist.cli

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.{MidiDeviceInfo, MidiManager}

object MicrotonalistToolApp {

  def main(args: Array[String]): Unit = {
    args match {
      case Array("midi-devices") => printMidiDevices()
      case _ => println(
        """Usage:
          |midi-devices    prints all available MIDI devices
          |""".stripMargin
      )
    }
  }

  private def printMidiDevices(): Unit = {
    val businessync = Businessync(EventBus())
    val midiManager = MidiManager(businessync)

    // Endpoint is a term for input or output
    def printMidiDevicesByEndpoint(devicesInfo: Seq[MidiDeviceInfo], printLimit: MidiDeviceInfo => Unit): Unit = {
      devicesInfo.foreach { info =>
        println(
          s"""Name: ${info.name}
             |Vendor: ${info.vendor}
             |Version: ${info.version}
             |Description: ${info.description}""".stripMargin
        )
        printLimit(info)
        println()
      }
    }

    println("=== Input Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.inputDevicesInfo,
      info => println(s"Max. Transmitters: ${info.maxTransmitters}"))

    println("\n=== Output Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.outputDevicesInfo,
      info => println(s"Max. Receivers: ${info.maxReceivers}"))

    midiManager.close()
  }
}
```

The printed text is byte-for-byte what the old `MidiSystem` / `fromHandlerCountToString` code printed.

- [ ] **Step 9: Compile and run the suites**

`mcp__metals__compile-full` (the `cli` and `tuner` must compile: `Track` only names the types). Then:

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green, no warnings. Verify the deletions took:

```bash
grep -rn 'MidiDevice\.Info\|isInputDevice\|isOutputDevice' sc-midi/src cli/src
```

Expected: `MidiDevice.Info` appears only in `JavaMidiConverters.scala` (the `asMidiDeviceId` extension and the
`asMidiDeviceInfo` body), `JavaMidiConvertersTest.scala` (`TestDeviceInfo`) and `MidiManager.scala`
(`resolveDevice`); `isInputDevice` / `isOutputDevice` appear only on `MidiDeviceInfo`, `MidiDeviceHandle` and their
tests.

- [ ] **Step 10: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceId.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala \
        sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala \
        cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala
git commit -m "[#278/#282] Expose MidiDeviceInfo from the device layer and resolve each device once in the manager"
```

---

## Task 4: The traits, `JavaMidiManager` / `JavaMidiDeviceHandle`, and the composition roots

**Files:**
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala` (the trait)
- Rewrite: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala` (the trait + `State`)
- Create by `git mv`: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala`
- Create by `git mv`: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEvent.scala` (ScalaDoc)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerModule.scala`
- Modify: `app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala`
- Modify: `cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala`
- Test (create): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceHandleTest.scala`
- Test (modify): `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala`
- Test (create): `cli/src/test/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolAppTest.scala`

**Interfaces:**
- Consumes: Task 3's classes.
- Produces: `trait MidiManager extends AutoCloseable` (surface below); `trait MidiDeviceHandle extends AutoCloseable`
  with `id`, `info`, `isInputDevice`, `isOutputDevice`, `endpointType`, `state`, `isConnected`, `isOpen`, `open()`,
  `close()`, `receiver`, `transmitter` and `object MidiDeviceHandle { enum State }`;
  `class JavaMidiManager(businessync: Businessync) extends MidiManager`;
  `class JavaMidiDeviceHandle private[javamidi](id, businessync) extends MidiDeviceHandle` with
  `def device: Option[MidiDevice]`, `private[javamidi] def onConnect(info: MidiDeviceInfo, device: MidiDevice)`,
  `private[javamidi] def onDisconnect()`;
  `class TunerModule(businessync: Businessync, trackRepo: TrackRepo, midiManager: MidiManager)`;
  `private[cli] def MicrotonalistToolApp.printMidiDevices(midiManager: MidiManager): Unit`.

- [ ] **Step 1: Move the two classes into `javamidi` with `git mv`**

```bash
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala
```

- [ ] **Step 2: Write the `MidiDeviceHandle` trait (with the derived members abstract for now)**

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala` (a new file: the hook adds
the license header on commit) with the trait below. In this step, `isInputDevice`, `isOutputDevice` and
`endpointType` are declared **abstract**; Step 5 makes them concrete after a red test.

```scala
package org.calinburloiu.music.scmidi

/**
 * Handle to a single MIDI device, identified by a [[MidiDeviceId]].
 *
 * A [[MidiManager]] creates the handles and keeps them up to date behind the scenes. A handle can exist for a device
 * that is not connected to the system: [[info]] is defined only while the device is connected, and the manager
 * informs the handle when the physical device gets connected or disconnected.
 *
 * A device can only be used after it is opened via [[open]]; when it is no longer needed, [[close]] must be called.
 * The operation is reference-counted, and a device may be requested to be opened before it is connected: once it
 * becomes connected, it is also opened.
 *
 * A handle exposes a [[MidiReceiver]] and a [[ConcurrentMidiTransmitter]] via [[receiver]] and [[transmitter]]. They
 * can be wired while the device is disconnected or closed, in which case they do nothing; once the device becomes
 * usable, the wiring works without any change.
 *
 * [[state]] tells the current state of the handle and of its device; see [[MidiDeviceHandle.State]] for the
 * transitions. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandle]] is the Java Sound implementation.
 */
trait MidiDeviceHandle extends AutoCloseable {

  /** Unique identifier of the MIDI device. */
  def id: MidiDeviceId

  /**
   * Retrieves the information about the MIDI device.
   *
   * @return The MIDI device information while the device is connected; otherwise, None.
   */
  def info: Option[MidiDeviceInfo]

  /**
   * Determines if the associated MIDI device is an input device. If it is, then its [[transmitter]] can be used to
   * subscribe to the messages the device sends, otherwise that will do nothing.
   *
   * @return True if the MIDI device supports input, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isInputDevice: Boolean

  /**
   * Determines if the associated MIDI device is an output device. If it is, then its [[receiver]] can be used to send
   * messages to the device, otherwise that will do nothing.
   *
   * @return True if the MIDI device supports output, false otherwise — including while it is disconnected, when its
   *         capabilities are not known.
   */
  def isOutputDevice: Boolean

  /**
   * Tells whether the device supports input and/or output.
   *
   * @return A [[MidiEndpointType]] indicating the input/output capabilities of the device; [[MidiEndpointType.None]]
   *         while it is disconnected.
   */
  def endpointType: MidiEndpointType

  /**
   * Retrieves the current state of the handle and its device.
   */
  def state: MidiDeviceHandle.State

  /**
   * Checks whether the MIDI device is currently connected to the system.
   *
   * @return True if the device is connected, false otherwise.
   */
  def isConnected: Boolean

  /**
   * Determines if the MIDI device is currently open.
   *
   * @return True if the device is open, false otherwise.
   * @see [[open]] and [[close]], the methods that update this state.
   */
  def isOpen: Boolean

  /**
   * Attempts to open the MIDI device associated with this handle.
   *
   *   - If the device is not yet connected, the handle transitions to [[MidiDeviceHandle.State.WaitingToOpen]] and
   *     once it becomes connected it will then open.
   *   - If the device is already connected, the device will attempt to open immediately.
   *
   * This is a reference-counted operation; the device will only transition to an opened state if this is the first
   * call to the method, and it is in a state that allows opening.
   */
  def open(): Unit

  /**
   * Closes the MIDI device handle, updating its internal state.
   *
   * If this is the last reference to the device, it is properly closed or its state is adjusted depending on
   * the current state and connection status.
   */
  override def close(): Unit

  /**
   * Retrieves the receiver of the device, which can be used to send MIDI messages to it. A message sent while the
   * device is not open is dropped.
   *
   * @return The MIDI receiver instance.
   */
  def receiver: MidiReceiver

  /**
   * Retrieves the transmitter of the device, which can be used to subscribe to the MIDI messages it sends. Receivers
   * may be added before the device is connected or open; they start getting messages when it is.
   *
   * @return The transmitter instance.
   */
  def transmitter: ConcurrentMidiTransmitter
}

object MidiDeviceHandle {

  /**
   * Represents the state of a MIDI device's connection and openness.
   *
   * {{{
   *    ┌─────────────┐     onConnect    ┌────┐
   *    │             ├──────────────────►    │
   *    │WaitingToOpen│                  │Open│
   *    │             │            ┌─────►    │
   *    └▲────────────┘            │     └────┘
   *     │   │                   open       │
   *     │   │                     │      close
   *     │   │            ┌────────┴┐       │
   *     │   │            │Connected◄───────┘
   *     │   │close       └─▲────┬──┘
   * open│   │              │    │
   *     │   │     onConnect│    │onDisconnect
   *     │   │              │    │
   *     │   │             ┌┴────▼┐
   *     │   └─────────────►      │
   *     │                 │Closed│
   *     └─────────────────┤      │
   *                       └──────┘
   * }}}
   *
   * @param isConnected Indicates whether the device is connected.
   * @param isOpen      Indicates whether the device is open for use.
   */
  //@formatter:off
  enum State(val isConnected: Boolean, val isOpen: Boolean) {
    case Closed         extends State(isConnected = false,  isOpen = false)
    case Connected      extends State(isConnected = true,   isOpen = false)
    case WaitingToOpen  extends State(isConnected = false,  isOpen = false)
    case Open           extends State(isConnected = true,   isOpen = true)
  }
  //@formatter:on
}
```

- [ ] **Step 3: Turn the moved handle into `JavaMidiDeviceHandle`**

Replace the content of `javamidi/JavaMidiDeviceHandle.scala` below its (moved) license header with:

```scala
package org.calinburloiu.music.scmidi.javamidi

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiDevice, MidiMessage, Receiver}

/**
 * [[MidiDeviceHandle]] over a Java Sound [[MidiDevice]].
 *
 * [[JavaMidiManager]] creates the instances and keeps them up to date: it calls [[onConnect]] with the device it
 * resolved when the device is connected or opened, and [[onDisconnect]] when the device goes away. Only while the
 * device is connected are the [[MidiDevice]], via the [[device]] accessor, and the [[MidiDeviceInfo]], via the
 * [[info]] accessor, defined on the instance.
 *
 * The handle is the only place where messages cross between the Scala model and Java Sound: [[receiver]] converts
 * each [[Midi1Msg]] with `asJava` and sends it to the open device (a [[Midi2Msg]] is dropped with a warning, since a
 * Java Sound device speaks MIDI 1.0 only), and the Java `Receiver` registered on the device's transmitter converts
 * with `asScala` and fans out to the receivers of [[transmitter]].
 *
 * @param id          Unique identifier of the MIDI device.
 * @param businessync Used for publishing MIDI events about the device state.
 */
@ThreadSafe
class JavaMidiDeviceHandle private[javamidi](override val id: MidiDeviceId,
                                            businessync: Businessync)
  extends MidiDeviceHandle, Locking, LazyLogging {

  private implicit val lock: Lock = ReentrantLock()

  @volatile private var _info: Option[MidiDeviceInfo] = None
  @volatile private var _device: Option[MidiDevice] = None

  private var _state: State = State.Closed

  private var openRefCount: Int = 0

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

  override def info: Option[MidiDeviceInfo] = _info

  /**
   * Retrieves the Java Sound device behind this handle. It is a member of the implementation only, not of the
   * [[MidiDeviceHandle]] API.
   *
   * @return The MIDI device while it is connected; otherwise, None.
   */
  def device: Option[MidiDevice] = _device

  override def isInputDevice: Boolean = _info.exists(_.isInputDevice)

  override def isOutputDevice: Boolean = _info.exists(_.isOutputDevice)

  override def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)

  override def state: State = withLock {
    _state
  }

  /**
   * Informs the instance that the device got connected to the system.
   *
   * @param info   Information about the connected MIDI device.
   * @param device The resolved Java Sound device, which [[JavaMidiManager]] obtains once per environment scan.
   */
  private[javamidi] def onConnect(info: MidiDeviceInfo, device: MidiDevice): Unit = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"JavaMidiDeviceHandle $id!")

    onDisconnect()

    _device = Some(device)
    _info = Some(info)

    if (_state == State.Closed) {
      _state = State.Connected
    } else if (_state == State.WaitingToOpen) {
      doOpen()
    }
  }

  /**
   * Informs the instance that the device got disconnected from the system.
   */
  private[javamidi] def onDisconnect(): Unit = withLock {
    try {
      _device.foreach(_.close())
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to disconnect from $endpointType device $id!", exception)
        businessync.publish(MidiDeviceFailedToDisconnectEvent(id, exception))
    }

    _device = None
    _info = None
  }

  /**
   * @see `MidiDevice.open()` from the Java MIDI API, which is called by this method to open the device.
   */
  override def open(): Unit = withLock {
    openRefCount += 1
    if (openRefCount == 1) {
      if (_state == State.Closed) {
        _state = State.WaitingToOpen
      } else if (_state == State.Connected) {
        doOpen()
      }
    }
  }

  /**
   * @see `MidiDevice.close()` from the Java MIDI API, which is called by this method to close the device.
   */
  override def close(): Unit = withLock {
    openRefCount -= 1
    if (openRefCount == 0) {
      if (_state == State.WaitingToOpen) {
        _state = State.Closed
      } else if (_state == State.Open) {
        try {
          _device.foreach(_.close())
        } catch {
          case exception: Exception =>
            logger.error(s"Failed to close $endpointType device $id!", exception)
            businessync.publish(MidiDeviceFailedToCloseEvent(id, exception))
        }

        _state = State.Connected
      }
    }
  }

  override def isConnected: Boolean = _device.isDefined

  override def isOpen: Boolean = _device.exists(_.isOpen)

  override def receiver: MidiReceiver = _receiver

  override def transmitter: ConcurrentMidiTransmitter = _transmitter

  private def doOpen(): Unit = withLock {
    _state = State.Open

    try {
      _device.foreach { dev =>
        dev.open()

        if (isInputDevice) {
          dev.getTransmitter.setReceiver(inboundReceiver)
        }
      }
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to open $endpointType device $id.", exception)
        businessync.publish(MidiDeviceFailedToOpenEvent(id, exception))
    }
  }
}
```

The three `override def isInputDevice / isOutputDevice / endpointType` lines are deleted again in Step 5.

- [ ] **Step 4: Write the `MidiManager` trait and turn the moved manager into `JavaMidiManager`**

Create `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala`:

```scala
package org.calinburloiu.music.scmidi

/**
 * Manages connections to MIDI devices and gives information about them.
 *
 * The trait has separate sets of methods for inputs and outputs, because a platform may expose two endpoints (two
 * [[MidiDeviceHandle]]s) for the same physical device, one for input and the other for output. Note that in this
 * case, there is a single [[MidiDeviceId]].
 *
 * An implementation scans the environment on [[refresh]] and typically also when the platform reports a change, and
 * publishes [[MidiEvent]]s about what it finds. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiManager]] is the
 * Java Sound implementation; consumers receive a [[MidiManager]] and the composition root picks the implementation.
 */
trait MidiManager extends AutoCloseable {

  /**
   * Rescans the environment for MIDI device information and updates the internal state, publishing the
   * [[MidiEvent]]s that describe what changed.
   */
  def refresh(): Unit

  /** @return whether the input device with the given identifier is currently connected. */
  def isInputAvailable(deviceId: MidiDeviceId): Boolean

  /** @return the information of the input device with the given identifier, if it is currently connected. */
  def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo]

  /** @return the identifiers of the input devices currently connected. */
  def inputDeviceIds: Seq[MidiDeviceId]

  /** @return the information of the input devices currently connected. */
  def inputDevicesInfo: Seq[MidiDeviceInfo]

  /**
   * Opens an input connection to a MIDI device based on its unique identifier. The device need not be connected: the
   * handle opens it once it is.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * Tries to sequentially open a connection with the first input device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableInput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle]

  /** @return the handle of the input device with the given identifier, if it was opened through this manager. */
  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the handles of the input devices opened through this manager. */
  def inputOpenedDevices: Seq[MidiDeviceHandle]

  /**
   * Closes the input device with the given identifier, if it was opened through this manager. The operation is
   * reference-counted at the handle level.
   */
  def closeInput(deviceId: MidiDeviceId): Unit

  /** @return whether the output device with the given identifier is currently connected. */
  def isOutputAvailable(deviceId: MidiDeviceId): Boolean

  /** @return the information of the output device with the given identifier, if it is currently connected. */
  def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo]

  /** @return the identifiers of the output devices currently connected. */
  def outputDeviceIds: Seq[MidiDeviceId]

  /** @return the information of the output devices currently connected. */
  def outputDevicesInfo: Seq[MidiDeviceInfo]

  /**
   * Opens an output connection to a MIDI device based on its unique identifier. The device need not be connected:
   * the handle opens it once it is.
   *
   * @param deviceId Unique identifier of the device.
   * @return a handle object for the device.
   */
  def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * Tries to sequentially open a connection with the first output device available from the provided sequence (in
   * that order).
   *
   * @param deviceIds A sequence of unique identifiers of the devices.
   * @return a handle object for the device that succeeded.
   */
  def openFirstAvailableOutput(deviceIds: Seq[MidiDeviceId]): Option[MidiDeviceHandle]

  /** @return the handle of the output device with the given identifier, if it was opened through this manager. */
  def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the handles of the output devices opened through this manager. */
  def outputOpenedDevices: Seq[MidiDeviceHandle]

  /**
   * Closes the output device with the given identifier, if it was opened through this manager. The operation is
   * reference-counted at the handle level.
   */
  def closeOutput(deviceId: MidiDeviceId): Unit

  /** Closes every device opened through this manager and stops watching the environment. */
  override def close(): Unit
}
```

Then edit `javamidi/JavaMidiManager.scala` (the file Task 3 wrote, moved):

1. `package org.calinburloiu.music.scmidi` → `package org.calinburloiu.music.scmidi.javamidi`.
2. Add `import org.calinburloiu.music.scmidi.*` as the first `org.calinburloiu.music.scmidi` import (the events,
   `MidiDeviceId`, `MidiDeviceInfo`, `MidiEndpointType`, `MidiManager`, `MidiDeviceHandle` all come from it).
3. Replace the class ScalaDoc and header:

```scala
/**
 * [[MidiManager]] over Java Sound and CoreMIDI4J.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[JavaMidiDeviceHandle]]) instances for the same physical device, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each [[refresh]] resolves every device once, builds its [[MidiDeviceInfo]] and keeps the resolved device so that
 * it can be handed to the [[JavaMidiDeviceHandle]] when the device is opened.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 */
class JavaMidiManager(businessync: Businessync) extends MidiManager with StrictLogging {

  import JavaMidiManager.*
```

4. Prefix `override` to `refresh`, `close`, and each of the twenty per-direction methods (`isInputAvailable` …
   `closeOutput`); their ScalaDocs may be dropped where the trait's says the same (keep the two `openFirstAvailable…`
   ones or drop them — either is fine, the trait documents them).
5. `object MidiManager {` → `object JavaMidiManager {`.
6. In `MidiEndpoint`: `ConcurrentHashMap[MidiDeviceId, MidiDeviceHandle]()` → `ConcurrentHashMap[MidiDeviceId,
   JavaMidiDeviceHandle]()`; `MidiDeviceHandle(deviceId, businessync)` → `JavaMidiDeviceHandle(deviceId,
   businessync)`; the return types of `openDevice`, `openFirstAvailableDevice`, `deviceHandleOf` and `openedDevices`
   become `JavaMidiDeviceHandle` / `Option[JavaMidiDeviceHandle]` / `Seq[JavaMidiDeviceHandle]` (the trait-typed
   `override`s in the class still compile: a `JavaMidiDeviceHandle` is a `MidiDeviceHandle`).

- [ ] **Step 5: Red → green for the trait's derived members**

Write `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceHandleTest.scala`, whose fake implements
the abstract members only, so that it exercises the trait's own `isInputDevice` / `isOutputDevice` / `endpointType`:

```scala
package org.calinburloiu.music.scmidi

import org.calinburloiu.music.scmidi.MidiConnectionLimit.{Limited, Unlimited}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class MidiDeviceHandleTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  /** A handle that only knows its info; the members under test derive from it. */
  private class InfoOnlyHandle(override val info: Option[MidiDeviceInfo]) extends MidiDeviceHandle {
    override def id: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    override def state: MidiDeviceHandle.State = MidiDeviceHandle.State.Closed

    override def isConnected: Boolean = false

    override def isOpen: Boolean = false

    override def open(): Unit = {}

    override def close(): Unit = {}

    override def receiver: MidiReceiver = NoOpMidiReceiver()

    override def transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  }

  private def info(maxTransmitters: MidiConnectionLimit, maxReceivers: MidiConnectionLimit): MidiDeviceInfo =
    MidiDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0", maxTransmitters, maxReceivers)

  behavior of "endpointType"

  it should "derive the directions from the info while connected and report none while disconnected" in {
    // Given
    val cases = Table[Option[MidiDeviceInfo], MidiEndpointType, Boolean, Boolean](
      ("info", "endpointType", "isInputDevice", "isOutputDevice"),
      (None, MidiEndpointType.None, false, false),
      (Some(info(Unlimited, Limited(0))), MidiEndpointType.Input, true, false),
      (Some(info(Limited(0), Limited(1))), MidiEndpointType.Output, false, true),
      (Some(info(Limited(1), Unlimited)), MidiEndpointType.InputOutput, true, true)
    )

    forAll(cases) { (info, endpointType, isInputDevice, isOutputDevice) =>
      // When
      val handle = InfoOnlyHandle(info)

      // Then
      handle.endpointType shouldEqual endpointType
      handle.isInputDevice shouldBe isInputDevice
      handle.isOutputDevice shouldBe isOutputDevice
    }
  }
}
```

To make it compile *and* fail, give the three members a `???` body in the trait for a moment:

```scala
  def isInputDevice: Boolean = ???

  def isOutputDevice: Boolean = ???

  def endpointType: MidiEndpointType = ???
```

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiDeviceHandleTest -- -oNCXEHLOPQRMWS"`

Expected: compiles (the Java handle still overrides the three); fails with `NotImplementedError`.

Then implement them in the trait (ScalaDocs from Step 2 unchanged):

```scala
  def isInputDevice: Boolean = info.exists(_.isInputDevice)

  def isOutputDevice: Boolean = info.exists(_.isOutputDevice)

  def endpointType: MidiEndpointType = MidiEndpointType(isInputDevice, isOutputDevice)
```

and delete the three `override def isInputDevice / isOutputDevice / endpointType` lines from
`JavaMidiDeviceHandle.scala` (they would now be duplicates). Run the class again: PASS.

- [ ] **Step 6: `MidiEvent` ScalaDoc**

In `MidiEvent.scala`: "Base class for all MIDI events emitted by [[MidiManager]]." → "Base class for all MIDI
events emitted by a [[MidiManager]] implementation."; and in `MidiEnvironmentChangedEvent`, replace "This event is
associated with a [[uk.co.xfactorylibrarians.coremidi4j.CoreMidiNotification]]." with "An implementation publishes
it when the platform reports such a change and then rescans the environment (the Java Sound implementation reacts to
a CoreMIDI4J notification)."

- [ ] **Step 7: `TunerModule` receives the manager; `MicrotonalistApp` instantiates `JavaMidiManager`**

Replace the content of `TunerModule.scala` below the license header with:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.MidiManager

/**
 * Composition root of the `tuner` module: lazily wires the sessions, the services and the [[TrackManager]] around
 * the [[MidiManager]] it is given.
 *
 * @param businessync The event bus and business thread the sessions and services run on.
 * @param trackRepo   Where [[TrackSession]] loads tracks from.
 * @param midiManager Opens the MIDI devices of the tracks. The caller owns it and closes it after this module; it is
 *                    not closed by [[close]].
 */
class TunerModule(businessync: Businessync,
                  trackRepo: TrackRepo,
                  midiManager: MidiManager) extends AutoCloseable {

  lazy val tuningService: TuningService = new TuningService(tuningSession, businessync)

  lazy val tuningSession: TuningSession = new TuningSession(businessync)

  lazy val trackService: TrackService = new TrackService(trackSession, businessync)

  private lazy val trackManager = new TrackManager(midiManager, tuningService)
  businessync.register(trackManager)

  private lazy val trackSession = new TrackSession(trackManager, trackRepo, businessync)

  override def close(): Unit = {
    trackManager.close()
  }
}
```

In `MicrotonalistApp.scala`: add `import org.calinburloiu.music.scmidi.javamidi.JavaMidiManager` (after the
`org.calinburloiu.music.microtonalist.ui.TuningListFrame` import); replace

```scala
    val tunerModule = new TunerModule(businessync, formatModule.defaultTrackRepo)
```

with

```scala
    val midiManager = JavaMidiManager(businessync)
    val tunerModule = new TunerModule(businessync, formatModule.defaultTrackRepo, midiManager)
```

and in the shutdown hook, right after `tunerModule.close()`, add `midiManager.close()` (same order as today's
`TunerModule.close()`: tracks first, then the manager).

- [ ] **Step 8: `TrackTest` passes a stub**

In `TrackTest.scala`: change the `scmidi` import to `import org.calinburloiu.music.scmidi.{MidiManager, MidiNote,
MidiReceiver}` and replace

```scala
    // The spec has no device input and no device output, so the track never touches the manager.
    // TODO #282 Pass a stub once MidiManager is a trait.
    val track: Track = Track(spec = spec, midiManager = null, tuningService = tuningService)
```

with

```scala
    // The spec has no device input and no device output, so the track never touches the manager.
    val midiManager: MidiManager = stub[MidiManager]
    val track: Track = Track(spec = spec, midiManager = midiManager, tuningService = tuningService)
```

- [ ] **Step 9: Red → green for the `cli` printing through the trait**

Create `cli/src/test/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolAppTest.scala` (the `cli`
module already has ScalaTest and ScalaMock in `Test` scope through `commonDependencies`):

```scala
package org.calinburloiu.music.microtonalist.cli

import org.calinburloiu.music.scmidi.{MidiConnectionLimit, MidiDeviceInfo, MidiManager}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream

class MicrotonalistToolAppTest extends AnyFlatSpec with Matchers with MockFactory {

  behavior of "printMidiDevices"

  it should "print the fields and the connection limit of every input and output device" in {
    // Given
    val input = MidiDeviceInfo(
      name = "CoreMIDI4J - Seaboard",
      vendor = "ROLI Ltd.",
      description = "MPE controller",
      version = "1.0",
      maxTransmitters = MidiConnectionLimit.Unlimited,
      maxReceivers = MidiConnectionLimit.Limited(0)
    )
    val output = MidiDeviceInfo(
      name = "CoreMIDI4J - FP-90",
      vendor = "Roland",
      description = "Digital piano",
      version = "2.1",
      maxTransmitters = MidiConnectionLimit.Limited(0),
      maxReceivers = MidiConnectionLimit.Limited(1)
    )
    val midiManager = stub[MidiManager]
    (() => midiManager.inputDevicesInfo).when().returns(Seq(input))
    (() => midiManager.outputDevicesInfo).when().returns(Seq(output))
    val out = ByteArrayOutputStream()

    // When
    Console.withOut(out) {
      MicrotonalistToolApp.printMidiDevices(midiManager)
    }

    // Then
    out.toString shouldEqual
      """=== Input Devices ===
        |
        |Name: CoreMIDI4J - Seaboard
        |Vendor: ROLI Ltd.
        |Version: 1.0
        |Description: MPE controller
        |Max. Transmitters: unlimited
        |
        |
        |=== Output Devices ===
        |
        |Name: CoreMIDI4J - FP-90
        |Vendor: Roland
        |Version: 2.1
        |Description: Digital piano
        |Max. Receivers: 1
        |
        |""".stripMargin
  }
}
```

To make it compile and fail, change `MicrotonalistToolApp` so that `main` builds the manager and
`printMidiDevices` takes it, with a `???` body:

```scala
package org.calinburloiu.music.microtonalist.cli

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.javamidi.JavaMidiManager
import org.calinburloiu.music.scmidi.{MidiDeviceInfo, MidiManager}

object MicrotonalistToolApp {

  def main(args: Array[String]): Unit = {
    args match {
      case Array("midi-devices") =>
        val midiManager = JavaMidiManager(Businessync(EventBus()))
        printMidiDevices(midiManager)
        midiManager.close()
      case _ => println(
        """Usage:
          |midi-devices    prints all available MIDI devices
          |""".stripMargin
      )
    }
  }

  /**
   * Prints every input and output device of `midiManager`: its name, vendor, version and description, and how many
   * transmitters (inputs) or receivers (outputs) it can open.
   */
  private[cli] def printMidiDevices(midiManager: MidiManager): Unit = ???
}
```

Run: `sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"`

Expected: compiles; fails with `NotImplementedError`.

Then move the printing into the method (the body is Task 3's, minus the manager construction and the `close()`):

```scala
  private[cli] def printMidiDevices(midiManager: MidiManager): Unit = {
    // Endpoint is a term for input or output
    def printMidiDevicesByEndpoint(devicesInfo: Seq[MidiDeviceInfo], printLimit: MidiDeviceInfo => Unit): Unit = {
      devicesInfo.foreach { info =>
        println(
          s"""Name: ${info.name}
             |Vendor: ${info.vendor}
             |Version: ${info.version}
             |Description: ${info.description}""".stripMargin
        )
        printLimit(info)
        println()
      }
    }

    println("=== Input Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.inputDevicesInfo,
      info => println(s"Max. Transmitters: ${info.maxTransmitters}"))

    println("\n=== Output Devices ===\n")
    printMidiDevicesByEndpoint(midiManager.outputDevicesInfo,
      info => println(s"Max. Receivers: ${info.maxReceivers}"))
  }
```

Run the `cli` suite again: PASS. If the expected string is off by a blank line, fix the *test*'s expectation to the
actual output only after checking it against the old program's `println` sequence (`"=== Input Devices ===\n"`,
four lines, the limit line, an empty line, then `"\n=== Output Devices ===\n"`): the output must not change.

- [ ] **Step 10: Compile everything and verify the isolation**

`mcp__metals__compile-full`: no errors, no new warnings. Then:

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"
grep -rln 'import javax.sound.midi\|import uk.co.xfactorylibrarians' --include='*.scala' */src/main
```

Expected: green; the grep lists exactly `JavaMidiConverters.scala`, `JavaMidiDeviceHandle.scala` and
`JavaMidiManager.scala`, all under `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/`. Also
`git status` must show the two moves as renames (`R`), not as delete + add; if it does not, the moved files
diverged too much — that is acceptable, but check that no license header was lost.

- [ ] **Step 11: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi sc-midi/src/test/scala/org/calinburloiu/music/scmidi \
        tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerModule.scala \
        tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala \
        app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala \
        cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala \
        cli/src/test/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolAppTest.scala
git commit -m "[#278/#282] Make MidiManager and MidiDeviceHandle traits; move the Java Sound implementation to javamidi"
```

---

## Task 5: The `JavaMidiEnvironment` seam (D11)

**Files:**
- Create: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiEnvironment.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala`

**Interfaces:**
- Consumes: `JavaMidiManager` (Task 4); CoreMIDI4J's `CoreMidiDeviceProvider.getMidiDeviceInfo`,
  `addNotificationListener`, `removeNotificationListener`; `MidiSystem.getMidiDevice`.
- Produces: `trait JavaMidiEnvironment { def deviceInfos: Seq[MidiDevice.Info]; def deviceOf(info: MidiDevice.Info):
  MidiDevice; def onEnvironmentChanged(listener: () => Unit): AutoCloseable }`; `object CoreMidi4JEnvironment
  extends JavaMidiEnvironment`; `class JavaMidiManager(businessync: Businessync, environment: JavaMidiEnvironment =
  CoreMidi4JEnvironment)`.

This task is a refactor (delegation only, behaviour preserved), done green-to-green; the design (Section 4) asks for
no test of the seam.

- [ ] **Step 1: Create `JavaMidiEnvironment.scala`**

```scala
package org.calinburloiu.music.scmidi.javamidi

import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}

import javax.sound.midi.{MidiDevice, MidiSystem}
import scala.collection.immutable.ArraySeq

/**
 * The Java Sound environment a [[JavaMidiManager]] runs in: which devices are present, how a device is resolved, and
 * how changes of the environment are reported.
 *
 * It is the seam that keeps the manager's device bookkeeping testable without MIDI hardware: a test can pass a fake
 * environment over fake devices. [[CoreMidi4JEnvironment]] is the production implementation.
 */
trait JavaMidiEnvironment {

  /** The information of every MIDI device currently present, as Java Sound reports it. */
  def deviceInfos: Seq[MidiDevice.Info]

  /**
   * Resolves the device described by `info`.
   *
   * @throws javax.sound.midi.MidiUnavailableException if the device cannot be resolved because of a resource
   *                                                    restriction.
   * @throws IllegalArgumentException                   if `info` does not describe a device of this environment.
   */
  def deviceOf(info: MidiDevice.Info): MidiDevice

  /**
   * Subscribes to changes of the MIDI environment, such as a device being plugged in or unplugged.
   *
   * @param listener called on every change.
   * @return a subscription; closing it unsubscribes the listener.
   */
  def onEnvironmentChanged(listener: () => Unit): AutoCloseable
}

/**
 * The production [[JavaMidiEnvironment]]: delegates device discovery and change notifications to CoreMIDI4J, which
 * replaces the default Java Sound MIDI device provider to make Java MIDI work on macOS (this should also work on
 * Windows), and device resolution to `MidiSystem`. It is the only production code that calls these statics.
 */
object CoreMidi4JEnvironment extends JavaMidiEnvironment {

  override def deviceInfos: Seq[MidiDevice.Info] = ArraySeq.unsafeWrapArray(CoreMidiDeviceProvider.getMidiDeviceInfo)

  override def deviceOf(info: MidiDevice.Info): MidiDevice = MidiSystem.getMidiDevice(info)

  override def onEnvironmentChanged(listener: () => Unit): AutoCloseable = {
    // CoreMIDI4J matches listeners by identity on removal, so the same adapter instance must be used for both calls.
    val notification: CoreMidiNotification = () => listener()
    CoreMidiDeviceProvider.addNotificationListener(notification)
    () => CoreMidiDeviceProvider.removeNotificationListener(notification)
  }
}
```

- [ ] **Step 2: Route `JavaMidiManager` through the environment**

In `JavaMidiManager.scala`:

1. Delete `import uk.co.xfactorylibrarians.coremidi4j.{CoreMidiDeviceProvider, CoreMidiNotification}` and change
   `import javax.sound.midi.{MidiDevice, MidiSystem, MidiUnavailableException}` to
   `import javax.sound.midi.{MidiDevice, MidiUnavailableException}`.
2. Replace the class header and its ScalaDoc's `@param` list with:

```scala
/**
 * [[MidiManager]] over Java Sound and CoreMIDI4J.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[JavaMidiDeviceHandle]]) instances for the same physical device, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each [[refresh]] resolves every device once through the [[JavaMidiEnvironment]], builds its [[MidiDeviceInfo]] and
 * keeps the resolved device so that it can be handed to the [[JavaMidiDeviceHandle]] when the device is opened. The
 * manager also refreshes whenever the environment reports a change, until it is closed.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 * @param environment The Java Sound environment to scan and subscribe to; the production default is
 *                    [[CoreMidi4JEnvironment]], a fake is what a test passes.
 */
class JavaMidiManager(businessync: Businessync,
                      environment: JavaMidiEnvironment = CoreMidi4JEnvironment)
  extends MidiManager with StrictLogging {
```

3. Replace the `onMidiNotification` value, the `init()` call and the `init()` method with (they must stay *below*
   the two endpoint values, which `refresh()` uses):

```scala
  private val environmentSubscription: AutoCloseable = init()

  private def init(): AutoCloseable = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    environment.onEnvironmentChanged(() => onEnvironmentChanged())
  }

  private def onEnvironmentChanged(): Unit = {
    logger.info("The MIDI environment has changed.")
    businessync.publish(MidiEnvironmentChangedEvent)
    refresh()
  }
```

4. In `refresh()`, replace the two comment lines and `val deviceInfoArray = CoreMidiDeviceProvider.getMidiDeviceInfo`
   with nothing, and `for (javaInfo <- deviceInfoArray; …` with `for (javaInfo <- environment.deviceInfos; …` (the
   CoreMIDI4J remark now lives on `CoreMidi4JEnvironment`).
5. In `resolveDevice`, `Some(MidiSystem.getMidiDevice(javaInfo))` → `Some(environment.deviceOf(javaInfo))`.
6. In `close()`, `CoreMidiDeviceProvider.removeNotificationListener(onMidiNotification)` →
   `environmentSubscription.close()`.

- [ ] **Step 3: Compile, run the suite, verify the statics moved**

`mcp__metals__compile-module` with `module = "sc-midi"`, then:

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
grep -rn 'CoreMidiDeviceProvider\|MidiSystem\.' sc-midi/src/main
```

Expected: green; the grep matches only `JavaMidiEnvironment.scala`.

- [ ] **Step 4: Commit**

```bash
git add sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiEnvironment.scala \
        sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala
git commit -m "[#278/#282] Put the CoreMIDI4J and MidiSystem statics behind the JavaMidiEnvironment seam"
```

---

## Task 6: Final checks, documentation and the pull request

**Files:**
- Modify: `docs/architecture/sc-midi/README.md`
- Modify: `docs/architecture/tuner/README.md`
- Modify: `docs/architecture/module-overview.md`
- Verify: every file changed in Tasks 1–5; `docs/architecture/data-flow.md` (no change expected — it names no
  class of this issue).

**Interfaces:** none new.

- [ ] **Step 1: Module tests**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow it for `sc-midi`, `tuner` and `cli`. Check `MidiConnectionLimit`,
`MidiDeviceInfo`, `MidiDeviceId`, `MidiDeviceHandle` (the trait) and `JavaMidiConverters` individually: the first
four should be at ~100%, the last at or above its previous level. `JavaMidiManager`, `JavaMidiDeviceHandle` and
`CoreMidi4JEnvironment` are uncovered by design (#177). The `sc-midi` statement total must stay at or above the 67%
floor (baseline 69.16); the branch total was already below its 52% floor at the base commit (50.95), so a
`coverageCheck` failure on that figure is pre-existing — report the numbers and move on, do not add tests to
`javamidi` to chase it. `tuner` must stay at or above 80/80 (baseline 84.85/82.66); `cli` rises from 0.

- [ ] **Step 3: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: green. `ui`, `format` and `composition` compile against the new `tuner` and `sc-midi` API without changes;
a failure there is pre-existing or environmental — report it, do not "fix" it in this PR.

- [ ] **Step 4: Update the `sc-midi` architecture document**

In `docs/architecture/sc-midi/README.md`:

1. "Responsibility", first sentence — replace "is a **Scala-idiomatic MIDI API layered over `javax.sound.midi`**.
   The standard Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this module wraps it to
   give the rest of Microtonalist:" with:

```markdown
is a **Scala-idiomatic MIDI API** with a **Java Sound implementation** kept apart in its `javamidi` package. The
standard Java Sound MIDI API is verbose, mutable, byte-oriented, and awkward on macOS; this module hides it behind
traits and typed messages to give the rest of Microtonalist:
```

2. "Responsibility", the paragraph starting "It is low-level infrastructure" — replace the whole paragraph with:

```markdown
It is low-level infrastructure: it knows nothing about scales, tunings, compositions, or the GUI, and depends only on
`businessync` (the device-event bus) and `common` (the `Locking` helper). Only the `javamidi` package imports
`javax.sound.midi` and CoreMIDI4J (#282): `tuner` and `cli` see the `MidiManager` / `MidiDeviceHandle` traits and the
`MidiMsg` model, and the composition roots (`MicrotonalistApp`, `MicrotonalistToolApp`) pick the implementation by
instantiating `JavaMidiManager`. Inside `javamidi`, messages cross to and from Java Sound in exactly one place,
`JavaMidiDeviceHandle`; everything upstream of it carries `MidiMsg`.
```

3. The "Package:" paragraph — replace it with:

```markdown
Package: `org.calinburloiu.music.scmidi` is the pure Scala API, with a `message` sub-package holding the message
model and its constants. The `javamidi` sub-package is the Java Sound implementation: `JavaMidiManager`,
`JavaMidiDeviceHandle`, the `JavaMidiEnvironment` seam with its `CoreMidi4JEnvironment` production implementation,
and `JavaMidiConverters`. The two live in the same sbt module; the isolation is enforced by convention and review,
not by the build (#278, D1). macOS support comes from **CoreMIDI4J**, which replaces the default Java Sound MIDI
device provider and prefixes device names with `"CoreMIDI4J - "` (stripped for display by
`MidiDeviceId.sanitizedName`).
```

4. "Device handling" — replace the `MidiManager` paragraph, the `MidiDeviceHandle` paragraph and the "Supporting value
   types" line with:

```markdown
**`MidiManager`** is the trait through which devices are discovered and opened. It is `AutoCloseable` and offers a
per-direction API mirrored for input and output (availability, id/info enumeration as `MidiDeviceInfo`,
`open*`/`close*`, `openFirstAvailable*`, handle lookup), because a platform may expose a physical bidirectional
device as two endpoints that nonetheless share one `MidiDeviceId`. `refresh()` rescans the environment; an
implementation also refreshes when the platform reports a change, emitting the device events described in
[Device lifecycle and events](#device-lifecycle-and-events) as it reconciles state.

**`MidiDeviceHandle`** is the trait for a handle to a single device identified by a `MidiDeviceId`, created and kept
up to date by the manager. A handle can exist for a device that is **not currently connected** —
`info: Option[MidiDeviceInfo]` is defined only once physically connected, and `isInputDevice` / `isOutputDevice` /
`endpointType` derive from it — and its lifecycle is **reference-counted**: the device opens on the first `open()`
and closes on the last `close()`. `open()` may be called before the device is connected — the handle moves to
`WaitingToOpen` and opens automatically when the device appears (the `State` enum in the companion captures the
Closed/Connected/WaitingToOpen/Open transitions, drawn in its ScalaDoc). Callers **send** to an output via
`handle.receiver: MidiReceiver` and **subscribe** to an input via `handle.transmitter: ConcurrentMidiTransmitter`;
both survive disconnect/reconnect without re-wiring.

Supporting value types: `MidiDeviceInfo` (`case class(name, vendor, description, version, maxTransmitters,
maxReceivers)` with a derived `id: MidiDeviceId` and `endpointType`), `MidiConnectionLimit` (an `enum` of
`Unlimited` / `Limited(count)` — how many transmitters or receivers a device can open; it prints as `unlimited` or the
count, and its `allowsConnections` is what a device's direction derives from), `MidiDeviceId` (`case class(name,
vendor)`) and `MidiEndpointType` (an `enum` of `None`/`Input`/`Output`/`InputOutput`).

**The Java Sound implementation** (`javamidi`). `JavaMidiManager(businessync, environment = CoreMidi4JEnvironment)`
keeps two internal endpoints — one for inputs, one for outputs — with the device diffing, the reference-counted
open/close bookkeeping and the `MidiEvent` publishing; each `refresh()` resolves every `MidiDevice` once, builds its
`MidiDeviceInfo` through `JavaMidiConverters.asMidiDeviceInfo` (Java Sound's `-1` becomes `Unlimited`) and hands the
resolved device to the handle when it is opened. `JavaMidiDeviceHandle` is the `@ThreadSafe` handle over a
`javax.sound.midi.MidiDevice` (exposed as `device: Option[MidiDevice]` on the concrete class only) and the **Java
Sound boundary**: its receiver converts each `Midi1Msg` with `asJava` and sends it to the open device (a `Midi2Msg`
is dropped with a warning, since Java Sound speaks MIDI 1.0 only), and the Java `Receiver` it hands to the device's
transmitter converts with `asScala` into an internal `MidiSplitter(ConcurrentMidiTransmitter())`.
`JavaMidiEnvironment` is the seam between the manager and the platform — `deviceInfos`, `deviceOf(info)` and
`onEnvironmentChanged(listener)` — so that the bookkeeping can be unit-tested over a fake environment (follow-up
work under #177); `CoreMidi4JEnvironment` is the production implementation and the only file that calls the
CoreMIDI4J and `MidiSystem` statics.
```

5. The `MidiEvent` paragraph — "everything `MidiManager` publishes on the bus" → "everything a `MidiManager`
   implementation publishes on the bus".

6. The `JavaMidiConverters` paragraph — replace "The same object also carries the `isInputDevice`/`isOutputDevice`
   extensions on `javax.sound.midi.MidiDevice`." with "The same object also builds the device-level API values from
   Java Sound: `device.asMidiDeviceInfo`, `info.asMidiDeviceId` and `connectionLimit(javaCount)`."

7. "How MIDI devices are opened, enumerated, and used", step 1 — replace with:

```markdown
1. Construct a single `JavaMidiManager(businessync)` at the composition root (`MicrotonalistApp`,
   `MicrotonalistToolApp`) and pass it around as a `MidiManager`; its initialization runs a first `refresh()` and
   subscribes to environment changes so the device list stays current.
```

8. "Device lifecycle and events" — "`MidiManager`'s internal endpoints" → "`JavaMidiManager`'s internal endpoints".

9. "Message conversion model" — "in `MidiDeviceHandle`" → "in `JavaMidiDeviceHandle`".

10. "Dependencies", second paragraph — replace with:

```markdown
**Depended on by** `tuner` (builds `MidiProcessor`-based pipelines and uses the `MidiManager` it is given for device
I/O), `cli` (lists connected devices) and `app` (instantiates `JavaMidiManager` and injects it into `TunerModule`);
`composition`, `format`, and `ui` reach it transitively through `tuner`.
```

11. "Notes / subject to change" — replace the first bullet with:

```markdown
- Coverage targets are currently below the project-wide 80% goal (TODO #177). `JavaMidiManager` and
  `JavaMidiDeviceHandle` are uncovered, but no longer hardware-bound: the `JavaMidiEnvironment` seam lets them be
  tested over a fake environment, which is the follow-up work under #177. A known defect of
  `purgeDisconnectedDevices` (an unplugged open device orphans its handle) is tracked by #<N>.
```

    and the last bullet with:

```markdown
- The `Sc` prefix is gone (#279), the `MidiTransmitter` family replaced `MultiTransmitter` (#280, #281), the
  pipeline carries `MidiMsg` end to end (#281) and the device layer is a pair of traits with a Java Sound
  implementation under `javamidi` (#282); the remaining #278 work is #285 (Channel Mode messages as their own
  types) — see `issues/00278-isolate-java-midi/`.
```

Replace `#<N>` with the Task 0 number.

- [ ] **Step 5: Update the `tuner` and module-overview documents**

In `docs/architecture/tuner/README.md`:

1. "Sessions, services, and events", the `TunerModule` bullet — replace with:

```markdown
- `TunerModule` is the composition root that lazily wires the sessions, services and the `TrackManager` around the
  `MidiManager` it is given. The `app` layer instantiates `JavaMidiManager` (from `sc-midi`'s `javamidi` package),
  injects it and closes it after the module; `tuner` itself imports nothing from `javamidi`.
```

2. "Dependencies", first paragraph — replace "(the Scala-idiomatic MIDI API: `MidiManager`," with "(the
   Scala-idiomatic MIDI API: the `MidiManager` / `MidiDeviceHandle` traits," and "The module imports nothing from
   `javax.sound.midi` (#281)." with "The module imports nothing from `javax.sound.midi` (#281) nor from the
   `javamidi` implementation package (#282)."

In `docs/architecture/module-overview.md`, the tree line for `sc-midi` — replace with:

```
├── sc-midi       (Scala-idiomatic MIDI API; Java Sound confined to its javamidi package, depends on businessync)
```

- [ ] **Step 6: Review the ScalaDocs and the conventions**

Read `MidiManager.scala`, `MidiDeviceHandle.scala`, `MidiDeviceInfo.scala`, `MidiConnectionLimit.scala`,
`MidiDeviceId.scala`, `JavaMidiManager.scala`, `JavaMidiDeviceHandle.scala`, `JavaMidiEnvironment.scala`,
`JavaMidiConverters.scala`, `TunerModule.scala` and `MicrotonalistToolApp.scala` once more and check that every
public identifier has a ScalaDoc, that no ScalaDoc of an API type in `scmidi` still describes a Java type as part of
its contract, that no line exceeds 120 columns, that no `new` slipped into rewritten code (the anonymous `Receiver`
in the handle is the exception), that `// TODO #<N>` carries the real number, and that the `MidiDeviceHandle` trait
has no leftover `???`. Compile once more with `mcp__metals__compile-full` and confirm there are no new warnings.

- [ ] **Step 7: Commit the documentation**

```bash
git add docs/architecture/sc-midi/README.md docs/architecture/tuner/README.md docs/architecture/module-overview.md
git commit -m "[#278/#282] Document the sc-midi API/implementation split and the JavaMidiEnvironment seam"
```

- [ ] **Step 8: Open the draft pull request against the stack**

The `contributing` skill's script targets `main`; this PR must target the branch chosen in Task 1, Step 0. Dry-run
first to get the resolved title, body, label and milestone:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 278/282 \
  "Make MidiManager and MidiDeviceHandle traits with a JavaMidiManager implementation" \
  "\`MidiManager\` and \`MidiDeviceHandle\` become traits of the \`scmidi\` API; \`MidiDeviceInfo\` (with \`MidiConnectionLimit\`) replaces \`MidiDevice.Info\` in every public signature; today's classes move to \`javamidi\` as \`JavaMidiManager\` / \`JavaMidiDeviceHandle\` behind a \`JavaMidiEnvironment\` seam (D11), resolving each device once per refresh; \`TunerModule\` receives a \`MidiManager\` and \`MicrotonalistApp\` / \`MicrotonalistToolApp\` instantiate \`JavaMidiManager\`. Only \`javamidi\` imports \`javax.sound.midi\`. The \`purgeDisconnectedDevices\` defect is carried over unchanged and tracked by #<N>. Design: decisions D8, D9 and D11 of \`issues/00278-isolate-java-midi/2026-09-07-isolate-java-midi-design.md\`; plan: \`issues/00278-isolate-java-midi/2026-09-08-282-midi-manager-traits-plan.md\` (both on PR #284). Stacked on the #281 PR." \
  --dry-run
```

Then run the printed commands by hand, adding `--base` to the `gh pr create` line (replace the base by `main` if
Step 0 chose it), and the project step with the URL `gh pr create` prints:

```bash
git push -u origin HEAD
gh pr create --draft --base refactoring/281-scala-typed-pipeline \
  --title '[#278/#282] Make MidiManager and MidiDeviceHandle traits with a JavaMidiManager implementation' \
  --body "$(printf '%s\n\nResolves #282' '<the body above>')" \
  --label refactoring --assignee @me --milestone sc-midi
gh project item-add 1 --owner calinburloiu --url <PR_URL>
```

Report the PR URL. Do not merge, rebase or retarget any PR of the stack.

---

## Self-review against the design

- **D8 — API types**: `MidiDeviceInfo(name, vendor, description, version, maxTransmitters, maxReceivers)` with
  derived `id` and `endpointType` (Task 1); `enum MidiConnectionLimit { Unlimited; Limited(count) }`, printing as
  `unlimited` or the count, from which the `cli` prints and the handle's direction derives (Tasks 1, 3, 4);
  `MidiDeviceId.correspondsToInfo(MidiDeviceInfo)` and the `MidiDevice.Info` factory on the Java side as
  `asMidiDeviceId` (Tasks 2, 3, see the design note); `trait MidiManager extends AutoCloseable` with the unchanged
  per-direction surface (Task 4); `trait MidiDeviceHandle extends AutoCloseable` with exactly the listed members and
  `State` in the companion, `device` off the API (Task 4).
- **D8 — Java implementation**: `JavaMidiManager(businessync)` with the endpoint bookkeeping and the CoreMIDI4J
  listener, building `MidiDeviceInfo` from Java Sound (Tasks 3, 4); `JavaMidiDeviceHandle` with `device` public on
  the concrete class only and the D7 boundary conversion unchanged (Task 4); `JavaMidiConverters` gains the
  `MidiDeviceInfo` builder from a `MidiDevice` mapping `-1` to `Unlimited` and loses the two capability helpers
  (Tasks 2, 3). The bookkeeping is not lifted into a base class (out of scope, Section 7).
- **D9**: `TunerModule` takes a `MidiManager`, `MicrotonalistApp` instantiates `JavaMidiManager`, `tuner` imports
  nothing from `javamidi` (Task 4); `cli` instantiates `JavaMidiManager` and keeps printing the fields and the max
  transmitter / receiver counts from `MidiConnectionLimit` (Tasks 3, 4, pinned by `MicrotonalistToolAppTest`); the
  `import javax.sound.midi` grep over `src/main` matches only `javamidi` (Task 4, Step 10).
- **D11**: the seam with `CoreMidi4JEnvironment` as the default constructor argument, the subscription returned as an
  `AutoCloseable` adapting a `() => Unit` to `CoreMidiNotification`, resolution once per refresh in the manager with
  the handle taking the resolved device, the statics in exactly one file (Task 5, with Task 3 having moved the
  resolution); behaviour pinned in prose: `MidiUnavailableException` / `IllegalArgumentException` drop silently, any
  other exception logs and publishes `MidiDeviceFailedToConnectEvent` from the manager (Task 3's `resolveDevice`),
  the listener still publishes `MidiEnvironmentChangedEvent` then refreshes, `close()` unsubscribes (Task 5). The
  `devices` → `deviceInfos` + `deviceOf` refinement is recorded in [Design notes](#design-notes) and applied to the
  design document. No `JavaMidiManager` suite (Section 4).
- **Section 8**: the bug is filed before any code (Task 0) with the four findings and a related one; the purge loop is
  carried verbatim under `// TODO #<N>` (Task 3) and no test pins it.
- **Section 3, row 4**: D8, D9, D11; `MidiDeviceInfo`, the two traits, `JavaMidiManager` / `JavaMidiDeviceHandle`,
  the seam, `TunerModule` injection, `cli` / `app`; only `javamidi` imports Java Sound.
- **Section 4**: `MidiDeviceInfo` and the updated `MidiDeviceId` tests (Tasks 1, 3), plus `MidiConnectionLimit`, the
  converter builders, the trait's derived members and the `cli` (Tasks 1, 2, 4); `JavaMidiConvertersTest` remains
  the Java-boundary test; floors unchanged, the pre-existing branch shortfall reported, not chased (Task 6).
- **Section 5**: ScalaDocs on every new public type and member (Tasks 1–5, reviewed in Task 6); the `sc-midi` README
  rewritten around the API/implementation split (packages, device traits, value types, the seam, the composition
  root), the `tuner` README's `TunerModule` and dependencies, `module-overview.md` narrowed to the `javamidi` package;
  `data-flow.md` checked — nothing to narrow (Task 6).
- **Placeholder scan**: every code step carries its code or an exact substitution list; the only symbol not fixed
  by this plan is the Task 0 issue number `#<N>`, which Task 0 produces and the plan says where to write.
- **Type consistency**: `MidiConnectionLimit.Unlimited` / `.Limited(count)` / `.allowsConnections`,
  `MidiDeviceInfo.id` / `.isInputDevice` / `.isOutputDevice` / `.endpointType`, `MidiDeviceId.correspondsToInfo(info:
  MidiDeviceInfo)`, `JavaMidiConverters.connectionLimit(javaMaxConnections)` / `info.asMidiDeviceId` /
  `device.asMidiDeviceInfo`, `MidiDeviceHandle.info: Option[MidiDeviceInfo]` / `.state: MidiDeviceHandle.State`,
  `JavaMidiDeviceHandle.onConnect(info: MidiDeviceInfo, device: MidiDevice)` / `.onDisconnect()` / `.device`,
  `JavaMidiManager(businessync, environment)`, `JavaMidiEnvironment.deviceInfos` / `.deviceOf(info)` /
  `.onEnvironmentChanged(listener)`, `CoreMidi4JEnvironment`, `TunerModule(businessync, trackRepo, midiManager)`,
  `MicrotonalistToolApp.printMidiDevices(midiManager)` are spelled the same in every task.

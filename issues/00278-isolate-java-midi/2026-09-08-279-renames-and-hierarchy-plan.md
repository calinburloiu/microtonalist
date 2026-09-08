# `sc-midi` Renames and Message Hierarchy (#279) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date**: 2026-09-08
- **Issue**: [#279](https://github.com/calinburloiu/microtonalist/issues/279) — sub-issue 1 of
  [#278](https://github.com/calinburloiu/microtonalist/issues/278) "Isolate the Java Sound implementation from the
  sc-midi Scala API"
- **Base commit**: `208e19d670fbedee0257df60da88b64d8deee851` (branch `refactoring/isolate-java-midi`, head of draft
  PR [#284](https://github.com/calinburloiu/microtonalist/pull/284)); the code under `sc-midi/`, `tuner/` and `format/`
  is identical to `main` at `fd8f6d6`
- **Design this plan implements**: [`2026-09-07-isolate-java-midi-design.md`](2026-09-07-isolate-java-midi-design.md)
  — decisions **D2** (drop the `Sc` prefix, `MidiMsg` suffix, naming rule, delete `mapShortMessageChannel`), **D3**
  (`Midi1Msg` / `Midi2Msg`), and the part of **D1** that the `#279` row of Section 3 assigns to this sub-issue: create
  the `org.calinburloiu.music.scmidi.javamidi` package and move `JavaMidiConverters` and the `MidiDevice` capability
  helpers into it. Nothing else from the design is in scope here.
- **Milestone**: `sc-midi`

**Goal:** Rename every `Sc`-prefixed `sc-midi` type, give the message model the `MidiMsg` suffix with `Midi1Msg` /
`Midi2Msg` under `MidiMsg`, record the `Msg` naming rule, and move the Java Sound converters and device helpers into
the new `javamidi` package — with no behaviour change and a green suite after every task.

**Architecture:** Three uniform, tool-driven rename passes (message model, constants objects, the receiver/tracker/enum
trio) each end in a green compile and a green suite. Two moves follow (the converters object, then the two
`MidiDevice` helpers), then the one small behavioural cleanup the design mandates (`mapShortMessageChannel` deleted in
favour of `ChannelMidiMsg.mapChannel`), then the hierarchy change (`Midi1Msg` / `Midi2Msg`, with `asJava` narrowed to
`Midi1Msg`). Documentation closes the work. Every intermediate commit compiles and passes the suite, so the branch can
be reviewed, bisected, or rebased at any task boundary.

**Tech Stack:** Scala 3, sbt 1 driven through `sbtn` against the running BSP server, Metals MCP for compiling,
ScalaTest 3 (`AnyFlatSpec` + `Matchers` + `TableDrivenPropertyChecks`), ScalaMock, scoverage, `perl -pi` for the
mechanical substitutions (BSD `sed` on macOS has no `\b`). Modules touched: `sc-midi`, `tuner`, `format`.

---

## Global Constraints

- **Purely mechanical sub-issue; the suite must stay green after every step.** Section 3 of the design lists #279 as
  "Mechanical; suite stays green." Every task below ends with a compile and a test run of the touched modules and a
  commit only when both are green. Never commit red production code.
- **TDD, adapted to the nature of each task.** The repository's strict red/green/refactor rule applies to *logic*.
    * Tasks 1–5 are renames and moves with no behaviour change. There is no logic to write a failing test for; the
      existing suite is the test, and the "red" of a rename is the compile error the renamed symbol produces until
      every reference is updated. Do not invent tests that merely assert a name exists.
    * Tasks 6 and 7 add a contract (a forwarding behaviour, a type hierarchy) and follow red/green/refactor strictly.
      Type-level facts are asserted with `scala.compiletime.testing.typeChecks` so the test **compiles and fails at
      runtime** for the right reason, as the workflow requires, instead of failing to compile.
- **Coding conventions** (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines,
  no `new` (except for Java classes such as `ShortMessage`, which the existing converters already use), no `return`,
  Scala 3 `enum` for enumerations, ScalaDoc on every public identifier, `// TODO #<issue>` for every TODO.
- **Test conventions** (`docs/development/test-conventions.md`): Given/When/Then comments, no `if` in tests,
  `behavior of` sections, fixtures for repeated setup. New cases go in the `behavior of` block matching their subject.
- **License headers**: never write them by hand for `.scala` files; the `.githooks/pre-commit` hook adds them. `Read`
  skips them, so files appear to start around line 17 with real line numbers preserved.
- **Compile** with `mcp__metals__compile-full` (whole project) or `mcp__metals__compile-module` with
  `module = "sc-midi"` / `"tuner"` / `"format"`. Fall back to `sbtn compile` only if Metals is unavailable.
- **Test** through `sbtn` only, always with the reporter flags:
  `sbtn "<module>/testOnly * -- -oNCXEHLOPQRMWS"`, a single class with
  `sbtn "<module>/testOnly <FQCN> -- -oNCXEHLOPQRMWS"`, the whole suite with
  `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- **Coverage** is checked once, in Task 9, with the `scoverage-inspector` skill. Floors at the base commit
  (`build.sbt`, `coverageSettings`): `sc-midi` stmt 67 / branch 52, `tuner` stmt 80 / branch 80. Floors must never
  drop; the design (Section 4) keeps `MidiManager`/`MidiDeviceHandle` uncovered.
- **Substitution tooling.** Use `perl -pi -e '...'` (supports `\b`); macOS `sed -i` needs a `''` argument and lacks
  word boundaries. Restrict every substitution to `sc-midi/src`, `tuner/src`, `format/src` and
  `docs/architecture/sc-midi/README.md`. **Never** touch `issues/` (historical documents keep the old names),
  `coverage-reports/`, or any `target*/` directory. `rg` is not installed; use `grep` (through `rtk proxy grep` when
  the hook's filtering hides the output you need).
- **Commit messages** start with `[#278/#279] ` (parent/child prefix, contributing skill) and end with the session's
  attribution trailers.
- **Branch and PR.** The plan lives on `refactoring/isolate-java-midi` (PR #284, documentation only). Implement on a
  new branch `refactoring/279-drop-sc-prefix` created from `main` once PR #284 has merged — or from
  `refactoring/isolate-java-midi` if implementation starts earlier, in which case rebase onto `main` after #284
  merges. Open the draft PR with the contributing skill's script:
  `.claude/skills/contributing/scripts/microtonalist-gh pr 278/279 "Drop the Sc prefix from sc-midi and add the Midi1Msg/Midi2Msg hierarchy"`.
  Sub-issues #281 and #285 depend on this one merging first (design, Section 3).
- **Out of scope** (do not absorb): D4–D10 of the design; `MultiTransmitter`; `Tuner`, `TuningChanger`,
  `MidiProcessor` and `Track` staying typed on Java `MidiMessage`/`Receiver` (they change in #281); the `Sc`-prefixed
  example PR title in `CONTRIBUTING.md` and the contributing skill (`[#151] Add ScProgramChangeMidiMessage` — a
  historical example, not a type); raising coverage floors (#177).

---

## File Structure

### Renames (Tasks 1–3)

Every rename is a `git mv` of the file plus a repository-wide identifier substitution. Test classes follow their
production class (`<Class>Test`).

| Old identifier / file                                   | New identifier / file                                  | Task |
|---------------------------------------------------------|--------------------------------------------------------|------|
| `message/ScMidiMessage.scala` — `ScMidiMessage`         | `message/MidiMsg.scala` — `MidiMsg`                    | 1    |
| every `<X>ScMidiMessage` type (`NoteOnScMidiMessage`, `CcScMidiMessage`, `SysExScMidiMessage`, `TextMetaScMidiMessage`, `UnsupportedScMidiMessage`, `ChannelScMidiMessage`, `MetaScMidiMessage`, …) | `<X>MidiMsg` (`NoteOnMidiMsg`, `CcMidiMsg`, `SysExMidiMsg`, `TextMetaMidiMsg`, `UnsupportedMidiMsg`, `ChannelMidiMsg`, `MetaMidiMsg`, …) | 1 |
| `message/ScMidiMessageTest.scala` — `ScMidiMessageTest` | `message/MidiMsgTest.scala` — `MidiMsgTest`            | 1    |
| `MpeTunerTest.extractScMidiMessages` (test helper)      | `MpeTunerTest.extractMidiMessages` (helpers keep the full word) | 1 |
| `message/ScMidiCc.scala` — `ScMidiCc`                   | `message/MidiCc.scala` — `MidiCc`                      | 2    |
| `message/ScMidiRpn.scala` — `ScMidiRpn`                 | `message/MidiRpn.scala` — `MidiRpn`                    | 2    |
| `message/ScMidiNrpn.scala` — `ScMidiNrpn`               | `message/MidiNrpn.scala` — `MidiNrpn`                  | 2    |
| `ScMidiReceiver.scala` — `ScMidiReceiver`               | `MidiReceiver.scala` — `MidiReceiver`                  | 3    |
| `ScMidiChannelStateTracker.scala` — `ScMidiChannelStateTracker` | `MidiChannelStateTracker.scala` — `MidiChannelStateTracker` | 3 |
| `ScMidiChannelStateTrackerTest.scala`                   | `MidiChannelStateTrackerTest.scala`                    | 3    |
| `ScMidiKeySignatureMode` (enum, inside the message file) | `MidiKeySignatureMode`                                | 3    |

Files that reference these names (all updated by the substitutions; listed so the executor can check the diff):

- `sc-midi/src/main`: `message/JavaMidiConverters.scala`, `message/ScMidiCc.scala`, `message/ScMidiMessage.scala`,
  `message/ScMidiNrpn.scala`, `message/ScMidiRpn.scala`, `MidiProcessor.scala`, `PitchBendSensitivity.scala`,
  `RpnMessages.scala`, `RpnSelector.scala`, `ScMidiChannelStateTracker.scala`, `ScMidiReceiver.scala`.
- `sc-midi/src/test`: `message/JavaMidiConvertersTest.scala`, `message/ScMidiMessageTest.scala`,
  `MidiProcessorTest.scala`, `MidiSerialProcessorTest.scala`, `MidiSplitterTest.scala`, `PitchBendSensitivityTest.scala`,
  `RpnMessagesTest.scala`, `ScMidiChannelStateTrackerTest.scala`.
- `tuner/src/main`: `MonophonicPitchBendTuner.scala`, `MpeExpression.scala`, `MpeMessageRouting.scala`,
  `MpeTuner.scala`, `MtsMessageGenerator.scala`, `PedalTuningChanger.scala`.
- `tuner/src/test`: `MonophonicPitchBendTunerTest.scala`, `MpeMessageRoutingTest.scala`, `MpeTunerTest.scala`,
  `MtsTunerTest.scala`, `PedalTuningChangerTest.scala`, `TunerProcessorTest.scala`, `TuningChangeProcessorTest.scala`,
  `TuningChangeTriggersTest.scala`.
- `format/src/main`: `JsonTuningChangerPluginFormat.scala`.
- `docs/architecture/sc-midi/README.md`.

### Moves and the new package (Tasks 4–6)

| Item                                                            | From                                                    | To                                                                  | Task |
|-----------------------------------------------------------------|---------------------------------------------------------|---------------------------------------------------------------------|------|
| `JavaMidiConverters` (object, unchanged body)                   | `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/JavaMidiConverters.scala` | `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala` | 4 |
| `JavaMidiConvertersTest`                                        | `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/JavaMidiConvertersTest.scala` | `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala` | 4 |
| `isInputDevice(device)` / `isOutputDevice(device)`              | `package object scmidi` (`package.scala`)               | extension methods on `MidiDevice` inside `JavaMidiConverters`       | 5    |
| `mapShortMessageChannel` (both overloads)                       | `package object scmidi`                                 | deleted; `ChannelMidiMsg.mapChannel` covers it                      | 6    |

The two helpers go *into* `JavaMidiConverters` rather than into a `javamidi` package object because D8 later says
"the two `MidiDevice` capability helpers moved by #279 are deleted here [in `JavaMidiConverters`]"; putting them there
now makes that deletion a local edit. After Task 6, `package.scala` imports nothing from `javax.sound.midi`.

### Hierarchy (Task 7)

`message/MidiMsg.scala` gains `sealed trait Midi1Msg extends MidiMsg` and `sealed trait Midi2Msg extends MidiMsg`
directly under `sealed trait MidiMsg`; the six direct subtypes of `MidiMsg` (`ChannelMidiMsg`, `SysCommonMidiMsg`,
`SysRealTimeMidiMsg`, `MetaMidiMsg`, `SysExMidiMsg`, `UnsupportedMidiMsg`) re-parent to `Midi1Msg`.
`javamidi/JavaMidiConverters.scala` defines `asJava` on `Midi1Msg`.

### Documentation (Task 8)

- `docs/development/coding-conventions.md`: new section recording the `Msg` naming rule.
- `docs/architecture/sc-midi/README.md`: new names, the `javamidi` package, the hierarchy, the deleted helpers.

---

### Task 1: `ScMidiMessage` → `MidiMsg` (uniform substitution)

**Files:**
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiMessage.scala` →
  `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala`
- Rename: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/ScMidiMessageTest.scala` →
  `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala`
- Modify: every file in the "Renames" list above that contains the substring `ScMidiMessage` (36 Scala files plus the
  README).

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed trait MidiMsg`; `sealed abstract class ChannelMidiMsg(val channel: Int)` with
  `def mapChannel(map: Int => Int): ChannelMidiMsg`; `sealed trait SysCommonMidiMsg`, `SysRealTimeMidiMsg`,
  `MetaMidiMsg`; case classes `NoteOnMidiMsg(channel, midiNote, velocity)`, `NoteOffMidiMsg(...)`,
  `PolyPressureMidiMsg(channel, midiNote, value)`, `CcMidiMsg(channel, number, value)`,
  `ProgramChangeMidiMsg(channel, program)`, `ChannelPressureMidiMsg(channel, value)`,
  `PitchBendMidiMsg(channel, value)`, `MidiTimeCodeMidiMsg`, `SongPositionPointerMidiMsg`, `SongSelectMidiMsg`,
  `SysExMidiMsg(data: ArraySeq[Byte])`, `UnsupportedMidiMsg(data: ArraySeq[Byte])`, all `<X>MetaMidiMsg` meta
  events, and case objects `TuneRequestMidiMsg`, `TimingClockMidiMsg`, `StartMidiMsg`, `ContinueMidiMsg`,
  `StopMidiMsg`, `ActiveSensingMidiMsg`, `SystemResetMidiMsg`, `EndOfTrackMetaMidiMsg`. Companion objects keep
  their members (`NoteOnMidiMsg.DefaultVelocity`, `PitchBendMidiMsg.fromCents`, `<X>MetaMidiMsg.MetaType`, …).
  Later tasks use exactly these names.

- [ ] **Step 1: Rename the two files with git**

```bash
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiMessage.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala
git mv sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/ScMidiMessageTest.scala \
       sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala
```

- [ ] **Step 2: Substitute the substring `ScMidiMessage` by `MidiMsg` everywhere it appears**

This is the one uniform substitution D2 mandates: it turns `ScMidiMessage` into `MidiMsg`, `NoteOnScMidiMessage` into
`NoteOnMidiMsg`, `ChannelScMidiMessage` into `ChannelMidiMsg`, `ScMidiMessageTest` into `MidiMsgTest`, the
`behavior of "NoteOnScMidiMessage"` strings into `"NoteOnMidiMsg"`, and the ScalaDoc prose along with them.

```bash
grep -rl --include='*.scala' 'ScMidiMessage' sc-midi/src tuner/src format/src \
  | xargs perl -pi -e 's/ScMidiMessage/MidiMsg/g'
perl -pi -e 's/ScMidiMessage/MidiMsg/g' docs/architecture/sc-midi/README.md
```

- [ ] **Step 3: Restore the full word on the test helper (naming rule: `Msg` is for types only)**

The substitution turned the `MpeTunerTest` helper `extractScMidiMessages` into `extractMidiMsgs`. Helpers keep the
full word:

```bash
perl -pi -e 's/extractMidiMsgs/extractMidiMessages/g' \
  tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MpeTunerTest.scala
```

- [ ] **Step 4: Verify nothing was missed and nothing outside scope was touched**

```bash
grep -rn 'ScMidiMessage\|MidiMsgs\b' --include='*.scala' --include='*.md' sc-midi tuner format docs
git status --short
```

Expected: the first command prints nothing. `git status` lists only the two renames and files under `sc-midi/src`,
`tuner/src`, `format/src` and `docs/architecture/sc-midi/README.md`.

- [ ] **Step 5: Compile the whole project**

Run: `mcp__metals__compile-full`
Expected: success, zero errors. If a "not found" error remains, it names a reference the grep in Step 4 could not see
(e.g. a string built at runtime); fix it by hand and recompile.

- [ ] **Step 6: Run the three touched modules' suites**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green, same test counts as before the rename.

- [ ] **Step 7: Commit**

```bash
git add -A sc-midi/src tuner/src format/src docs/architecture/sc-midi/README.md
git commit -m "[#278/#279] Rename the message model: ScMidiMessage becomes MidiMsg"
```

---

### Task 2: `ScMidiCc` / `ScMidiRpn` / `ScMidiNrpn` → `MidiCc` / `MidiRpn` / `MidiNrpn`

**Files:**
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiCc.scala` → `.../message/MidiCc.scala`
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiRpn.scala` → `.../message/MidiRpn.scala`
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/ScMidiNrpn.scala` → `.../message/MidiNrpn.scala`
- Modify: every file referencing the three objects (`RpnMessages.scala`, `RpnSelector.scala`, `PitchBendSensitivity.scala`,
  `MidiChannelStateTracker` (still named `ScMidiChannelStateTracker.scala` at this point), `MidiMsg.scala` ScalaDoc,
  the `tuner` tuners and routing, `JsonTuningChangerPluginFormat.scala`, and their tests).

**Interfaces:**
- Consumes: Task 1's names.
- Produces: `object MidiCc` (e.g. `MidiCc.SustainPedal`, `MidiCc.DataEntryMsb`, `MidiCc.MpeSlide`), `object MidiRpn`,
  `object MidiNrpn`, with every member unchanged.

- [ ] **Step 1: Rename the three files**

```bash
m=sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message
git mv "$m/ScMidiCc.scala" "$m/MidiCc.scala"
git mv "$m/ScMidiRpn.scala" "$m/MidiRpn.scala"
git mv "$m/ScMidiNrpn.scala" "$m/MidiNrpn.scala"
```

- [ ] **Step 2: Substitute the three identifiers (word-bounded, so `ScMidiCc` never matches inside another name)**

```bash
grep -rlE --include='*.scala' '\bScMidi(Cc|Rpn|Nrpn)\b' sc-midi/src tuner/src format/src \
  | xargs perl -pi -e 's/\bScMidiCc\b/MidiCc/g; s/\bScMidiRpn\b/MidiRpn/g; s/\bScMidiNrpn\b/MidiNrpn/g'
perl -pi -e 's/\bScMidiCc\b/MidiCc/g; s/\bScMidiRpn\b/MidiRpn/g; s/\bScMidiNrpn\b/MidiNrpn/g' \
  docs/architecture/sc-midi/README.md
```

- [ ] **Step 3: Verify**

```bash
grep -rnE 'ScMidi(Cc|Rpn|Nrpn)\b' --include='*.scala' --include='*.md' sc-midi tuner format docs
```

Expected: no output.

- [ ] **Step 4: Compile the whole project**

Run: `mcp__metals__compile-full`
Expected: success, zero errors.

- [ ] **Step 5: Run the three touched modules' suites**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add -A sc-midi/src tuner/src format/src docs/architecture/sc-midi/README.md
git commit -m "[#278/#279] Rename ScMidiCc, ScMidiRpn and ScMidiNrpn to MidiCc, MidiRpn and MidiNrpn"
```

---

### Task 3: `ScMidiReceiver`, `ScMidiChannelStateTracker`, `ScMidiKeySignatureMode` and the final `ScMidi` sweep

**Files:**
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiReceiver.scala` → `.../scmidi/MidiReceiver.scala`
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala` →
  `.../scmidi/MidiChannelStateTracker.scala`
- Rename: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala` →
  `.../scmidi/MidiChannelStateTrackerTest.scala`
- Modify: `MidiMsg.scala` (the `ScMidiKeySignatureMode` enum), `JavaMidiConverters.scala`, `JavaMidiConvertersTest.scala`,
  `MidiMsgTest.scala`, `MpeMessageRouting.scala`, `MonophonicPitchBendTuner.scala`, `MpeTuner.scala`, their tests, the
  README.

**Interfaces:**
- Consumes: Tasks 1–2 names.
- Produces: `trait MidiReceiver extends AutoCloseable { def send(message: MidiMsg, timeStamp: Long = -1L): Unit; def close(): Unit }`;
  `class MidiChannelStateTracker(ccDefaults, rpnDefaults, nrpnDefaults, shallRespondToResetMessages) extends MidiReceiver`
  with companion `MidiChannelStateTracker.DefaultCcValues` etc.; `enum MidiKeySignatureMode { case Major, Minor }`.

- [ ] **Step 1: Rename the three files**

```bash
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiReceiver.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiReceiver.scala
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTracker.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiChannelStateTracker.scala
git mv sc-midi/src/test/scala/org/calinburloiu/music/scmidi/ScMidiChannelStateTrackerTest.scala \
       sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiChannelStateTrackerTest.scala
```

- [ ] **Step 2: Substitute the three identifiers**

`ScMidiChannelStateTrackerTest` is covered by the `ScMidiChannelStateTracker` substitution because the pattern is a
prefix match with `\b` only on the left.

```bash
grep -rlE --include='*.scala' 'ScMidiReceiver|ScMidiChannelStateTracker|ScMidiKeySignatureMode' \
    sc-midi/src tuner/src format/src \
  | xargs perl -pi -e 's/\bScMidiReceiver\b/MidiReceiver/g; s/\bScMidiChannelStateTracker/MidiChannelStateTracker/g; s/\bScMidiKeySignatureMode\b/MidiKeySignatureMode/g'
perl -pi -e 's/\bScMidiReceiver\b/MidiReceiver/g; s/\bScMidiChannelStateTracker/MidiChannelStateTracker/g; s/\bScMidiKeySignatureMode\b/MidiKeySignatureMode/g' \
  docs/architecture/sc-midi/README.md
```

- [ ] **Step 3: Sweep for any remaining `ScMidi` in code and architecture docs**

```bash
grep -rn 'ScMidi' --include='*.scala' --include='*.sbt' --include='*.md' \
  sc-midi tuner format app cli ui composition common config businessync intonation experiments docs build.sbt
```

Expected: no output. (`CONTRIBUTING.md` and `.claude/skills/contributing/SKILL.md` are deliberately excluded — see
"Out of scope".) Any hit is a name the three rename tasks missed: rename it by hand following the same rule (`Sc`
dropped; `MidiMsg` suffix only for message types).

- [ ] **Step 4: Compile the whole project**

Run: `mcp__metals__compile-full`
Expected: success, zero errors.

- [ ] **Step 5: Run the three touched modules' suites**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add -A sc-midi/src tuner/src format/src docs/architecture/sc-midi/README.md
git commit -m "[#278/#279] Drop the Sc prefix from MidiReceiver, MidiChannelStateTracker and MidiKeySignatureMode"
```

---

### Task 4: Move `JavaMidiConverters` into the new `javamidi` package

**Files:**
- Rename: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/JavaMidiConverters.scala` →
  `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala`
- Rename: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/JavaMidiConvertersTest.scala` →
  `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`
- Modify: the 20 files that `import org.calinburloiu.music.scmidi.message.JavaMidiConverters.*` — in `sc-midi`:
  `MidiProcessor.scala`, `PitchBendSensitivity.scala`, `MidiProcessorTest.scala`, `MidiSerialProcessorTest.scala`,
  `MidiSplitterTest.scala`, `PitchBendSensitivityTest.scala`; in `tuner`: `MpeTuner.scala`,
  `MonophonicPitchBendTuner.scala`, `PedalTuningChanger.scala`, `MtsTuner.scala`, `MonophonicPitchBendTunerTest.scala`,
  `TuningChangeProcessorTest.scala`, `TunerProcessorTest.scala`, `MpeTunerTest.scala`, `MtsMessageGeneratorTest.scala`,
  `MtsTunerTest.scala`, `PedalTuningChangerTest.scala`.
- Modify: `MidiMsg.scala` (two ScalaDoc links to `[[JavaMidiConverters]]`).

**Interfaces:**
- Consumes: Task 1–3 names.
- Produces: `object org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters` with the same `asJava` / `asScala`
  extensions; the package `org.calinburloiu.music.scmidi.javamidi` exists from here on. Import path for every user:
  `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*`.

- [ ] **Step 1: Move the two files**

```bash
mkdir -p sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi \
         sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi
git mv sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/JavaMidiConverters.scala \
       sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala
git mv sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/JavaMidiConvertersTest.scala \
       sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala
```

- [ ] **Step 2: Fix the package clause, the imports and the visibility modifiers of the moved files**

In `javamidi/JavaMidiConverters.scala`, replace the header (currently lines 17–22):

```scala
package org.calinburloiu.music.scmidi.javamidi

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.message.*

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq
```

and, in the same file, the two package-private helpers `bigEndian` and `fromBigEndian` (around lines 296 and 306)
change from `private[message]` to `private[javamidi]`. Update the usage example in the object's ScalaDoc to the new
import path:

```scala
 *   import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
```

In `javamidi/JavaMidiConvertersTest.scala`, replace the header (currently lines 17–26):

```scala
package org.calinburloiu.music.scmidi.javamidi

import org.calinburloiu.music.scmidi.MidiNote
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import javax.sound.midi.{MetaMessage, MidiMessage, ShortMessage, SysexMessage}
import scala.collection.immutable.ArraySeq
```

- [ ] **Step 3: Repoint every importer**

```bash
grep -rl --include='*.scala' 'scmidi.message.JavaMidiConverters' sc-midi/src tuner/src \
  | xargs perl -pi -e 's/scmidi\.message\.JavaMidiConverters/scmidi.javamidi.JavaMidiConverters/g'
```

- [ ] **Step 4: Fix the two ScalaDoc links in `MidiMsg.scala`**

`MidiMsg.scala` links `[[JavaMidiConverters]]` twice (in the `MidiMsg` ScalaDoc near line 30 and in the
`UnsupportedMidiMsg` ScalaDoc near line 627). The object is no longer in the same package, so make both links fully
qualified:

```scala
 * Use [[org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters]] to convert between `MidiMsg` and
 * [[javax.sound.midi.MidiMessage]].
```

```scala
 * [[org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters]] can reconstruct the original Java message (a
 * `ShortMessage`, `SysexMessage`, or `MetaMessage`, detected from the status byte) via `asJava`.
```

- [ ] **Step 5: Verify no stale reference to the old package remains**

```bash
grep -rn 'message.JavaMidiConverters\|package org.calinburloiu.music.scmidi.message' \
  sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi \
  sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi tuner/src docs/architecture/sc-midi/README.md
```

Expected: no output.

- [ ] **Step 6: Compile the whole project**

Run: `mcp__metals__compile-full`
Expected: success, zero errors. (If Metals does not pick up the new directory, run `mcp__metals__import-build` once,
then compile again.)

- [ ] **Step 7: Run the `sc-midi` and `tuner` suites**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green; `org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest` appears in the `sc-midi` run.

- [ ] **Step 8: Commit**

```bash
git add -A sc-midi/src tuner/src
git commit -m "[#278/#279] Move JavaMidiConverters into the scmidi.javamidi package"
```

---

### Task 5: Move `isInputDevice` / `isOutputDevice` into `javamidi` as `MidiDevice` extensions (TDD)

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala` (new extension)
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/package.scala:84-92` (delete the two helpers and the
  `MidiDevice` import)
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala:22,113,121,278` (call sites)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`

**Interfaces:**
- Consumes: `JavaMidiConverters` in `javamidi` (Task 4).
- Produces: inside `object JavaMidiConverters`,
  `extension (device: MidiDevice) { def isInputDevice: Boolean; def isOutputDevice: Boolean }`, available wherever
  `JavaMidiConverters.*` is imported. The package object no longer defines `isInputDevice` / `isOutputDevice`.

- [ ] **Step 1: Write the failing tests**

Add `MockFactory` to the test class and a new `behavior of` block at the end of
`javamidi/JavaMidiConvertersTest.scala`. ScalaMock's Scala 3 syntax for a no-argument Java method is
`(() => stub.method).when().returns(value)` (the form `TunerProcessorTest` uses).

```scala
import org.scalamock.scalatest.MockFactory

import javax.sound.midi.MidiDevice
```

```scala
class JavaMidiConvertersTest extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers with MockFactory {
```

```scala
  behavior of "JavaMidiConverters.isInputDevice"

  it should "be true for devices with unlimited or positive maximum transmitters and false otherwise" in {
    // Given
    val cases = Table(
      ("maxTransmitters", "expected"),
      (-1, true),
      (0, false),
      (1, true),
      (8, true)
    )

    forAll(cases) { (maxTransmitters, expected) =>
      val device = stub[MidiDevice]
      (() => device.getMaxTransmitters).when().returns(maxTransmitters)

      // When / Then
      device.isInputDevice shouldBe expected
    }
  }

  behavior of "JavaMidiConverters.isOutputDevice"

  it should "be true for devices with unlimited or positive maximum receivers and false otherwise" in {
    // Given
    val cases = Table(
      ("maxReceivers", "expected"),
      (-1, true),
      (0, false),
      (1, true),
      (8, true)
    )

    forAll(cases) { (maxReceivers, expected) =>
      val device = stub[MidiDevice]
      (() => device.getMaxReceivers).when().returns(maxReceivers)

      // When / Then
      device.isOutputDevice shouldBe expected
    }
  }
```

- [ ] **Step 2: Add the thinnest stub so the tests compile, then run them to see them fail for the right reason**

In `object JavaMidiConverters`, right after the `asScala` extension block, add:

```scala
  extension (device: MidiDevice) {
    def isInputDevice: Boolean = ???

    def isOutputDevice: Boolean = ???
  }
```

and add `MidiDevice` to the `javax.sound.midi` import of that file:

```scala
import javax.sound.midi.{MetaMessage, MidiDevice, MidiMessage, ShortMessage, SysexMessage}
```

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: the two new tests FAIL with `scala.NotImplementedError: an implementation is missing`; every other test in the
class passes.

- [ ] **Step 3: Implement the extensions (the bodies move verbatim from `package.scala`)**

```scala
  extension (device: MidiDevice) {
    /**
     * Tells whether this Java Sound device can be used as an input, that is, whether it can open at least one
     * `Transmitter`. Java Sound encodes "unlimited" as `-1`.
     */
    def isInputDevice: Boolean = {
      val maxTransmitters = device.getMaxTransmitters
      maxTransmitters == -1 /* unlimited */ || maxTransmitters > 0
    }

    /**
     * Tells whether this Java Sound device can be used as an output, that is, whether it can open at least one
     * `Receiver`. Java Sound encodes "unlimited" as `-1`.
     */
    def isOutputDevice: Boolean = {
      val maxReceivers = device.getMaxReceivers
      maxReceivers == -1 /* unlimited */ || maxReceivers > 0
    }
  }
```

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 4: Delete the package-object helpers and repoint `MidiDeviceHandle`**

In `package.scala`, delete the two methods `isInputDevice(device: MidiDevice)` and `isOutputDevice(device: MidiDevice)`
(lines 84–92) and drop `MidiDevice` from the `javax.sound.midi` import on line 19, which becomes:

```scala
import javax.sound.midi.{MidiMessage, ShortMessage}
```

In `MidiDeviceHandle.scala`:

- line 22: replace `import org.calinburloiu.music.scmidi` with
  `import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*`;
- line 113: `def isInputDevice: Boolean = _device.exists(_.isInputDevice)`;
- line 121: `def isOutputDevice: Boolean = _device.exists(_.isOutputDevice)`;
- line 278: `if (dev.isInputDevice) {`.

- [ ] **Step 5: Verify and compile**

```bash
grep -rn 'scmidi.isInputDevice\|scmidi.isOutputDevice\|def isInputDevice(device\|def isOutputDevice(device' sc-midi/src
```

Expected: no output.

Run: `mcp__metals__compile-full`
Expected: success, zero errors.

- [ ] **Step 6: Run the `sc-midi` suite**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add -A sc-midi/src
git commit -m "[#278/#279] Move the MidiDevice capability helpers into JavaMidiConverters as extensions"
```

---

### Task 6: Delete `mapShortMessageChannel`; `MonophonicPitchBendTuner` forwards through `ChannelMidiMsg.mapChannel` (TDD)

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/package.scala:19,94-102` (delete both overloads and the
  now-unused `javax.sound.midi` import)
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala:23,91-95`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTunerTest.scala` (the
  `behavior of "MonophonicPitchBendTuner when non-tuning-related MIDI messages are received"` block, near line 490)

**Interfaces:**
- Consumes: `ChannelMidiMsg.mapChannel(map: Int => Int): ChannelMidiMsg` (Task 1), `asJava` (Task 4).
- Produces: `package.scala` free of any `javax.sound.midi` import. `MonophonicPitchBendTuner.process` forwards
  channel messages on `outputChannel` and every other message unchanged.

**Why a test.** Today the helper rebuilds any `ShortMessage` through `ShortMessage(command, channel, data1, data2)`.
For a System Common or System Real-Time message that path is wrong (the "command" is `0xF0`, outside the `0x80`–`0xEF`
range Java accepts), so forwarding such a message through the tuner's fall-through branch is not exercised by any
current test. `ChannelMidiMsg.mapChannel` only applies to channel messages, which is the intended behaviour; the new
test pins it.

- [ ] **Step 1: Write the failing test**

Add to the `"MonophonicPitchBendTuner when non-tuning-related MIDI messages are received"` block, right after the
"forward modulation CC message on the correct channel" case:

```scala
  it should "forward a System Real-Time message unchanged" in new Fixture {
    // Given
    tuner.tune(customTuning2)

    // When
    output ++= tuner.process(TimingClockMidiMsg.asJava)

    // Then
    output.map(_.asScala) shouldEqual Seq(TimingClockMidiMsg)
  }
```

- [ ] **Step 2: Run the test to verify it fails for the right reason**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MonophonicPitchBendTunerTest -- -oNCXEHLOPQRMWS"`
Expected: the new case FAILS with an exception raised while the helper rebuilds the message
(`javax.sound.midi.InvalidMidiDataException: command out of range: 0xF0`, thrown by `ShortMessage`). Every other case
passes. The test compiles, so the failure is behavioural, not a compile error.

- [ ] **Step 3: Replace the helper call in `MonophonicPitchBendTuner.process`**

Change the beginning of `process` (lines 91–95) so `scMessage` is computed first and the forwarder maps the channel on
the typed message:

```scala
  override def process(message: MidiMessage): Seq[MidiMessage] = {
    val buffer = mutable.Buffer[MidiMessage]()
    val scMessage = message.asScala
    val forwardMessage = () => scMessage match {
      case channelMessage: ChannelMidiMsg => channelMessage.mapChannel(_ => outputChannel).asJava
      case _ => message
    }
```

and trim the import on line 23 to:

```scala
import org.calinburloiu.music.scmidi.{MidiChannelStateTracker, clampValue}
```

- [ ] **Step 4: Run the test class to verify it passes**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.MonophonicPitchBendTunerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS, including the "forward modulation CC message on the correct channel" case, which proves channel
messages still land on `outputChannel`.

- [ ] **Step 5: Delete the two overloads from the package object**

In `package.scala`, delete `mapShortMessageChannel(shortMessage: ShortMessage, map)` and
`mapShortMessageChannel(message: MidiMessage, map)` (lines 94–102) and delete the now-unused import on line 19
(`import javax.sound.midi.{MidiMessage, ShortMessage}`). The package object then imports only
`scala.language.implicitConversions` and `MidiRequirements`.

- [ ] **Step 6: Verify, compile and run both suites**

```bash
grep -rn 'mapShortMessageChannel\|javax.sound.midi' sc-midi/src/main/scala/org/calinburloiu/music/scmidi/package.scala tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MonophonicPitchBendTuner.scala
```

Expected: only the `import javax.sound.midi.{MidiMessage, ShortMessage}` line of `MonophonicPitchBendTuner.scala`
(still needed for its Java-typed `process` until #281); nothing from `package.scala`.

Run: `mcp__metals__compile-full` — expected success, zero errors.

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add -A sc-midi/src tuner/src
git commit -m "[#278/#279] Delete mapShortMessageChannel; forward through ChannelMidiMsg.mapChannel"
```

---

### Task 7: `Midi1Msg` / `Midi2Msg` under `MidiMsg`; `asJava` defined on `Midi1Msg` only (TDD)

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala:32-60,376,632`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConverters.scala:47-56,86-88`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala:70` (the one `MidiMsg`-typed
  value converted with `asJava`)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/message/MidiMsgTest.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiConvertersTest.scala`

**Interfaces:**
- Consumes: every name from Tasks 1–4.
- Produces:

```scala
sealed trait MidiMsg
sealed trait Midi1Msg extends MidiMsg
sealed trait Midi2Msg extends MidiMsg   // no members yet
```

  with `ChannelMidiMsg`, `SysCommonMidiMsg`, `SysRealTimeMidiMsg`, `MetaMidiMsg`, `SysExMidiMsg` and
  `UnsupportedMidiMsg` extending `Midi1Msg`; in `JavaMidiConverters`, `extension (message: Midi1Msg) { def asJava: MidiMessage }`
  and `asScala: MidiMsg` unchanged. `MidiProcessor.Receiver.send(scMessage: Midi1Msg, timeStamp: Long)`.

- [ ] **Step 1: Write the failing hierarchy tests in `MidiMsgTest`**

`scala.compiletime.testing.typeChecks` takes a string literal and reports, at runtime, whether that code type-checks in
the current scope — so each fact below is a runtime assertion, not a compile error. Add the import and a new block at
the end of the class:

```scala
import scala.collection.immutable.ArraySeq
import scala.compiletime.testing.typeChecks
```

```scala
  behavior of "MidiMsg hierarchy"

  it should "place every MIDI 1.0 message family under Midi1Msg" in {
    // When / Then
    typeChecks("val m: Midi1Msg = NoteOnMidiMsg(0, MidiNote(60))") shouldBe true // Channel Voice
    typeChecks("val m: Midi1Msg = TuneRequestMidiMsg") shouldBe true // System Common
    typeChecks("val m: Midi1Msg = TimingClockMidiMsg") shouldBe true // System Real-Time
    typeChecks("val m: Midi1Msg = SysExMidiMsg(ArraySeq.empty[Byte])") shouldBe true // System Exclusive
    typeChecks("val m: Midi1Msg = EndOfTrackMetaMidiMsg") shouldBe true // SMF meta
    typeChecks("val m: Midi1Msg = UnsupportedMidiMsg(ArraySeq.empty[Byte])") shouldBe true // fallback
  }

  it should "keep Midi1Msg and Midi2Msg as subtypes of MidiMsg" in {
    // When / Then
    typeChecks("summon[Midi1Msg <:< MidiMsg]") shouldBe true
    typeChecks("summon[Midi2Msg <:< MidiMsg]") shouldBe true
  }
```

- [ ] **Step 2: Write the failing converter test in `JavaMidiConvertersTest`**

```scala
import scala.compiletime.testing.typeChecks
```

```scala
  behavior of "JavaMidiConverters.asJava availability"

  it should "be defined for Midi1Msg but not for Midi2Msg" in {
    // When / Then
    typeChecks("(??? : Midi1Msg).asJava") shouldBe true
    typeChecks("(??? : Midi2Msg).asJava") shouldBe false
  }
```

- [ ] **Step 3: Add the thinnest stub so the tests compile, then run them to see them fail for the right reason**

In `MidiMsg.scala`, directly under `sealed trait MidiMsg` (line 32), add only the two empty traits:

```scala
sealed trait Midi1Msg extends MidiMsg

sealed trait Midi2Msg extends MidiMsg
```

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: "place every MIDI 1.0 message family under Midi1Msg" FAILS (`false was not true` on the first line: no
subtype is under `Midi1Msg` yet); "be defined for Midi1Msg but not for Midi2Msg" FAILS on its second line (`asJava`
is still defined on `MidiMsg`, so `Midi2Msg` has it too); "keep Midi1Msg and Midi2Msg as subtypes of MidiMsg" PASSES.
All pre-existing cases pass.

- [ ] **Step 4: Re-parent the six direct subtypes and document the traits**

In `MidiMsg.scala`, replace the top of the hierarchy (lines 24–60) with:

```scala
/**
 * Scala-idiomatic base trait of the immutable MIDI message model.
 *
 * Unlike Java's [[javax.sound.midi.MidiMessage]] (and its subclasses like [[javax.sound.midi.ShortMessage]]),
 * which expose raw byte data and mutable state, `MidiMsg` subtypes are immutable case classes with named,
 * validated parameters and Scala pattern matching support.
 *
 * The hierarchy is split by MIDI specification family: every MIDI 1.0 message (including the Standard MIDI File meta
 * events) is a [[Midi1Msg]]; [[Midi2Msg]] is reserved for MIDI 2.0 messages and has no members yet. Pipeline
 * signatures take `MidiMsg` so that they stay valid once MIDI 2.0 messages exist.
 *
 * Use [[org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters]] to convert between [[Midi1Msg]] and
 * [[javax.sound.midi.MidiMessage]].
 */
sealed trait MidiMsg

/**
 * Base trait of every message defined by the MIDI 1.0 specification family: Channel Voice and Channel Mode messages,
 * System Common, System Real-Time and System Exclusive messages, and the Standard MIDI File meta events.
 *
 * Only `Midi1Msg` values can be converted to [[javax.sound.midi.MidiMessage]], because Java Sound speaks MIDI 1.0 only.
 */
sealed trait Midi1Msg extends MidiMsg

/**
 * Base trait reserved for MIDI 2.0 messages. It has no members yet; a full MIDI 2.0 hierarchy is out of scope, see
 * [[https://github.com/calinburloiu/microtonalist/issues/283 #283]].
 */
sealed trait Midi2Msg extends MidiMsg

/**
 * Base class for all MIDI channel messages (both Voice and Mode). Subtypes carry a validated
 * `channel` field (0-15).
 *
 * @param channel The 0-indexed MIDI channel (0-15).
 */
sealed abstract class ChannelMidiMsg(val channel: Int) extends Midi1Msg {
  MidiRequirements.requireChannel(channel)

  /**
   * Returns a copy of this message with its `channel` rewritten by applying `map` to the current channel.
   *
   * The returned value has the same concrete subtype as `this`.
   *
   * @param map Function from the current channel (0-15) to the new channel (0-15).
   */
  def mapChannel(map: Int => Int): ChannelMidiMsg
}

/** Base trait for MIDI System Common messages. */
sealed trait SysCommonMidiMsg extends Midi1Msg

/** Base trait for MIDI System Real-Time messages. */
sealed trait SysRealTimeMidiMsg extends Midi1Msg

/** Base trait for Standard MIDI File (SMF) Meta messages. */
sealed trait MetaMidiMsg extends Midi1Msg
```

Then re-parent the two remaining direct subtypes further down the file:

```scala
case class SysExMidiMsg(data: ArraySeq[Byte]) extends Midi1Msg
```

```scala
case class UnsupportedMidiMsg(data: ArraySeq[Byte]) extends Midi1Msg
```

Verify with `grep -n 'extends MidiMsg' sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/MidiMsg.scala`:
expected exactly two hits, `Midi1Msg` and `Midi2Msg`.

- [ ] **Step 5: Narrow `asJava` to `Midi1Msg` in `JavaMidiConverters`**

Replace the outbound extension (lines 47–56) and the dispatch helper and map type (lines 86–89):

```scala
  extension (message: Midi1Msg) {
    /** Converts this [[Midi1Msg]] into the equivalent [[javax.sound.midi.MidiMessage]]. */
    def asJava: MidiMessage = {
      val builder = ToJavaMap.getOrElse(
        message.getClass,
        throw new IllegalStateException(s"No Java MIDI message builder registered for ${message.getClass}")
      )
      builder(message)
    }
  }
```

```scala
  private def entry[M <: Midi1Msg](cls: Class[M])(build: M => MidiMessage): (Class[?], Midi1Msg => MidiMessage) =
    (cls, (m: Midi1Msg) => build(m.asInstanceOf[M]))

  private val ToJavaMap: Map[Class[?], Midi1Msg => MidiMessage] = Map[Class[?], Midi1Msg => MidiMessage](
```

Update the object's ScalaDoc first line to "Bidirectional converters between [[Midi1Msg]] / [[MidiMsg]] and
[[javax.sound.midi.MidiMessage]] …" and its example to `val scala: MidiMsg = java.asScala`. `asScala` keeps returning
`MidiMsg`.

- [ ] **Step 6: Narrow the one `MidiMsg`-typed value that is converted with `asJava`**

`MidiProcessor.Receiver.send(scMessage: MidiMsg, timeStamp: Long)` (line 70 of `MidiProcessor.scala`) calls
`scMessage.asJava` and no longer compiles. This receiver is the Java-typed one that #281 replaces; narrow its
parameter for now:

```scala
    def send(scMessage: Midi1Msg, timeStamp: Long = -1L): this.type = {
      send(scMessage.asJava, timeStamp)
      this
    }
```

Run `mcp__metals__compile-full`. Every other `asJava` call site (`MpeTuner`, `MonophonicPitchBendTuner`, `MtsTuner`,
`PitchBendSensitivity`, the tests) converts a value whose static type is a concrete case class or `ChannelMidiMsg` /
`CcMidiMsg` / `SysExMidiMsg`, all under `Midi1Msg`, so they compile unchanged. If the compiler reports another
"value asJava is not a member of MidiMsg", the value is declared as `MidiMsg` somewhere it is only ever a MIDI 1.0
message: change that declaration to `Midi1Msg` and note the file in the commit message. Do **not** add a
`Midi2Msg` branch anywhere — dropping MIDI 2.0 messages at the device boundary is D7, sub-issue #281.

Expected: success, zero errors.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.message.MidiMsgTest org.calinburloiu.music.scmidi.javamidi.JavaMidiConvertersTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 8: Run the touched modules' suites**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green.

- [ ] **Step 9: Commit**

```bash
git add -A sc-midi/src tuner/src
git commit -m "[#278/#279] Add Midi1Msg and Midi2Msg under MidiMsg; asJava is defined on Midi1Msg only"
```

---

### Task 8: Documentation — the `Msg` naming rule and the `sc-midi` architecture document

**Files:**
- Modify: `docs/development/coding-conventions.md` (new section at the end)
- Modify: `docs/architecture/sc-midi/README.md`

**Interfaces:** none; prose only. Names must match Tasks 1–7 exactly.

- [ ] **Step 1: Record the naming rule in `coding-conventions.md`**

Append this section after "Class private internal backing variables for public getter / setter":

````markdown
## `Msg` is the suffix of MIDI message types only

The types of the `sc-midi` message model end in `Msg`: `MidiMsg` at the top, `Midi1Msg` / `Midi2Msg` beneath it, and
one `<Name>MidiMsg` per message (`NoteOnMidiMsg`, `CcMidiMsg`, `SysExMidiMsg`, `TextMetaMidiMsg`,
`UnsupportedMidiMsg`). The short suffix keeps them distinct from Java Sound's `MidiMessage` without import aliases
inside the Java implementation package.

Everything that is not a message type keeps the full word *message*: helper objects, methods, parameters, and prose
(`RpnMessages`, `PitchBendSensitivityMessages`, `MtsMessageGenerator`, `MidiRequirements`, `extractMidiMessages`).

Wrong:

```scala
object RpnMsgs
def extractMidiMsgs(output: Seq[MidiMessage]): Seq[MidiMsg]
case class NoteOnMidiMessage(channel: Int, midiNote: MidiNote, velocity: Int)
```

Correct:

```scala
object RpnMessages
def extractMidiMessages(output: Seq[MidiMessage]): Seq[MidiMsg]
case class NoteOnMidiMsg(channel: Int, midiNote: MidiNote, velocity: Int)
```
````

- [ ] **Step 2: Update the `sc-midi` architecture document**

Tasks 1–3 already substituted the type names in `docs/architecture/sc-midi/README.md`. Now make the prose match the
code after Tasks 4–7:

1. In "Responsibility", after the sentence starting "Package: `org.calinburloiu.music.scmidi`, with a `message`
   sub-package …", add: "A `javamidi` sub-package holds the code that touches Java Sound directly — today
   `JavaMidiConverters` and its `MidiDevice` capability extensions; the rest of the module is on its way to becoming a
   pure Scala API (see the `Architecture` milestone and #278)."
2. In "MIDI message model (`message` sub-package)", replace the first sentence with: "**`MidiMsg`** is the sealed base
   of the immutable message model — the Scala-idiomatic counterpart to Java's mutable, byte-oriented
   `MidiMessage`/`ShortMessage`. Directly under it sit **`Midi1Msg`**, the base of every MIDI 1.0 message, and
   **`Midi2Msg`**, reserved for MIDI 2.0 and empty for now (#283). Pipeline signatures take `MidiMsg`."; keep the rest of
   the paragraph, which already reads in terms of `ChannelMidiMsg`, `SysExMidiMsg`, `UnsupportedMidiMsg`, and
   `PitchBendMidiMsg`.
3. In the `JavaMidiConverters` paragraph, say it lives in the `javamidi` sub-package
   (`import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*`), that `asJava` is defined on `Midi1Msg`
   only, so converting a future MIDI 2.0 message is a compile-time error, and that it also carries the
   `isInputDevice` / `isOutputDevice` extensions on `javax.sound.midi.MidiDevice`.
4. In "MIDI domain helpers", the sentence "Smaller helpers (`isInputDevice`/`isOutputDevice`, `mapShortMessageChannel`,
   clamping) round out the package object." becomes "The package object also carries the `clampValue` helpers; channel
   rewriting is `ChannelMidiMsg.mapChannel`."
5. In "Message conversion model", refer to `MidiMsg` / `Midi1Msg` and `javamidi` consistently with the above.
6. In "Notes / subject to change", add: "- The `Sc` prefix is gone (#279); #280–#282 continue the isolation of the Java
   Sound implementation under `javamidi` — see `issues/00278-isolate-java-midi/`."

- [ ] **Step 3: Check that no stale name survives in the two documents**

```bash
grep -n 'ScMidi\|mapShortMessageChannel\|message.JavaMidiConverters' docs/development/coding-conventions.md docs/architecture/sc-midi/README.md
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/development/coding-conventions.md docs/architecture/sc-midi/README.md
git commit -m "[#278/#279] Record the Msg naming rule and update the sc-midi architecture document"
```

---

### Task 9: Final checks and the pull request

**Files:** none new. This task runs the repository's mandatory end-of-work checks and opens the PR.

- [ ] **Step 1: Module tests**

```bash
sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
sbtn "format/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all green.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and check `sc-midi` and `tuner` against their floors (`sc-midi` 67 / 52,
`tuner` 80 / 80). The moved converters and the tracker keep their existing coverage; the new `isInputDevice` /
`isOutputDevice` extensions are fully covered by Task 5's table test; `MidiDeviceHandle` stays uncovered as before.
Expected: both modules at or above their floor. If `sc-midi` dropped, the cause is a test that no longer runs (a class
renamed without its file, or a test file left outside `src/test`) — fix the packaging, not the threshold.

- [ ] **Step 3: Full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: all green across every module (`app`, `cli`, `ui` and the rest compile against the new names through
`tuner`).

- [ ] **Step 4: Documentation and ScalaDoc review**

```bash
grep -rn 'ScMidi' --include='*.scala' --include='*.md' sc-midi tuner format app cli ui docs
grep -rn 'javax.sound.midi' sc-midi/src/main/scala/org/calinburloiu/music/scmidi/package.scala
```

Expected: no output from either. Then read the ScalaDoc of `MidiMsg`, `Midi1Msg`, `Midi2Msg`, `MidiReceiver`,
`MidiChannelStateTracker`, `JavaMidiConverters` (including the two new extensions) and confirm each public identifier
introduced or moved by this plan is documented and mentions no old name.

- [ ] **Step 5: Push and open the draft PR**

```bash
git push -u origin refactoring/279-drop-sc-prefix
.claude/skills/contributing/scripts/microtonalist-gh pr 278/279 \
  "Drop the Sc prefix from sc-midi and add the Midi1Msg/Midi2Msg hierarchy" \
  "Sub-issue #279 of #278. Implements decisions D2 and D3 of the design and the javamidi move the #279 row of Section 3 assigns to it, with no behaviour change: every Sc-prefixed sc-midi type is renamed (message types take the MidiMsg suffix), Midi1Msg and Midi2Msg sit under MidiMsg with asJava defined on Midi1Msg only, JavaMidiConverters and the MidiDevice capability helpers live in org.calinburloiu.music.scmidi.javamidi, mapShortMessageChannel is deleted in favour of ChannelMidiMsg.mapChannel, and the Msg naming rule is recorded in docs/development/coding-conventions.md. Design: issues/00278-isolate-java-midi/2026-09-07-isolate-java-midi-design.md. Plan: issues/00278-isolate-java-midi/2026-09-08-279-renames-and-hierarchy-plan.md."
```

Expected: a draft PR titled `[#278/#279] Drop the Sc prefix from sc-midi and add the Midi1Msg/Midi2Msg hierarchy`,
labelled `refactoring`, milestone `sc-midi`, resolving #279, added to the project. Report its URL.

---

## Self-review against the design

- **D2 renames**: `ScMidiReceiver` → `MidiReceiver`, `ScMidiChannelStateTracker` → `MidiChannelStateTracker`,
  `ScMidiKeySignatureMode` → `MidiKeySignatureMode` (Task 3); `ScMidiCc`/`ScMidiRpn`/`ScMidiNrpn` →
  `MidiCc`/`MidiRpn`/`MidiNrpn` (Task 2); the uniform `ScMidiMessage` → `MidiMsg` substitution across `sc-midi`,
  `tuner`, `format` and their tests (Task 1); the naming rule in `coding-conventions.md` (Task 8);
  `mapShortMessageChannel` deleted with `ChannelMidiMsg.mapChannel` covering it (Task 6). The `multiTransmitter` →
  `transmitter` accessor rename belongs to D4's replacement of `MultiTransmitter` (#280/#281), not here.
- **D3 hierarchy**: `Midi1Msg` / `Midi2Msg` with every existing subtype under `Midi1Msg`, `asJava` on `Midi1Msg` only,
  pipeline signatures left on the top type where they already are Scala-typed (`MidiReceiver.send`) (Task 7).
- **D1, #279's share**: `org.calinburloiu.music.scmidi.javamidi` created; `JavaMidiConverters` moved (Task 4); the
  `MidiDevice` helpers moved out of the package object (Task 5). `MidiRequirements` stays in `message`.
- **Section 3 row**: mechanical; every task ends green; no other decision touched.
- **Section 4 testing**: `JavaMidiConvertersTest` moved with the converters and remains the Java-boundary test; the
  design's other new tests belong to later sub-issues.
- **Section 5 documentation**: the `sc-midi` README updated for what changed here; the full rewrite around the
  API/implementation split is #282's, when the split is complete.

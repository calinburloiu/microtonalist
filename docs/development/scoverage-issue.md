# scoverage + Scala 3 multi-module coverage failures

This page describes a known, reproducible failure mode that affects `sbt`
coverage runs on this project, what causes it, and how it is now mitigated.
Original tracking issue: [#183]; threshold-configuration PR: [#184]. The
isolation fixes that actually eliminate the race are [#186] (Metals/BSP
server) and [#198] (scoverage-inspector skill).

If a coverage command fails with one of the symptoms listed below, treat it
as a manifestation of *this* bug rather than a regression in the code under
test. The bug lives in the interaction between `sbt-scoverage`, Scala 3's
TASTy emission, and a **second JVM concurrently writing to the same
`target/classes/` tree** — not in microtonalist sources.

## Stack

- Scala 3.6.x
- sbt 1.10.x
- sbt-scoverage 2.4.x
- Multi-project build with cross-module dependencies

Upstream issues hinting at the same category (none is an exact match, but they
confirm sbt-scoverage + Scala 3 has unresolved compile-time bugs when
instrumented output is read mid-write):

- [scoverage/sbt-scoverage#517](https://github.com/scoverage/sbt-scoverage/issues/517)
- [scoverage/sbt-scoverage#511](https://github.com/scoverage/sbt-scoverage/issues/511)
- [scoverage/sbt-scoverage#470](https://github.com/scoverage/sbt-scoverage/issues/470)
- [sbt/sbt#1673](https://github.com/sbt/sbt/issues/1673) /
  [scoverage/sbt-scoverage#108](https://github.com/scoverage/sbt-scoverage/issues/108)
  (related parallel-compile race)

## Root cause

The failure is a **concurrent-writer race** on the per-project
`target/scala-3.x/classes/` directory. While `sbt coverageAll` /
`sbt coverageModules …` is compiling and writing instrumented `.class` /
`.tasty` files, a **second JVM compiling the same sources without
instrumentation** writes uninstrumented artifacts into the same tree at the
same time. The two writers interleave, and a downstream module ends up
reading a half-written or mismatched view of an upstream `.tasty`:

- companion-only artifacts where the class itself is missing, or
- partially-written TASTy that the Scala 3 compiler cannot decode.

Two real-world triggers have been confirmed on this project:

1. **IntelliJ IDEA's file watcher / auto-build.** When the project is open
   in IntelliJ and a developer runs `sbt coverageAll` from a terminal,
   IntelliJ notices source/output churn and kicks off its own
   (non-instrumented) compile of the affected modules into the same
   `target/scala-3.x/classes/` tree. The two compilers race and the
   instrumented TASTy gets clobbered or read mid-write. This is the trigger
   originally misdiagnosed as a pure Scala 3 / sbt-scoverage bug in [#183].
2. **The Metals BSP server.** When `bin/microtonalist-dev-stack start` is
   running (sbt-as-BSP for Metals MCP) and a CLI `sbt coverageAll` runs
   without target isolation, Metals' own BSP-driven compile races the CLI
   coverage compile on the same `classes/` directory. Hit concretely on
   2026-04-30: `sbt "coverageModule tuner"` died with
   `error while loading TrackSpecs … TrackSpecs.tasty` while
   `logs/metals-standalone-client.log` showed Metals running its own
   `compiling app` BSP build at the same wall-clock second.

Per-module coverage in isolation (no IntelliJ, no BSP server, no second
sbt) works fine. The failure only surfaces when *two* JVMs touch the same
`target/` at the same time.

## Symptom shapes

All of these are the same bug:

1. `value <X> is not a member of object <Pkg>.<ClassName>` — companion-only
   view; the class itself is missing from the compiled artifact.
2. `object <X> in package <Pkg> does not take parameters` — same root cause;
   the compiler resolves the bare object and complains that you can't call it.
3. `error while loading <X>, .../X.tasty` — Zinc / Scala 3 reports the TASTy
   file is unreadable.
4. `Not found: type <X>` — the class is entirely missing from the classpath
   at the time the dependent module compiles.
5. `NoClassDefFoundError: <X>` at test runtime — same root cause but tripped
   in the test class loader.

Concrete examples that have been observed on this codebase:

- `config/MainConfigManager.tasty` loaded as object-only when `app/compile`
  read it (`defaultConfigFile is not a member of object`,
  `MainConfigManager does not take parameters`).
- `intonation/EdoInterval.tasty`, `intonation/RatioInterval.tasty` loaded with
  `error while loading` when `composition/compile` read them.
- `tuner/Tuning` not found from `tuner/Test/compile`.
- `intonation/Test/compileIncremental` failed on `EdoInterval(...)` with the
  companion-only view.
- `tuner/TrackSpecs.tasty` unreadable during `coverageModule tuner` while
  Metals was compiling `app` in parallel (the [#186] trigger).

Note that the failing class and the failing downstream module are arbitrary —
any pair can be hit on a given run. **The pattern, not the specific class
name, is what identifies the bug.**

## How to recognize it

Treat a coverage-run failure as this issue if **all** of the following hold:

- The failing command is `sbt coverageAll`, `sbt coverageModules …`, or
  `sbt coverageCheck` (anything that flips `coverageEnabled := true` across
  multiple modules).
- The error matches one of the symptom shapes above and points at a
  `.tasty`/companion-class problem on a class that compiles fine without
  coverage.
- `sbt compile` and `sbt test` (no coverage) succeed on the same checkout.
- Another JVM was touching the same `target/` tree during the run — most
  commonly IntelliJ IDEA with the project open and auto-build enabled, or
  the Metals BSP server (`bin/microtonalist-dev-stack status` says
  *running*) when the coverage command was launched **without** a target
  suffix.

If those conditions hold, **do not change production code** trying to fix
it. The fix is to eliminate the concurrent writer — see below.

## How it's mitigated now

The race is eliminated by giving each JVM its own `target/` subdirectory via
the `-Dmicrotonalist.build.targetSuffix=<suffix>` system property (see
`targetSuffixOverride` in `build.sbt`). Each suffix produces a separate
`<project>/target<suffix>/` tree, so concurrent compilers no longer write
to the same `classes/` directory.

Two isolations are wired in:

- **Metals BSP server — [#186].** `bin/microtonalist-dev-stack start`
  launches the BSP-server sbt with `-Dmicrotonalist.build.targetSuffix=-bsp`,
  so Metals compiles into `<project>/target-bsp/`. CLI `sbt` invocations
  without the property continue to use `<project>/target/`.
- **scoverage-inspector skill — [#198].** The skill's wrapper scripts
  (`.claude/skills/scoverage-inspector/scripts/run_coverage_all.sh` and
  `run_coverage_modules.sh`) pass
  `-Dmicrotonalist.build.targetSuffix=-scoverage`, so coverage runs from
  the skill land in `<project>/target-scoverage/` and cannot collide with
  either IntelliJ's `target/` or the BSP server's `target-bsp/`.

If you run `sbt coverageAll` or `sbt coverageModules …` **manually** from a
terminal (i.e. not through the skill), prefer the same isolation:

```bash
sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageAll
```

…such that IntelliJ's auto-build is **not** simultaneously rebuilding
the same modules.

For **non-coverage** sbt commands while the dev stack is up
(`bin/microtonalist-dev-stack status` reports *running*), prefer `sbtn`
over `sbt`. `sbtn` routes the command through the existing BSP-server sbt,
which writes to `<project>/target-bsp/` — so there is still only one
writer. Spawning a fresh `sbt` JVM instead writes to `<project>/target/`
in parallel with the BSP server and reintroduces exactly the race this
doc is about. (Coverage commands are the documented exception — they
don't work via `sbtn`, so use `sbt -Dmicrotonalist.build.targetSuffix=-scoverage`
as shown above.)

The `coverageAll` / `coverageModules` / `coverageCheck` commands in
[`project/Coverage.scala`](../../project/Coverage.scala) also bracket the
run with `clean` (`clean; coverage; test; coverageReport[; coverageAggregate]`)
so a stale non-instrumented cache from a previous build is never carried
over. This reduces — but on its own does not eliminate — the race; the
target-suffix isolation above is what actually fixes it.

## What does *not* help

These were tried during the original investigation and did not fix the bug
(they treat the symptom, not the concurrent-writer cause):

- `sbt clean` / `rm -rf target` between runs.
- `Test / classLoaderLayeringStrategy := Flat` (a separate runtime
  class-loader fix that was correctly removed).
- Disabling parallel test execution.
- Lowering compile-task parallelism alone.
- Retrying the command. (Warm runs often succeed by luck — the second JVM
  may not happen to recompile the same module at the same instant — but
  this is not a reliable mitigation.)

The reliable fix is target-directory isolation via
`-Dmicrotonalist.build.targetSuffix=<suffix>`, as wired into the BSP-server
launcher and the scoverage-inspector skill.

[#183]: https://github.com/calinburloiu/microtonalist/issues/183

[#184]: https://github.com/calinburloiu/microtonalist/pull/184

[#186]: https://github.com/calinburloiu/microtonalist/issues/186

[#198]: https://github.com/calinburloiu/microtonalist/issues/198

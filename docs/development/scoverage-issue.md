# scoverage + Scala 3 multi-module coverage failures

This page describes a known, reproducible failure mode that affects `sbt`
coverage runs on this project and how to recognize it. Tracking issue: [#183];
fix PR: [#184].

If a coverage command fails with one of the symptoms listed below, treat it as
a manifestation of *this* bug rather than a regression in the code under test.
The bug lives in the interaction between `sbt-scoverage`, the Scala 3 compiler,
and TASTy emission for instrumented classes — not in microtonalist sources.

## Stack

- Scala 3.6.x
- sbt 1.10.x
- sbt-scoverage 2.4.x
- Multi-project build with cross-module dependencies

Upstream issues hinting at the same category (none is an exact match, but they
confirm sbt-scoverage + Scala 3 has unresolved compile-time bugs in this area):

- [scoverage/sbt-scoverage#517](https://github.com/scoverage/sbt-scoverage/issues/517)
- [scoverage/sbt-scoverage#511](https://github.com/scoverage/sbt-scoverage/issues/511)
- [scoverage/sbt-scoverage#470](https://github.com/scoverage/sbt-scoverage/issues/470)
- [sbt/sbt#1673](https://github.com/sbt/sbt/issues/1673) /
  [scoverage/sbt-scoverage#108](https://github.com/scoverage/sbt-scoverage/issues/108)
  (related parallel-compile race)

## Root cause (working theory)

When `coverageEnabled := true` is set globally and many modules cross-compile
in the same invocation from a cold cache, a downstream module ends up
compiling against an upstream module's `.class`/`.tasty` files that contain
**only the companion object, without the case class / class apply method**.
The Scala 3 compiler reads the partially-written, instrumented TASTy of the
upstream module before it is complete, then emits or fails on a truncated
view of the type.

Per-module compilation in isolation works fine; the failure surfaces only when
the aggregate compile/test sequence happens with coverage instrumentation on.

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

Note that the failing class and the failing downstream module are arbitrary —
any pair can be hit on a given run. **The pattern, not the specific class
name, is what identifies the bug.**

## How to recognize it

Treat a coverage-run failure as this issue if **all** of the following hold:

- The failing command is `sbt coverageAll`, `sbt coverageModules ...`, or
  `sbt coverageCheck` (anything that flips `coverageEnabled := true` across
  multiple modules).
- The error matches one of the symptom shapes above and points at a
  `.tasty`/companion-class problem on a class that compiles fine without
  coverage.
- `sbt compile` and `sbt test` (no coverage) succeed on the same checkout.
- A second invocation of the same coverage command, or a single-module
  coverage run targeting only the failing module
  (`sbt "coverageModules <one-module>"`), succeeds.

If those conditions hold, **do not change production code** trying to fix it.
Re-run the command. The bug is non-deterministic on cold caches; warm runs
generally succeed.

## What's already done about it

The `coverageAll` / `coverageModules` / `coverageCheck` commands in
[`project/Coverage.scala`](../../project/Coverage.scala) implement a two-pass
build that is meant to side-step the bug:

1. `clean; compile` — populates `target/` with **non-instrumented**
   `.class`/`.tasty` files so a fully valid TASTy set exists on disk.
2. `set Global / concurrentRestrictions += Tags.limit(Tags.Compile, 1)` then
   `coverage; test; coverageReport[; coverageAggregate]` — recompiles with
   instrumentation, with compile-task parallelism serialized as
   belt-and-braces protection against sbt#1673 / sbt-scoverage#108.

This makes the failure rare in practice but does not fully eliminate it. When
you do hit it, recognize it for what it is and retry rather than chasing it
as a code defect.

## What does *not* help

These were tried during the original investigation and did not fix the bug:

- `sbt clean` / `rm -rf target` between runs.
- `Test / classLoaderLayeringStrategy := Flat` (a separate runtime
  class-loader fix that was correctly removed).
- Disabling parallel test execution.
- Lowering compile-task parallelism alone.

The only reliable mitigations are the two-pass workflow encoded in the
custom commands and, if it still fails, retrying.

[#183]: https://github.com/calinburloiu/microtonalist/issues/183

[#184]: https://github.com/calinburloiu/microtonalist/pull/184

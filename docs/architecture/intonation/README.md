# `intonation` module architecture

## Responsibility

The `intonation` module is the lowest-level domain module of Microtonalist. It provides the pure mathematics of
microtonal pitch: musical **intervals**, **scales** built from them, and the **intonation standards** that describe how
those intervals are expressed (cents, just-intonation ratios, or EDO divisions). It also exposes free functions for
converting between cents, frequency ratios, EDO counts, and frequencies in Hz.

Its role in the layered architecture:

- It sits at the bottom of the domain stack and has **no application dependencies** — it knows nothing about MIDI,
  tunings, compositions, file formats, the GUI, or threading. It is reusable, side-effect-free value math.
- Higher modules build on it: `composition` models a piece in terms of `Scale`s and `IntonationStandard`s, `format`
  (de)serializes scales, and `tuner` ultimately consumes the resulting cent offsets.

Everything here is expressed in **musical logarithmic space** (where an octave is a doubling of frequency and intervals
add/subtract rather than multiply/divide). The public API of `Interval` performs all arithmetic in that space;
frequency-ratio multiplication is an internal detail.

Package: `org.calinburloiu.music.intonation` (sub-packages `.intervals` and `.instruments`).

## Key types

### `Interval`

`Interval` is a `sealed trait` (extending `Ordered[Interval]`) modelling a single musical interval. It exposes
`realValue` (decimal frequency ratio) and `cents`, logarithmic-space arithmetic (`+`, `-`, `*(n)`, `invert`, `reverse`),
octave handling (`normalize` and friends, folding an interval into `[unison, octave)`), and converters to the concrete
subtypes. The four concrete subtypes form a closed set:

| Subtype | Representation | Use | `intonationStandard` |
| ------- | -------------- | --- | -------------------- |
| `RatioInterval` | Just-intonation fraction | Exact ratios; cannot express temperaments | `JustIntonationStandard` |
| `CentsInterval` | Decimal cents | Approximates anything; allows fractions of a cent | `CentsIntonationStandard` |
| `EdoInterval` | `count` steps of an EDO | Exact within an EDO; integer steps only | `EdoIntonationStandard(edo)` |
| `RealInterval` | Generic decimal ratio | Most generic fallback; no JI/EDO precision | `CentsIntonationStandard` |

Mixed-type arithmetic follows fixed promotion rules documented on the trait: anything combined with a `CentsInterval`
yields a `CentsInterval`; otherwise the result is a `RealInterval`, except that two same-type non-cents intervals stay
in their own type (`RatioInterval + RatioInterval` reduces by GCD; `EdoInterval + EdoInterval` requires equal `edo`).

The companions add DSL helpers used across the codebase — `Unison`/`Octave` constants, the right-associative `/:` ratio
operator (e.g. `3 /: 2` is a perfect fifth), the postfix `.cents` operator on `Double`, and `EdoIntervalFactory(edo)`.
`Interval.fromRatioString` / `fromScalaTuningInterval` parse Scala-app `.scl` interval syntax.

### `Scale`

`Scale[+I <: Interval]` is an ordered, non-empty, covariant sequence of intervals validated to be sorted (ascending or
descending). Beyond sequence access it offers `relativeIntervals` (steps between adjacent intervals), an entropy-based
`softness` measure (equal-step scales score 1, harder scales approach 0), cent-tolerant comparison (`almostEquals`), and
conversion: `intonationStandard` is `Some` only when all intervals share one standard, and `convertToIntonationStandard`
returns a `ScaleConversionResult` (the converted scale plus the worst-case `IntonationConversionQuality`).

Typed `case class` subtypes — `RatiosScale`, `CentsScale`, `EdoScale` — carry a known element type, refine the API, and
offer varargs constructors. The `Scale` companion is the smart-constructor entry point: `Scale.create` picks the most
specific subtype from the interval types present (falling back to the generic `Scale` over `RealInterval` for mixed
input), ensures a unison is present, and sorts the intervals.

### Intonation standards

`IntonationStandard` is a `sealed abstract class` describing how intervals are expressed — `CentsIntonationStandard`,
`JustIntonationStandard`, `EdoIntonationStandard(countPerOctave)` — each carrying a `typeName` used for JSON
serialization in `format`. Its `conversionQualityTo(that)` classifies a standard-to-standard conversion: conversion to
cents is always lossless; conversion to just intonation is impossible (a ratio cannot be recovered from cents/EDO);
EDO-to-EDO is lossless only when the target EDO is a multiple of the source. The result is an
`IntonationConversionQuality` enum (`NoConversion` < `Lossless` < `Lossy` < `Impossible`), and a whole-scale conversion
takes the worst per-interval value.

### Free functions and supporting registries

The package object holds the shared unit-conversion math (cents/ratio/EDO/Hz conversions such as `fromRatioToCents` and
`fromCentsToHz`, plus numeric helpers `mod`/`gcd`/`lcm`) and `ConcertPitchFreq = 440.0` Hz. The sub-packages add small
standalone utilities: `intervals.SagittalInterval` (a registry of named JI comma/diesis constants, no logic) and
`instruments.StringInstrumentModel` (fret placement on a string).

## Interval arithmetic in logarithmic / cents space

A few conventions are worth internalizing before working in this module:

- **Addition is musical, not arithmetic on ratios.** `a + b` means "stack interval `b` on top of `a`" — multiplication
  of frequency ratios — but the public API hides that, so callers always think additively in log space.
- **Type promotion is deliberate.** Cents is the universal lossy fallback; combining anything with cents stays in cents.
  Same-type non-cents combinations stay precise; cross-type non-cents combinations fall back to `RealInterval`. This
  mirrors the `IntonationStandard.conversionQualityTo` lattice.
- **`CentsInterval` is not 1200-EDO.** Cents allow fractional values; `EdoInterval(1200, n)` allows only integer steps.
- **`EdoInterval` and 12-EDO.** Expressing an EDO step relative to the nearest 12-EDO semitone (plus an offset) is how
  tunings relate microtonal pitches back to the familiar 12-note keyboard.

## Dependencies

`intonation` has **no `dependsOn`** — by design, it is the foundation of the domain stack. Externally it uses Guava
(log/GCD/EDO math and index checks) plus the project-wide common dependencies (logging and, in `Test` scope,
ScalaTest/ScalaMock).

It is depended on by `composition` (models compositions in terms of `Scale`/`IntonationStandard`), `experiments`
(research executables over scales), and `app` directly; `format`, `tuner`, and `ui` reach it transitively through
`composition`.

## Notes / subject to change

- The `Scale` family mixes a covariant generic base (`Scale[+I]`) with concrete `case class` subtypes and uses internal
  `asInstanceOf` casts to preserve element types; treat the typed subtypes as the supported public surface.
- `SagittalInterval` is currently a flat constant registry (no Sagittal notation parsing/rendering yet) and is excluded
  from coverage.

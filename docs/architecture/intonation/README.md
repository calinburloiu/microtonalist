# `intonation` module architecture

## Responsibility

The `intonation` module is the lowest-level domain module of Microtonalist. It provides the pure mathematics of
microtonal pitch: musical **intervals**, **scales** built from them, and the **intonation standards** that describe how
those intervals are expressed (cents, just-intonation ratios, or EDO divisions). It also exposes a set of free functions
for converting between cents, frequency ratios, EDO counts, and frequencies in Hz.

Its role in the layered architecture:

- It sits at the bottom of the domain stack and has **no application dependencies** — it does not know about MIDI,
  tunings, compositions, file formats, the GUI, or threading. It is reusable, side-effect-free value math.
- Higher modules build on it: `composition` models a microtonal piece in terms of `Scale`s and `IntonationStandard`s,
  and `format` (de)serializes those scales; `tuner` ultimately consumes the resulting cent offsets. See
  [Dependencies](#dependencies).

Everything here is expressed in the **musical logarithmic space** (where an octave is a doubling of frequency and
intervals add/subtract rather than multiply/divide). The public API of `Interval` performs all arithmetic in that space;
frequency-ratio multiplication is an internal detail.

Package: `org.calinburloiu.music.intonation` (sub-packages `.intervals` and `.instruments`).

## Key types

### `Interval` and its subtypes (`intonation/src/main/scala/org/calinburloiu/music/intonation/Interval.scala`)

`Interval` (`Interval.scala:41`) is a `sealed trait` extending `Ordered[Interval]`. It models a single musical
interval and exposes:

- `realValue: Double` — the interval as a decimal frequency ratio.
- `cents: Double` — the interval in cents (default derives from `realValue`).
- Logarithmic-space arithmetic: `+`, `-` (`Interval.scala:82`, `:90`), `*(n: Int)` (repeated addition), `invert`
  (octave complement), `reverse` (flip direction).
- Octave handling: `isNormalized`, `normalize`, `normalizationFactor` / `normalizationLogFactor` — fold an interval
  into `[unison, octave)`.
- `isUnison`, `intonationStandard`, and converters `toRealInterval`, `toCentsInterval`, `toEdoInterval(edo)`.

Four concrete subtypes (sealed — the set is closed), each a `case class`:

| Type | Representation | Precision / use | `intonationStandard` |
| ---- | -------------- | --------------- | -------------------- |
| `RatioInterval(numerator, denominator)` (`:250`) | Just-intonation fraction | Exact ratios; cannot express temperaments | `JustIntonationStandard` |
| `CentsInterval(cents)` (`:372`) | Decimal cents | Approximates anything; allows fractions of a cent | `CentsIntonationStandard` |
| `EdoInterval(edo, count)` (`:459`) | `count` steps of an `edo`-equal division of the octave | Exact within an EDO; integer steps only | `EdoIntonationStandard(edo)` |
| `RealInterval(realValue)` (`:177`) | Generic decimal ratio | Most generic fallback; no JI/EDO precision | `CentsIntonationStandard` |

Mixed-type arithmetic follows fixed promotion rules (documented on the trait, `Interval.scala:25`):

- any interval combined with a `CentsInterval` yields a `CentsInterval`;
- otherwise a `RealInterval` results, except that two same-type non-cents intervals stay in their own type (e.g.
  `RatioInterval + RatioInterval = RatioInterval`, with GCD reduction; `EdoInterval + EdoInterval` requires equal
  `edo`).

Companion objects add convenience and DSL helpers:

- `RatioInterval` (`:333`): `Unison`/`Octave` constants, an implicit `(Int, Int) => RatioInterval`, the
  right-associative `/:` infix operator (e.g. `3 /: 2` is a perfect fifth), and `harmonicSeriesOf(...)`.
- `CentsInterval` (`:439`): `Unison`/`Octave` and the postfix `.cent` / `.cents` operators on `Double`.
- `RealInterval` (`:237`) and `EdoInterval` (`:544`): `Unison`/`Octave` (`unisonFor`/`octaveFor` for EDO), plus an
  `EdoInterval.apply(edo, countRelativeToStandard)` that expresses a count relative to the nearest 12-EDO semitone;
  `EdoInterval.countRelativeToStandard` is the inverse (`Interval.scala:535`).
- `EdoIntervalFactory(edo)` (`:583`) — a small factory that fixes `edo` so callers only supply `count`.
- `Interval.fromRatioString` / `Interval.fromScalaTuningInterval` (`:135`, `:159`) — parse Scala-app `.scl` interval
  syntax (an integer/fraction is a ratio; a number with a dot is cents).

### `Scale` and its subtypes (`intonation/src/main/scala/org/calinburloiu/music/intonation/Scale.scala`)

`Scale[+I <: Interval]` (`Scale.scala:23`) is an ordered, non-empty, covariant sequence of intervals (validated to be
sorted ascending or descending). Notable members:

- `apply(index)`, `size`, `range`, `direction` (1 ascending / -1 descending / 0 single element).
- `relativeIntervals` (`:62`) — the steps between adjacent intervals.
- `entropy(logBase)` and `softness` (`:84`, `:102`) — an entropy-based measure of how evenly spaced a scale's steps
  are; the softest n-note scale (equal steps) has `softness == 1`, harder scales approach 0.
- `transpose`, `rename`, `indexOfUnison`, `almostEquals(that, centsTolerance)` (cent-tolerant comparison), and
  value-based `equals`/`hashCode`.
- `intonationStandard: Option[IntonationStandard]` (`:111`) — `Some` only if all intervals share one standard.
- `convertToIntonationStandard(newIntonationStandard)` (`:143`) — returns a `ScaleConversionResult` (the converted
  `Option[Scale]` plus the worst-case `IntonationConversionQuality`).
- Predicates `isCentsScale` / `isRatiosScale` / `isEdoScale`.

Typed subtypes (`case class`es) carry a known element type and refine the API (e.g. typed `transpose`, a definite
`intonationStandard`): `RatiosScale` (`:285`), `CentsScale` (`:318`), `EdoScale` (`:356`, which requires all intervals
to share the same `edo` and exposes `edo`). Their companions offer ergonomic varargs constructors (e.g.
`CentsScale("name", 0.0, 200.0, 400.0)`, `EdoScale("name", edo, counts...)`).

The `Scale` companion (`:216`) is the smart-constructor entry point: `Scale.create(name, intervals)` picks the most
specific subtype from the interval types present (falling back to the generic `Scale` over `RealInterval` for mixed
input), `Scale.create(name, intervals, intonationStandard)` forces a target standard, and `createUnisonScale` builds a
single-unison scale. The private `processIntervals` ensures a unison is present and sorts the intervals.

### Intonation standards (`intonation/src/main/scala/org/calinburloiu/music/intonation/IntonationStandard.scala`)

`IntonationStandard` (`IntonationStandard.scala:25`) is a `sealed abstract class` (each carries a `typeName` used for
JSON serialization in the `format` module) describing how intervals are expressed:

- `CentsIntonationStandard` (`typeName = "cents"`),
- `JustIntonationStandard` (`typeName = "justIntonation"`),
- `EdoIntonationStandard(countPerOctave)` (`typeName = "edo"`).

Each provides a `unison` interval and `conversionQualityTo(that)` (`:35`), which classifies a standard-to-standard
conversion. Conversion to cents is always lossless; conversion to just intonation is impossible (you cannot recover a
ratio from cents/EDO); EDO-to-EDO is lossless when the target EDO is a multiple of the source, otherwise lossy.

### `IntonationConversionQuality` (`intonation/.../IntonationConversionQuality.scala`)

A Scala 3 `enum` (`IntonationConversionQuality.scala:22`) ranking a conversion, ordered by ordinal from best to worst:
`NoConversion`, `Lossless`, `Lossy`, `Impossible`. `Scale.convertToIntonationStandard` takes the maximum (worst)
per-interval ordinal to score a whole-scale conversion.

### Free functions and constants (`intonation/src/main/scala/org/calinburloiu/music/intonation/package.scala`)

The package object holds the unit-conversion math shared across the module:

- `ConcertPitchFreq = 440.0` Hz (A4).
- Cents/ratio/EDO conversions: `fromRealValueToCents`, `fromRatioToCents`, `fromEdoToCents`, `fromCentsToRealValue`,
  `fromEdoToRealValue`.
- Frequency conversions: `fromCentsToHz`, `fromHzToCents`.
- Numeric helpers: `mod` (always-non-negative modulus), `gcd`, `lcm` over integer sequences.

### Supporting registries (sub-packages)

- `intervals.SagittalInterval` (`intonation/.../intervals/SagittalInterval.scala`)
  — a registry of named just-intonation comma/diesis constants (schismas, kleismas, commas, dieses) as
  `RatioInterval`s.
  It contains no logic and is excluded from coverage in `build.sbt`.
- `instruments.StringInstrumentModel` (`intonation/.../instruments/StringInstrumentModel.scala`)
  — computes fret placement (`fretSize`) on a string of a given length from intervals; a small, standalone utility.

## Interval arithmetic in logarithmic / cents space

A few conventions are worth internalizing before working in this module:

- **Addition is musical, not arithmetic on ratios.** `a + b` means "stack interval `b` on top of `a`", which is
  multiplication of frequency ratios — but the public API hides that: callers always think additively in log space.
  `*(n)` repeats an interval `n` times.
- **Type promotion is deliberate.** Cents is the universal lossy fallback; combining anything with cents stays in cents.
  Same-type non-cents combinations stay precise (ratios reduce by GCD; EDO requires matching `edo`). Cross-type
  non-cents combinations fall back to `RealInterval`. This mirrors the `IntonationStandard.conversionQualityTo` lattice.
- **`CentsInterval` is not 1200-EDO.** Cents allow fractional values; `EdoInterval(1200, n)` only allows integer steps.
- **Normalization** folds an interval into one octave `[unison, octave)`; `invert` (octave complement) requires a
  normalized interval and will throw otherwise.
- **`EdoInterval` and 12-EDO.** `countRelativeToStandard` / `EdoInterval(edo, (semitones, offset))` let callers reason
  about an EDO step as "the nearest 12-EDO semitone plus an offset", which is how tunings relate microtonal pitches back
  to the familiar 12-note keyboard.

## Dependencies

Verified against `build.sbt` (the `intonation` project block at `build.sbt:204`).

**Upstream (what `intonation` depends on):**

- No other Microtonalist modules — `intonation` has no `dependsOn`, by design (it is the foundation of the domain
  stack).
- External libraries: **Guava** (`com.google.common.math.{DoubleMath, IntMath}`, `com.google.common.base.Preconditions`)
  for log/GCD/EDO math and index checks, plus the project-wide `commonDependencies` (Logback, scala-logging, and
  ScalaTest/ScalaMock in `Test` scope).

**Downstream (what depends on `intonation`):**

- `composition` (`dependsOn(intonation, tuner)`) — models compositions in terms of `Scale` and `IntonationStandard`.
- `experiments` (`dependsOn(intonation)`) — research executables (e.g. the soft-chromatic-genus study) over scales.
- `app` (lists `intonation` among its direct `dependsOn`).
- Transitively, `format`, `tuner`, and `ui` reach `intonation` through `composition`.

## Notes / subject to change

- The `Scale` family mixes a covariant generic base (`Scale[+I]`) with concrete `case class` subtypes and uses
  `asInstanceOf` casts internally (e.g. in `relativeIntervals` and `processIntervals`) to preserve element types; treat
  the typed subtypes as the supported public surface.
- `SagittalInterval` is currently a flat constant registry (no Sagittal notation parsing/rendering yet); it is excluded
  from coverage.

# `experiments` module architecture

## Responsibility

`experiments` is a separate, standalone executable used for research and experimentation — not part of the shipped
Microtonalist app. It hosts ad-hoc intonation studies that explore musical questions (e.g. how scales behave across
intonation standards) by computing and printing results to stdout. The code here is exploratory and non-production: it
is throwaway, has no tests, and may be added to, rewritten, or deleted freely as investigations come and go.

## Key types

`SoftChromaticGenusStudy` — a study of "soft chromatic" Hicaz-style tetrachords. The class defines a few candidate
`RatiosScale`s, a list of "good" EDOs, and helpers that print each scale's intervals, its relative intervals, and
quarter-tone / augmented-second threshold checks; its companion `object` has the `main` method, the executable entry
point, which prints the studies first in just intonation and then converted to each EDO. This single study is currently
the whole module; new studies are added as additional entry points, each an `object` with a `main` method (Scala 3.8
deprecated the `App` trait).

## Dependencies

The module declares one application dependency, `intonation`, using types such as `RatiosScale`, `Scale`, `Interval`,
`EdoIntonationStandard`, and the `RatioInterval` infix operators. Nothing depends on `experiments`.

## Build

`root` aggregates the module like any other, so tasks run on `root` such as `compile`, `test`, `fix` and `lint` cover
it. It is excluded from coverage measurement (`coverageEnabled := false`) and has no fat JAR: run a study with
`sbtn experiments/run`, or with `sbtn "experiments/runMain <main class>"` once there are several.

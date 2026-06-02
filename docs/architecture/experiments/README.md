# `experiments` module architecture

## Responsibility

`experiments` is a separate, standalone executable used for research and experimentation — not part of the shipped
Microtonalist app. It hosts ad-hoc intonation studies that explore musical questions (e.g. how scales behave across
intonation standards) by computing and printing results to stdout. The code here is exploratory and non-production: it
is throwaway, has no tests, and may be added to, rewritten, or deleted freely as investigations come and go.

## Key types

`SoftChromaticGenusStudy` — a study of "soft chromatic" Hicaz-style tetrachords. The class defines a few candidate
`RatiosScale`s, a list of "good" EDOs, and helpers that print each scale's intervals, its relative intervals, and
quarter-tone / augmented-second threshold checks; its companion `object` extends `App` and is the executable entry
point, printing the studies first in just intonation and then converted to each EDO. This single study is currently the
whole module; new studies are added as additional `App`-style entry points.

## Dependencies

The module declares one application dependency, `intonation`, using types such as `RatiosScale`, `Scale`, `Interval`,
`EdoIntonationStandard`, and the `RatioInterval` infix operators. Nothing depends on `experiments`, and it is **not**
part of the `root` aggregate, so `root` build/test tasks skip it — build or run it explicitly. Its `assembly` main class
is `SoftChromaticGenusStudy`.

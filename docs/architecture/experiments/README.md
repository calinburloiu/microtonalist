# `experiments` module architecture

## Responsibility

`experiments` is a separate, standalone executable used for research and experimentation — not part of the shipped
Microtonalist app. It hosts ad-hoc intonation studies that explore musical questions (e.g. how scales behave across
intonation standards) by computing and printing results to stdout. The code here is exploratory and non-production: it
is throwaway, has no tests, and may be added to, rewritten, or deleted freely as investigations come and go.

## Key types

- `SoftChromaticGenusStudy`
  (`experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments/SoftChromaticGenusStudy.scala:22`) —
  a study of "soft chromatic" Hicaz-style tetrachords. The class
  (`SoftChromaticGenusStudy.scala:22`) defines a few candidate `RatiosScale`s, a list of "good" EDOs, and helpers
  (`printStruct`, `printStructForAllEdos`) that print each scale's intervals, its relative intervals, and threshold
  checks (quarter-tone and augmented-second cutoffs). The companion object
  (`SoftChromaticGenusStudy.scala:65`) extends `App` and is the executable entry point; it prints the studies first in
  just intonation, then converted to each EDO.

This single study is currently the entire module. New studies are added as additional `App`-style entry points.

## Dependencies

Per `build.sbt` (the `experiments` project), the module declares one application dependency:

```scala
lazy val experiments = (project in file("experiments"))
  .dependsOn(
    intonation,
  )
```

It uses `intonation` types such as `RatiosScale`, `Scale`, `Interval`, `EdoIntonationStandard`, and the `RatioInterval`
infix operators. Nothing depends on `experiments`; the `root` project aggregates it but no module builds on it. Its
`assembly` main class is `SoftChromaticGenusStudy`.

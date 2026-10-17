# `intonation-format` module architecture

## Responsibility

`intonation-format` reads and writes the `Scale`s of [`intonation`](../../intonation/docs/ARCHITECTURE.md), in
Microtonalist's JSON (`.jscl`) and in the Huygens-Fokker Scala format (`.scl`), which it only reads. It depends on
neither `composition`, `rig` nor `tuner`, so that a tool such as `experiments` can load scales without them.

## Key types

- **`ScaleFormat`** — (de)serializes a scale: `JsonScaleFormat` and `HuygensFokkerScalaScaleFormat`.
  `ScaleFormatRegistry` picks one by media type, falling back to the file extension.
- **`ScaleRepo`** — reads a scale from a `URI`; `DefaultScaleRepo` picks the `FileScaleRepo`, `HttpScaleRepo` or
  `LibraryScaleRepo` by its scheme.
- **`ScaleFormatContext`** — what a scale read for a composition takes from it: a name and the composition's
  intonation standard. `ScaleContextConverter` applies it, and publishes a `ScaleLossyConversionEvent` on the
  `Businessync` when converting the intervals loses precision.
- **`JsonIntervalFormat`** and **`JsonIntonationStandardPluginFormat`** — the JSON formats of an `Interval` and of an
  `IntonationStandard`, which `composition-format` uses too.

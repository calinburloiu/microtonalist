# `tuning` module architecture

## Responsibility

`tuning` holds the values that the tuning engine shares with its clients: the `Tuning` that `composition` builds and a
tuner applies, and the `TuningChange` that a tuning changer decides and `TuningService` carries out. It has no MIDI
protocol, thread or plugin of its own, so that `composition` and `ui` use these values without depending on the engine
of [`tuner`](../../tuner/docs/ARCHITECTURE.md), whose package it shares.

## Key types

- **`Tuning`** — 12 optional cent offsets, one per pitch class; `Tuning.Standard` is 12-EDO. `DefaultCentsTolerance`,
  in the package object, is the tolerance for comparing cent values.
- **`TuningChange`** — a decision about the tuning: effective (`PreviousTuningChange`, `NextTuningChange`,
  `IndexTuningChange`) or ineffective (`NoTuningChange`, `MayTriggerTuningChange`). The
  [tuning changers](../../tuning-changer/docs/ARCHITECTURE.md) return it, and `TuningService.changeTuning` applies the
  effective ones.

## Subject to change

- A `Tuning` will keep the lineage of its scales, which will make `tuning` depend on `intonation` (#34).

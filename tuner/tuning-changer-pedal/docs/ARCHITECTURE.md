# `tuning-changer-pedal` module architecture

## Responsibility

`tuning-changer-pedal` holds `PedalTuningChanger`, the [tuning changer](../../tuning-changer/docs/ARCHITECTURE.md) of
type `pedal`, which changes the tuning when a pedal is pressed. It suits the pedals that, like a piano's, return to
their initial position once released.

## Key types

- **`PedalTuningChanger`** — triggers a change when the value of a trigger's Control Change rises above a threshold,
  and again only after it has fallen back to it, so that a pedal held down changes the tuning once. Its triggers are
  the controller numbers 0–119, MIDI 1.0 reserving 120–127 for the Channel Mode messages.

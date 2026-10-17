# `tuner-mpb` module architecture

## Responsibility

`tuner-mpb` holds `MonophonicPitchBendTuner`, the [tuner](../../tuner/docs/ARCHITECTURE.md) of type
`monophonicPitchBend`, which tunes each note with Pitch Bend. Pitch Bend applies to a whole channel, so the tuner folds
all input onto one output channel and plays one note at a time.

## Key types

- **`MonophonicPitchBendTuner`** — adds the offset of the note's pitch class to the performer's Pitch Bend. It tunes
  exactly only the offsets within its Pitch Bend Sensitivity, which it configures on reset and follows from the input,
  and clamps the others, with a warning.

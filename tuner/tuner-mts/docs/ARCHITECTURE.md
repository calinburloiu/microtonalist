# `tuner-mts` module architecture

## Responsibility

`tuner-mts` holds the [tuners](../../tuner/docs/ARCHITECTURE.md) of the MIDI Tuning Standard (MTS): they retune the
output instrument's pitch table in advance, with an MTS Octave SysEx message, and pass the notes through.

## Key types

- **`MtsTuner`** — the base class of the four tuners, one per MTS Octave form: `MtsOctave1ByteNonRealTimeTuner`,
  `MtsOctave2ByteNonRealTimeTuner`, `MtsOctave1ByteRealTimeTuner` and `MtsOctave2ByteRealTimeTuner`. Its `thru` says
  whether the input messages reach the output too, which an instrument with local control on doesn't need.
- **`MtsMessageGenerator`** — encodes a `Tuning` as the SysEx message of one form: whole cents from -64 to +63 in the
  1-byte form, cents from -100 to +100 in the 2-byte form. Its `canEncode` tells whether a tuning fits.

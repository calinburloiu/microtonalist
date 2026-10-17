# `tuner-mpe` module architecture

## Responsibility

`tuner-mpe` holds `MpeTuner`, the [tuner](../../tuner/docs/ARCHITECTURE.md) of type `mpe`, which tunes polyphonically
with MIDI Polyphonic Expression (MPE): it gives each note a Member Channel of its own and bends that channel's pitch by
the offset of the note's pitch class.

## Key types

- **`MpeTuner`** — the tuner. Its `MpeInputMode` says whether the input is conventional MIDI, which it converts to MPE,
  or already MPE.
- **`MpeZones`** — the Lower and Upper `MpeZone`s of the output, which the MPE Configuration Messages of the input
  change.
- **`MpeMessageRouting`** — routes each input message by its channel's role in the Zones.
- **`MpeChannelAllocator`** — allocates the notes to Member Channels, whose state `MpeChannelState` tracks.

The message flow and the allocation are in [`mpe-tuner.md`](mpe-tuner.md), and the design, with where it departs from
the MPE Specification, in the [MPE Tuner paper](mpe-tuner-paper.md).

# `tuner` module architecture

## Responsibility

`tuner` is the low-level MIDI tuning engine. It turns a `Tuning` into the MIDI messages an output instrument needs to
play microtonally, and holds the runtime tuning state behind a thread-safe service.

It doesn't decide which tunings exist: `composition` builds them and hands over a `Seq[Tuning]`. The values it works
with, `Tuning` and `TuningChange`, are in [`tuning`](../../tuning/docs/ARCHITECTURE.md), with which it shares its
package. Finding when to change the tuning is the job of the
[tuning changers](../../tuning-changer/docs/ARCHITECTURE.md).

## Key types

`Tuner` is a plugin, which `format` (de)serializes by its `familyName` and `typeName`.

**`Tuner`** is the protocol abstraction. `reset()` configures the output instrument, `tune(tuning)` stores a tuning and
emits the messages it needs now, and `process(message)` rewrites each message of the track. The trait keeps the current
tuning: `tune` and `reset` are `final` and delegate to the `onTune` / `onReset` hooks, `reset` restating the current
tuning after the configuration. `canTune(tuning)` tells whether a tuning is within the tuner's limits, such as its
Pitch Bend Sensitivity; the tuner clamps any other tuning, with a warning, rather than throw.

| Protocol | Tuner | How it tunes | Polyphony |
| -------- | ----- | ------------ | --------- |
| MTS (MIDI Tuning Standard) Octave, 1-byte/2-byte, real/non-real-time | `MtsOctave*Tuner` | An MTS SysEx retunes the instrument's pitch table in advance; notes pass through. | Polyphonic |
| Monophonic Pitch Bend | `MonophonicPitchBendTuner` | Pitch Bend, all input folded onto one output channel. | Monophonic |
| MPE | `MpeTuner` | Pitch Bend per note, on the MPE Member Channel it allocates to the note. | Polyphonic |

`MpeTuner` routes each message by its channel's role in the MPE Zones and allocates notes to Member Channels; see
[`mpe-tuner.md`](../../tuner-mpe/docs/mpe-tuner.md).

**Session and service.** `TuningSession` holds the tunings and the current index, and publishes a `TuningEvent` on
every change, which the rig and the UI follow. It runs on the business thread, behind the thread-safe
`TuningService`, whose `changeTuning` applies an effective `TuningChange`. `TunerModule` wires the two.

## Threading

`TuningSession` is `@NotThreadSafe` and runs on the business thread (see
[`businessync`](../../../base/businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners
are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's callback thread, and tunes and
resets them on the business thread (see [`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).

## Subject to change

- `TuningService.tunings` is deprecated until the UI moves to JavaFX (#99).
- A tuning beyond a tuner's limits is only warned about when applied (#326).
- The tuners will move to modules of their own: `tuner-mpe`, `tuner-mts` and `tuner-mpb` (#366).

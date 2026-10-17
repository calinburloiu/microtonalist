# `tuner` module architecture

## Responsibility

`tuner` is the low-level MIDI tuning engine. It turns a `Tuning` into the MIDI messages an output instrument needs to
play microtonally, and holds the runtime tuning state behind a thread-safe service.

It doesn't decide which tunings exist: `composition` builds them and hands over a `Seq[Tuning]`. The values it works
with, `Tuning` and `TuningChange`, are in [`tuning`](../../tuning/docs/ARCHITECTURE.md), with which it shares its
package. Finding when to change the tuning is the job of the
[tuning changers](../../tuning-changer/docs/ARCHITECTURE.md).

## Key types

`Tuner` is a plugin, which [`tuner-format`](../../tuner-format/docs/ARCHITECTURE.md) (de)serializes by its
`familyName` and `typeName`.

**`Tuner`** is the protocol abstraction. `reset()` configures the output instrument, `tune(tuning)` stores a tuning and
emits the messages it needs now, and `process(message)` rewrites each message of the track. The trait keeps the current
tuning: `tune` and `reset` are `final` and delegate to the `onTune` / `onReset` hooks, `reset` restating the current
tuning after the configuration. `canTune(tuning)` tells whether a tuning is within the tuner's limits, such as its
Pitch Bend Sensitivity; the tuner clamps any other tuning, with a warning, rather than throw.

Each tuner has a module of its own, in a subpackage of `org.calinburloiu.music.microtonalist.tuner`:

| Protocol | Module | How it tunes | Polyphony |
| -------- | ------ | ------------ | --------- |
| MTS (MIDI Tuning Standard) Octave, 1-byte/2-byte, real/non-real-time | [`tuner-mts`](../../tuner-mts/docs/ARCHITECTURE.md) | An MTS SysEx retunes the instrument's pitch table in advance; notes pass through. | Polyphonic |
| Monophonic Pitch Bend | [`tuner-mpb`](../../tuner-mpb/docs/ARCHITECTURE.md) | Pitch Bend, all input folded onto one output channel. | Monophonic |
| MPE | [`tuner-mpe`](../../tuner-mpe/docs/ARCHITECTURE.md) | Pitch Bend per note, on the MPE Member Channel it allocates to the note. | Polyphonic |

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

# `tuner` module architecture

## Responsibility

`tuner` is the low-level MIDI tuning engine. It turns a `Tuning` into the MIDI messages an output instrument needs to
play microtonally, detects tuning-change triggers in the MIDI messages, and holds the runtime tuning state behind a
thread-safe service.

It doesn't decide which tunings exist: `composition` builds them and hands over a `Seq[Tuning]`.

## Key types

`Tuner` and `TuningChanger` are plugins, which `format` (de)serializes by their `familyName` and `typeName`.

**`Tuning`** holds 12 optional cent offsets, one per pitch class; `Tuning.Standard` is 12-EDO.

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
[`mpe-tuner.md`](mpe-tuner.md).

**Tuning-change detection.** A `TuningChanger` inspects each message and returns a `TuningChange`: effective (previous,
next or by index) or ineffective. `PedalTuningChanger` triggers on a pedal-like Control Change crossing a threshold.

**Session and service.** `TuningSession` holds the tunings and the current index, and publishes a `TuningEvent` on
every change, which the rig and the UI follow. It runs on the business thread, behind the thread-safe
`TuningService`. `TunerModule` wires the two.

## Threading

`TuningSession` is `@NotThreadSafe` and runs on the business thread (see
[`businessync`](../../businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners and the
tuning changers are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's callback
thread, and tunes and resets them on the business thread (see [`rig`](../../rig/docs/ARCHITECTURE.md#threading)).

## Subject to change

- `TuningService.tunings` is deprecated until the UI moves to JavaFX (#99).
- A tuning beyond a tuner's limits is only warned about when applied (#326).

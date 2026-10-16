# `tuning-changer` module architecture

## Responsibility

`tuning-changer` holds the contract of the tuning changers: the plugins that find, in a track's MIDI messages, when to
change the tuning, e.g. when a pedal is pressed. It sits beside [`tuner`](../../tuner/docs/ARCHITECTURE.md), not on
it: a tuning changer returns a [`TuningChange`](../../tuning/docs/ARCHITECTURE.md), which `rig`'s
`TuningChangeProcessor` hands to `TuningService` (see `rig`'s
[tuning-change flow](../../../rig/rig/docs/ARCHITECTURE.md#tuning-change-flow)).

Each implementation has a module of its own, in a subpackage of `org.calinburloiu.music.microtonalist.tuner.changer`;
[`tuning-changer-pedal`](../../tuning-changer-pedal/docs/ARCHITECTURE.md) is the only one so far.

## Key types

- **`TuningChanger`** — the plugin, of the `tuningChanger` family, which
  [`tuning-changer-format`](../../tuning-changer-format/docs/ARCHITECTURE.md) (de)serializes by its `typeName`. It
  decides a `TuningChange` for each MIDI message, and whether the messages that trigger a change reach the output.
- **`TuningChangeTriggers`** — the triggers, e.g. Control Change numbers, that select the previous, the next or a given
  tuning; an implementation looks up the `TuningChange` of the trigger it detects.

## Threading

Tuning changers are `@NotThreadSafe`: a track runs MIDI messages through them on the input device's callback thread,
and resets them on the business thread (see [`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).

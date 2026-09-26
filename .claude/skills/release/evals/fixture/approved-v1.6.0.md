## v1.6.0 (@DATE@)

This release keeps an instrument in tune when its output device is **turned off and on again**, and makes every tuner
**clamp a tuning it cannot apply exactly** instead of failing.

### User-facing changes

- **An output device that reopens keeps the current tuning** ([#303](https://github.com/calinburloiu/microtonalist/issues/303), [#322](https://github.com/calinburloiu/microtonalist/issues/322)). Turning a digital piano off overnight and on again
  no longer leaves it in 12-EDO, and the Monophonic Pitch Bend Tuner no longer plays the first C after a reset untuned
  ([#297](https://github.com/calinburloiu/microtonalist/issues/297)). A reset also stops the notes and releases the pedals the device was left with.
- **Tunings beyond a tuner's limits are clamped, with a warning** ([#322](https://github.com/calinburloiu/microtonalist/issues/322)). The Monophonic Pitch Bend Tuner beyond its Pitch
  Bend Sensitivity and the 2-byte MTS tuners beyond ±100 cents used to fail; they now apply the closest tuning they can.

### Developer-facing changes

- **`Tuner` retains its current tuning** ([#323](https://github.com/calinburloiu/microtonalist/issues/323)). `tune` and `reset` are `final`, store the tuning in the trait and
  delegate to the new `onTune` / `onReset`; `reset` restates the current tuning.
- **`Tuner.canTune`** tells whether a tuner can apply a tuning exactly in its current configuration ([#327](https://github.com/calinburloiu/microtonalist/issues/327)).
- **Agent instructions**: the Metals MCP `fileInFocus` workaround for glob searches is dropped ([#324](https://github.com/calinburloiu/microtonalist/issues/324)).

### Known issues

- A tuning a tuner cannot apply exactly is reported only when it is played, not when the tunings are loaded ([#326](https://github.com/calinburloiu/microtonalist/issues/326)).
- Rebuilt tracks play in 12-EDO until the next tuning change ([#305](https://github.com/calinburloiu/microtonalist/issues/305)).

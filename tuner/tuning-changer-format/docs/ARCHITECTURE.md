# `tuning-changer-format` module architecture

## Responsibility

`tuning-changer-format` (de)serializes the [tuning changers](../../tuning-changer/docs/ARCHITECTURE.md) of a rig's
tracks. `JsonTuningChangerPluginFormat`, the format of the `tuningChanger` plugin family, gathers the type specs of the
tuning changers, whose formats are in a `format` subpackage of their tuning changer's package. So far there is
`org.calinburloiu.music.microtonalist.tuner.changer.pedal.format.JsonPedalTuningChangerPluginFormatting`, for
[`tuning-changer-pedal`](../../tuning-changer-pedal/docs/ARCHITECTURE.md), whose triggers are Control Change numbers
(`format-core`'s `ccNumberFormat`).

Adding a tuning changer adds its format here.

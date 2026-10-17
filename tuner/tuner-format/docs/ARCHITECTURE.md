# `tuner-format` module architecture

## Responsibility

`tuner-format` (de)serializes the [tuners](../../tuner/docs/ARCHITECTURE.md) of a rig's tracks.
`JsonTunerPluginFormat`, the format of the `tuner` plugin family, gathers the type specs of the tuners, whose formats
are in a `format` subpackage of their tuner's package (`…` abbreviates `org.calinburloiu.music.microtonalist`):

| Tuner module | Its format |
| ------------ | ---------- |
| [`tuner-mpe`](../../tuner-mpe/docs/ARCHITECTURE.md) | `…tuner.mpe.format.JsonMpeTunerPluginFormatting` |
| [`tuner-mts`](../../tuner-mts/docs/ARCHITECTURE.md) | `…tuner.mts.format.JsonMtsTunerPluginFormatting`, a spec per MTS Octave form |
| [`tuner-mpb`](../../tuner-mpb/docs/ARCHITECTURE.md) | `…tuner.mpb.format.JsonMonophonicPitchBendTunerPluginFormatting` |

Adding a tuner adds its format here.

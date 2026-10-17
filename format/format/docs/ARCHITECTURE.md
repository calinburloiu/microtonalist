# `format` module architecture

## Responsibility

The format modules read the objects that Microtonalist persists, compositions, scales and rigs, from files, over HTTP
or from the user's library; most of their `write` methods are `???` stubs. `format` holds `FormatModule`, which wires
them for `app`.

Each of them but `format-core` is named after the module whose types it (de)serializes:

| Module | (De)serializes |
| ------ | -------------- |
| [`format-core`](../../format-core/docs/ARCHITECTURE.md) | nothing of its own: the infrastructure that the others share |
| [`intonation-format`](../../../theory/intonation-format/docs/ARCHITECTURE.md) | scales (`.jscl`, Scala's `.scl`) |
| [`composition-format`](../../../theory/composition-format/docs/ARCHITECTURE.md) | compositions (`.mtlist`) and their plugins |
| [`rig-format`](../../../rig/rig-format/docs/ARCHITECTURE.md) | rigs (`.mtlist.tracks`) and their track I/O plugins |
| [`tuner-format`](../../../tuner/tuner-format/docs/ARCHITECTURE.md) | the tuners of a rig's tracks |
| [`tuning-changer-format`](../../../tuner/tuning-changer-format/docs/ARCHITECTURE.md) | the tuning changers of a rig's tracks |

Each persisted object has a format and a repository; the group's README gives what they are, in
[*Formats and repositories*](../../README.md#formats-and-repositories), and how they are named.

## Key types

- **`FormatModule`** — builds the formats and repositories, and exposes `defaultCompositionRepo`, `defaultScaleRepo`
  and `defaultRigRepo`. It gives `JsonRigFormat` the family formats of `tuner-format` and `tuning-changer-format`, so
  that `rig-format` depends on no tuner or tuning changer.

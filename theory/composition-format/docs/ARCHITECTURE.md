# `composition-format` module architecture

## Responsibility

`composition-format` reads and writes the `Composition`s of [`composition`](../../composition/docs/ARCHITECTURE.md), in
Microtonalist's JSON (`.mtlist`), with the formats of the composition plugins. It reads the scales that a composition
holds or refers to through [`intonation-format`](../../intonation-format/docs/ARCHITECTURE.md).

## Key types

- **`CompositionFormat`** — (de)serializes a composition. `JsonCompositionFormat` reads the JSON into a
  `CompositionRepr`, which mirrors its shape, loads the scales it refers to, concurrently and each once, and then builds
  the `Composition`.
- **`CompositionRepo`** — reads a composition from a `URI`; `DefaultCompositionRepo` picks the `FileCompositionRepo` or
  the `HttpCompositionRepo` by its scheme. Compositions have no library repository.
- **Plugin formats** — `JsonTuningMapperPluginFormat`, `JsonTuningReducerPluginFormat`,
  `JsonTuningReferencePluginFormat` and `JsonSoftChromaticGenusMappingPluginFormat`, with `KeyboardMappingFormat` for a
  manual tuning mapper's keys.

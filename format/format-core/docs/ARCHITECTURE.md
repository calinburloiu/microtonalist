# `format-core` module architecture

## Responsibility

`format-core` holds what the [format modules](../../format/docs/ARCHITECTURE.md) share: picking a repository by URI
scheme, resolving `$ref` references, (de)serializing plugins, deferring reads, and the JSON formats of MIDI values. It
depends on no other format module, and of the application on `common` and `sc-midi` only.

## Key types

- **`RepoSelector`** — picks a repository for a `URI`. `DefaultRepoSelector` keys on its scheme: none or `file`,
  `http` or `https`, and `microtonalist` for the user's library. The package object resolves relative URIs and library
  URLs (`resolveBaseUriWithOverride`, `resolveLibraryUrl`).
- **`JsonPreprocessor`** — replaces, before validation, each JSON object holding a `$ref` with the JSON it refers to,
  which its `JsonPreprocessorRefLoader`s load from a file or over HTTP. The loaded properties win over those beside the
  `$ref`. It guards against cycles and deep nesting; `NoJsonPreprocessor` resolves nothing.
- **`JsonPluginFormat`** — (de)serializes a plugin family by the `typeName` in the JSON: a `TypeSpec` per type gives
  the format of its settings, or a singleton for a type without settings. A family may have a default type, and a type
  without settings may be written as a bare string. A composition's global `settings` override a type's defaults, and
  a plugin's own settings override both.
- **`DeferrableRead`** — a value already read (`AlreadyRead`) or read later (`DeferredRead`), e.g. a scale that a
  composition refers to by URI. It caches the `Future` of its load.
- **MIDI value formats** — `JsonCommonMidiFormat`, `MidiNoteFormat`, `PitchClassFormat`, and the package object's
  `uint7Format` (0–127) and `ccNumberFormat` (0–119, MIDI 1.0 reserving 120–127 for the Channel Mode messages). The JSON
  Schemas under `json-schemas/v1/` mirror the last two as `uint7.schema.json` and `ccNumber.schema.json`; no test checks
  them, so they change with the formats.

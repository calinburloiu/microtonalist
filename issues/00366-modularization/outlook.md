# Modularization outlook (draft)

- **Date:** 2026-10-16
- **Based on commit:** `c8ada5c6` (`main`)
- **Issue:** none. #366 leaves this out of its scope, and no issue tracks it.
- **Status:** draft, kept for reference. The JavaFX UI code (#99) may change it.

The ideas that [`proposal.md`](proposal.md) leaves out, building on its modules. Packages abbreviate
`org.calinburloiu.music.microtonalist` as `…`.

## Feature module groups

Each tuner and tuning changer becomes a feature: up to three modules in a folder of its own.

| Module | Depends on | Never on |
| ------ | ---------- | -------- |
| `tuner-mpe` (domain) | `tuner` | format, UI |
| `tuner-mpe-format` | `tuner-mpe`, `format-core` | UI |
| `tuner-mpe-ui` | `tuner-mpe`, `ui-core` | format |
| `rig-format`, `ui` | the contract modules (`tuner`, `tuning-changer`, `format-core`, `ui-core`) | any feature module |
| `app` | every module | |

- **Format.** `tuner-mpe-format` takes the `…tuner.mpe.format` package out of `tuner-format` as it is, so no package
  is renamed. `tuner-format` and `tuning-changer-format` keep only the family formats.
- **UI.** `ui-core` and the `-ui` modules come with the JavaFX GUI (#99), and only for features with a UI.

## App-owned registration

`app` owns the list of plugins, and the `format` aggregator module goes away:

- Each plugin family's format becomes an instance built from the `TypeSpec`s that `app` registers. The proposal's
  `JsonRigFormat` already takes the family formats as constructor parameters, and the plugin formats already expose
  their specs (`JsonMpeTunerPluginFormating.spec`, `JsonMtsTunerPluginFormating.specs`).
- `ui` gets each feature's view and view model the same way.
- `FormatModule`'s wiring moves to `app` with the module wiring work (#100).

Adding a tuner or tuning changer then touches only its feature folder and `app`'s list.

## Folder layout

The proposal's `tuner/` grouping folder gets a folder per feature, and `ui/` gets `ui-core`. Feature folders aren't
modules, so their names are short:

```
tuner/
  tuner/  tuner-format/  tuning/  tuning-changer/  tuning-changer-format/
  mpe/                      CLAUDE.md and architecture doc for the whole feature
    tuner-mpe/  tuner-mpe-format/  tuner-mpe-ui/
  mts/
    tuner-mts/  tuner-mts-format/
  mpb/
    tuner-mpb/  tuner-mpb-format/
  pedal-changer/
    tuning-changer-pedal/  tuning-changer-pedal-format/
ui/
  ui-core/  ui/
```

A feature's architecture doc replaces the per-module docs of its modules.

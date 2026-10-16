# `rig-format` module architecture

## Responsibility

`rig-format` reads and writes the `RigSpec`s of [`rig`](../../rig/docs/ARCHITECTURE.md), in Microtonalist's JSON
tracks files (`.mtlist.tracks`), with the formats of the track I/O plugins. It implements `rig`'s `RigRepo`.

## Key types

- **`RigFormat`** — (de)serializes a rig. `JsonRigFormat` takes the formats of the tuner and tuning changer families as
  parameters, which `FormatModule` fills from [`tuner-format`](../../../tuner/tuner-format/docs/ARCHITECTURE.md) and
  [`tuning-changer-format`](../../../tuner/tuning-changer-format/docs/ARCHITECTURE.md): `rig-format` depends on the
  plugin contracts, `tuner` and `tuning-changer`, and on no plugin.
- **`RigRepo` implementations** — `DefaultRigRepo` picks the `FileRigRepo`, `HttpRigRepo` or `LibraryRigRepo` by the
  URI's scheme.
- **`JsonTrackInputSpecPluginFormat`** and **`JsonTrackOutputSpecPluginFormat`** — the formats of a track's input and
  output, a device or another track.

## Subject to change

- The tracks file keeps its JSON schema and `.mtlist.tracks` extension until they are renamed after the rig (#365).
